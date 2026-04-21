package com.kovanlabs.logcontroller.controller;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.model.AppService;
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import com.kovanlabs.logcontroller.service.LogProcessingService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@RequestMapping({"/logs", "/api/logs"})
public class LogController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogController.class);

    @Autowired
    private LogProcessingService processingService;

    @Autowired
    private ElasticRepository elasticRepository;

    @Autowired
    private AppServiceRepository appServiceRepository;

    @Autowired
    private ServiceAccessAuthorizationService accessAuthorizationService;

    // single log
    private final ObjectMapper mapper = new ObjectMapper();

    @PostMapping
    public ResponseEntity<String> ingest(@RequestBody LogEvent log) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.LOGS_WRITE);

        try {
            String json = mapper.writeValueAsString(log); //convert to JSON
            processingService.process(json);
            return ResponseEntity.ok("Log received");
        } catch (JsonProcessingException ex) {
            LOGGER.error("Error processing single log", ex);
            return ResponseEntity.status(500).body("Error processing log");
        } catch (RuntimeException ex) {
            LOGGER.error("Unexpected error processing single log", ex);
            return ResponseEntity.status(500).body("Error processing log");
        }
    }

    // batch logs
    @PostMapping("/batch")
    public ResponseEntity<String> ingestBatch(@RequestBody List<LogEvent> logs) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.LOGS_WRITE);

        try {
            for (LogEvent log : logs) {
                String json = mapper.writeValueAsString(log);
                processingService.process(json);
            }
            return ResponseEntity.ok("Received " + logs.size() + " logs");
        } catch (JsonProcessingException ex) {
            LOGGER.error("Error processing batch logs", ex);
            return ResponseEntity.status(500).body("Error processing batch");
        } catch (RuntimeException ex) {
            LOGGER.error("Unexpected error processing batch logs", ex);
            return ResponseEntity.status(500).body("Error processing batch");
        }
    }

    // search logs
    @GetMapping
    public ResponseEntity<List<LogEvent>> search(
            @RequestParam(value = "service", required = false) String service,
            @RequestParam(value = "environment", required = false) String environment,
            @RequestParam(value = "level", required = false) String level,
            @RequestParam(value = "traceId", required = false) String traceId,
            @RequestParam(value = "message", required = false) String message,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size
    ) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.LOGS_READ);
        try {
            List<LogEvent> logs = elasticRepository.search(
                    service,
                    environment,
                    level,
                    traceId,
                    message,
                    from,
                    to,
                    page,
                    size,
                    context
            );
            return ResponseEntity.ok(logs == null ? List.of() : logs);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to fetch logs, returning empty result: {}", ex.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/services")
    public ResponseEntity<List<String>> services() {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.LOGS_READ);
        try {
            List<AppService> activeServices = appServiceRepository.findByIsActiveTrue();
            List<String> services;

            if (context.isAdmin()) {
                services = activeServices.stream()
                        .map(AppService::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .toList();
            } else {
                Set<String> allowed = context.allowedServices().stream()
                        .filter(value -> value != null && !value.isBlank())
                        .map(value -> value.trim().toLowerCase(Locale.ROOT))
                        .collect(java.util.stream.Collectors.toSet());

                boolean allowAll = allowed.contains("*");
                services = activeServices.stream()
                        .map(AppService::getName)
                        .filter(name -> name != null && !name.isBlank())
                        .filter(name -> allowAll || allowed.contains(name.trim().toLowerCase(Locale.ROOT)))
                        .distinct()
                        .toList();
            }

            return ResponseEntity.ok(services == null ? List.of() : services);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to fetch service list, returning empty result: {}", ex.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    @GetMapping("/metrics")
    public ResponseEntity<Map<String, Object>> metrics(
            @RequestParam(value = "service", required = false) String service,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "timePreset", required = false) String timePreset
    ) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.METRICS_READ);
        try {
            Map<String, Object> metrics = elasticRepository.getMetrics(service, from, to, timePreset, context);
            return ResponseEntity.ok(metrics == null ? defaultMetricsResponse() : metrics);
        } catch (RuntimeException ex) {
            LOGGER.warn("Failed to fetch metrics, returning fallback payload: {}", ex.getMessage());
            return ResponseEntity.ok(defaultMetricsResponse());
        }
    }

    private Map<String, Object> defaultMetricsResponse() {
        return Map.of(
                "totalLogs", 0,
                "errorCount", 0,
                "errorRate", 0.0,
                "avgResponseTime", 0.0,
                "p95Latency", 0.0,
                "bucketInterval", "1m",
                "throughputOverTime", List.of(),
                "levelDistribution", List.of()
        );
    }

    private void requirePermission(AuthenticatedUserContext context, String permission) {
        if (context.isAdmin()) {
            return;
        }
        if (!context.hasPermission(permission)) {
            throw new ResponseStatusException(FORBIDDEN, "Missing required permission: " + permission);
        }
    }
}