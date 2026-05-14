package com.kovanlabs.logcontroller.controller;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.service.AlertService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class AlertControllerTest {

    @Mock private AlertService alertService;
    @Mock private ServiceAccessAuthorizationService accessAuthorizationService;

    @InjectMocks private AlertController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void getAlerts_adminSeesAllServiceGroups() throws Exception {
        AuthenticatedUserContext adminCtx = adminContext();
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminCtx);

        Map<String, List<Alert>> grouped = new LinkedHashMap<>();
        grouped.put("payment-service", List.of(alert("payment-service", "CRITICAL", 5)));
        grouped.put("auth-service",    List.of(alert("auth-service",    "WARNING",  2)));
        when(alertService.getActiveAlertsGroupedByService()).thenReturn(grouped);

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment-service", hasSize(1)))
                .andExpect(jsonPath("$.payment-service[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.payment-service[0].count").value(5))
                .andExpect(jsonPath("$.auth-service", hasSize(1)));
    }

    @Test
    void getAlerts_devUserSeesOnlyAllowedServices() throws Exception {
        AuthenticatedUserContext devCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV,
                List.of("payment-service"),
                List.of(PermissionName.ALERTS_READ));
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(devCtx);

        Map<String, List<Alert>> grouped = new LinkedHashMap<>();
        grouped.put("payment-service", List.of(alert("payment-service", "WARNING", 3)));
        grouped.put("auth-service",    List.of(alert("auth-service",    "CRITICAL", 10)));
        when(alertService.getActiveAlertsGroupedByService()).thenReturn(grouped);

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment-service", hasSize(1)))
                .andExpect(jsonPath("$.auth-service").doesNotExist());
    }

    @Test
    void getAlerts_devWithoutAlertsReadPermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"), List.of());
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isForbidden());

        verify(alertService, never()).getActiveAlertsGroupedByService();
    }

    @Test
    void getAlerts_noActiveAlerts_returnsEmptyMap() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext());
        when(alertService.getActiveAlertsGroupedByService()).thenReturn(Map.of());

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(content().json("{}"));
    }

    @Test
    void getAlerts_alertWithNullTimestamp_serialisesTimestampAsNull() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext());

        Alert alertWithNullTs = new Alert("payment-service", "DB error", 1, "WARNING", Instant.now());
        Map<String, List<Alert>> grouped = Map.of("payment-service", List.of(alertWithNullTs));
        when(alertService.getActiveAlertsGroupedByService()).thenReturn(grouped);

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment-service[0].service").value("payment-service"))
                .andExpect(jsonPath("$.payment-service[0].message").value("DB error"));
    }

    @Test
    void getAlerts_responseContainsAllRequiredFields() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext());
        Alert a = alert("payment-service", "CRITICAL", 7);
        when(alertService.getActiveAlertsGroupedByService())
                .thenReturn(Map.of("payment-service", List.of(a)));

        mockMvc.perform(get("/api/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment-service[0].service").exists())
                .andExpect(jsonPath("$.payment-service[0].message").exists())
                .andExpect(jsonPath("$.payment-service[0].count").exists())
                .andExpect(jsonPath("$.payment-service[0].severity").exists())
                .andExpect(jsonPath("$.payment-service[0].timestamp").exists());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private AuthenticatedUserContext adminContext() {
        return new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN,
                List.of("payment-service", "auth-service"),
                List.of(PermissionName.ALERTS_READ));
    }

    private Alert alert(String service, String severity, int count) {
        return new Alert(service, service + " has " + count + " errors", count, severity, Instant.now());
    }
}
