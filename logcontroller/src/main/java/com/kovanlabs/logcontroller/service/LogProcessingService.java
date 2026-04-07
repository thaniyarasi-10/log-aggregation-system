package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.parser.LogParser;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

@Service
public class LogProcessingService {

    @Autowired
    private LogParser parser;

    @Autowired
    private ElasticRepository repository;

    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

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

        processed.forEach(repository::save);
    }
}