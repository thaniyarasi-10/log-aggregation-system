package com.kovanlabs.logcontroller.consumer;

import com.kovanlabs.logcontroller.service.LogProcessingService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LogConsumer {

    @Autowired
    private LogProcessingService service;
    @KafkaListener(topics = "app-logs", groupId = "log-group")
    public void consume(String message) {
        service.process(message);
    }
}