package com.kovanlabs.logcontroller.notification.listener;

import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import com.kovanlabs.logcontroller.notification.service.AlertNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Listens for {@link AlertGeneratedEvent} and delegates to the notification service.
 *
 * <h3>Why a separate listener class?</h3>
 * <p>Keeping the listener thin (one method, no business logic) makes it easy to swap
 * the transport in the future — e.g. replace the in-process Spring event with a Kafka
 * consumer by changing only this class, leaving {@link AlertNotificationService} untouched.
 *
 * <h3>Async execution</h3>
 * <p>{@code @Async("notificationExecutor")} ensures the listener returns immediately to
 * the Spring event multicaster. The actual notification work runs on the dedicated
 * {@code notificationExecutor} thread pool. The scheduler thread is never blocked.
 *
 * <h3>Exception handling</h3>
 * <p>Any uncaught exception is caught here and logged. It must never propagate back to
 * the event multicaster, which would suppress subsequent listeners for the same event.
 */
@Component
public class AlertNotificationListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertNotificationListener.class);

    private final AlertNotificationService notificationService;

    public AlertNotificationListener(AlertNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @Async("notificationExecutor")
    @EventListener
    public void onAlertGenerated(AlertGeneratedEvent event) {
        try {
            LOGGER.debug("AlertNotificationListener received event: {}", event);
            notificationService.processAlertNotification(event);
        } catch (Exception ex) {
            // Defensive catch — AlertNotificationService already handles its own errors.
            // This guard ensures the event multicaster is never disrupted.
            LOGGER.error("Unexpected error in AlertNotificationListener for event '{}': {}",
                    event, ex.getMessage(), ex);
        }
    }
}
