package com.kovanlabs.logcontroller.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.parser.LogParser;
import com.kovanlabs.logcontroller.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.repository.ElasticRepository;

@Service
public class LogProcessingService {

    @Autowired
    private LogParser parser;

    @Autowired
    private ElasticRepository repository;

    @Autowired
    private AppServiceRepository appServiceRepository;

    @Autowired
    private SimpMessagingTemplate messagingTemplate;

    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

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
        messagingTemplate.convertAndSend("/topic/logs", event);
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
                    repository.save(event);
                    messagingTemplate.convertAndSend("/topic/logs", event);
                });
    }

    private boolean isServiceApproved(LogEvent event) {
        if (event == null || event.getService() == null || event.getService().isBlank()) {
            return false;
        }

        String normalizedServiceName = normalizeServiceName(event.getService());
        if (normalizedServiceName.isBlank()) {
            return false;
        }

        boolean isApproved = appServiceRepository.existsByNameAndIsActiveTrue(normalizedServiceName);
        if (!isApproved) {
            return false;
        }

        event.setService(normalizedServiceName);
        return true;
    }

    private String normalizeServiceName(String value) {
        if (value == null) {
            return "";
        }

        return value.toLowerCase(Locale.ROOT).trim();
    }
}