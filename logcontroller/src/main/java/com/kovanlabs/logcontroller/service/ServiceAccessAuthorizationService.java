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
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.AppUserRepository;
import com.kovanlabs.logcontroller.repository.RolePermissionMappingRepository;
import com.kovanlabs.logcontroller.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.repository.UserServiceMappingRepository;

@Service
public class ServiceAccessAuthorizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceAccessAuthorizationService.class);
    private static final String ADMIN_ROLE = "ADMIN";
    private static final String PERMISSION_USERS_MANAGE = "users:manage";
    private static final String PERMISSION_SERVICES_MANAGE = "services:manage";
    private static final String PERMISSION_SERVICES_READ = "services:read";
    private static final List<String> DEFAULT_READ_PERMISSIONS = List.of(
            "logs:read",
            "metrics:read",
            "alerts:read",
            "services:read");

    private final AppUserRepository appUserRepository;
    private final AppServiceRepository appServiceRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final RolePermissionMappingRepository rolePermissionMappingRepository;
    private final OAuthUserEmailResolver emailResolver;
    private final boolean failOpenWhenDbUnavailable;
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
            @Value("${app.auth.fail-open-when-db-unavailable:true}") boolean failOpenWhenDbUnavailable,
            @Value("${app.auth.admin-emails:}") String adminEmailsCsv,
            @Value("${app.auth.db-retry-cooldown-ms:5000}") long dbRetryCooldownMs,
            @Value("${app.auth.context-cache-ttl-ms:2000}") long contextCacheTtlMs) {
        this.appUserRepository = appUserRepository;
        this.appServiceRepository = appServiceRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
        this.userRoleMappingRepository = userRoleMappingRepository;
        this.rolePermissionMappingRepository = rolePermissionMappingRepository;
        this.emailResolver = emailResolver;
        this.failOpenWhenDbUnavailable = failOpenWhenDbUnavailable;
        this.adminEmails = parseAdminEmails(adminEmailsCsv);
        this.dbRetryCooldownMs = Math.max(1000L, dbRetryCooldownMs);
        this.contextCacheTtlMs = Math.max(500L, contextCacheTtlMs);
    }

    public AuthenticatedUserContext getCurrentUserAccessContext() {
        return emailResolver.getCurrentUserEmail()
                .map(this::getUserAccessContext)
                .orElseGet(() -> {
                    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
                    if (authentication != null && authentication.isAuthenticated()) {
                        String fallbackEmail = normalizeEmailForLookup(authentication.getName());
                        return buildAuthenticatedReadFallbackContext(
                                fallbackEmail.isBlank() ? "authenticated@local" : fallbackEmail);
                    }
                    return new AuthenticatedUserContext("unknown@local", UserRole.USER, List.of(), List.of());
                });
    }

    public boolean canManageUsers(AuthenticatedUserContext context) {
        return context != null && (context.isAdmin() || context.hasPermission(PermissionName.USERS_MANAGE));
    }

    public boolean canManageServices(AuthenticatedUserContext context) {
        return context != null && (context.isAdmin() || context.hasPermission(PermissionName.SERVICES_MANAGE));
    }

    public List<AppService> getAccessibleServices(String email) {
        if (email == null || email.isBlank()) {
            LOGGER.debug("getAccessibleServices email is blank -> returning 0 services");
            return List.of();
        }

        try {
            String normalizedEmail = normalizeEmailForLookup(email);
            AuthenticatedUserContext context = getUserAccessContext(normalizedEmail);
            List<AppService> services = resolveServicesFromContext(context);
            clearCooldownIfNeeded();
            return services;
        } catch (RuntimeException ex) {
            markDbUnavailable(email, "services", ex);
            return List.of();
        }
    }

    public AuthenticatedUserContext getUserAccessContext(String email) {
        if (email == null || email.isBlank()) {
            return new AuthenticatedUserContext("unknown@local", UserRole.USER, List.of(), List.of());
        }

        String normalizedEmail = normalizeEmailForLookup(email);
        AuthenticatedUserContext cached = readCachedContext(normalizedEmail);
        if (cached != null) {
            return applyAdminEmailOverride(normalizedEmail, cached);
        }

        if (isDbInCooldown(email, "access context")) {
            AuthenticatedUserContext context = readCachedContext(normalizedEmail, true)
                .orElseGet(() -> buildDbUnavailableFallbackContext(normalizedEmail));
            context = applyAdminEmailOverride(normalizedEmail, context);
            writeCachedContext(normalizedEmail, context);
            return context;
        }

        try {
        AuthenticatedUserContext context = resolveUserByEmailCandidates(normalizedEmail)
            .map(this::toAuthenticatedContext)
            .orElseGet(() -> buildFallbackContext(normalizedEmail));
            context = applyAdminEmailOverride(normalizedEmail, context);
            writeCachedContext(normalizedEmail, context);
            clearCooldownIfNeeded();
            return context;
        } catch (RuntimeException ex) {
            markDbUnavailable(email, "access context", ex);
            AuthenticatedUserContext fallback = buildDbUnavailableFallbackContext(normalizedEmail);
            fallback = applyAdminEmailOverride(normalizedEmail, fallback);
            writeCachedContext(normalizedEmail, fallback);
            return fallback;
        }
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

    private AuthenticatedUserContext applyAdminEmailOverride(String email, AuthenticatedUserContext context) {
        if (context == null || !isConfiguredAdminEmail(email)) {
            return context;
        }

        List<String> elevatedPermissions = fallbackPermissions(List.of("ADMIN"), true, List.of("*"));
        return new AuthenticatedUserContext(
                context.email(),
                UserRole.ADMIN,
                List.of("*"),
                elevatedPermissions);
    }

    private boolean isDbInCooldown(String email, String operation) {
        long retryAt = retryAfterEpochMs.get();
        long now = System.currentTimeMillis();
        if (retryAt > now) {
            if (cooldownLogPrinted.compareAndSet(false, true)) {
                long remainingMs = retryAt - now;
                LOGGER.warn(
                        "Skipping DB lookup for {} while datasource is unavailable (email='{}', retryInMs={})",
                        operation,
                        email,
                        remainingMs);
            }
            return true;
        }
        return false;
    }

    private void markDbUnavailable(String email, String operation, RuntimeException ex) {
        long retryAt = System.currentTimeMillis() + dbRetryCooldownMs;
        retryAfterEpochMs.set(retryAt);
        cooldownLogPrinted.set(false);
        LOGGER.warn(
                "DB unavailable while resolving {} for '{}': {}. Next retry in {} ms",
                operation,
                email,
                ex.getMessage(),
                dbRetryCooldownMs);
    }

    private void clearCooldownIfNeeded() {
        if (retryAfterEpochMs.get() != 0) {
            retryAfterEpochMs.set(0);
            cooldownLogPrinted.set(false);
            LOGGER.info("Datasource connectivity restored; DB-backed authorization resumed.");
        }
    }

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

    private AuthenticatedUserContext toAuthenticatedContext(AppUser user) {
        List<String> roleNames = userRoleMappingRepository.findRoleNamesByUserId(user.getId()).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();

        List<String> permissionNames = rolePermissionMappingRepository.findPermissionNamesByUserId(user.getId()).stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
            .toList();

        boolean admin = roleNames.stream()
            .map(value -> value.toUpperCase(Locale.ROOT))
            .anyMatch(ADMIN_ROLE::equals)
            || currentAuthenticationIsAdmin()
            || hasAdminPermission(permissionNames);

        UserRole role = admin ? UserRole.ADMIN : UserRole.USER;

        List<String> allowedServices = resolveServicesForUser(user.getId(), admin).stream()
                .map(AppService::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();

        if (permissionNames.isEmpty()) {
            permissionNames = fallbackPermissions(roleNames, admin, allowedServices);
        }

        if (allowedServices.isEmpty() && !admin) {
            allowedServices = extractAllowedServicesFromAuthentication();
        }

        if (allowedServices.isEmpty() && !admin && hasReadPermission(permissionNames)) {
            allowedServices = appServiceRepository.findByIsActiveTrue().stream()
                    .map(AppService::getName)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(name -> !name.isBlank())
                    .distinct()
                    .toList();
        }

        LOGGER.debug(
                "getUserAccessContext email='{}' userId='{}' roleNames='{}' allowedServicesCount={} permissionsCount={}",
                user.getEmail(),
                user.getId(),
                roleNames,
                allowedServices.size(),
                permissionNames.size());

        return new AuthenticatedUserContext(user.getEmail(), role, allowedServices, permissionNames);
    }

    private AuthenticatedUserContext buildFallbackContext(String email) {
        boolean admin = currentAuthenticationIsAdmin();
        List<String> allowedServices = admin
                ? appServiceRepository.findByIsActiveTrue().stream()
                        .map(AppService::getName)
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .distinct()
                        .toList()
                : extractAllowedServicesFromAuthentication();

        if (!admin && allowedServices.isEmpty() && failOpenWhenDbUnavailable) {
            allowedServices = List.of("*");
        }

        List<String> permissions = fallbackPermissions(List.of(), admin, allowedServices);
        if (!admin && permissions.isEmpty() && failOpenWhenDbUnavailable) {
            permissions = DEFAULT_READ_PERMISSIONS;
        }
        return new AuthenticatedUserContext(email, admin ? UserRole.ADMIN : UserRole.USER, allowedServices, permissions);
    }

    private AuthenticatedUserContext buildDbUnavailableFallbackContext(String email) {
        boolean admin = currentAuthenticationIsAdmin();
        List<String> allowedServices = extractAllowedServicesFromAuthentication();

        if (allowedServices.isEmpty() && failOpenWhenDbUnavailable) {
            // During temporary DB outages, keep authenticated users operational in read-only mode.
            allowedServices = List.of("*");
        }

        List<String> permissions;
        if (admin) {
            permissions = fallbackPermissions(List.of(), true, allowedServices);
        } else if (failOpenWhenDbUnavailable) {
            permissions = DEFAULT_READ_PERMISSIONS;
        } else {
            permissions = List.of();
        }

        return new AuthenticatedUserContext(email, admin ? UserRole.ADMIN : UserRole.USER, allowedServices, permissions);
    }

    private AuthenticatedUserContext buildAuthenticatedReadFallbackContext(String email) {
        boolean admin = currentAuthenticationIsAdmin();
        List<String> allowedServices = List.of("*");
        List<String> permissions = admin
                ? fallbackPermissions(List.of(), true, allowedServices)
                : DEFAULT_READ_PERMISSIONS;
        return new AuthenticatedUserContext(email, admin ? UserRole.ADMIN : UserRole.USER, allowedServices, permissions);
    }

    private java.util.Optional<AppUser> resolveUserByEmailCandidates(String rawEmail) {
        List<String> candidates = candidateEmails(rawEmail);
        for (String candidate : candidates) {
            java.util.Optional<AppUser> user = appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue(candidate);
            if (user.isPresent()) {
                return user;
            }
        }
        return java.util.Optional.empty();
    }

    private List<String> candidateEmails(String rawEmail) {
        String normalized = normalizeEmailForLookup(rawEmail);
        if (normalized.isBlank()) {
            return List.of();
        }

        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<>();
        candidates.add(normalized);

        int extMarker = normalized.indexOf("#ext#");
        if (extMarker > 0) {
            String externalPrefix = normalized.substring(0, extMarker);
            String recovered = externalPrefix.replace('_', '@');
            if (!recovered.isBlank()) {
                candidates.add(recovered);
            }
        }

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

    private List<AppService> resolveServicesFromContext(AuthenticatedUserContext context) {
        if (context == null) {
            return List.of();
        }

        // ADMIN always gets ALL active services — no filtering
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

        if (normalized.contains("*")) {
            return appServiceRepository.findByIsActiveTrue();
        }

        return appServiceRepository.findByIsActiveTrue().stream()
                .filter(service -> service.getName() != null
                        && normalized.contains(service.getName().trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

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

    private List<String> extractAllowedServicesFromAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return List.of();
        }

        Object principal = authentication.getPrincipal();
        Object raw = null;
        if (principal instanceof OidcUser oidcUser) {
            raw = oidcUser.getAttribute("allowed_services");
        } else if (principal instanceof OAuth2User oauth2User) {
            raw = oauth2User.getAttribute("allowed_services");
        }

        if (raw instanceof Iterable<?> iterable) {
            return toDistinctStringList(iterable);
        }

        return List.of();
    }

    private List<String> fallbackPermissions(List<String> roleNames, boolean admin, List<String> allowedServices) {
        if (admin) {
            return List.of(
                    "logs:read",
                    "logs:write",
                    "metrics:read",
                    "alerts:read",
                    "alerts:write",
                    PERMISSION_SERVICES_READ,
                    PERMISSION_SERVICES_MANAGE,
                    PERMISSION_USERS_MANAGE);
        }

        Set<String> normalizedRoles = roleNames.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());

        boolean roleCanManageUsers = normalizedRoles.stream()
                .anyMatch(role -> role.contains("ADMIN") || role.contains("USER_MANAGER"));
        boolean roleCanManageServices = normalizedRoles.stream()
                .anyMatch(role -> role.contains("ADMIN") || role.contains("SERVICE_MANAGER"));

        java.util.LinkedHashSet<String> computedPermissions = new java.util.LinkedHashSet<>();

        if (normalizedRoles.contains("DEVELOPER") || normalizedRoles.contains("DEV")) {
            computedPermissions.addAll(DEFAULT_READ_PERMISSIONS);
        }

        if (normalizedRoles.contains("VIEWER")) {
            computedPermissions.add("logs:read");
            computedPermissions.add("metrics:read");
            computedPermissions.add(PERMISSION_SERVICES_READ);
        }

        if (computedPermissions.isEmpty() && allowedServices != null && !allowedServices.isEmpty()) {
            computedPermissions.addAll(DEFAULT_READ_PERMISSIONS);
        }

        if (roleCanManageUsers) {
            computedPermissions.add(PERMISSION_USERS_MANAGE);
        }

        if (roleCanManageServices) {
            computedPermissions.add(PERMISSION_SERVICES_MANAGE);
        }

        return List.copyOf(computedPermissions);
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

    private boolean hasReadPermission(List<String> permissionNames) {
        if (permissionNames == null || permissionNames.isEmpty()) {
            return false;
        }

        return permissionNames.stream()
                .filter(Objects::nonNull)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .anyMatch(value -> "logs:read".equals(value) || "metrics:read".equals(value));
    }

    private boolean containsAdminValue(Iterable<?> values) {
        for (Object value : values) {
            if (value != null && value.toString().toUpperCase(Locale.ROOT).contains(ADMIN_ROLE)) {
                return true;
            }
        }
        return false;
    }

    private List<String> toDistinctStringList(Iterable<?> values) {
        return java.util.stream.StreamSupport.stream(values.spliterator(), false)
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private AuthenticatedUserContext readCachedContext(String email) {
        return readCachedContext(email, false).orElse(null);
    }

    private java.util.Optional<AuthenticatedUserContext> readCachedContext(String email, boolean allowExpired) {
        CachedAccessContext cached = contextCache.get(email);
        if (cached == null) {
            return java.util.Optional.empty();
        }

        long now = System.currentTimeMillis();
        if (allowExpired || cached.expiresAtEpochMs() > now) {
            return java.util.Optional.of(cached.context());
        }

        contextCache.remove(email, cached);
        return java.util.Optional.empty();
    }

    private void writeCachedContext(String email, AuthenticatedUserContext context) {
        contextCache.put(email, new CachedAccessContext(context, System.currentTimeMillis() + contextCacheTtlMs));
    }

    private record CachedAccessContext(AuthenticatedUserContext context, long expiresAtEpochMs) {
    }
}
