package com.kovanlabs.logcontroller.controller;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.service.AlertService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Exposes active alerts to the frontend.
 *
 * <p>Response shape — keyed by service name:
 * <pre>
 * {
 *   "service-name": [
 *     { "service": "...", "message": "...", "count": N, "severity": "...", "timestamp": "..." }
 *   ]
 * }
 * </pre>
 *
 * <p>Timestamps are UTC ISO-8601 strings (e.g. {@code 2026-05-08T10:30:00Z}).
 * <p>RBAC: requires {@code alerts:read} permission. Admins see all services;
 * non-admins see only alerts for their mapped services.
 */
@RestController
@CrossOrigin(
        origins = "http://localhost:3000",
        allowedHeaders = "*",
        methods = {RequestMethod.GET, RequestMethod.OPTIONS}
)
@RequestMapping("/api/alerts")
public class AlertController {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertController.class);

    @Autowired
    private AlertService alertService;

    @Autowired
    private ServiceAccessAuthorizationService accessAuthorizationService;

    @GetMapping
    public Map<String, List<Map<String, Object>>> getAlerts() {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();

        if (!context.isAdmin() && !context.hasPermission(PermissionName.ALERTS_READ)) {
            LOGGER.warn("GET /alerts — 403 for email='{}'", context.email());
            throw new ResponseStatusException(FORBIDDEN,
                    "Missing required permission: " + PermissionName.ALERTS_READ);
        }

        Map<String, List<Alert>> grouped = alertService.getActiveAlertsGroupedByService();

        Map<String, List<Map<String, Object>>> result = grouped.entrySet().stream()
                .filter(entry -> context.isAdmin() || context.isServiceAllowed(entry.getKey()))
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream()
                                .map(this::toResponse)
                                .collect(Collectors.toList()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        LOGGER.debug("GET /alerts — email='{}' returning {} group(s)",
                context.email(), result.size());

        return result;
    }

    private Map<String, Object> toResponse(Alert alert) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service",   alert.getService());
        data.put("message",   alert.getMessage());
        data.put("count",     alert.getCount());
        data.put("severity",  alert.getSeverity());
        data.put("timestamp", alert.getTimestamp() != null ? alert.getTimestamp().toString() : null);
        return data;
    }
}
