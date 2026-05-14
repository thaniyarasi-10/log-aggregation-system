package com.kovanlabs.logcontroller.consumer;

import com.kovanlabs.logcontroller.service.LogProcessingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogConsumerTest {

    @Mock private LogProcessingService processingService;

    @InjectMocks private LogConsumer consumer;

    // ── consume — happy path ──────────────────────────────────────────────────

    @Test
    void consume_validJsonMessage_delegatesToProcessingService() {
        String message = "{\"service\":\"payment-service\",\"level\":\"ERROR\",\"message\":\"DB timeout\"}";

        consumer.consume(message);

        verify(processingService).processKafkaRecord(message);
    }

    @Test
    void consume_plainTextMessage_delegatesToProcessingService() {
        String message = "plain text log line";

        consumer.consume(message);

        verify(processingService).processKafkaRecord(message);
    }

    // ── consume — null / blank ────────────────────────────────────────────────

    @Test
    void consume_nullMessage_stillDelegatesToProcessingService() {
        // LogConsumer does not filter — it delegates and lets LogProcessingService handle null
        consumer.consume(null);

        verify(processingService).processKafkaRecord(null);
    }

    @Test
    void consume_emptyMessage_delegatesToProcessingService() {
        consumer.consume("");

        verify(processingService).processKafkaRecord("");
    }

    // ── consume — exception handling ──────────────────────────────────────────

    @Test
    void consume_processingServiceThrowsRuntimeException_doesNotPropagateException() {
        doThrow(new RuntimeException("Kafka processing failed"))
                .when(processingService).processKafkaRecord(anyString());

        // Must not throw — consumer must not crash the Kafka listener thread
        consumer.consume("{\"service\":\"svc\",\"level\":\"ERROR\"}");

        verify(processingService).processKafkaRecord(anyString());
    }

    @Test
    void consume_processingServiceThrowsNullPointerException_doesNotPropagateException() {
        doThrow(new NullPointerException("NPE in processing"))
                .when(processingService).processKafkaRecord(anyString());

        consumer.consume("{\"service\":\"svc\"}");

        // No exception propagated
    }

    // ── consume — message forwarding ─────────────────────────────────────────

    @Test
    void consume_largeMessage_forwardsEntireMessageToProcessingService() {
        String largeMessage = "{\"service\":\"payment-service\",\"level\":\"ERROR\","
                + "\"message\":\"" + "x".repeat(5000) + "\"}";

        consumer.consume(largeMessage);

        verify(processingService).processKafkaRecord(largeMessage);
    }

    @Test
    void consume_multipleMessages_eachDelegatedIndependently() {
        String msg1 = "{\"service\":\"svc1\",\"level\":\"INFO\"}";
        String msg2 = "{\"service\":\"svc2\",\"level\":\"ERROR\"}";
        String msg3 = "{\"service\":\"svc3\",\"level\":\"WARN\"}";

        consumer.consume(msg1);
        consumer.consume(msg2);
        consumer.consume(msg3);

        verify(processingService).processKafkaRecord(msg1);
        verify(processingService).processKafkaRecord(msg2);
        verify(processingService).processKafkaRecord(msg3);
        verifyNoMoreInteractions(processingService);
    }
}
