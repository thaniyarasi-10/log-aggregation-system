package com.kovanlabs.logcontroller.notification.service;

import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.RolePermissionMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserRoleMappingRepository;
import com.kovanlabs.logcontroller.jpa.repository.UserServiceMappingRepository;
import com.kovanlabs.logcontroller.model.AppUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the set of email addresses that should receive a notification for a given alert.
 *
 * <h3>RBAC rules (mirrors {@code AlertController.getAlerts()})</h3>
 * <ol>
 *   <li>User must be active ({@code is_active = true}).</li>
 *   <li>User must have the {@code alerts:read} permission (via their role → role_permission_mapping).</li>
 *   <li>ADMIN users receive alerts for ALL services.</li>
 *   <li>Non-admin users receive alerts only for services they are mapped to in {@code user_service}.</li>
 * </ol>
 *
 * <p>This service is intentionally <strong>session-free</strong>. It queries the database
 * directly — no {@code SecurityContextHolder}, no JWT, no active HTTP request required.
 * It works correctly when called from a background scheduler thread.
 */
@Service
public class NotificationRecipientResolver {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationRecipientResolver.class);
    private static final String ADMIN_ROLE = "ADMIN";

    private final AppUserRepository appUserRepository;
    private final UserRoleMappingRepository userRoleMappingRepository;
    private final RolePermissionMappingRepository rolePermissionMappingRepository;
    private final UserServiceMappingRepository userServiceMappingRepository;

    public NotificationRecipientResolver(
            AppUserRepository appUserRepository,
            UserRoleMappingRepository userRoleMappingRepository,
            RolePermissionMappingRepository rolePermissionMappingRepository,
            UserServiceMappingRepository userServiceMappingRepository) {
        this.appUserRepository             = appUserRepository;
        this.userRoleMappingRepository     = userRoleMappingRepository;
        this.rolePermissionMappingRepository = rolePermissionMappingRepository;
        this.userServiceMappingRepository  = userServiceMappingRepository;
    }

    /**
     * Returns the list of {@link RecipientInfo} objects for users who should receive
     * an email notification for an alert on {@code serviceName}.
     *
     * <p>The list is deduplicated by email address (case-insensitive).
     *
     * @param serviceName the service that triggered the alert (e.g. {@code "payment-service"})
     * @return non-null, possibly empty list of eligible recipients
     */
    @Transactional(readOnly = true)
    public List<RecipientInfo> resolveRecipients(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            LOGGER.warn("resolveRecipients called with blank serviceName — returning empty list");
            return List.of();
        }

        List<AppUser> activeUsers = appUserRepository.findByIsActiveTrueOrderByUsernameAsc();
        if (activeUsers.isEmpty()) {
            LOGGER.debug("No active users found — no notification recipients");
            return List.of();
        }

        List<RecipientInfo> recipients = new ArrayList<>();
        Set<String> seenEmails = new HashSet<>();

        for (AppUser user : activeUsers) {
            try {
                processUser(user, serviceName, recipients, seenEmails);
            } catch (Exception ex) {
                // Never let a single user's DB error abort the entire recipient resolution.
                LOGGER.warn("Failed to resolve notification eligibility for userId='{}': {}",
                        user.getId(), ex.getMessage());
            }
        }

        LOGGER.debug("Resolved {} recipient(s) for service='{}'", recipients.size(), serviceName);
        return Collections.unmodifiableList(recipients);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void processUser(AppUser user, String serviceName,
                             List<RecipientInfo> recipients, Set<String> seenEmails) {
        String email = user.getEmail();
        if (email == null || email.isBlank()) {
            return;
        }

        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        if (seenEmails.contains(normalizedEmail)) {
            return; // already added via another path
        }

        // 1. Must have alerts:read permission
        List<String> permissions = rolePermissionMappingRepository
                .findPermissionNamesByUserId(user.getId());
        boolean hasAlertsRead = permissions.stream()
                .filter(Objects::nonNull)
                .map(p -> p.trim().toLowerCase(Locale.ROOT))
                .anyMatch(p -> p.equals(PermissionName.ALERTS_READ));

        if (!hasAlertsRead) {
            LOGGER.trace("User '{}' skipped — no alerts:read permission", email);
            return;
        }

        // 2. Determine role
        List<String> roleNames = userRoleMappingRepository.findRoleNamesByUserId(user.getId());
        boolean isAdmin = roleNames.stream()
                .filter(Objects::nonNull)
                .map(r -> r.trim().toUpperCase(Locale.ROOT))
                .anyMatch(ADMIN_ROLE::equals);

        // 3. Service scope check
        if (!isAdmin) {
            List<String> mappedServices = userServiceMappingRepository
                    .findServiceNamesByUserId(user.getId());
            boolean serviceAllowed = mappedServices.stream()
                    .filter(Objects::nonNull)
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .anyMatch(s -> s.equals(serviceName.trim().toLowerCase(Locale.ROOT)));

            if (!serviceAllowed) {
                LOGGER.trace("User '{}' skipped — service '{}' not in their mapped services",
                        email, serviceName);
                return;
            }
        }

        seenEmails.add(normalizedEmail);
        recipients.add(new RecipientInfo(user.getId(), email, user.getUsername(), isAdmin));
        LOGGER.debug("Recipient resolved — email='{}' admin={} service='{}'",
                email, isAdmin, serviceName);
    }

    // -------------------------------------------------------------------------
    // Value object
    // -------------------------------------------------------------------------

    /**
     * Lightweight DTO carrying the resolved recipient's identity.
     * Passed to the notification service to avoid re-querying the DB.
     */
    public record RecipientInfo(String userId, String email, String username, boolean admin) {}
}
