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

    @KafkaListener(
            topics = "app-logs",
            groupId = "${spring.kafka.consumer.group-id:log-group-v2}",
            containerFactory = "kafkaListenerContainerFactory"
    )
    public void consume(String message) {
//        LOGGER.info("KAFKA RECEIVED — length={} preview='{}'",
//                message == null ? 0 : message.length(),
//                message == null ? "null" : message.substring(0, Math.min(120, message.length())));
        try {
            service.processKafkaRecord(message);
        } catch (RuntimeException ex) {
            LOGGER.error("Unhandled error processing Kafka record: {}", ex.getMessage(), ex);
        }
    }
}
