package com.kovanlabs.logcontroller.service;

import com.kovanlabs.logcontroller.jpa.repository.AppServiceRepository;
import com.kovanlabs.logcontroller.model.LogEvent;
import com.kovanlabs.logcontroller.parser.LogParser;
import com.kovanlabs.logcontroller.repository.ElasticRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogProcessingServiceTest {

    @Mock private LogParser parser;
    @Mock private ElasticRepository repository;
    @Mock private MongoLogPersistenceService mongoLogPersistenceService;
    @Mock private AppServiceRepository appServiceRepository;
    @Mock private WebSocketLogBroadcaster webSocketLogBroadcaster;

    @InjectMocks private LogProcessingService service;

    @BeforeEach
    void setUp() {
        // Default: service is approved in DB
        when(appServiceRepository.existsByNameAndIsActiveTrue(anyString())).thenReturn(true);
    }

    // ── processKafkaRecord ────────────────────────────────────────────────────

    @Test
    void processKafkaRecord_validLog_persistsToMongoElasticAndBroadcasts() {
        LogEvent event = logEvent("payment-service", "ERROR");
        when(parser.parse(anyString())).thenReturn(event);

        service.processKafkaRecord("{\"service\":\"payment-service\",\"level\":\"ERROR\"}");

        verify(mongoLogPersistenceService).save(event);
        verify(repository).save(event);
        verify(webSocketLogBroadcaster).broadcast(event);
    }

    @Test
    void processKafkaRecord_nullMessage_skipsAllProcessing() {
        service.processKafkaRecord(null);

        verify(parser, never()).parse(any());
        verify(repository, never()).save(any());
        verify(mongoLogPersistenceService, never()).save(any());
    }

    @Test
    void processKafkaRecord_blankMessage_skipsAllProcessing() {
        service.processKafkaRecord("   ");

        verify(parser, never()).parse(any());
    }

    @Test
    void processKafkaRecord_parserReturnsNull_dropsRecord() {
        when(parser.parse(anyString())).thenReturn(null);

        service.processKafkaRecord("{\"bad\":\"json\"}");

        verify(repository, never()).save(any());
        verify(mongoLogPersistenceService, never()).save(any());
        verify(webSocketLogBroadcaster, never()).broadcast(any());
    }

    @Test
    void processKafkaRecord_serviceNotApproved_dropsRecord() {
        LogEvent event = logEvent("unknown-service", "INFO");
        when(parser.parse(anyString())).thenReturn(event);
        when(appServiceRepository.existsByNameAndIsActiveTrue("unknown-service")).thenReturn(false);

        service.processKafkaRecord("{\"service\":\"unknown-service\"}");

        verify(repository, never()).save(any());
        verify(mongoLogPersistenceService, never()).save(any());
        verify(webSocketLogBroadcaster, never()).broadcast(any());
    }

    @Test
    void processKafkaRecord_nullServiceOnEvent_dropsRecord() {
        LogEvent event = new LogEvent();
        event.setService(null);
        event.setLevel("INFO");
        when(parser.parse(anyString())).thenReturn(event);

        service.processKafkaRecord("{\"level\":\"INFO\"}");

        verify(repository, never()).save(any());
    }

    @Test
    void processKafkaRecord_blankServiceOnEvent_dropsRecord() {
        LogEvent event = new LogEvent();
        event.setService("   ");
        event.setLevel("INFO");
        when(parser.parse(anyString())).thenReturn(event);

        service.processKafkaRecord("{\"service\":\"   \"}");

        verify(repository, never()).save(any());
    }

    @Test
    void processKafkaRecord_serviceNameNormalisedToLowercase() {
        LogEvent event = logEvent("Payment-Service", "INFO");
        when(parser.parse(anyString())).thenReturn(event);
        when(appServiceRepository.existsByNameAndIsActiveTrue("payment-service")).thenReturn(true);

        service.processKafkaRecord("{\"service\":\"Payment-Service\"}");

        ArgumentCaptor<LogEvent> captor = ArgumentCaptor.forClass(LogEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getService()).isEqualTo("payment-service");
    }

    @Test
    void processKafkaRecord_approvedServiceCachedOnSecondCall_dbQueriedOnlyOnce() {
        LogEvent event = logEvent("payment-service", "INFO");
        when(parser.parse(anyString())).thenReturn(event);

        service.processKafkaRecord("{\"service\":\"payment-service\"}");
        service.processKafkaRecord("{\"service\":\"payment-service\"}");

        // DB should only be queried once — second call hits the cache
        verify(appServiceRepository, times(1)).existsByNameAndIsActiveTrue("payment-service");
    }

    // ── process (legacy buffer path) ─────────────────────────────────────────

    @Test
    void process_addsToBufferAndDrainsWhenFull() {
        LogEvent event = logEvent("auth-service", "INFO");
        when(parser.parse(anyString())).thenReturn(event);

        // Fill buffer to trigger drain (threshold = 50)
        for (int i = 0; i < 50; i++) {
            service.process("{\"service\":\"auth-service\",\"level\":\"INFO\"}");
        }

        verify(repository, atLeast(1)).save(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private LogEvent logEvent(String service, String level) {
        LogEvent e = new LogEvent();
        e.setService(service);
        e.setLevel(level);
        e.setMessage("test message");
        e.setTimestamp("2026-05-01T10:00:00Z");
        return e;
    }
}
