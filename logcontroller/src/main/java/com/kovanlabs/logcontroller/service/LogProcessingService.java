package com.kovanlabs.logcontroller.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.parser.LogParser;
import com.kovanlabs.logcontroller.jpa.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.ElasticRepository;

@Service
public class LogProcessingService {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogProcessingService.class);

    // How long an approved service name stays in the local cache before being
    // re-validated against the database. 60 seconds balances freshness vs DB load.
    // A service deactivated in the admin panel will stop being accepted within 60s.
    private static final long SERVICE_CACHE_TTL_MS = 60_000L;

    @Autowired
    private LogParser parser;

    @Autowired
    private ElasticRepository repository;

    @Autowired
    private MongoLogPersistenceService mongoLogPersistenceService;

    @Autowired
    private AppServiceRepository appServiceRepository;

    /**
     * Role-aware broadcaster — replaces the old global
     * {@code messagingTemplate.convertAndSend("/topic/logs", event)} call.
     * Each log is delivered only to users who are authorised to see it.
     */
    @Autowired
    private WebSocketLogBroadcaster webSocketLogBroadcaster;

    // ── Service approval cache ────────────────────────────────────────────────
    // Caches approved service names with a TTL to avoid a synchronous PostgreSQL
    // query on every Kafka record. Without this cache, each message triggers a DB
    // round-trip that becomes the primary bottleneck at high throughput, causing
    // consumer lag to grow unboundedly.
    //
    // Structure: serviceName → expiry timestamp (System.currentTimeMillis() + TTL)
    private final ConcurrentHashMap<String, Long> approvedServiceCache = new ConcurrentHashMap<>();

    // ── Throughput counters for lag visibility ────────────────────────────────
    private final AtomicLong processedSinceLastReport = new AtomicLong(0);
    private final AtomicLong droppedSinceLastReport   = new AtomicLong(0);

    // ── Legacy buffer (used by process() path only) ───────────────────────────
    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

    // ─────────────────────────────────────────────────────────────────────────
    // Kafka fast path — called directly by LogConsumer for every Kafka record.
    // This method must return quickly to avoid blocking the consumer thread and
    // causing max.poll.interval.ms violations that trigger rebalances.
    // ─────────────────────────────────────────────────────────────────────────
    public void processKafkaRecord(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return;
        }

        LogEvent event = parser.parse(rawLog);
        if (event == null) {
            LOGGER.warn("Kafka record produced null event after parsing — skipping. Raw (truncated): {}",
                    rawLog.length() > 200 ? rawLog.substring(0, 200) + "…" : rawLog);
            droppedSinceLastReport.incrementAndGet();
            return;
        }

        if (!isServiceApproved(event)) {
            // Warning is logged inside isServiceApproved — just count and return.
            droppedSinceLastReport.incrementAndGet();
            return;
        }

        mongoLogPersistenceService.save(event);
        repository.save(event);

        // Broadcast to each user's private queue based on their role and service mappings.
        // ADMIN users receive all events; DEV users receive only events for their mapped services.
        LOGGER.debug("KAFKA PIPELINE — persisted service='{}' level='{}', broadcasting to WebSocket clients",
                event.getService(), event.getLevel());
        webSocketLogBroadcaster.broadcast(event);

        processedSinceLastReport.incrementAndGet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Legacy buffer path — kept for any callers that use process() directly.
    // Not used by the Kafka consumer.
    // ─────────────────────────────────────────────────────────────────────────
    public void process(String rawLog) {
        buffer.add(rawLog);
        if (buffer.size() >= 50) {
            drain();
        }
    }

    // flush every 5 seconds regardless of buffer size
    @Scheduled(fixedDelay = 5000)
    public void scheduledDrain() {
        if (!buffer.isEmpty()) {
            drain();
        }
    }

    private synchronized void drain() {
        if (buffer.isEmpty()) return;

        List<String> batch = new ArrayList<>();
        while (!buffer.isEmpty()) {
            batch.add(buffer.poll());
        }

        List<LogEvent> processed = batch.stream()
                .map(parser::parse)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        processed.stream()
                .filter(this::isServiceApproved)
                .forEach(event -> {
                    LOGGER.debug("Buffered log parsed. Persisting to MongoDB for service={}", event.getService());
                    mongoLogPersistenceService.save(event);
                    LOGGER.debug("MongoDB save call completed for buffered service={}", event.getService());
                    repository.save(event);
                    webSocketLogBroadcaster.broadcast(event);
                });
    }


    @Scheduled(fixedDelay = 30_000)
    public void logThroughputReport() {
        long processed = processedSinceLastReport.getAndSet(0);
        long dropped   = droppedSinceLastReport.getAndSet(0);
        long cacheSize = approvedServiceCache.size();

        if (processed == 0 && dropped == 0) {
            LOGGER.debug("KAFKA THROUGHPUT — no records in last 30s (topic idle or consumer stopped)");}
//        else {
//            LOGGER.info("KAFKA THROUGHPUT — processed={} dropped={} approvedServicesCached={} (last 30s)",
//                    processed, dropped, cacheSize);
//        }
    }

    @Scheduled(fixedDelay = 300_000)
    public void evictExpiredServiceCache() {
        long now = System.currentTimeMillis();
        int before = approvedServiceCache.size();
        approvedServiceCache.entrySet().removeIf(entry -> now >= entry.getValue());
        int evicted = before - approvedServiceCache.size();
        if (evicted > 0) {
            LOGGER.debug("Service approval cache eviction — removed {} expired entries, {} remaining",
                    evicted, approvedServiceCache.size());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Service approval check with in-memory cache.
    //
    // Without the cache: every Kafka record triggers a synchronous SELECT against
    // PostgreSQL (Supabase, remote). At 100 msg/s that's 100 DB queries/s — the
    // round-trip latency alone (10-50ms) caps throughput at 20-100 msg/s per thread
    // and causes consumer lag to grow.
    //
    // With the cache: the DB is queried at most once per service per 60 seconds,
    // regardless of message rate. 99%+ of records are served from the local map.
    // ─────────────────────────────────────────────────────────────────────────
    private boolean isServiceApproved(LogEvent event) {
        if (event == null || event.getService() == null || event.getService().isBlank()) {
            LOGGER.warn("SERVICE CHECK: null or blank service — dropping log");
            return false;
        }

        String normalizedServiceName = normalizeServiceName(event.getService());
        long now = System.currentTimeMillis();

        // Cache hit — still within TTL
        Long expiry = approvedServiceCache.get(normalizedServiceName);
        if (expiry != null && now < expiry) {
            LOGGER.debug("SERVICE CHECK: '{}' approved (cache hit) — proceeding to persist", normalizedServiceName);
            event.setService(normalizedServiceName);
            return true;
        }

        // Cache miss or expired — query the database
        boolean isApproved = appServiceRepository.existsByNameAndIsActiveTrue(normalizedServiceName);

        if (isApproved) {
            approvedServiceCache.put(normalizedServiceName, now + SERVICE_CACHE_TTL_MS);
            LOGGER.debug("SERVICE CHECK: '{}' approved (DB lookup, cached for {}ms) — proceeding to persist",
                    normalizedServiceName, SERVICE_CACHE_TTL_MS);
            event.setService(normalizedServiceName);
            return true;
        }

        // Not approved — evict any stale cache entry and warn
        approvedServiceCache.remove(normalizedServiceName);
        LOGGER.warn("SERVICE CHECK: '{}' (normalized from '{}') is not registered/active — dropping log. " +
                "Add this service via the admin panel to allow ingestion.",
                normalizedServiceName, event.getService());
        return false;
    }

    private String normalizeServiceName(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).trim();
    }
}
