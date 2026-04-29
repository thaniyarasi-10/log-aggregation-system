package com.kovanlabs.logcontroller.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.model.MongoLogEvent;
import com.kovanlabs.logcontroller.mongo.repository.MongoLogEventRepository;

@Service
public class MongoLogPersistenceService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MongoLogPersistenceService.class);

    private final MongoLogEventRepository mongoLogEventRepository;

    public MongoLogPersistenceService(MongoLogEventRepository mongoLogEventRepository) {
        this.mongoLogEventRepository = mongoLogEventRepository;
    }

    public void save(LogEvent event) {
        if (event == null) {
            return;
        }

        try {
            MongoLogEvent mongoLogEvent = MongoLogEvent.from(event);
            LOGGER.debug(
                    "About to persist log to MongoDB. service={}, level={}, timestamp={}",
                    mongoLogEvent.getServiceName(),
                    mongoLogEvent.getLevel(),
                    mongoLogEvent.getTimestamp()
            );
            mongoLogEventRepository.save(mongoLogEvent);
            LOGGER.debug(
                    "Successfully persisted log to MongoDB. service={}, level={}",
                    mongoLogEvent.getServiceName(),
                    mongoLogEvent.getLevel()
            );
        } catch (RuntimeException ex) {
            // Mongo persistence should not block the real-time pipeline.
            LOGGER.warn("Failed to store log event in MongoDB: {}", ex.getMessage());
        }

    }
}
