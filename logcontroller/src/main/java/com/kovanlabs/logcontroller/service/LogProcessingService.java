package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.parser.LogParser;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Service
public class LogProcessingService {

    private static final long SERVICE_ALIAS_CACHE_TTL_MS = 30_000L;

    @Autowired
    private LogParser parser;

    @Autowired
    private ElasticRepository repository;

    @Autowired
    private AppServiceRepository appServiceRepository;

    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();
    private volatile Map<String, String> activeServiceAliasMap = Map.of();
    private volatile long aliasCacheLoadedAtMs = 0L;

    public void process(String rawLog) {
        buffer.add(rawLog);
        if (buffer.size() >= 50) {
            drain();
        }
    }

    public void processKafkaRecord(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return;
        }

        LogEvent event = parser.parse(rawLog);
        if (event == null || !isServiceApproved(event)) {
            return;
        }

        repository.save(event);
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
                .forEach(repository::save);
    }

    private boolean isServiceApproved(LogEvent event) {
        if (event == null || event.getService() == null || event.getService().isBlank()) {
            return false;
        }

        String canonicalServiceName = resolveApprovedServiceName(event.getService());
        if (canonicalServiceName == null) {
            return false;
        }

        event.setService(canonicalServiceName);
        return true;
    }

    private String resolveApprovedServiceName(String rawServiceName) {
        if (rawServiceName == null || rawServiceName.isBlank()) {
            return null;
        }

        String trimmed = rawServiceName.trim();
        if (appServiceRepository.existsByNameIgnoreCaseAndIsActiveTrue(trimmed)) {
            return trimmed;
        }

        refreshServiceAliasCacheIfStale();
        Map<String, String> aliasMap = activeServiceAliasMap;
        if (aliasMap.isEmpty()) {
            return null;
        }

        String lower = trimmed.toLowerCase();
        String normalized = normalizeServiceName(trimmed);

        String canonical = aliasMap.get(lower);
        if (canonical != null) {
            return canonical;
        }

        if (!normalized.isBlank()) {
            canonical = aliasMap.get(normalized);
            if (canonical != null) {
                return canonical;
            }
        }

        return null;
    }

    private void refreshServiceAliasCacheIfStale() {
        long now = System.currentTimeMillis();
        if (now - aliasCacheLoadedAtMs < SERVICE_ALIAS_CACHE_TTL_MS && !activeServiceAliasMap.isEmpty()) {
            return;
        }

        synchronized (this) {
            long recheckNow = System.currentTimeMillis();
            if (recheckNow - aliasCacheLoadedAtMs < SERVICE_ALIAS_CACHE_TTL_MS && !activeServiceAliasMap.isEmpty()) {
                return;
            }

            Map<String, String> aliases = new HashMap<>();
            appServiceRepository.findByIsActiveTrue().forEach(service -> {
                if (service == null || service.getName() == null || service.getName().isBlank()) {
                    return;
                }

                String canonical = service.getName().trim();
                aliases.put(canonical.toLowerCase(), canonical);

                String normalized = normalizeServiceName(canonical);
                if (!normalized.isBlank()) {
                    aliases.put(normalized, canonical);
                }
            });

            activeServiceAliasMap = Map.copyOf(aliases);
            aliasCacheLoadedAtMs = recheckNow;
        }
    }

    private String normalizeServiceName(String value) {
        if (value == null) {
            return "";
        }

        return value.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }
}