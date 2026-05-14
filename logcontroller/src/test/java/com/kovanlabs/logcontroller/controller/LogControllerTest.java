package com.kovanlabs.logcontroller.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.jpa.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import com.kovanlabs.logcontroller.service.LogProcessingService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class LogControllerTest {

    @Mock private LogProcessingService processingService;
    @Mock private ElasticRepository elasticRepository;
    @Mock private AppServiceRepository appServiceRepository;
    @Mock private ServiceAccessAuthorizationService accessAuthorizationService;

    @InjectMocks private LogController controller;

    private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private AuthenticatedUserContext adminContext;
    private AuthenticatedUserContext devContext;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();

        adminContext = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN,
                List.of("payment-service", "auth-service"),
                List.of(PermissionName.LOGS_READ, PermissionName.LOGS_WRITE, PermissionName.METRICS_READ));

        devContext = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV,
                List.of("payment-service"),
                List.of(PermissionName.LOGS_READ, PermissionName.LOGS_WRITE));
    }

    // ── ingest ────────────────────────────────────────────────────────────────

    @Test
    void ingest_adminWithWritePermission_returns200() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);

        LogEvent log = logEvent("payment-service", "ERROR", "DB timeout");
        mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(log)))
                .andExpect(status().isOk())
                .andExpect(content().string("Log received"));

        verify(processingService).process(anyString());
    }

    @Test
    void ingest_devWithWritePermission_returns200() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(devContext);

        LogEvent log = logEvent("payment-service", "INFO", "Request processed");
        mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(log)))
                .andExpect(status().isOk())
                .andExpect(content().string("Log received"));
    }

    @Test
    void ingest_missingWritePermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"), List.of());
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logEvent("payment-service", "INFO", "msg"))))
                .andExpect(status().isForbidden());

        verify(processingService, never()).process(anyString());
    }

    @Test
    void ingest_processingServiceThrowsRuntimeException_returns500() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        doThrow(new RuntimeException("Kafka down")).when(processingService).process(anyString());

        mockMvc.perform(post("/api/logs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logEvent("payment-service", "ERROR", "msg"))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Error processing log"));
    }

    // ── ingestBatch ───────────────────────────────────────────────────────────

    @Test
    void ingestBatch_twoLogs_processesEachAndReturnsCount() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);

        List<LogEvent> logs = List.of(
                logEvent("auth-service", "ERROR", "Token expired"),
                logEvent("auth-service", "INFO", "Login success"));

        mockMvc.perform(post("/api/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(logs)))
                .andExpect(status().isOk())
                .andExpect(content().string("Received 2 logs"));

        verify(processingService, times(2)).process(anyString());
    }

    @Test
    void ingestBatch_missingWritePermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(post("/api/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(logEvent("svc", "INFO", "msg")))))
                .andExpect(status().isForbidden());

        verify(processingService, never()).process(anyString());
    }

    @Test
    void ingestBatch_processingThrows_returns500() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        doThrow(new RuntimeException("ES down")).when(processingService).process(anyString());

        mockMvc.perform(post("/api/logs/batch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(List.of(logEvent("svc", "ERROR", "msg")))))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string("Error processing batch"));
    }

    // ── search ────────────────────────────────────────────────────────────────

    @Test
    void search_withNoFilters_returnsLogsFromElastic() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        LogEvent event = logEvent("payment-service", "ERROR", "DB timeout");
        when(elasticRepository.searchMulti(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(List.of(event));

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].service").value("payment-service"));
    }

    @Test
    void search_elasticThrows_returnsEmptyList() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.searchMulti(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any())).thenThrow(new RuntimeException("ES unavailable"));

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void search_elasticReturnsNull_returnsEmptyList() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.searchMulti(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(null);

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void search_missingReadPermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(get("/api/logs"))
                .andExpect(status().isForbidden());
    }

    @Test
    void search_commaSeparatedServiceParam_splitAndPassedCorrectly() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.searchMulti(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/logs").param("service", "auth-service,payment-service"))
                .andExpect(status().isOk());

        verify(elasticRepository).searchMulti(
                argThat(svcs -> svcs.contains("auth-service") && svcs.contains("payment-service")),
                any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    @Test
    void search_multipleServiceParams_mergedAndDeduped() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.searchMulti(any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt(), any())).thenReturn(List.of());

        mockMvc.perform(get("/api/logs")
                        .param("services", "auth-service")
                        .param("services", "auth-service"))
                .andExpect(status().isOk());

        verify(elasticRepository).searchMulti(
                argThat(svcs -> svcs.size() == 1 && svcs.contains("auth-service")),
                any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
    }

    // ── services ──────────────────────────────────────────────────────────────

    @Test
    void services_adminSeesAllActiveServices() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(appServiceRepository.findByIsActiveTrue()).thenReturn(List.of(
                appService("payment-service"), appService("auth-service")));

        mockMvc.perform(get("/api/logs/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$", containsInAnyOrder("payment-service", "auth-service")));
    }

    @Test
    void services_devSeesOnlyAllowedServices() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(devContext);
        when(appServiceRepository.findByIsActiveTrue()).thenReturn(List.of(
                appService("payment-service"), appService("auth-service"), appService("order-service")));

        mockMvc.perform(get("/api/logs/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0]").value("payment-service"));
    }

    @Test
    void services_repositoryThrows_returnsEmptyList() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(appServiceRepository.findByIsActiveTrue()).thenThrow(new RuntimeException("DB down"));

        mockMvc.perform(get("/api/logs/services"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void services_missingReadPermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(get("/api/logs/services"))
                .andExpect(status().isForbidden());
    }

    // ── metrics ───────────────────────────────────────────────────────────────

    @Test
    void metrics_adminWithPermission_returnsMetricsPayload() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        Map<String, Object> payload = Map.of("totalLogs", 100L, "errorCount", 5L, "errorRate", 5.0);
        when(elasticRepository.getMetrics(any(), any(), any(), any(), any())).thenReturn(payload);

        mockMvc.perform(get("/api/logs/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLogs").value(100))
                .andExpect(jsonPath("$.errorCount").value(5));
    }

    @Test
    void metrics_elasticThrows_returnsFallbackPayload() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.getMetrics(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("ES timeout"));

        mockMvc.perform(get("/api/logs/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLogs").value(0))
                .andExpect(jsonPath("$.errorCount").value(0))
                .andExpect(jsonPath("$.errorRate").value(0.0));
    }

    @Test
    void metrics_elasticReturnsNull_returnsFallbackPayload() throws Exception {
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(adminContext);
        when(elasticRepository.getMetrics(any(), any(), any(), any(), any())).thenReturn(null);

        mockMvc.perform(get("/api/logs/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalLogs").value(0));
    }

    @Test
    void metrics_missingMetricsReadPermission_returns403() throws Exception {
        AuthenticatedUserContext noPermCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("payment-service"),
                List.of(PermissionName.LOGS_READ));
        when(accessAuthorizationService.getCurrentUserAccessContext()).thenReturn(noPermCtx);

        mockMvc.perform(get("/api/logs/metrics"))
                .andExpect(status().isForbidden());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LogEvent logEvent(String service, String level, String message) {
        LogEvent e = new LogEvent();
        e.setService(service);
        e.setLevel(level);
        e.setMessage(message);
        e.setTimestamp("2026-05-01T10:00:00Z");
        return e;
    }

    private AppService appService(String name) {
        AppService s = new AppService();
        s.setName(name);
        s.setActive(true);
        return s;
    }
}
