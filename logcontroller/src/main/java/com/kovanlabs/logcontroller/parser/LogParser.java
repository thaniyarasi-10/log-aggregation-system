package com.kovanlabs.logcontroller.parser;

import java.time.Instant;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.model.LogEvent;

@Component
public class LogParser {

    private final ObjectMapper mapper = new ObjectMapper();

    public LogEvent parse(String message) {
        if (message == null || message.trim().isEmpty()) {
            return fallbackEvent("");
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> log = mapper.readValue(message, Map.class);

            // handle nested JSON
            Object msg = log.get("message");

            if (msg instanceof String && ((String) msg).startsWith("{")) {
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> inner = mapper.readValue((String) msg, Map.class);
                    log.putAll(inner);
                } catch (Exception ignored) {
                    // Keep original message when nested parsing fails.
                }
            }

            // SAFE conversion
            LogEvent event = new LogEvent();

                event.setService(firstString(
                    log,
                    "service",
                    "serviceName",
                    "service_name",
                    "project",
                    "projectName",
                    "app",
                    "app_name",
                    "application",
                    "logger",
                    "loggerName"
                ));
            event.setLevel(firstString(log, "level", "severity", "logLevel"));
            event.setMessage(firstString(log, "message", "msg", "log", "event"));
            event.setTimestamp(firstString(log, "timestamp", "@timestamp", "time", "datetime"));
            event.setInstance(firstString(log, "instance", "host", "hostname", "pod"));
            event.setEnvironment(firstString(log, "environment", "env"));
            event.setTraceId(firstString(log, "traceId", "trace_id", "trace"));
            event.setSpanId(firstString(log, "spanId", "span_id", "span"));
            event.setUserId(firstString(log, "userId", "user_id", "user"));
            event.setEndpoint(firstString(log, "endpoint", "path", "url"));
            event.setMethod(firstString(log, "method", "httpMethod"));
            event.setStatusCode(asInteger(firstValue(log, "statusCode", "status", "httpStatus")));
            event.setResponseTime(asInteger(firstValue(log, "responseTime", "latency", "durationMs")));
            event.setErrorCode(firstString(log, "errorCode", "code"));
            event.setErrorDetails(firstString(log, "errorDetails", "stack", "exception"));
            event.setTags(log.get("tags"));

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

            return event;

        } catch (Exception e) {
            System.out.println("FAILED TO PARSE JSON LOG, FALLING BACK TO RAW MESSAGE: " + message);
            return fallbackEvent(message);
        }
    }

    private LogEvent fallbackEvent(String rawMessage) {
        LogEvent event = new LogEvent();
        event.setService("unknown");
        event.setLevel("INFO");
        event.setMessage(rawMessage == null ? "" : rawMessage);
        event.setTimestamp(Instant.now().toString());
        return event;
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
}