package com.kovanlabs.logcontroller.consumer;

import com.kovanlabs.logcontroller.service.LogProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LogConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogConsumer.class);

    @Autowired
    private LogProcessingService service;

    @KafkaListener(topics = "app-logs", groupId = "log-group")
    public void consume(String message) {
        try {
            service.processKafkaRecord(message);
        } catch (RuntimeException ex) {
            LOGGER.warn("Skipping Kafka log due to processing error: {}", ex.getMessage());
        }
    }
}