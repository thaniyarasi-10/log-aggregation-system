package com.kovanlabs.logcontroller.controller;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.service.AlertService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "http://localhost:3000", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.OPTIONS})
@RequestMapping("/alerts")
public class AlertController {

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private AlertService alertService;

    @GetMapping
    public Map<String, List<Map<String, Object>>> getAlerts() {
        return alertService.getRecentAlertsGroupedByService().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().stream().map(this::toResponse).collect(Collectors.toList()),
                        (left, right) -> left,
                        LinkedHashMap::new
                ));
    }

    private Map<String, Object> toResponse(Alert alert) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", alert.getService());
        data.put("message", alert.getMessage());
        data.put("count", alert.getCount());
        data.put("severity", alert.getSeverity());
        data.put("timestamp", alert.getTimestamp() != null ? alert.getTimestamp().format(TS_FORMAT) : null);
        return data;
    }
}
