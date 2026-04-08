package com.kovanlabs.logcontroller.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import org.junit.jupiter.api.Test;

import com.kovanlabs.logcontroller.model.LogEvent;

class LogParserTest {

    private final LogParser parser = new LogParser();

    @Test
    void parseReturnsFallbackForEmptyMessage() {
        LogEvent event = parser.parse("   ");

        assertEquals("unknown", event.getService());
        assertEquals("INFO", event.getLevel());
        assertEquals("", event.getMessage());
        assertNotNull(event.getTimestamp());
    }

    @Test
    void parseMapsKnownJsonFields() {
        String raw = "{\"service\":\"api\",\"level\":\"ERROR\",\"message\":\"failed\",\"statusCode\":500,\"responseTime\":123}";

        LogEvent event = parser.parse(raw);

        assertEquals("api", event.getService());
        assertEquals("ERROR", event.getLevel());
        assertEquals("failed", event.getMessage());
        assertEquals(500, event.getStatusCode());
        assertEquals(123, event.getResponseTime());
    }

    @Test
    void parseMergesNestedMessageJsonPayload() {
        String raw = "{\"service\":\"gateway\",\"message\":\"{\\\"endpoint\\\":\\\"/health\\\",\\\"method\\\":\\\"GET\\\"}\"}";

        LogEvent event = parser.parse(raw);

        assertEquals("gateway", event.getService());
        assertEquals("/health", event.getEndpoint());
        assertEquals("GET", event.getMethod());
    }
}
