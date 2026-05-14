package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.mongo.repository.MongoLogEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MongoLogPersistenceServiceTest {

    @Mock private MongoLogEventRepository mongoLogEventRepository;

    @InjectMocks private MongoLogPersistenceService service;

    @Test
    void save_validEvent_persistsToMongo() {
        LogEvent event = logEvent("payment-service", "ERROR", "DB timeout");

        service.save(event);

        verify(mongoLogEventRepository).save(any());
    }

    @Test
    void save_nullEvent_skipsMongoSave() {
        service.save(null);

        verify(mongoLogEventRepository, never()).save(any());
    }

    @Test
    void save_mongoThrowsRuntimeException_doesNotPropagateException() {
        doThrow(new RuntimeException("Mongo connection refused"))
                .when(mongoLogEventRepository).save(any());

        // Must not throw — Mongo persistence must not block the real-time pipeline
        service.save(logEvent("payment-service", "ERROR", "msg"));
    }

    @Test
    void save_eventWithAllFields_persistsCorrectly() {
        LogEvent event = logEvent("auth-service", "INFO", "Login success");
        event.setTimestamp("2026-05-01T10:00:00Z");
        event.setTraceId("trace-123");
        event.setStatusCode(200);
        event.setResponseTime(45.5);

        service.save(event);

        verify(mongoLogEventRepository).save(any());
    }

    @Test
    void save_multipleEvents_eachPersistedIndependently() {
        service.save(logEvent("svc1", "ERROR", "error1"));
        service.save(logEvent("svc2", "INFO", "info1"));
        service.save(logEvent("svc3", "WARN", "warn1"));

        verify(mongoLogEventRepository, times(3)).save(any());
    }

    private LogEvent logEvent(String service, String level, String message) {
        LogEvent e = new LogEvent();
        e.setService(service);
        e.setLevel(level);
        e.setMessage(message);
        e.setTimestamp("2026-05-01T10:00:00Z");
        return e;
    }
}
