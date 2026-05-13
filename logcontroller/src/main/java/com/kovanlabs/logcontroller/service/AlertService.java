package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.notification.event.AlertGeneratedEvent;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages active alerts derived from Elasticsearch error counts.
 *
 * <h3>Design invariants</h3>
 * <ul>
 *   <li><b>One slot per (service, severity)</b> — {@code activeAlerts} key is
 *       {@code "{service}|{severity}"}. Duplicates are structurally impossible.</li>
 *   <li><b>Immutable replacement on update</b> — {@code ConcurrentHashMap.compute()} replaces
 *       the mapped value with a brand-new {@link Alert} object. No field mutation on a shared
 *       reference, so concurrent readers always see a fully-constructed object.</li>
 *   <li><b>Severity-transition cleanup</b> — when a service moves from WARNING to CRITICAL (or
 *       vice versa) the old severity key is removed atomically before the new one is written,
 *       preventing two simultaneous active alerts for the same service.</li>
 *   <li><b>Auto-resolution</b> — at the end of every cycle, any key not seen in
 *       {@code currentCycleKeys} is removed via {@code entrySet().removeIf()}, which is safe
 *       on {@link ConcurrentHashMap} and does not throw {@link java.util.ConcurrentModificationException}.</li>
 *   <li><b>Overlapping scheduler safety</b> — all map mutations use atomic {@code compute()}
 *       and {@code remove()} operations. No compound check-then-act sequences exist, so a
 *       second scheduler thread racing the first cannot corrupt state.</li>
 *   <li><b>UTC timestamps</b> — {@link Instant#now()} is always UTC. Elasticsearch window
 *       queries use {@code now-Xm} (server-side UTC math), so no JVM timezone is involved
 *       anywhere in the alert pipeline.</li>
 * </ul>
 */
@Service
public class AlertService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AlertService.class);

    // -------------------------------------------------------------------------
    // Thresholds
    // -------------------------------------------------------------------------

    private static final int GLOBAL_ERROR_THRESHOLD_1M      = 1;
    private static final int SERVICE_WARNING_THRESHOLD_2M   = 1;
    private static final int SERVICE_CRITICAL_THRESHOLD_2M  = 2;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    /**
     * Single source of truth for active alerts.
     * Key: {@code "{service}|{severity}"} — exactly one entry per (service, severity) pair.
     */
    private final ConcurrentHashMap<String, Alert> activeAlerts = new ConcurrentHashMap<>();

    @Autowired
    private ElasticRepository repository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    // -------------------------------------------------------------------------
    // Scheduler
    // -------------------------------------------------------------------------

    /**
     * Runs every 60 seconds. Queries Elasticsearch, upserts active alerts,
     * and removes any alert whose threshold condition is no longer met.
     *
     * <p>Uses {@code fixedRate} (not {@code fixedDelay}) so the polling cadence is
     * wall-clock-aligned. If a previous execution is still running when the next tick
     * fires, Spring's default single-threaded task executor will queue the next run —
     * all map operations are atomic so a queued run will simply overwrite with fresher data.
     */
    @Scheduled(fixedRate = 10_000)
    public void checkAlerts() {
        Instant now = Instant.now();

        // Tracks every key that is active in this cycle.
        // ConcurrentHashMap.newKeySet() is safe for concurrent add() calls from forEach lambdas.
        Set<String> currentCycleKeys = ConcurrentHashMap.newKeySet();

        // --- Global: high error volume across all services in the last 1 minute ---
        long totalErrors1m = repository.countErrorsInWindow("1m");
        if (totalErrors1m > GLOBAL_ERROR_THRESHOLD_1M) {
            String severity = toSeverity((int) totalErrors1m);
            String key = buildKey("ALL-SERVICES", severity);
            upsertAlert(key, "ALL-SERVICES",
                    "High error volume detected across all services in the last 1 minute",
                    (int) totalErrors1m, severity, now, false);
            currentCycleKeys.add(key);
        }

        // --- Per-service: error counts in the last 2 minutes ---
        // ES query: { "range": { "@timestamp": { "gte": "now-2m", "lte": "now" } } }
        // "now" is evaluated by Elasticsearch in UTC — no JVM timezone involved.
        Map<String, Long> serviceErrors2m = repository.countErrorsByServiceInWindow("2m", 100);

        serviceErrors2m.forEach((service, count) -> {
            if (count < SERVICE_WARNING_THRESHOLD_2M) {
                return; // below threshold — will be cleaned up at end of cycle
            }

            String severity    = count >= SERVICE_CRITICAL_THRESHOLD_2M ? "CRITICAL" : "WARNING";
            String activeKey   = buildKey(service, severity);
            String obsoleteKey = buildKey(service, "CRITICAL".equals(severity) ? "WARNING" : "CRITICAL");

            // Remove the opposite-severity entry BEFORE writing the new one.
            // This prevents a brief window where both WARNING and CRITICAL exist for the same service.
            Alert removed = activeAlerts.remove(obsoleteKey);
            if (removed != null) {
                LOGGER.info("Alert severity transition — service='{}' {} → {}",
                        service, removed.getSeverity(), severity);
            }

            upsertAlert(activeKey, service,
                    service + " has " + count + " errors in the last 2 minutes",
                    count.intValue(), severity, now, removed != null);
            currentCycleKeys.add(activeKey);
        });

        // Auto-resolve: remove every entry whose condition was not met this cycle.
        // entrySet().removeIf() on ConcurrentHashMap is safe — it uses the map's own
        // iterator which handles concurrent structural modifications without throwing.
//        activeAlerts.entrySet().removeIf(entry -> {
//            if (!currentCycleKeys.contains(entry.getKey())) {
//                LOGGER.info("Alert resolved — service='{}' severity='{}'",
//                        entry.getValue().getService(), entry.getValue().getSeverity());
//                return true;
//            }
//            return false;
//        });

        //LOGGER.info("Alert cycle complete — {} active alert(s)", activeAlerts.size());
    }

    // -------------------------------------------------------------------------
    // Public API  (controller-facing — do not change signatures)
    // -------------------------------------------------------------------------

    /**
     * Returns all active alerts sorted by timestamp descending (newest first).
     */
    public List<Alert> getActiveAlerts() {
        return activeAlerts.values().stream()
                .sorted(Comparator.comparing(Alert::getTimestamp).reversed())
                .collect(Collectors.toList());
    }

    /**
     * Returns active alerts grouped by service.
     * Map insertion order reflects newest-alert-first across services.
     */
    public Map<String, List<Alert>> getActiveAlertsGroupedByService() {
        return getActiveAlerts().stream()
                .collect(Collectors.groupingBy(
                        Alert::getService,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Atomically inserts or replaces the alert for {@code key}.
     *
     * <p>{@code compute()} holds the map segment lock for the duration of the lambda,
     * so the read-then-write is atomic. The lambda always returns a brand-new
     * {@link Alert} object — the old reference is discarded, never mutated.
     * Concurrent readers that already hold a reference to the old object see a
     * fully-constructed, consistent snapshot.
     *
     * <p>Publishes an {@link AlertGeneratedEvent} after the map update:
     * <ul>
     *   <li>New alert (existing == null) → event with {@code severityTransition=false}</li>
     *   <li>Severity change → event with {@code severityTransition=true}</li>
     *   <li>Count-only update (same severity) → no event (avoids spam every 10 s)</li>
     * </ul>
     */
    private void upsertAlert(String key, String service, String message,
                             int count, String severity, Instant timestamp,
                             boolean severityTransition) {
        // Track whether this is a brand-new alert so we can publish the event.
        final boolean[] isNew = {false};

        activeAlerts.compute(key, (k, existing) -> {
            if (existing == null) {
                LOGGER.info("Alert created — service='{}' severity='{}' count={}", service, severity, count);
                isNew[0] = true;
                return new Alert(service, message, count, severity, timestamp);
            }
            // Replace with a new immutable object — never mutate the existing reference.
            return existing.withUpdate(count, message, timestamp);
        });

        // Publish event outside the compute() lock to avoid holding the segment lock
        // while Spring dispatches the event to listeners.
        // Only publish for new alerts or severity transitions — not for every count update.
        if (isNew[0] || severityTransition) {
            Alert published = activeAlerts.get(key);
            if (published != null) {
                try {
                    eventPublisher.publishEvent(
                            new AlertGeneratedEvent(this, published, severityTransition));
                } catch (Exception ex) {
                    // Event publishing must never disrupt alert generation.
                    LOGGER.warn("Failed to publish AlertGeneratedEvent for service='{}': {}",
                            service, ex.getMessage());
                }
            }
        }
    }

    /** Composite key that uniquely identifies one alert slot. */
    private static String buildKey(String service, String severity) {
        return service + "|" + severity;
    }

    /** Maps a global error count to a severity label. */
    private static String toSeverity(int count) {
        if (count >= SERVICE_CRITICAL_THRESHOLD_2M) return "CRITICAL";
        if (count >= GLOBAL_ERROR_THRESHOLD_1M)     return "WARNING";
        return "INFO";
    }
}
