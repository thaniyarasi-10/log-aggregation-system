package com.kovanlabs.logcontroller.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
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
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import com.kovanlabs.logcontroller.service.LogProcessingService;
import com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.OPTIONS})
@RequestMapping("/logs")
public class LogController {

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
        try {
            String json = mapper.writeValueAsString(log); //convert to JSON
            processingService.process(json);
            return ResponseEntity.ok("Log received");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Error processing log");
        }
    }

    // batch logs
    @PostMapping("/batch")
    public ResponseEntity<String> ingestBatch(@RequestBody List<LogEvent> logs) {
        try {
            for (LogEvent log : logs) {
                String json = mapper.writeValueAsString(log);
                processingService.process(json);
            }
            return ResponseEntity.ok("Received " + logs.size() + " logs");
        } catch (Exception e) {
            e.printStackTrace();
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
        return ResponseEntity.ok(elasticRepository.getMetrics(service, from, to, timePreset, context));
    }
}