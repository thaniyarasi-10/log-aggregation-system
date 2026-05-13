package com.kovanlabs.logcontroller.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.jpa.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.RolePermissionMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;

/**
 * Authoritative RBAC authorization service.
 *
 * <h3>Security invariants</h3>
 * <ul>
 *   <li>All permissions come exclusively from DB {@code role_permission_mapping} rows.
 *       There are no implicit, fallback, or default permissions.</li>
 *   <li>OAuth identity (Azure AD) answers <em>who</em> the user is.
 *       The DB answers <em>what</em> they are allowed to do.</li>
 *   <li>Unregistered OAuth users (no DB record) → 403 Forbidden.</li>
 *   <li>Registered users with no roles → 403 Forbidden.</li>
 *   <li>DB outage → 503 Service Unavailable (fail-closed, never fail-open).</li>
 *   <li>Admin email override elevates only users explicitly listed in
 *       {@code app.auth.admin-emails}; it never grants access to unknown users.</li>
 * </ul>
 */
@Service
public class ServiceAccessAuthorizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceAccessAuthorizationService.class);
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String PERMISSION_USERS_MANAGE = "users:manage";
    private static final String PERMISSION_SERVICES_MANAGE = "services:manage";
    private static final String PERMISSION_SERVICES_READ = "services:read";

    // -------------------------------------------------------------------------
    // No DEFAULT_READ_PERMISSIONS constant — implicit permissions are forbidden.
    // All permissions must be explicitly stored in role_permission_mapping.
    // -------------------------------------------------------------------------

    private final AppUserRepository appUserRepository;
    private final AppServiceRepository appServiceRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final RolePermissionMappingRepository rolePermissionMappingRepository;
    private final OAuthUserEmailResolver emailResolver;
    private final Set<String> adminEmails;
    private final long dbRetryCooldownMs;
    private final long contextCacheTtlMs;

    private final AtomicLong retryAfterEpochMs = new AtomicLong(0);
    private final AtomicBoolean cooldownLogPrinted = new AtomicBoolean(false);
    private final Map<String, CachedAccessContext> contextCache = new ConcurrentHashMap<>();

    public ServiceAccessAuthorizationService(
            AppUserRepository appUserRepository,
            AppServiceRepository appServiceRepository,
            UserServiceMappingRepository userServiceMappingRepository,
            UserRoleMappingRepository userRoleMappingRepository,
            RolePermissionMappingRepository rolePermissionMappingRepository,
            OAuthUserEmailResolver emailResolver,
            @Value("${app.auth.admin-emails:}") String adminEmailsCsv,
            @Value("${app.auth.db-retry-cooldown-ms:5000}") long dbRetryCooldownMs,
            @Value("${app.auth.context-cache-ttl-ms:2000}") long contextCacheTtlMs) {
        this.appUserRepository = appUserRepository;
        this.appServiceRepository = appServiceRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
        this.userRoleMappingRepository = userRoleMappingRepository;
        this.rolePermissionMappingRepository = rolePermissionMappingRepository;
        this.emailResolver = emailResolver;
        this.adminEmails = parseAdminEmails(adminEmailsCsv);
        this.dbRetryCooldownMs = Math.max(1000L, dbRetryCooldownMs);
        this.contextCacheTtlMs = Math.max(500L, contextCacheTtlMs);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Resolves the RBAC context for the currently authenticated OAuth principal.
     *
     * <p>Throws {@link ResponseStatusException}:
     * <ul>
     *   <li>401 — no authenticated principal in the security context</li>
     *   <li>403 — email cannot be resolved, user not in DB, or user has no roles</li>
     *   <li>503 — DB is temporarily unavailable (fail-closed)</li>
     * </ul>
     */
    public AuthenticatedUserContext getCurrentUserAccessContext() {
        return emailResolver.getCurrentUserEmail()
                .map(this::getUserAccessContext)
                .orElseGet(() -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && authentication.isAuthenticated()) {
                        String name = authentication.getName();
                        LOGGER.warn("RBAC DENY — could not resolve email from OAuth principal for name='{}'. " +
                                "No permissions granted.", name);
                        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Unable to resolve user identity from OAuth token");
                    }
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
                });
    }

    public boolean canManageUsers(AuthenticatedUserContext context) {
        return context != null && (context.isAdmin() || context.hasPermission(PermissionName.USERS_MANAGE));
    }

    public boolean canManageServices(AuthenticatedUserContext context) {
        return context != null && (context.isAdmin() || context.hasPermission(PermissionName.SERVICES_MANAGE));
    }

    /**
     * Returns the list of services accessible to the given email.
     * Propagates 403/503 from {@link #getUserAccessContext} on authorization failure.
     */
    public List<AppService> getAccessibleServices(String email) {
        if (email == null || email.isBlank()) {
            LOGGER.debug("getAccessibleServices — blank email, returning empty list");
            return List.of();
        }

        String normalizedEmail = normalizeEmailForLookup(email);
        AuthenticatedUserContext context = getUserAccessContext(normalizedEmail);
        List<AppService> services = resolveServicesFromContext(context);
        clearCooldownIfNeeded();
        return services;
    }

    /**
     * Resolves the full RBAC context for the given email address.
     *
     * <p>Authorization decisions:
     * <ul>
     *   <li>Blank email → 403</li>
     *   <li>DB in cooldown → 503 (fail-closed)</li>
     *   <li>No DB user found → 403 "No roles assigned to user"</li>
     *   <li>DB user found but no roles → 403 "No roles assigned to user"</li>
     *   <li>DB error → 503 (fail-closed)</li>
     * </ul>
     */
    public AuthenticatedUserContext getUserAccessContext(String email) {
        if (email == null || email.isBlank()) {
            LOGGER.warn("RBAC DENY — getUserAccessContext called with blank email");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No roles assigned to user");
        }

        String normalizedEmail = normalizeEmailForLookup(email);

        // Return cached context if still valid.
        AuthenticatedUserContext cached = readCachedContext(normalizedEmail);
        if (cached != null) {
            return safeApplyAdminEmailOverride(normalizedEmail, cached);
        }

        // DB is in cooldown — fail closed with 503.
        if (isDbInCooldown(email, "access context")) {
            LOGGER.warn("RBAC DB OUTAGE — denying access for email='{}' while datasource is unavailable", email);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authorization service temporarily unavailable. Please try again shortly.");
        }

        try {
            AuthenticatedUserContext context = resolveUserByEmailCandidates(normalizedEmail)
                    .map(this::toAuthenticatedContext)
                    .orElseGet(() -> denyUnregisteredUser(normalizedEmail));
            context = safeApplyAdminEmailOverride(normalizedEmail, context);
            writeCachedContext(normalizedEmail, context);
            clearCooldownIfNeeded();
            return context;
        } catch (ResponseStatusException ex) {
            // Re-throw 403/401/503 directly — never wrap in a DB-fallback path.
            throw ex;
        } catch (RuntimeException ex) {
            markDbUnavailable(email, "access context", ex);
            LOGGER.warn("RBAC DB OUTAGE — denying access for email='{}': {}", email, ex.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Authorization service temporarily unavailable. Please try again shortly.");
        }
    }

    // =========================================================================
    // Private — core resolution
    // =========================================================================

    /**
     * Builds the RBAC context for a confirmed DB user.
     *
     * <p>Throws 403 if the user has no roles assigned.
     * Logs a warning (but does not throw) if the user has roles but no permissions
     * in {@code role_permission_mapping} — the caller will receive an empty permission
     * set and all protected endpoints will return 403 until an admin configures the mapping.
     */
    private AuthenticatedUserContext toAuthenticatedContext(AppUser user) {
        List<String> roleNames;
        try {
            roleNames = userRoleMappingRepository.findRoleNamesByUserId(user.getId()).stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to load roles for userId='{}': {}", user.getId(), ex.getMessage(), ex);
            throw ex;
        }

        // Hard deny — a registered user with no roles must not receive any access.
        if (roleNames.isEmpty()) {
            LOGGER.warn("RBAC DENY — userId='{}' email='{}' has no roles assigned. " +
                    "An admin must assign at least one role before this user can access the system.",
                    user.getId(), user.getEmail());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No roles assigned to user");
        }

        List<String> permissionNames;
        try {
            permissionNames = rolePermissionMappingRepository.findPermissionNamesByUserId(user.getId()).stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to load permissions for userId='{}': {}", user.getId(), ex.getMessage(), ex);
            throw ex;
        }

        boolean admin = roleNames.stream()
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLE::equals)
                || currentAuthenticationIsAdmin()
                || hasAdminPermission(permissionNames);

        UserRole role = admin ? UserRole.ADMIN : UserRole.DEV;

        List<String> allowedServices;
        try {
            allowedServices = resolveServicesForUser(user.getId(), admin).stream()
                    .map(AppService::getName)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
        } catch (RuntimeException ex) {
            LOGGER.error("Failed to load services for userId='{}': {}", user.getId(), ex.getMessage(), ex);
            throw ex;
        }

        // Permissions come exclusively from DB role_permission_mapping.
        // No fallback permissions are ever granted.
        if (permissionNames.isEmpty()) {
            LOGGER.warn("RBAC WARN — userId='{}' email='{}' roles={} has no permissions in role_permission_mapping. " +
                    "All protected endpoints will return 403 until permissions are configured by an admin.",
                    user.getId(), user.getEmail(), roleNames);
        }

        LOGGER.debug("RBAC OK — email='{}' userId='{}' roles={} services={} permissions={}",
                user.getEmail(), user.getId(), roleNames, allowedServices.size(), permissionNames.size());

        return new AuthenticatedUserContext(user.getEmail(), role, allowedServices, permissionNames);
    }

    /**
     * Called when no DB user record exists for the resolved OAuth email.
     * Always throws 403 — unregistered users are never granted access.
     */
    private AuthenticatedUserContext denyUnregisteredUser(String email) {
        LOGGER.warn("RBAC DENY — no DB user found for email='{}'. " +
                "User must be registered and assigned roles by an admin before accessing the system.", email);
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No roles assigned to user");
    }

    // =========================================================================
    // Private — admin email override
    // =========================================================================

    /**
     * Elevates the context to ADMIN for emails listed in {@code app.auth.admin-emails}.
     * Only applies to users who already have a valid DB-backed context — it never
     * creates access for unknown users.
     */
    private AuthenticatedUserContext safeApplyAdminEmailOverride(String email, AuthenticatedUserContext context) {
        try {
            return applyAdminEmailOverride(email, context);
        } catch (RuntimeException ex) {
            LOGGER.warn("Could not apply admin email override for '{}' — using existing context: {}", email, ex.getMessage());
            return context;
        }
    }

    private AuthenticatedUserContext applyAdminEmailOverride(String email, AuthenticatedUserContext context) {
        if (context == null || !isConfiguredAdminEmail(email)) {
            return context;
        }

        List<String> allServiceNames = appServiceRepository.findByIsActiveTrue().stream()
                .map(AppService::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();

        List<String> adminPermissions = List.of(
                "logs:read",
                "logs:write",
                "metrics:read",
                "alerts:read",
                "alerts:write",
                PERMISSION_SERVICES_READ,
                PERMISSION_SERVICES_MANAGE,
                PERMISSION_USERS_MANAGE);

        return new AuthenticatedUserContext(
                context.email(),
                UserRole.ADMIN,
                allServiceNames,
                adminPermissions);
    }

    private Set<String> parseAdminEmails(String rawCsv) {
        if (rawCsv == null || rawCsv.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(rawCsv.split(","))
                .map(this::normalizeEmailForLookup)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean isConfiguredAdminEmail(String email) {
        return email != null && !email.isBlank() && adminEmails.contains(normalizeEmailForLookup(email));
    }

    // =========================================================================
    // Private — DB cooldown / retry
    // =========================================================================

    private boolean isDbInCooldown(String email, String operation) {
        long retryAt = retryAfterEpochMs.get();
        long now = System.currentTimeMillis();
        if (retryAt > now) {
            if (cooldownLogPrinted.compareAndSet(false, true)) {
                LOGGER.warn("Skipping DB lookup for {} while datasource is unavailable (email='{}', retryInMs={})",
                        operation, email, retryAt - now);
            }
            return true;
        }
        return false;
    }

    private void markDbUnavailable(String email, String operation, RuntimeException ex) {
        long retryAt = System.currentTimeMillis() + dbRetryCooldownMs;
        retryAfterEpochMs.set(retryAt);
        cooldownLogPrinted.set(false);
        LOGGER.warn("DB unavailable while resolving {} for '{}': {}. Next retry in {} ms",
                operation, email, ex.getMessage(), dbRetryCooldownMs);
    }

    private void clearCooldownIfNeeded() {
        if (retryAfterEpochMs.get() != 0) {
            retryAfterEpochMs.set(0);
            cooldownLogPrinted.set(false);
            LOGGER.info("Datasource connectivity restored; DB-backed authorization resumed.");
        }
    }

    // =========================================================================
    // Private — service resolution
    // =========================================================================

    private List<AppService> resolveServicesForUser(String userId, boolean admin) {
        if (admin) {
            return appServiceRepository.findByIsActiveTrue().stream()
                    .filter(Objects::nonNull)
                    .toList();
        }
        return userServiceMappingRepository.findServicesByUserId(userId).stream()
                .filter(Objects::nonNull)
                .toList();
    }

    private List<AppService> resolveServicesFromContext(AuthenticatedUserContext context) {
        if (context == null) {
            return List.of();
        }
        if (context.isAdmin()) {
            return appServiceRepository.findByIsActiveTrue();
        }
        List<String> allowed = context.allowedServices();
        if (allowed == null || allowed.isEmpty()) {
            return List.of();
        }
        Set<String> normalized = allowed.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // Wildcard sentinel is never used — treat it as no-access.
        if (normalized.contains("*")) {
            LOGGER.warn("RBAC WARN — wildcard '*' found in allowedServices for context email='{}'. " +
                    "Wildcard is not permitted; returning empty service list.", context.email());
            return List.of();
        }

        return appServiceRepository.findByIsActiveTrue().stream()
                .filter(service -> service.getName() != null
                        && normalized.contains(service.getName().trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    // =========================================================================
    // Private — email resolution helpers
    // =========================================================================

    private java.util.Optional<AppUser> resolveUserByEmailCandidates(String rawEmail) {
        List<String> candidates = candidateEmails(rawEmail);
        for (String candidate : candidates) {
            java.util.Optional<AppUser> user = appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue(candidate);
            if (user.isPresent()) {
                if (!candidate.equals(rawEmail)) {
                    LOGGER.info("RBAC resolved email '{}' to existing user id='{}' via candidate '{}'",
                            rawEmail, user.get().getId(), candidate);
                }
                return user;
            }
        }
        LOGGER.warn("RBAC could not resolve any DB user for email='{}' (tried {} candidate(s))",
                rawEmail, candidates.size());
        return java.util.Optional.empty();
    }

    private List<String> candidateEmails(String rawEmail) {
        String normalized = normalizeEmailForLookup(rawEmail);
        if (normalized.isBlank()) {
            return List.of();
        }

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(normalized);

        // Azure AD external-user suffix: "user_domain.com#EXT#@tenant.onmicrosoft.com"
        int extMarker = normalized.indexOf("#ext#");
        if (extMarker > 0) {
            String externalPrefix = normalized.substring(0, extMarker);
            String recovered = externalPrefix.replace('_', '@');
            if (!recovered.isBlank()) {
                candidates.add(recovered);
            }
        }

        // Pipe-delimited OID: "oid|email"
        int pipeMarker = normalized.indexOf('|');
        if (pipeMarker > 0 && pipeMarker < normalized.length() - 1) {
            String tail = normalized.substring(pipeMarker + 1).trim();
            if (!tail.isBlank()) {
                candidates.add(tail.toLowerCase(Locale.ROOT));
            }
        }

        return List.copyOf(candidates);
    }

    private String normalizeEmailForLookup(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // =========================================================================
    // Private — authentication introspection helpers
    // =========================================================================

    /**
     * Checks whether the current Spring Security authentication carries an ADMIN
     * authority or ADMIN-valued claim. Used only as a secondary signal inside
     * {@link #toAuthenticatedContext} — the DB role is always the primary source.
     */
    private boolean currentAuthenticationIsAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }

        boolean authorityBasedAdmin = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(Objects::nonNull)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.contains(ADMIN_ROLE));
        if (authorityBasedAdmin) {
            return true;
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof OidcUser oidcUser) {
            return containsAdminValue(oidcUser.getClaims().values());
        }
        if (principal instanceof OAuth2User oauth2User) {
            return containsAdminValue(oauth2User.getAttributes().values());
        }

        return false;
    }

    private boolean hasAdminPermission(List<String> permissionNames) {
        if (permissionNames == null || permissionNames.isEmpty()) {
            return false;
        }
        return permissionNames.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> PERMISSION_SERVICES_MANAGE.equals(value) || PERMISSION_USERS_MANAGE.equals(value));
    }

    private boolean containsAdminValue(Iterable<?> values) {
        for (Object value : values) {
            if (value != null && value.toString().toUpperCase(Locale.ROOT).contains(ADMIN_ROLE)) {
                return true;
            }
        }
        return false;
    }

    // =========================================================================
    // Private — context cache
    // =========================================================================

    private AuthenticatedUserContext readCachedContext(String email) {
        CachedAccessContext cached = contextCache.get(email);
        if (cached == null) {
            return null;
        }
        if (cached.expiresAtEpochMs() > System.currentTimeMillis()) {
            return cached.context();
        }
        contextCache.remove(email, cached);
        return null;
    }

    private void writeCachedContext(String email, AuthenticatedUserContext context) {
        contextCache.put(email, new CachedAccessContext(context, System.currentTimeMillis() + contextCacheTtlMs));
    }

    private record CachedAccessContext(AuthenticatedUserContext context, long expiresAtEpochMs) {
    }
}
