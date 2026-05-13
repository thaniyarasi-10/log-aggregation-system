package com.kovanlabs.logcontroller.notification.service;

import com.kovanlabs.logcontroller.jpa.repository.AppUserRepository;
import com.kovanlabs.logcontroller.jpa.repository.NotificationPreferenceRepository;
import com.kovanlabs.logcontroller.model.AppUser;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

/**
 * Manages per-user notification preferences.
 *
 * <p>Preferences are created on first access (upsert semantics). A user who has never
 * configured preferences gets the safe default: email enabled.
 */
@Service
public class NotificationPreferenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationPreferenceService.class);

    private final NotificationPreferenceRepository preferenceRepository;
    private final AppUserRepository appUserRepository;

    public NotificationPreferenceService(
            NotificationPreferenceRepository preferenceRepository,
            AppUserRepository appUserRepository) {
        this.preferenceRepository = preferenceRepository;
        this.appUserRepository    = appUserRepository;
    }

    /**
     * Returns the preference for the given user email, creating and persisting a default
     * row if none exists. The row is flushed immediately so the caller always receives
     * a managed, persisted entity with a valid ID — never a transient in-memory object.
     */
    @Transactional
    public NotificationPreference getOrCreatePreference(String email) {
        AppUser user = appUserRepository.findByEmailIgnoreCaseAndIsActiveTrue(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "User not found: " + email));

        return preferenceRepository.findByUser_Id(user.getId())
                .orElseGet(() -> createDefault(user));
    }

    /**
     * Returns the preference for the given user ID, or a transient default if none exists.
     * Used internally by the notification pipeline only — does NOT persist a new row.
     * Never call this from a user-facing endpoint; use {@link #getOrCreatePreference} instead.
     */
    @Transactional(readOnly = true)
    public NotificationPreference getPreferenceOrDefault(String userId) {
        return preferenceRepository.findByUser_Id(userId)
                .orElseGet(() -> {
                    // Transient default — intentionally not persisted here.
                    // The pipeline only needs to read emailEnabled; it never writes back.
                    NotificationPreference defaults = new NotificationPreference();
                    defaults.setEmailEnabled(true);
                    return defaults;
                });
    }

    /**
     * Updates the email-enabled toggle for the given user.
     *
     * @param email        the user's email
     * @param emailEnabled whether to receive email alerts
     * @return the saved preference
     */
    @Transactional
    public NotificationPreference updatePreference(String email, boolean emailEnabled) {
        NotificationPreference pref = getOrCreatePreference(email);
        pref.setEmailEnabled(emailEnabled);
        pref.setUpdatedAt(LocalDateTime.now());
        NotificationPreference saved = preferenceRepository.save(pref);
        LOGGER.info("Notification preference updated — email='{}' enabled={}", email, emailEnabled);
        return saved;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private NotificationPreference createDefault(AppUser user) {
        NotificationPreference pref = new NotificationPreference();
        pref.setUser(user);
        pref.setEmailEnabled(true);
        pref.setCreatedAt(LocalDateTime.now());
        pref.setUpdatedAt(LocalDateTime.now());
        // saveAndFlush forces an immediate INSERT within the current transaction.
        // Using save() alone defers the INSERT to flush time, which can be after the
        // transaction boundary in some proxy configurations — causing the row to appear
        // missing on the next read within the same request.
        NotificationPreference saved = preferenceRepository.saveAndFlush(pref);
        LOGGER.info("Created default notification preference — userId='{}' emailEnabled=true", user.getId());
        return saved;
    }
}
