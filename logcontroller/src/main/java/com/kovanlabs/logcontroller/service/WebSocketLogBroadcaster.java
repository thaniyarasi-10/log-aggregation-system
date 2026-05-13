package com.kovanlabs.logcontroller.service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;

/**
 * Role-aware WebSocket broadcaster for live log events.
 *
 * Security model — server-side enforcement layers:
 *   1. HTTP handshake (WebSocketConfig) rejects unauthenticated upgrade requests.
 *   2. STOMP authorization (WebSocketSecurityConfig) requires authentication on
 *      every CONNECT and SUBSCRIBE frame.
 *   3. THIS CLASS checks role and service mapping before calling
 *      convertAndSendToUser, so unauthorized logs are never submitted to the
 *      messaging infrastructure at all.
 *   4. Frontend guard (useRealtimeLogs) drops any event whose service is not in
 *      the user's allowedServices list as a final client-side safety net.
 *
 * Routing rules:
 *   ADMIN  — receives every log event regardless of service.
 *   DEV    — receives only events whose service (normalised to lowercase) is
 *            present in the user's allowedServices set from user_service table.
 *   No role / no service mapping — receives nothing.
 *
 * Performance:
 *   Active-user and service-mapping data is cached for CACHE_TTL_MS to avoid
 *   a DB round-trip on every Kafka message. Call invalidateCache() to force an
 *   immediate refresh after role or service-mapping changes.
 */
@Service
public class WebSocketLogBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketLogBroadcaster.class);

    private static final String USER_QUEUE = "/queue/logs";
    private static final String ADMIN_ROLE = "ADMIN";

    private static final long CACHE_TTL_MS        = 30_000L;
    private static final long ERROR_LOG_THROTTLE_MS = 30_000L;

    private final SimpMessagingTemplate messagingTemplate;
    private final AppUserRepository appUserRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;

    // Routing-profile cache — volatile reference replaced atomically on refresh.
    // Individual UserRoutingProfile records are immutable, so no further locking
    // is needed once the list reference is published.
    private volatile List<UserRoutingProfile> cachedProfiles = List.of();
    private final AtomicLong cacheExpiresAtMs = new AtomicLong(0);

    // Global throttle for DB / cache-rebuild error log lines.
    private final AtomicLong nextGlobalErrorLogAtMs = new AtomicLong(0);

    // Per-user throttle so one bad session does not flood the application log.
    private final ConcurrentHashMap<String, Long> perUserErrorThrottle = new ConcurrentHashMap<>();

    public WebSocketLogBroadcaster(
            SimpMessagingTemplate messagingTemplate,
            AppUserRepository appUserRepository,
            UserRoleMappingRepository userRoleMappingRepository,
            UserServiceMappingRepository userServiceMappingRepository) {
        this.messagingTemplate = messagingTemplate;
        this.appUserRepository = appUserRepository;
        this.userRoleMappingRepository = userRoleMappingRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Broadcasts event to every user who is authorised to see it.
     *
     * Authorization is enforced here (server-side) before any message is
     * submitted to the STOMP broker. A DEV user will never receive a log for a
     * service that is not in their allowedServices set, even if the frontend
     * filter is bypassed or disabled.
     */
    public void broadcast(LogEvent event) {
        if (event == null) {
            return;
        }

        String rawService = event.getService();
        if (rawService == null || rawService.isBlank()) {
            LOGGER.debug("WS BROADCAST — skipping event with blank service");
            return;
        }

        // Normalise once; reuse for every profile comparison.
        String normalizedService = rawService.trim().toLowerCase(Locale.ROOT);

        try {
            List<UserRoutingProfile> profiles = getProfiles();

            if (profiles.isEmpty()) {
                LOGGER.debug("WS BROADCAST — no active user profiles; skipping service='{}'", normalizedService);
                return;
            }

            int sent    = 0;
            int skipped = 0;

            for (UserRoutingProfile profile : profiles) {
                // Guard: profile email must be non-blank.
                // toRoutingProfile() already filters nulls, but be defensive.
                if (profile.email() == null || profile.email().isBlank()) {
                    LOGGER.warn("WS BROADCAST — skipping profile with blank email (userId unknown)");
                    continue;
                }

                // RBAC check — server-side enforcement:
                //   ADMIN  → eligible for all services
                //   DEV    → eligible only if service is in their allowedServices set
                //
                // An empty allowedServices set for a non-admin means no access.
                // This is intentional: a DEV with no service mappings receives nothing.
                boolean eligible = profile.isAdmin()
                        || profile.allowedServices().contains(normalizedService);

                if (!eligible) {
                    skipped++;
                    LOGGER.debug(
                            "WS BROADCAST — RBAC deny user='{}' service='{}' isAdmin={} allowedServices={}",
                            profile.email(), normalizedService, profile.isAdmin(),
                            profile.allowedServices());
                    continue;
                }

                // Deliver to the user's private queue.
                // convertAndSendToUser routes only to sessions whose Principal.getName()
                // equals profile.email() — no cross-user leakage is possible at the
                // broker level either.
                try {
                    messagingTemplate.convertAndSendToUser(
                            profile.email(),
                            USER_QUEUE,
                            event);
                    sent++;
                    LOGGER.debug("WS BROADCAST — delivered service='{}' level='{}' to user='{}'",
                            normalizedService, event.getLevel(), profile.email());
                } catch (Exception ex) {
                    logPerUserErrorThrottled(profile.email(), ex);
                }
            }

            LOGGER.debug("WS BROADCAST — service='{}' level='{}' sent={} skipped(RBAC)={}",
                    normalizedService, event.getLevel(), sent, skipped);

        } catch (Exception ex) {
            logGlobalErrorThrottled("broadcast", ex);
        }
    }

    /**
     * Rebuilds the routing profile for a single user immediately and replaces
     * their entry in the cached list without touching any other user's profile.
     *
     * Use this after any admin operation that changes one user's roles or service
     * mappings (create user, update user, approve service request, deactivate user).
     * The new permissions take effect on the very next broadcast cycle — no TTL wait.
     *
     * If the user is not found in the DB (deactivated or deleted), their profile
     * is removed from the cache so they stop receiving logs immediately.
     *
     * Thread-safety: the volatile write to cachedProfiles publishes the new list
     * atomically. Concurrent broadcast calls reading the old list are safe because
     * UserRoutingProfile records are immutable.
     *
     * @param email the user's email address (case-insensitive)
     */
    public void invalidateCacheForUser(String email) {
        if (email == null || email.isBlank()) {
            LOGGER.warn("WS BROADCASTER — invalidateCacheForUser called with blank email; ignored");
            return;
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);

        try {
            // Look up the user from DB to rebuild their profile.
            // If the user is inactive or missing, their profile is simply removed.
            UserRoutingProfile freshProfile = appUserRepository
                    .findByEmailIgnoreCaseAndIsActiveTrue(normalizedEmail)
                    .map(this::toRoutingProfile)
                    .orElse(null);

            // Replace the affected user's entry in the cached list atomically.
            // All other profiles are preserved — no full DB scan needed.
            List<UserRoutingProfile> current = cachedProfiles;
            List<UserRoutingProfile> updated;

            if (freshProfile != null) {
                // Rebuild: remove old entry for this email, add the fresh one.
                updated = new java.util.ArrayList<>(current.size() + 1);
                for (UserRoutingProfile p : current) {
                    if (!normalizedEmail.equals(p.email() == null ? null
                            : p.email().trim().toLowerCase(Locale.ROOT))) {
                        updated.add(p);
                    }
                }
                updated.add(freshProfile);
                updated = java.util.Collections.unmodifiableList(updated);
                LOGGER.info("WS BROADCASTER — cache updated for user='{}' isAdmin={} services={}",
                        normalizedEmail, freshProfile.isAdmin(), freshProfile.allowedServices());
            } else {
                // User is inactive or has no roles — remove from routing entirely.
                updated = current.stream()
                        .filter(p -> !normalizedEmail.equals(p.email() == null ? null
                                : p.email().trim().toLowerCase(Locale.ROOT)))
                        .collect(Collectors.toUnmodifiableList());
                LOGGER.info("WS BROADCASTER — user='{}' removed from routing cache "
                        + "(inactive, no roles, or not found in DB)", normalizedEmail);
            }

            cachedProfiles = updated;
            // Keep the existing TTL — we only patched one entry, the rest are still fresh.

        } catch (Exception ex) {
            // On any error fall back to a full cache invalidation so the next
            // broadcast triggers a complete rebuild from DB.
            LOGGER.warn("WS BROADCASTER — per-user cache update failed for '{}'; "
                    + "falling back to full invalidation: {} — {}",
                    normalizedEmail, ex.getClass().getSimpleName(), ex.getMessage());
            invalidateCache();
        }
    }

    /**
     * Expires the full routing-profile cache so the next broadcast call rebuilds
     * all profiles from the database.
     *
     * Use this after service-level changes that affect multiple users at once
     * (deactivate service, rename service) where a per-user patch is not practical.
     */
    public void invalidateCache() {
        cacheExpiresAtMs.set(0);
        LOGGER.info("WS BROADCASTER — full routing cache invalidated; will rebuild on next broadcast");
    }

    /**
     * Forces an immediate synchronous full cache rebuild from the database.
     *
     * Use this after bulk admin operations where multiple users are affected.
     * Blocks until the DB query completes; on DB failure falls back to the
     * existing cached profiles and logs a warning.
     */
    public void refreshNow() {
        LOGGER.debug("WS BROADCASTER — forced full cache refresh requested");
        List<UserRoutingProfile> fresh = buildProfiles();
        cachedProfiles = fresh;
        cacheExpiresAtMs.set(System.currentTimeMillis() + CACHE_TTL_MS);
        LOGGER.info("WS BROADCASTER — forced full cache refresh complete: {} active user(s)", fresh.size());
    }

    // =========================================================================
    // Private — cache management
    // =========================================================================

    /**
     * Returns the cached profile list if still valid, otherwise rebuilds it.
     * Thread-safe: the volatile write to cachedProfiles is visible to all threads
     * immediately after the assignment.
     */
    private List<UserRoutingProfile> getProfiles() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAtMs.get()) {
            return cachedProfiles;
        }

        List<UserRoutingProfile> fresh = buildProfiles();
        cachedProfiles = fresh;
        cacheExpiresAtMs.set(now + CACHE_TTL_MS);
        LOGGER.debug("WS BROADCASTER — routing cache refreshed: {} active user(s)", fresh.size());
        return fresh;
    }

    /**
     * Queries the database and builds a fresh list of routing profiles.
     * On any DB error, falls back to the last known good cachedProfiles so
     * that broadcasts continue (with potentially stale data) rather than
     * silently dropping all messages.
     */
    private List<UserRoutingProfile> buildProfiles() {
        try {
            List<AppUser> activeUsers = appUserRepository.findByIsActiveTrueOrderByUsernameAsc();

            List<UserRoutingProfile> profiles = activeUsers.stream()
                    .map(this::toRoutingProfile)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            LOGGER.debug("WS BROADCASTER — built {} routing profile(s) from {} active user(s)",
                    profiles.size(), activeUsers.size());
            return profiles;

        } catch (Exception ex) {
            logGlobalErrorThrottled("buildProfiles", ex);
            // Fall back to stale cache — better than dropping all broadcasts.
            LOGGER.warn("WS BROADCASTER — DB error during profile rebuild; using stale cache ({} profile(s))",
                    cachedProfiles.size());
            return cachedProfiles;
        }
    }

    /**
     * Builds a UserRoutingProfile for a single user.
     *
     * Returns null (filtered out by the caller) when:
     *   - The user has no roles assigned (must not receive any logs).
     *   - A DB error occurs for this specific user (logged, not propagated).
     *
     * Admin users get an empty allowedServices set — the broadcast loop checks
     * isAdmin() first, so the empty set is never used as a deny signal for admins.
     *
     * DEV users with no service mappings get an empty allowedServices set, which
     * means they pass the null/blank check but fail the contains() check for every
     * service — effectively receiving nothing until mappings are added.
     */
    private UserRoutingProfile toRoutingProfile(AppUser user) {
        if (user == null || user.getId() == null) {
            LOGGER.warn("WS BROADCASTER — skipping null or ID-less user in profile build");
            return null;
        }

        if (user.getEmail() == null || user.getEmail().isBlank()) {
            LOGGER.warn("WS BROADCASTER — skipping user id='{}' with blank email", user.getId());
            return null;
        }

        try {
            List<String> roleNames = userRoleMappingRepository
                    .findDistinctRoleNamesByUserId(user.getId())
                    .stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(r -> !r.isBlank())
                    .toList();

            if (roleNames.isEmpty()) {
                // No roles assigned — user must not receive any live logs.
                LOGGER.debug("WS BROADCASTER — user='{}' has no roles; excluded from routing",
                        user.getEmail());
                return null;
            }

            boolean admin = roleNames.stream()
                    .map(r -> r.toUpperCase(Locale.ROOT))
                    .anyMatch(ADMIN_ROLE::equals);

            Set<String> allowedServices;
            if (admin) {
                // Admins receive all logs — no service filter needed.
                // Empty set is the sentinel; broadcast loop checks isAdmin() first.
                allowedServices = Set.of();
                LOGGER.debug("WS BROADCASTER — user='{}' is ADMIN; will receive all services",
                        user.getEmail());
            } else {
                allowedServices = userServiceMappingRepository
                        .findServiceNamesByUserId(user.getId())
                        .stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());

                if (allowedServices.isEmpty()) {
                    LOGGER.warn("WS BROADCASTER — DEV user='{}' has no service mappings; "
                            + "will receive no live logs until services are assigned",
                            user.getEmail());
                } else {
                    LOGGER.debug("WS BROADCASTER — DEV user='{}' mapped to {} service(s): {}",
                            user.getEmail(), allowedServices.size(), allowedServices);
                }
            }

            return new UserRoutingProfile(user.getEmail(), admin, allowedServices);

        } catch (Exception ex) {
            // Log per-user errors with the global throttle to avoid log flooding
            // when a single user's DB rows are corrupt or missing.
            logGlobalErrorThrottled("toRoutingProfile[" + user.getEmail() + "]", ex);
            return null;
        }
    }

    // =========================================================================
    // Private — logging helpers
    // =========================================================================

    private void logGlobalErrorThrottled(String operation, Exception ex) {
        long now = System.currentTimeMillis();
        if (now >= nextGlobalErrorLogAtMs.get()) {
            nextGlobalErrorLogAtMs.set(now + ERROR_LOG_THROTTLE_MS);
            LOGGER.warn("WS BROADCASTER — {} failed (throttled, next log in {}ms): {} — {}",
                    operation, ERROR_LOG_THROTTLE_MS,
                    ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    private void logPerUserErrorThrottled(String email, Exception ex) {
        long now = System.currentTimeMillis();
        long nextLog = perUserErrorThrottle.getOrDefault(email, 0L);
        if (now >= nextLog) {
            perUserErrorThrottle.put(email, now + ERROR_LOG_THROTTLE_MS);
            LOGGER.warn("WS BROADCASTER — delivery failed for user='{}' (throttled): {} — {}",
                    email, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }

    // =========================================================================
    // Private — routing profile record
    // =========================================================================

    /**
     * Immutable snapshot of a user's WebSocket routing permissions.
     *
     * email           — the user's email address, used as the STOMP principal name.
     * isAdmin         — true if the user has the ADMIN role.
     * allowedServices — lowercase service names the user may receive logs for.
     *                   Always empty for admins (isAdmin() is checked first).
     *                   May be empty for DEV users with no service mappings.
     */
    private record UserRoutingProfile(
            String email,
            boolean isAdmin,
            Set<String> allowedServices) {
    }
}
