package com.kovanlabs.logcontroller.controller;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.PermissionName;
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import com.kovanlabs.logcontroller.service.LogProcessingService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

import static org.springframework.http.HttpStatus.FORBIDDEN;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@RequestMapping("/logs")
public class LogController {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogController.class);

    @Autowired
    private LogProcessingService processingService;

    @Autowired
    private ElasticRepository elasticRepository;

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
        List<LogEvent> logs =
                elasticRepository.search(service, environment, level, traceId, message, from, to, page, size, context);

        return ResponseEntity.ok(logs);
    }

    @GetMapping("/services")
    public ResponseEntity<List<String>> services(
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "size", defaultValue = "200") int size
    ) {
        AuthenticatedUserContext context = accessAuthorizationService.getCurrentUserAccessContext();
        requirePermission(context, PermissionName.LOGS_READ);
        return ResponseEntity.ok(elasticRepository.getDistinctServices(from, to, size, context));
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
        return ResponseEntity.ok(elasticRepository.getMetrics(service, from, to, timePreset, context));
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