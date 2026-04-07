package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.Alert;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.stream.Collectors;

@Service
public class AlertService {

    private static final int GLOBAL_THRESHOLD_1M = 5;
    private static final int SERVICE_WARNING_THRESHOLD_2M = 5;
    private static final int SERVICE_CRITICAL_THRESHOLD_2M = 10;
    private static final int MAX_ALERT_HISTORY = 200;

    private final ConcurrentLinkedDeque<Alert> alertHistory = new ConcurrentLinkedDeque<>();
    private final Map<String, LocalDateTime> serviceCooldown = new ConcurrentHashMap<>();

    @Autowired
    private ElasticRepository repository;

    @Scheduled(fixedRate = 60000)
    public void checkAlerts() {
        LocalDateTime now = LocalDateTime.now();

        long totalErrorsLastMinute = repository.countErrorsInWindow("1m");
        if (totalErrorsLastMinute > GLOBAL_THRESHOLD_1M) {
            addAlertWithCooldown(new Alert(
                    "ALL-SERVICES",
                    "High error volume detected in last 1 minute",
                    (int) totalErrorsLastMinute,
                    toSeverity((int) totalErrorsLastMinute),
                    now
            ));
        }

        Map<String, Long> serviceErrorCounts = repository.countErrorsByServiceInWindow("2m", 100);
        serviceErrorCounts.forEach((service, count) -> {
            if (count >= SERVICE_WARNING_THRESHOLD_2M) {
                String severity = count >= SERVICE_CRITICAL_THRESHOLD_2M ? "CRITICAL" : "WARNING";
                addAlertWithCooldown(new Alert(
                        service,
                        service + " has " + count + " errors in last 2 minutes",
                        count.intValue(),
                        severity,
                        now
                ));
            }
        });
    }

    public List<Alert> getRecentAlerts() {
        List<Alert> alerts = new ArrayList<>(alertHistory);
        alerts.sort(Comparator.comparing(Alert::getTimestamp).reversed());
        return alerts;
    }

    public Map<String, List<Alert>> getRecentAlertsGroupedByService() {
        return getRecentAlerts().stream()
                .collect(Collectors.groupingBy(
                        Alert::getService,
                        LinkedHashMap::new,
                        Collectors.toList()
                ));
    }

    private void addAlertWithCooldown(Alert alert) {
        String key = alert.getService() + "|" + alert.getSeverity();
        LocalDateTime lastAlertTime = serviceCooldown.get(key);
        if (lastAlertTime != null && lastAlertTime.plusSeconds(45).isAfter(alert.getTimestamp())) {
            return;
        }

        serviceCooldown.put(key, alert.getTimestamp());
        alertHistory.addFirst(alert);
        while (alertHistory.size() > MAX_ALERT_HISTORY) {
            alertHistory.pollLast();
        }
    }

    private String toSeverity(int count) {
        if (count >= SERVICE_CRITICAL_THRESHOLD_2M) {
            return "CRITICAL";
        }
        if (count >= GLOBAL_THRESHOLD_1M) {
            return "WARNING";
        }
        return "INFO";
    }
}
