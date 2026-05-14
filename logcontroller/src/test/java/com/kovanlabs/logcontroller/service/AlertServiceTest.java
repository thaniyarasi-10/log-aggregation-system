package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertServiceTest {

    @Mock private ElasticRepository repository;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private AlertService alertService;

    @BeforeEach
    void setUp() {
        // Default: no global errors, no per-service errors
        when(repository.countErrorsInWindow(anyString())).thenReturn(0L);
        when(repository.countErrorsByServiceInWindow(anyString(), anyInt())).thenReturn(Map.of());
    }

    // ── checkAlerts — global threshold ───────────────────────────────────────

    @Test
    void checkAlerts_globalErrorsAboveThreshold_createsGlobalAlert() {
        when(repository.countErrorsInWindow("1m")).thenReturn(5L);

        alertService.checkAlerts();

        List<Alert> alerts = alertService.getActiveAlerts();
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).getService()).isEqualTo("ALL-SERVICES");
        assertThat(alerts.get(0).getCount()).isEqualTo(5);
    }

    @Test
    void checkAlerts_globalErrorsBelowThreshold_noGlobalAlert() {
        when(repository.countErrorsInWindow("1m")).thenReturn(0L);

        alertService.checkAlerts();

        assertThat(alertService.getActiveAlerts()).isEmpty();
    }

    // ── checkAlerts — per-service thresholds ─────────────────────────────────

    @Test
    void checkAlerts_serviceErrorsAtWarningThreshold_createsWarningAlert() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 1L));

        alertService.checkAlerts();

        List<Alert> alerts = alertService.getActiveAlerts();
        Alert paymentAlert = alerts.stream()
                .filter(a -> "payment-service".equals(a.getService()))
                .findFirst().orElseThrow();
        assertThat(paymentAlert.getSeverity()).isEqualTo("WARNING");
        assertThat(paymentAlert.getCount()).isEqualTo(1);
    }

    @Test
    void checkAlerts_serviceErrorsAtCriticalThreshold_createsCriticalAlert() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L));

        alertService.checkAlerts();

        Alert alert = alertService.getActiveAlerts().stream()
                .filter(a -> "payment-service".equals(a.getService()))
                .findFirst().orElseThrow();
        assertThat(alert.getSeverity()).isEqualTo("CRITICAL");
    }

    @Test
    void checkAlerts_serviceErrorsBelowWarningThreshold_noAlertCreated() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 0L));

        alertService.checkAlerts();

        assertThat(alertService.getActiveAlerts()).isEmpty();
    }

    @Test
    void checkAlerts_multipleServices_createsAlertForEach() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 3L, "auth-service", 1L));

        alertService.checkAlerts();

        List<Alert> alerts = alertService.getActiveAlerts();
        assertThat(alerts.stream().map(Alert::getService))
                .containsExactlyInAnyOrder("payment-service", "auth-service");
    }

    // ── event publishing ──────────────────────────────────────────────────────

    @Test
    void checkAlerts_newAlert_publishesAlertGeneratedEvent() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L));

        alertService.checkAlerts();

        ArgumentCaptor<AlertGeneratedEvent> captor = ArgumentCaptor.forClass(AlertGeneratedEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        assertThat(captor.getValue().getAlert().getService()).isEqualTo("payment-service");
        assertThat(captor.getValue().isSeverityTransition()).isFalse();
    }

    @Test
    void checkAlerts_countUpdateOnExistingAlert_doesNotPublishEvent() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L));

        alertService.checkAlerts(); // creates alert — publishes event
        alertService.checkAlerts(); // updates count only — no new event

        // Event published only once (for the new alert)
        verify(eventPublisher, times(1)).publishEvent(any(AlertGeneratedEvent.class));
    }

    @Test
    void checkAlerts_eventPublisherThrows_doesNotPropagateException() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L));
        doThrow(new RuntimeException("Event bus down")).when(eventPublisher).publishEvent(any());

        // Must not throw
        alertService.checkAlerts();
    }

    // ── getActiveAlertsGroupedByService ──────────────────────────────────────

    @Test
    void getActiveAlertsGroupedByService_groupsByServiceName() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L, "auth-service", 1L));

        alertService.checkAlerts();

        Map<String, List<Alert>> grouped = alertService.getActiveAlertsGroupedByService();
        assertThat(grouped).containsKeys("payment-service", "auth-service");
        assertThat(grouped.get("payment-service")).hasSize(1);
    }

    @Test
    void getActiveAlertsGroupedByService_noAlerts_returnsEmptyMap() {
        Map<String, List<Alert>> grouped = alertService.getActiveAlertsGroupedByService();
        assertThat(grouped).isEmpty();
    }

    // ── getActiveAlerts ordering ──────────────────────────────────────────────

    @Test
    void getActiveAlerts_sortedByTimestampDescending() {
        when(repository.countErrorsByServiceInWindow("2m", 100))
                .thenReturn(Map.of("payment-service", 2L, "auth-service", 1L));

        alertService.checkAlerts();

        List<Alert> alerts = alertService.getActiveAlerts();
        // Timestamps should be in descending order
        for (int i = 0; i < alerts.size() - 1; i++) {
            assertThat(alerts.get(i).getTimestamp())
                    .isAfterOrEqualTo(alerts.get(i + 1).getTimestamp());
        }
    }

    // ── Alert.withUpdate ─────────────────────────────────────────────────────

    @Test
    void alertWithUpdate_returnsNewInstanceWithUpdatedFields() {
        Alert original = new Alert("svc", "original msg", 1, "WARNING",
                java.time.Instant.parse("2026-01-01T00:00:00Z"));
        java.time.Instant newTs = java.time.Instant.parse("2026-01-02T00:00:00Z");

        Alert updated = original.withUpdate(5, "new msg", newTs);

        assertThat(updated.getCount()).isEqualTo(5);
        assertThat(updated.getMessage()).isEqualTo("new msg");
        assertThat(updated.getTimestamp()).isEqualTo(newTs);
        // Immutable fields unchanged
        assertThat(updated.getService()).isEqualTo("svc");
        assertThat(updated.getSeverity()).isEqualTo("WARNING");
        // Original not mutated
        assertThat(original.getCount()).isEqualTo(1);
    }
}
