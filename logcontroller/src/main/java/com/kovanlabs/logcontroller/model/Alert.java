package com.kovanlabs.logcontroller.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Immutable value object representing one active alert slot for a (service, severity) pair.
 *
 * <p>Immutability is intentional: the {@link com.kovanlabs.logcontroller.service.AlertService}
 * replaces the entire object on every upsert rather than mutating fields in-place. This
 * eliminates the need for field-level synchronization and makes concurrent reads safe without
 * any locking.
 *
 * <p>Timestamps are always UTC {@link Instant} — no JVM-local time, no IST/UTC drift.
 */
public final class Alert {

    private final String service;
    private final String message;
    private final int count;
    private final String severity;
    private final Instant timestamp;

    public Alert(String service, String message, int count, String severity, Instant timestamp) {
        this.service   = Objects.requireNonNull(service,   "service must not be null");
        this.message   = Objects.requireNonNull(message,   "message must not be null");
        this.severity  = Objects.requireNonNull(severity,  "severity must not be null");
        this.timestamp = Objects.requireNonNull(timestamp, "timestamp must not be null");
        this.count     = count;
    }

    public String getService()    { return service; }
    public String getMessage()    { return message; }
    public int    getCount()      { return count; }
    public String getSeverity()   { return severity; }
    public Instant getTimestamp() { return timestamp; }

    /**
     * Returns a new {@code Alert} with updated count, message, and timestamp,
     * keeping service and severity unchanged (they are part of the map key).
     */
    public Alert withUpdate(int newCount, String newMessage, Instant newTimestamp) {
        return new Alert(this.service, newMessage, newCount, this.severity, newTimestamp);
    }

    @Override
    public String toString() {
        return "Alert{service='" + service + "', severity='" + severity +
               "', count=" + count + ", timestamp=" + timestamp + '}';
    }
}
