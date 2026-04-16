package com.kovanlabs.logcontroller.service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.UserAccountRole;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.AppUserRepository;
import com.kovanlabs.logcontroller.repository.UserServiceMappingRepository;

@Service
public class ServiceAccessAuthorizationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceAccessAuthorizationService.class);

    private final AppUserRepository appUserRepository;
    private final AppServiceRepository appServiceRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;
    private final OAuthUserEmailResolver emailResolver;
    private final long dbRetryCooldownMs;

    private final AtomicLong retryAfterEpochMs = new AtomicLong(0);
    private final AtomicBoolean cooldownLogPrinted = new AtomicBoolean(false);

    public ServiceAccessAuthorizationService(
            AppUserRepository appUserRepository,
            AppServiceRepository appServiceRepository,
            UserServiceMappingRepository userServiceMappingRepository,
            OAuthUserEmailResolver emailResolver,
            @Value("${app.auth.db-retry-cooldown-ms:5000}") long dbRetryCooldownMs) {
        this.appUserRepository = appUserRepository;
        this.appServiceRepository = appServiceRepository;
        this.userServiceMappingRepository = userServiceMappingRepository;
        this.emailResolver = emailResolver;
        this.dbRetryCooldownMs = Math.max(1000L, dbRetryCooldownMs);
    }

    public AuthenticatedUserContext getCurrentUserAccessContext() {
        return emailResolver.getCurrentUserEmail()
                .map(this::getUserAccessContext)
                .orElseGet(() -> new AuthenticatedUserContext("unknown@local", UserRole.USER, List.of()));
    }

    public List<AppService> getAccessibleServices(String email) {
        if (email == null || email.isBlank()) {
            LOGGER.debug("getAccessibleServices email is blank -> returning 0 services");
            return List.of();
        }

        if (isDbInCooldown(email, "services")) {
            return List.of();
        }

        try {
            String normalizedEmail = email.trim();
            List<AppService> services = appUserRepository.findByEmailIgnoreCase(normalizedEmail)
                    .map(user -> {
                        UserAccountRole role = user.getRole();
                    List<AppService> resolvedServices = resolveServicesForUser(user);
                        LOGGER.debug(
                                "getAccessibleServices email='{}' userId='{}' role='{}' servicesReturned={}",
                                normalizedEmail,
                                user.getId(),
                                role,
                        resolvedServices.size());
                    return resolvedServices;
                    })
                    .orElseGet(() -> {
                        LOGGER.debug("getAccessibleServices email='{}' user not found -> returning 0 services", normalizedEmail);
                        return List.of();
                    });
            clearCooldownIfNeeded();
            return services;
        } catch (RuntimeException ex) {
            markDbUnavailable(email, "services", ex);
            return List.of();
        }
    }

    public AuthenticatedUserContext getUserAccessContext(String email) {
        if (email == null || email.isBlank()) {
            return new AuthenticatedUserContext("unknown@local", UserRole.USER, List.of());
        }

        if (isDbInCooldown(email, "access context")) {
            return new AuthenticatedUserContext(email.trim(), UserRole.USER, List.of());
        }

        try {
            AuthenticatedUserContext context = appUserRepository.findByEmailIgnoreCase(email.trim())
                    .map(this::toAuthenticatedContext)
                    .orElseGet(() -> new AuthenticatedUserContext(email.trim(), UserRole.USER, List.of()));
            clearCooldownIfNeeded();
            return context;
        } catch (RuntimeException ex) {
            markDbUnavailable(email, "access context", ex);
            // Strict authorization: DB outage must not grant broad access.
            return new AuthenticatedUserContext(email.trim(), UserRole.USER, List.of());
        }
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

    private List<AppService> resolveServicesForUser(AppUser user) {
        UserAccountRole role = user.getRole();
        if (role == UserAccountRole.ADMIN) {
            return appServiceRepository.findAll().stream()
                    .filter(Objects::nonNull)
                    .toList();
        }

        if (role == UserAccountRole.DEV) {
            return userServiceMappingRepository.findServicesByUserId(user.getId()).stream()
                    .filter(Objects::nonNull)
                    .toList();
        }

        return List.<AppService>of();
    }

    private AuthenticatedUserContext toAuthenticatedContext(AppUser user) {
        UserRole role = user.getRole() == UserAccountRole.ADMIN ? UserRole.ADMIN : UserRole.USER;

        List<String> allowedServices = resolveServicesForUser(user).stream()
                .map(AppService::getName)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .toList();

        LOGGER.debug(
                "getUserAccessContext email='{}' userId='{}' role='{}' allowedServicesCount={}",
                user.getEmail(),
                user.getId(),
                user.getRole(),
                allowedServices.size());

        return new AuthenticatedUserContext(user.getEmail(), role, allowedServices);
    }
}
