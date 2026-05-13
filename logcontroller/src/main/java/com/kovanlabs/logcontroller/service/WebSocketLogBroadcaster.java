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
 * <h3>Routing rules</h3>
 * <ul>
 *   <li><b>ADMIN</b> — receives every log event regardless of service.</li>
 *   <li><b>DEV / non-admin</b> — receives only events whose {@code service} field
 *       matches one of the services mapped to that user in {@code user_service}.</li>
 *   <li><b>Users with no role or no service mapping</b> — receive nothing.</li>
 * </ul>
 *
 * <h3>Delivery mechanism</h3>
 * Uses {@link SimpMessagingTemplate#convertAndSendToUser} so each message is
 * delivered only to the WebSocket session(s) whose STOMP {@code Principal} matches
 * the target email. The client subscribes to {@code /user/queue/logs}.
 *
 * <h3>Performance</h3>
 * Active-user and service-mapping data is cached for
 * {@value #CACHE_TTL_MS} ms to avoid a DB round-trip on every Kafka message.
 * The cache is invalidated lazily on TTL expiry — no background thread needed.
 */
@Service
public class WebSocketLogBroadcaster {

    private static final Logger LOGGER = LoggerFactory.getLogger(WebSocketLogBroadcaster.class);

    /** User-specific queue destination — client subscribes to /user/queue/logs */
    private static final String USER_QUEUE = "/queue/logs";

    /** Role name that grants full access to all log streams */
    private static final String ADMIN_ROLE = "ADMIN";

    private static final long CACHE_TTL_MS = 30_000L;

    private static final long ERROR_LOG_THROTTLE_MS = 30_000L;

    private final SimpMessagingTemplate messagingTemplate;
    private final AppUserRepository appUserRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;




    private volatile List<UserRoutingProfile> cachedProfiles = List.of();
    private final AtomicLong cacheExpiresAtMs = new AtomicLong(0);

    /** Throttle gate for DB-error log messages */
    private final AtomicLong nextErrorLogAtMs = new AtomicLong(0);

    /** Per-user error throttle so one bad user doesn't flood the log */
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

    public void broadcast(LogEvent event) {
        if (event == null || event.getService() == null || event.getService().isBlank()) {
            return;
        }

        String normalizedService = event.getService().trim().toLowerCase(Locale.ROOT);

        try {
            List<UserRoutingProfile> profiles = getProfiles();
            int sent = 0;

            LOGGER.debug("WS BROADCAST — attempting delivery for service='{}' to {} active user profile(s)",
                    normalizedService, profiles.size());

            for (UserRoutingProfile profile : profiles) {
                boolean eligible = profile.isAdmin() || profile.allowedServices().contains(normalizedService);
                LOGGER.debug("WS BROADCAST — user='{}' isAdmin={} eligible={} allowedServices={}",
                        profile.email(), profile.isAdmin(), eligible, profile.allowedServices());

                if (eligible) {
                    try {
                        messagingTemplate.convertAndSendToUser(
                                profile.email(),
                                USER_QUEUE,
                                event);
                        sent++;
                        LOGGER.debug("WS BROADCAST — sent to user='{}'", profile.email());
                    } catch (Exception ex) {
                        logPerUserErrorThrottled(profile.email(), ex);
                    }
                }
            }

//            LOGGER.info("WS BROADCAST — service='{}' level='{}' delivered to {}/{} user(s)",
//                    normalizedService, event.getLevel(), sent, profiles.size());

        } catch (Exception ex) {
            logErrorThrottled("broadcast", ex);
        }
    }


    public void invalidateCache() {
        cacheExpiresAtMs.set(0);
        LOGGER.debug("WS BROADCASTER — routing cache invalidated");
    }

    private List<UserRoutingProfile> getProfiles() {
        long now = System.currentTimeMillis();
        if (now < cacheExpiresAtMs.get()) {
            return cachedProfiles;
        }

        // TTL expired — rebuild from DB
        List<UserRoutingProfile> fresh = buildProfiles();
        cachedProfiles = fresh;
        cacheExpiresAtMs.set(now + CACHE_TTL_MS);
        LOGGER.debug("WS BROADCASTER — routing cache refreshed: {} active user(s)", fresh.size());
        return fresh;
    }

    private List<UserRoutingProfile> buildProfiles() {
        try {
            List<AppUser> activeUsers = appUserRepository.findByIsActiveTrueOrderByUsernameAsc();

            return activeUsers.stream()
                    .map(this::toRoutingProfile)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception ex) {
            logErrorThrottled("buildProfiles", ex);
            return cachedProfiles;
        }
    }

    private UserRoutingProfile toRoutingProfile(AppUser user) {
        try {
            List<String> roleNames = userRoleMappingRepository
                    .findDistinctRoleNamesByUserId(user.getId())
                    .stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(r -> !r.isBlank())
                    .toList();

            if (roleNames.isEmpty()) {
                // No roles — user must not receive any live logs
                return null;
            }

            boolean admin = roleNames.stream()
                    .map(r -> r.toUpperCase(Locale.ROOT))
                    .anyMatch(ADMIN_ROLE::equals);

            Set<String> allowedServices;
            if (admin) {
                // Admins receive all logs — no service filter needed.
                // Use an empty set as the sentinel; the broadcast loop checks isAdmin() first.
                allowedServices = Set.of();
            } else {
                allowedServices = userServiceMappingRepository
                        .findServiceNamesByUserId(user.getId())
                        .stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .map(s -> s.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            }

            return new UserRoutingProfile(user.getEmail(), admin, allowedServices);

        } catch (Exception ex) {
            logErrorThrottled("toRoutingProfile[" + user.getId() + "]", ex);
            return null;
        }
    }

    // =========================================================================
    // Private — logging helpers
    // =========================================================================

    private void logErrorThrottled(String operation, Exception ex) {
        long now = System.currentTimeMillis();
        if (now >= nextErrorLogAtMs.get()) {
            nextErrorLogAtMs.set(now + ERROR_LOG_THROTTLE_MS);
            LOGGER.warn("WS BROADCASTER {} failed: {}", operation, ex.getMessage());
        }
    }

    private void logPerUserErrorThrottled(String email, Exception ex) {
        long now = System.currentTimeMillis();
        long nextLog = perUserErrorThrottle.getOrDefault(email, 0L);
        if (now >= nextLog) {
            perUserErrorThrottle.put(email, now + ERROR_LOG_THROTTLE_MS);
            LOGGER.warn("WS BROADCASTER — failed to deliver to user='{}': {}", email, ex.getMessage());
        }
    }

    private record UserRoutingProfile(
            String email,
            boolean isAdmin,
            Set<String> allowedServices) {
    }
}
