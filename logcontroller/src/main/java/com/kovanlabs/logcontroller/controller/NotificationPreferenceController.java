package com.kovanlabs.logcontroller.controller;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.model.NotificationPreference;
import com.kovanlabs.logcontroller.notification.service.NotificationPreferenceService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for managing per-user notification preferences.
 *
 * <p>All endpoints are scoped to the currently authenticated user.
 *
 * <h3>Endpoints</h3>
 * <ul>
 *   <li>{@code GET /api/notifications/preferences} — get current user's preferences</li>
 *   <li>{@code PUT /api/notifications/preferences} — update current user's preferences</li>
 * </ul>
 */
@RestController
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.OPTIONS})
@RequestMapping("/api/notifications/preferences")
public class NotificationPreferenceController {

    private static final Logger LOGGER = LoggerFactory.getLogger(NotificationPreferenceController.class);

    private final NotificationPreferenceService preferenceService;
    private final ServiceAccessAuthorizationService accessAuthorizationService;

    public NotificationPreferenceController(
            NotificationPreferenceService preferenceService,
            ServiceAccessAuthorizationService accessAuthorizationService) {
        this.preferenceService        = preferenceService;
        this.accessAuthorizationService = accessAuthorizationService;
    }

    /** Returns the notification preferences for the currently authenticated user. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> getPreferences() {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        NotificationPreference pref = preferenceService.getOrCreatePreference(context.email());
        LOGGER.debug("GET /api/notifications/preferences — email='{}'", context.email());
        return ResponseEntity.ok(toResponse(pref));
    }

    /**
     * Updates the notification preferences for the currently authenticated user.
     *
     * <p>Request body:
     * <pre>{ "emailEnabled": true }</pre>
     */
    @PutMapping
    public ResponseEntity<Map<String, Object>> updatePreferences(@RequestBody Map<String, Object> body) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();

        NotificationPreference current = preferenceService.getOrCreatePreference(context.email());

        boolean emailEnabled = body.containsKey("emailEnabled")
                ? Boolean.parseBoolean(String.valueOf(body.get("emailEnabled")))
                : current.isEmailEnabled();

        NotificationPreference updated = preferenceService.updatePreference(context.email(), emailEnabled);

        LOGGER.info("PUT /api/notifications/preferences — email='{}' enabled={}", context.email(), emailEnabled);

        return ResponseEntity.ok(toResponse(updated));
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private Map<String, Object> toResponse(NotificationPreference pref) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("emailEnabled", pref.isEmailEnabled());
        response.put("createdAt",    pref.getCreatedAt() != null ? pref.getCreatedAt().toString() : null);
        response.put("updatedAt",    pref.getUpdatedAt() != null ? pref.getUpdatedAt().toString() : null);
        return response;
    }
}
