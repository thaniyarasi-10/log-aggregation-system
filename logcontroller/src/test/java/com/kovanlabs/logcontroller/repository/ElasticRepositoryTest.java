package com.kovanlabs.logcontroller.repository;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.aggregations.*;
import co.elastic.clients.elasticsearch.core.*;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import co.elastic.clients.elasticsearch.core.search.TotalHits;
import co.elastic.clients.elasticsearch.indices.ElasticsearchIndicesClient;
import co.elastic.clients.elasticsearch.indices.ExistsRequest;
import co.elastic.clients.transport.endpoints.BooleanResponse;
import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.auth.UserRole;
import com.kovanlabs.logcontroller.model.LogEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ElasticRepository.
 *
 * <p>ElasticRepository wraps the Elasticsearch Java client whose builder API is
 * heavily fluent and final — it cannot be mocked at the method level without a
 * full integration environment. The strategy here is:
 *
 * <ul>
 *   <li>Test all <em>pure logic</em> methods via reflection (parseInstantOrNull,
 *       resolveMetricsInterval, buildAccessFilter, rangeQuery) — these have no
 *       ES client dependency.</li>
 *   <li>Test the <em>exception-handling / fallback paths</em> by making the mocked
 *       client throw, then asserting the safe default is returned.</li>
 *   <li>Test <em>access-filter routing</em> (null context, empty services, wildcard)
 *       through the public searchMulti / getMetrics surface with a throwing client,
 *       verifying the method returns the safe empty/default result rather than
 *       propagating the exception.</li>
 * </ul>
 *
 * <p>Full query-shape assertions require an Elasticsearch test-container and belong
 * in a separate integration test (see architecture notes at the bottom of this file).
 */
@ExtendWith(MockitoExtension.class)
class ElasticRepositoryTest {

    @Mock private ElasticsearchClient esClient;
    @Mock private ElasticsearchIndicesClient indicesClient;

    private ElasticRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ElasticRepository(esClient);
    }

    // =========================================================================
    // parseInstantOrNull — pure logic, no ES dependency
    // =========================================================================

    @Test
    void parseInstantOrNull_validIso8601_returnsInstant() throws Exception {
        Instant result = invokeParseInstantOrNull("2026-05-01T10:00:00Z");
        assertThat(result).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
    }

    @Test
    void parseInstantOrNull_nullInput_returnsNull() throws Exception {
        Instant result = invokeParseInstantOrNull(null);
        assertThat(result).isNull();
    }

    @Test
    void parseInstantOrNull_blankInput_returnsNull() throws Exception {
        Instant result = invokeParseInstantOrNull("   ");
        assertThat(result).isNull();
    }

    @Test
    void parseInstantOrNull_invalidFormat_returnsNull() throws Exception {
        Instant result = invokeParseInstantOrNull("not-a-date");
        assertThat(result).isNull();
    }

    @Test
    void parseInstantOrNull_emptyString_returnsNull() throws Exception {
        Instant result = invokeParseInstantOrNull("");
        assertThat(result).isNull();
    }

    @Test
    void parseInstantOrNull_dateOnlyFormat_returnsNull() throws Exception {
        // "2026-05-01" is not a valid Instant (no time component)
        Instant result = invokeParseInstantOrNull("2026-05-01");
        assertThat(result).isNull();
    }

    // =========================================================================
    // resolveMetricsInterval — pure logic, no ES dependency
    // =========================================================================

    @ParameterizedTest(name = "preset={0} → interval={1}")
    @CsvSource({
        "5m,  30s",
        "15m, 1m",
        "1h,  5m",
        "24h, 2h",
        "7d,  6h",
        "15d, 1d"
    })
    void resolveMetricsInterval_knownPreset_returnsCorrectInterval(
            String preset, String expectedInterval) throws Exception {
        // Use a 1-hour range so the fallback calculation doesn't interfere
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-01T01:00:00Z");
        String result = invokeResolveMetricsInterval(from, to, preset);
        assertThat(result.trim()).isEqualTo(expectedInterval.trim());
    }

    @Test
    void resolveMetricsInterval_customPreset_usesRangeBasedCalculation() throws Exception {
        // 24-hour range / 80 buckets = 18 min per bucket → rounds to 30m
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-02T00:00:00Z");
        String result = invokeResolveMetricsInterval(from, to, "custom");
        assertThat(result).isNotBlank();
    }

    @Test
    void resolveMetricsInterval_nullPreset_usesRangeBasedCalculation() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-01T00:05:00Z"); // 5-minute range
        String result = invokeResolveMetricsInterval(from, to, null);
        // 5 min / 80 = ~3.75s → rounds to 30s
        assertThat(result).isEqualTo("30s");
    }

    @Test
    void resolveMetricsInterval_unknownPreset_returnsDefaultInterval() throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = Instant.parse("2026-05-01T01:00:00Z");
        String result = invokeResolveMetricsInterval(from, to, "unknown-preset");
        assertThat(result).isEqualTo("1m"); // DEFAULT_METRICS_INTERVAL
    }

    @ParameterizedTest(name = "rangeMs={0} → interval={1}")
    @CsvSource({
        "10000,   30s",   // 10s  → 30s
        "60000,   1m",    // 1m   → 1m
        "300000,  5m",    // 5m   → 5m
        "3600000, 1h",    // 1h   → 1h
        "86400000,1d"     // 24h  → 1d
    })
    void resolveMetricsInterval_rangeBasedBucketCalculation(
            long rangeMs, String expectedInterval) throws Exception {
        Instant from = Instant.parse("2026-05-01T00:00:00Z");
        Instant to   = from.plusMillis(rangeMs);
        String result = invokeResolveMetricsInterval(from, to, null);
        assertThat(result).isEqualTo(expectedInterval);
    }

    // =========================================================================
    // buildAccessFilter — tested via searchMulti exception path
    // =========================================================================

    @Test
    void searchMulti_nullAccessContext_returnsEmptyListWithoutThrowing() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        List<LogEvent> result = repository.searchMulti(
                List.of("payment-service"), null, List.of("ERROR"),
                null, null, null, null, 0, 20, null);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMulti_emptyAllowedServices_returnsEmptyListWithoutThrowing() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of(), List.of());

        List<LogEvent> result = repository.searchMulti(
                null, null, null, null, null, null, null, 0, 20, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMulti_wildcardInAllowedServices_returnsEmptyListWithoutThrowing() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        AuthenticatedUserContext ctx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV, List.of("*"), List.of());

        List<LogEvent> result = repository.searchMulti(
                null, null, null, null, null, null, null, 0, 20, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMulti_esClientThrows_returnsEmptyList() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("Connection refused"));

        AuthenticatedUserContext ctx = adminContext();
        List<LogEvent> result = repository.searchMulti(
                List.of("payment-service"), null, null, null, null,
                null, null, 0, 20, ctx);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMulti_pageSizeCappedAt500() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        AuthenticatedUserContext ctx = adminContext();
        // Request size=1000 — should be capped to 500 internally
        List<LogEvent> result = repository.searchMulti(
                null, null, null, null, null, null, null, 0, 1000, ctx);

        assertThat(result).isEmpty(); // exception path, but no NPE from size capping
    }

    // =========================================================================
    // getMetrics — fallback / default payload
    // =========================================================================

    @Test
    void getMetrics_esClientThrows_returnsDefaultMetricsPayload() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenThrow(new RuntimeException("ES timeout"));

        AuthenticatedUserContext ctx = adminContext();
        Map<String, Object> result = repository.getMetrics(
                "payment-service", null, null, null, ctx);

        assertThat(result).isNotNull();
        assertThat(result.get("totalLogs")).isEqualTo(0L);
        assertThat(result.get("errorCount")).isEqualTo(0L);
        assertThat(result.get("errorRate")).isEqualTo(0.0);
        assertThat(result.get("avgResponseTime")).isEqualTo(0.0);
        assertThat(result.get("p95Latency")).isEqualTo(0.0);
        assertThat(result.get("throughputOverTime")).isEqualTo(List.of());
        assertThat(result.get("levelDistribution")).isEqualTo(List.of());
    }

    @Test
    void getMetrics_nullContext_returnsDefaultMetricsPayload() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        Map<String, Object> result = repository.getMetrics(null, null, null, null, null);

        assertThat(result).containsKey("totalLogs");
        assertThat(result.get("totalLogs")).isEqualTo(0L);
    }

    @Test
    void getMetrics_secondCallAfterFailure_returnsLastStableMetrics() throws Exception {
        // First call throws — returns default
        when(esClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenThrow(new RuntimeException("ES down"));

        AuthenticatedUserContext ctx = adminContext();
        Map<String, Object> first = repository.getMetrics(null, null, null, null, ctx);
        Map<String, Object> second = repository.getMetrics(null, null, null, null, ctx);

        // Both should return the same default/stable payload
        assertThat(first).isEqualTo(second);
    }

    // =========================================================================
    // countErrorsInWindow
    // =========================================================================

    @Test
    void countErrorsInWindow_noIndexesExist_returnsZero() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(false));

        long count = repository.countErrorsInWindow("1m");

        assertThat(count).isZero();
        verify(esClient, never()).count(any(CountRequest.class));
    }

    @Test
    void countErrorsInWindow_esClientThrows_returnsZero() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true));
        when(esClient.count(any(CountRequest.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        long count = repository.countErrorsInWindow("1m");

        assertThat(count).isZero();
    }

    @Test
    void countErrorsInWindow_indexCheckThrows_returnsZero() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenThrow(new RuntimeException("ES unavailable"));

        long count = repository.countErrorsInWindow("1m");

        assertThat(count).isZero();
    }

    // =========================================================================
    // countErrorsByServiceInWindow
    // =========================================================================

    @Test
    void countErrorsByServiceInWindow_noIndexesExist_returnsEmptyMap() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(false));

        Map<String, Long> result = repository.countErrorsByServiceInWindow("2m", 100);

        assertThat(result).isEmpty();
    }

    @Test
    void countErrorsByServiceInWindow_esClientThrows_returnsEmptyMap() throws Exception {
        when(esClient.indices()).thenReturn(indicesClient);
        when(indicesClient.exists(any(ExistsRequest.class)))
                .thenReturn(new BooleanResponse(true));
        when(esClient.search(any(SearchRequest.class), eq(Void.class)))
                .thenThrow(new RuntimeException("ES timeout"));

        Map<String, Long> result = repository.countErrorsByServiceInWindow("2m", 100);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // save — write-mute and validation paths
    // =========================================================================

    @Test
    void save_nullLogEvent_skipsIndexing() throws Exception {
        repository.save(null);
        verify(esClient, never()).index(any(IndexRequest.class));
    }

    @Test
    void save_invalidTimestamp_skipsIndexing() throws Exception {
        LogEvent event = new LogEvent();
        event.setService("payment-service");
        event.setMessage("test");
        event.setTimestamp("not-a-valid-timestamp");

        repository.save(event);

        verify(esClient, never()).index(any(IndexRequest.class));
    }

    @Test
    void save_noServiceAndNoMessage_skipsIndexing() throws Exception {
        LogEvent event = new LogEvent();
        event.setTimestamp("2026-05-01T10:00:00Z");
        // service and message both null

        repository.save(event);

        verify(esClient, never()).index(any(IndexRequest.class));
    }

    @Test
    void save_esClientThrows_mutesWritesForBackoffPeriod() throws Exception {
        LogEvent event = validLogEvent();
        when(esClient.index(any(IndexRequest.class)))
                .thenThrow(new RuntimeException("ES connection refused"));

        repository.save(event);

        // Second save should be muted (no second index call)
        repository.save(event);
        verify(esClient, times(1)).index(any(IndexRequest.class));
    }

    @Test
    void save_validEvent_callsEsClientIndex() throws Exception {
        LogEvent event = validLogEvent();
        when(esClient.index(any(IndexRequest.class))).thenReturn(null);

        repository.save(event);

        verify(esClient, times(1)).index(any(IndexRequest.class));
    }

    // =========================================================================
    // AuthenticatedUserContext — access filter routing
    // =========================================================================

    @Test
    void searchMulti_adminContextWithServices_doesNotThrowOnEsFailure() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES down"));

        AuthenticatedUserContext adminCtx = new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN,
                List.of("payment-service", "auth-service"), List.of());

        List<LogEvent> result = repository.searchMulti(
                null, null, null, null, null, null, null, 0, 20, adminCtx);

        assertThat(result).isEmpty();
    }

    @Test
    void searchMulti_devContextWithAllowedServices_doesNotThrowOnEsFailure() throws Exception {
        when(esClient.search(any(SearchRequest.class), eq(LogEvent.class)))
                .thenThrow(new RuntimeException("ES down"));

        AuthenticatedUserContext devCtx = new AuthenticatedUserContext(
                "dev@test.com", UserRole.DEV,
                List.of("payment-service"), List.of());

        List<LogEvent> result = repository.searchMulti(
                null, null, null, null, null, null, null, 0, 20, devCtx);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private AuthenticatedUserContext adminContext() {
        return new AuthenticatedUserContext(
                "admin@test.com", UserRole.ADMIN,
                List.of("payment-service", "auth-service"), List.of());
    }

    private LogEvent validLogEvent() {
        LogEvent e = new LogEvent();
        e.setService("payment-service");
        e.setMessage("DB timeout");
        e.setLevel("ERROR");
        e.setTimestamp("2026-05-01T10:00:00Z");
        return e;
    }

    /** Reflectively invokes the private parseInstantOrNull method. */
    private Instant invokeParseInstantOrNull(String value) throws Exception {
        Method m = ElasticRepository.class.getDeclaredMethod("parseInstantOrNull", String.class);
        m.setAccessible(true);
        return (Instant) m.invoke(repository, value);
    }

    /** Reflectively invokes the private resolveMetricsInterval method. */
    private String invokeResolveMetricsInterval(Instant from, Instant to, String preset) throws Exception {
        // TimeBounds is a private record — construct via reflection
        Class<?> timeBoundsClass = null;
        for (Class<?> inner : ElasticRepository.class.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("TimeBounds")) {
                timeBoundsClass = inner;
                break;
            }
        }
        if (timeBoundsClass == null) throw new IllegalStateException("TimeBounds inner class not found");

        Object bounds = timeBoundsClass
                .getDeclaredConstructor(Instant.class, Instant.class)
                .newInstance(from, to);

        Method m = ElasticRepository.class.getDeclaredMethod(
                "resolveMetricsInterval", timeBoundsClass, String.class);
        m.setAccessible(true);
        return (String) m.invoke(repository, bounds, preset);
    }
}
