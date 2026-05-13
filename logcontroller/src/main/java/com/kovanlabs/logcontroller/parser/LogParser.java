package com.kovanlabs.logcontroller.parser;

import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.model.LogEvent;

@Component
public class LogParser {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogParser.class);

    private final ObjectMapper mapper = new ObjectMapper();

    public LogEvent parse(String message) {
        if (message == null || message.trim().isEmpty()) {
            return fallbackEvent("");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> log = mapper.readValue(message, Map.class);

            LOGGER.debug("RAW KAFKA JSON keys: {}", log.keySet());

            // Handle nested JSON in the message field (e.g. logstash-style wrapping).
            // IMPORTANT: only merge keys that are NOT already present in the outer map.
            // log.putAll(inner) would overwrite the Filebeat @timestamp and service fields
            // with stale application-level values, causing index date mismatches and
            // service resolution failures.
            Object msg = log.get("message");
            if (msg instanceof String && ((String) msg).startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = mapper.readValue((String) msg, Map.class);
                    // Only add keys absent from the outer map — never overwrite Filebeat fields
                    for (Map.Entry<String, Object> entry : inner.entrySet()) {
                        log.putIfAbsent(entry.getKey(), entry.getValue());
                    }
                } catch (Exception ignored) {
                    // Keep original message when nested parsing fails.
                }
            }

            LogEvent event = new LogEvent();

            // ── @timestamp ────────────────────────────────────────────────────────────
            event.setTimestamp(firstString(log, "@timestamp", "timestamp", "time", "datetime"));

            // ── service ───────────────────────────────────────────────────────────────
            // Filebeat enriched payloads do not have a flat "service" field.
            // Check common Filebeat locations before falling back to generic keys.
            String service = resolveService(log);
            event.setService(service);

            // ── level ─────────────────────────────────────────────────────────────────
            // Filebeat wraps the log level inside a nested "log" object: {"log":{"level":"info"}}
            // Always normalize to uppercase so ES keyword queries ("INFO", "ERROR") match consistently.
            String level = firstString(log, "level", "severity", "logLevel");
            if (level == null || level.isBlank()) {
                level = nestedString(log, "log", "level");
            }
            if (level != null) {
                level = level.trim().toUpperCase(java.util.Locale.ROOT);
            }
            event.setLevel(level);

            // ── message ───────────────────────────────────────────────────────────────
            event.setMessage(firstString(log, "message", "msg", "log_message", "event"));

            // ── host / instance ───────────────────────────────────────────────────────
            // Filebeat puts hostname inside a nested "host" object: {"host":{"name":"..."}}
            String instance = firstString(log, "instance", "hostname", "pod");
            if (instance == null || instance.isBlank()) {
                instance = nestedString(log, "host", "name");
            }
            event.setInstance(instance);

            // ── remaining flat fields ─────────────────────────────────────────────────
            event.setEnvironment(firstString(log, "environment", "env"));
            event.setTraceId(firstString(log, "traceId", "trace_id", "trace"));
            event.setSpanId(firstString(log, "spanId", "span_id", "span"));
            event.setUserId(firstString(log, "userId", "user_id", "user"));
            event.setEndpoint(firstString(log, "endpoint", "path", "url"));
            event.setMethod(firstString(log, "method", "httpMethod"));
            event.setStatusCode(asInteger(firstValue(log, "statusCode", "status", "httpStatus")));
            event.setResponseTime(asDouble(firstValue(log, "responseTime", "latency", "durationMs")));
            event.setErrorCode(firstString(log, "errorCode", "code"));
            event.setErrorDetails(firstString(log, "errorDetails", "stack", "exception"));
            event.setTags(log.get("tags"));

            // ── defaults ──────────────────────────────────────────────────────────────
            if (event.getService() == null || event.getService().isBlank()) {
                event.setService("unknown");
            }
            if (event.getLevel() == null || event.getLevel().isBlank()) {
                event.setLevel("INFO");
            }
            if (event.getMessage() == null || event.getMessage().isBlank()) {
                event.setMessage(message);
            }
            if (event.getTimestamp() == null || event.getTimestamp().isBlank()) {
                event.setTimestamp(Instant.now().toString());
            }

            LOGGER.debug("PARSED EVENT → service='{}' level='{}' timestamp='{}' message='{}'",
                    event.getService(), event.getLevel(), event.getTimestamp(),
                    truncate(event.getMessage(), 120));

            return event;

        } catch (Exception e) {
            LOGGER.warn("Failed to parse Kafka JSON log, falling back to raw message. Error: {}", e.getMessage());
            return fallbackEvent(message);
        }
    }

    // ── Service resolution ────────────────────────────────────────────────────────
    //
    // Priority order (highest → lowest):
    //
    //   1. fields.service / fields.app  — explicit custom field in filebeat.yml (most reliable)
    //   2. Flat top-level keys          — service, serviceName, project, app, application
    //   3. ECS service.name             — standard ECS field
    //   4. log.logger                   — Java logger name, strip package prefix
    //   5. host.name                    — hostname as last meaningful fallback
    //   6. agent.name                   — LAST RESORT: almost always "filebeat", not the service
    //
    // NOTE: agent.name was previously at position 3, causing ALL services without
    // fields.service to resolve to "filebeat" → not in DB → silent drop.
    @SuppressWarnings("unchecked")
    private String resolveService(Map<String, Object> log) {
        // 1. fields.service / fields.app — highest priority custom field
        Object fieldsObj = log.get("fields");
        if (fieldsObj instanceof Map<?, ?> fields) {
            String svc = stringOrNull(((Map<String, Object>) fields).get("service"));
            if (svc != null) return svc;
            svc = stringOrNull(((Map<String, Object>) fields).get("app"));
            if (svc != null) return svc;
        }

        // 2. Flat top-level keys (simple / non-Filebeat payloads)
        String flat = firstString(log,
                "service", "serviceName", "service_name",
                "project", "projectName",
                "app", "app_name", "application");
        if (flat != null && !flat.isBlank()) return flat;

        // 3. ECS service.name
        String ecsService = nestedString(log, "service", "name");
        if (ecsService != null && !ecsService.isBlank()) return ecsService;

        // 4. log.logger — strip package prefix to get the short class/service name
        String loggerName = nestedString(log, "log", "logger");
        if (loggerName != null && !loggerName.isBlank()) {
            // e.g. "com.example.orderservice.OrderController" → "orderservice"
            String[] parts = loggerName.split("\\.");
            if (parts.length >= 2) return parts[parts.length - 2];
            return parts[parts.length - 1];
        }

        // 5. host.name
        String hostName = nestedString(log, "host", "name");
        if (hostName != null && !hostName.isBlank()) return hostName;

        // 6. agent.name — LAST RESORT (usually "filebeat", not the service name)
        String agentName = nestedString(log, "agent", "name");
        if (agentName != null && !agentName.isBlank()
                && !"filebeat".equalsIgnoreCase(agentName)
                && !"metricbeat".equalsIgnoreCase(agentName)) {
            return agentName;
        }

        return null; // caller sets "unknown"
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private LogEvent fallbackEvent(String rawMessage) {
        LogEvent event = new LogEvent();
        event.setService("unknown");
        event.setLevel("INFO");
        event.setMessage(rawMessage == null ? "" : rawMessage);
        event.setTimestamp(Instant.now().toString());
        return event;
    }

    /** Reads a value from a nested map: log.get(outerKey) → Map → get(innerKey). */
    @SuppressWarnings("unchecked")
    private String nestedString(Map<String, Object> log, String outerKey, String innerKey) {
        Object outer = log.get(outerKey);
        if (outer instanceof Map<?, ?> map) {
            return stringOrNull(((Map<String, Object>) map).get(innerKey));
        }
        return null;
    }

    private String stringOrNull(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    private Object firstValue(Map<String, Object> data, String... keys) {
        for (String key : keys) {
            if (data.containsKey(key) && data.get(key) != null) {
                return data.get(key);
            }
        }
        return null;
    }

    private String firstString(Map<String, Object> data, String... keys) {
        Object value = firstValue(data, keys);
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number number) return number.doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "…";
    }
}