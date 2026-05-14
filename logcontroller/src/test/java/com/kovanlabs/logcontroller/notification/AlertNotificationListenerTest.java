package com.kovanlabs.logcontroller.notification;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import com.kovanlabs.logcontroller.notification.listener.AlertNotificationListener;
import com.kovanlabs.logcontroller.notification.service.AlertNotificationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AlertNotificationListenerTest {

    @Mock private AlertNotificationService notificationService;

    @InjectMocks private AlertNotificationListener listener;

    @Test
    void onAlertGenerated_delegatesToNotificationService() {
        AlertGeneratedEvent event = event(false);

        listener.onAlertGenerated(event);

        verify(notificationService).processAlertNotification(event);
    }

    @Test
    void onAlertGenerated_severityTransitionEvent_delegatesWithTransitionFlag() {
        AlertGeneratedEvent event = event(true);

        listener.onAlertGenerated(event);

        verify(notificationService).processAlertNotification(event);
    }

    @Test
    void onAlertGenerated_notificationServiceThrows_doesNotPropagateException() {
        doThrow(new RuntimeException("Notification failed"))
                .when(notificationService).processAlertNotification(any());

        // Must not throw — listener must never disrupt the event multicaster
        listener.onAlertGenerated(event(false));
    }

    private AlertGeneratedEvent event(boolean severityTransition) {
        Alert alert = new Alert("payment-service", "DB errors", 5, "CRITICAL", Instant.now());
        return new AlertGeneratedEvent(this, alert, severityTransition);
    }
}
