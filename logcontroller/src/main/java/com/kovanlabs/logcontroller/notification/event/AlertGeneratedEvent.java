package com.kovanlabs.logcontroller.notification.event;

import com.kovanlabs.logcontroller.model.Alert;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Objects;

/**
 * Spring {@link ApplicationEvent} published whenever a new alert is created or
 * its severity transitions (e.g. WARNING → CRITICAL).
 *
 * <p>This event is the single integration point between the alert engine and the
 * notification layer. The alert engine publishes it; the notification listener
 * consumes it asynchronously on a dedicated thread pool — the scheduler is never
 * blocked.
 *
 * <p>The event carries a snapshot of the {@link Alert} at the moment of publication.
 * Because {@link Alert} is immutable, the snapshot is safe to pass across threads
 * without copying.
 */
public class AlertGeneratedEvent extends ApplicationEvent {

    private final Alert alert;
    private final boolean severityTransition;
    private final Instant publishedAt;

    /**
     * @param source             the object that published the event (typically {@code AlertService})
     * @param alert              immutable snapshot of the alert that was created/updated
     * @param severityTransition {@code true} when the alert replaced an existing entry with a
     *                           different severity (e.g. WARNING → CRITICAL); used by the
     *                           notification layer to bypass the cooldown for escalations
     */
    public AlertGeneratedEvent(Object source, Alert alert, boolean severityTransition) {
        super(source);
        this.alert             = Objects.requireNonNull(alert, "alert must not be null");
        this.severityTransition = severityTransition;
        this.publishedAt       = Instant.now();
    }

    public Alert getAlert() {
        return alert;
    }

    public boolean isSeverityTransition() {
        return severityTransition;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    @Override
    public String toString() {
        return "AlertGeneratedEvent{service='" + alert.getService() +
               "', severity='" + alert.getSeverity() +
               "', transition=" + severityTransition +
               ", publishedAt=" + publishedAt + '}';
    }
}
