package com.kovanlabs.logcontroller.notification.service;

import java.util.Locale;

/**
 * Ordered severity levels used for preference filtering.
 *
 * <p>Ordinal order: INFO(0) < WARNING(1) < ERROR(2) < CRITICAL(3).
 * A user with {@code minSeverity = "WARNING"} receives WARNING, ERROR, and CRITICAL alerts.
 */
public enum SeverityLevel {
    INFO,
    WARNING,
    ERROR,
    CRITICAL;

    /**
     * Parses a severity string case-insensitively.
     * Returns {@link #WARNING} as a safe default for unrecognised values.
     */
    public static SeverityLevel fromString(String value) {
        if (value == null || value.isBlank()) {
            return WARNING;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return WARNING;
        }
    }

    /** Returns {@code true} if this level is at least as severe as {@code threshold}. */
    public boolean isAtLeast(SeverityLevel threshold) {
        return this.ordinal() >= threshold.ordinal();
    }
}
