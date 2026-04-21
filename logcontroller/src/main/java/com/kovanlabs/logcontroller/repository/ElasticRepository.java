package com.kovanlabs.logcontroller.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.model.LogEvent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.MatchQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.WildcardQuery;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

@Repository
public class ElasticRepository {

        private static final Logger LOGGER = LoggerFactory.getLogger(ElasticRepository.class);

    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String METRICS_TIMESTAMP_FIELD = "timestamp";
    private static final String LEVEL_KEYWORD   = "level.keyword";
    private static final String SERVICE_KEYWORD = "service.keyword";
                private static final String PROJECT_KEYWORD = "project.keyword";
        private static final String ENV_KEYWORD     = "environment.keyword";
        private static final String TRACE_KEYWORD   = "traceId.keyword";
        private static final String MESSAGE_FIELD   = "message";
    private static final String INDEX_PATTERN   = "app-logs-*";
                private static final String NO_ACCESS_SENTINEL = "__NO_ACCESS__";
        private static final String ERROR_LEVEL = "ERROR";
        private static final String RESPONSE_TIME_FIELD = "responseTime";
        private static final String AGG_TOTAL_COUNT = "total_count";
        private static final String AGG_ERROR_COUNT = "error_count";
        private static final String AGG_AVG_RESPONSE_TIME = "avg_response_time";
        private static final String AGG_P95_LATENCY = "p95_latency";
        private static final String AGG_LEVEL_DISTRIBUTION = "level_distribution";
        private static final String AGG_THROUGHPUT_OVER_TIME = "throughput_over_time";
        private static final String AGG_BUCKET_ERROR_COUNT = "bucket_error_count";
        private static final String AGG_BUCKET_AVG_RESPONSE_TIME = "bucket_avg_response_time";
        private static final String DEFAULT_METRICS_INTERVAL = "1m";
        private static final int TARGET_BUCKET_COUNT = 80;
        private static final String PRESET_5M = "5m";
        private static final String PRESET_15M = "15m";
        private static final String PRESET_1H = "1h";
        private static final String PRESET_24H = "24h";
        private static final String PRESET_7D = "7d";
        private static final String PRESET_15D = "15d";
        private static final String PRESET_CUSTOM = "custom";
        private static final Duration RETENTION_PERIOD = Duration.ofDays(15);
        private static final long WRITE_BACKOFF_MS = 30_000L;
        private static final long ERROR_LOG_THROTTLE_MS = 30_000L;

        private volatile long writesMutedUntilMs = 0L;
        private volatile long nextErrorLogAtMs = 0L;
        private volatile Map<String, Object> lastStableMetrics = defaultMetricsPayload();

        private final ElasticsearchClient client;

        public ElasticRepository(ElasticsearchClient client) {
                this.client = client;
        }

    // SAVE
    public void save(LogEvent log) {
                long now = System.currentTimeMillis();
                if (now < writesMutedUntilMs) {
                        return;
                }

        try {
            String index = "app-logs-" + LocalDate.now();
            IndexRequest<LogEvent> request = IndexRequest.of(i -> i
                    .index(index)
                    .document(log)
            );
            client.index(request);

                        if (writesMutedUntilMs != 0L) {
                                writesMutedUntilMs = 0L;
                                LOGGER.info("Elasticsearch connection restored. Resuming log persistence.");
                        }
        } catch (Exception e) {
                        writesMutedUntilMs = now + WRITE_BACKOFF_MS;
                        logErrorThrottled("write", e);
        }
    }

//SEARCH
        public List<LogEvent> search(String service, String environment, String level,
                                                                 String traceId, String message,
                                 String from, String to,
                                 int page, int size,
                                 AuthenticatedUserContext accessContext) {
        try {
                        TimeBounds bounds = resolveTimeBounds(from, to);
            BoolQuery boolQuery = BoolQuery.of(b -> b
                                                                                .filter(buildFilters(service, environment, level, traceId, message, bounds, accessContext)));

            SearchRequest request = SearchRequest.of(s -> s
                    .index(INDEX_PATTERN)
                    .query(boolQuery._toQuery())
                    .from(page * size)
                    .size(size)
                    .sort(sort -> sort
                            .field(f -> f
                                    .field(METRICS_TIMESTAMP_FIELD)
                                            .order(SortOrder.Desc)))
            );

            SearchResponse<LogEvent> response = client.search(request, LogEvent.class);

            return response.hits().hits()
                    .stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .toList();

        } catch (Exception e) {
                        logErrorThrottled("search", e);
            return new ArrayList<>();
        }
    }

        public List<String> getDistinctServices(String from, String to, int maxServices, AuthenticatedUserContext accessContext) {
                try {
                        boolean hasTimeFilter = hasText(from) || hasText(to);
                        List<Query> filters = new ArrayList<>();

                        if (hasTimeFilter) {
                                TimeBounds bounds = resolveTimeBounds(from, to);
                                filters.add(rangeQuery(bounds));
                        }

                        buildAccessFilter(accessContext).ifPresent(filters::add);

                        Query query = filters.isEmpty()
                                ? QueryBuilders.matchAll().build()._toQuery()
                                : BoolQuery.of(b -> b.filter(filters))._toQuery();

                        SearchRequest request = SearchRequest.of(s -> s
                                        .index(INDEX_PATTERN)
                                        .query(query)
                                        .size(0)
                                        .aggregations("services", a -> a
                                                        .terms(t -> t
                                                                        .field(SERVICE_KEYWORD)
                                                                        .size(Math.max(1, maxServices))
                                                        ))
                                        .aggregations("projects", a -> a
                                                        .terms(t -> t
                                                                        .field(PROJECT_KEYWORD)
                                                                        .size(Math.max(1, maxServices))
                                                        ))
                        );

                        SearchResponse<Void> response = client.search(request, Void.class);

                        List<String> services = response.aggregations()
                                        .get("services")
                                        .sterms()
                                        .buckets()
                                        .array()
                                        .stream()
                                        .map(bucket -> bucket.key().stringValue())
                                        .filter(Objects::nonNull)
                                        .filter(svc -> !svc.isBlank())
                                        .toList();

                        List<String> projects = response.aggregations()
                                        .get("projects")
                                        .sterms()
                                        .buckets()
                                        .array()
                                        .stream()
                                        .map(bucket -> bucket.key().stringValue())
                                        .filter(Objects::nonNull)
                                        .filter(project -> !project.isBlank())
                                        .toList();

                        return Stream.concat(services.stream(), projects.stream())
                                        .map(String::trim)
                                        .filter(name -> !name.isBlank())
                                        .distinct()
                                        .sorted()
                                        .toList();

                } catch (Exception e) {
                        logErrorThrottled("getDistinctServices", e);
                        return new ArrayList<>();
                }
        }

// METRICS

        public Map<String, Object> getMetrics(String service, String from, String to, String timePreset, AuthenticatedUserContext accessContext) {
        try {
                                                TimeBounds bounds = resolveTimeBounds(from, to);
                                                                                                String interval = resolveMetricsInterval(bounds, timePreset);
                        int intervalSeconds = toIntervalSeconds(interval);
                                                SearchRequest request = buildMetricsRequest(service, bounds, interval, accessContext);

            SearchResponse<Void> response = client.search(request, Void.class);
                                                Map<String, Object> payload = toMetricsPayload(response, interval, intervalSeconds);
                                                lastStableMetrics = payload;
                                                return payload;

        } catch (Exception e) {
                        logErrorThrottled("metrics", e);
                        return lastStableMetrics == null || lastStableMetrics.isEmpty() ? defaultMetricsPayload() : lastStableMetrics;
        }
    }

                private Map<String, Object> defaultMetricsPayload() {
                                return Stream.of(
                                                                Map.entry("totalLogs", 0L),
                                                                Map.entry("errorCount", 0L),
                                                                Map.entry("errorRate", 0.0),
                                                                Map.entry("avgResponseTime", 0.0),
                                                                Map.entry("p95Latency", 0.0),
                                                                Map.entry("bucketInterval", DEFAULT_METRICS_INTERVAL),
                                                                Map.entry("throughputOverTime", List.<Map<String, Object>>of()),
                                                                Map.entry("levelDistribution", List.<Map<String, Object>>of())
                                ).collect(Collectors.toMap(
                                                                Map.Entry::getKey,
                                                                Map.Entry::getValue
                                ));
                }

        private SearchRequest buildMetricsRequest(String service, TimeBounds bounds, String interval, AuthenticatedUserContext accessContext) {
        BoolQuery boolQuery = BoolQuery.of(b -> b.filter(buildMetricFilters(service, bounds, accessContext)));

        return SearchRequest.of(s -> s
                .index(INDEX_PATTERN)
                .query(boolQuery._toQuery())
                .size(0)
                .aggregations(AGG_TOTAL_COUNT, a -> a
                        .valueCount(v -> v.field("timestamp")))
                .aggregations(AGG_ERROR_COUNT, a -> a
                        .filter(f -> f
                                .term(t -> t
                                        .field(LEVEL_KEYWORD)
                                        .value(ERROR_LEVEL))))
                .aggregations(AGG_AVG_RESPONSE_TIME, a -> a
                        .avg(avg -> avg.field(RESPONSE_TIME_FIELD)))
                .aggregations(AGG_P95_LATENCY, a -> a
                        .percentiles(p -> p
                                .field(RESPONSE_TIME_FIELD)
                                .percents(95.0)))
                .aggregations(AGG_LEVEL_DISTRIBUTION, a -> a
                        .terms(t -> t
                                .field(LEVEL_KEYWORD)
                                .size(10)))
                .aggregations(AGG_THROUGHPUT_OVER_TIME, a -> a
                        .dateHistogram(dh -> dh
                                .field(METRICS_TIMESTAMP_FIELD)
                                .fixedInterval(Time.of(t -> t.time(interval)))
                                .minDocCount(0)
                                .extendedBounds(eb -> eb
                                        .min(FieldDateMath.of(f -> f.expr(bounds.fromIso())))
                                        .max(FieldDateMath.of(f -> f.expr(bounds.toIso()))))
                        )
                        .aggregations(AGG_BUCKET_ERROR_COUNT, sub -> sub
                                .filter(f -> f
                                        .term(t -> t
                                                .field(LEVEL_KEYWORD)
                                                .value(ERROR_LEVEL))))
                        .aggregations(AGG_BUCKET_AVG_RESPONSE_TIME, sub -> sub
                                .avg(avg -> avg.field(RESPONSE_TIME_FIELD))))
        );
    }

        private List<Query> buildMetricFilters(String service, TimeBounds bounds, AuthenticatedUserContext accessContext) {
        List<Query> filters = new ArrayList<>();

        buildServiceFilter(service, accessContext).ifPresent(filters::add);

                buildAccessFilter(accessContext).ifPresent(filters::add);

                filters.add(rangeQuery(bounds));

        return filters;
    }

        private Map<String, Object> toMetricsPayload(SearchResponse<Void> response, String interval, int intervalSeconds) {
        List<Map<String, Object>> throughputOverTime = response.aggregations()
                .get(AGG_THROUGHPUT_OVER_TIME)
                .dateHistogram()
                .buckets()
                .array()
                .stream()
                .sorted(Comparator.comparingLong(bucket -> bucket.key()))
                .map(bucket -> {
                    long bucketErrors = bucket.aggregations().get(AGG_BUCKET_ERROR_COUNT).filter().docCount();
                    long bucketCount = bucket.docCount();
                                        double throughputPerSecond = intervalSeconds > 0 ? (bucketCount * 1.0 / intervalSeconds) : 0.0;
                    return Map.<String, Object>of(
                            "time", Objects.toString(bucket.keyAsString(), ""),
                            "count", bucketCount,
                            "intervalSeconds", intervalSeconds,
                            "throughputPerSecond", normalizeDouble(throughputPerSecond),
                            "errorCount", bucketErrors,
                            "errorRate", bucketCount > 0 ? (bucketErrors * 100.0 / bucketCount) : 0.0,
                            "avgResponseTime", normalizeDouble(bucket.aggregations().get(AGG_BUCKET_AVG_RESPONSE_TIME).avg().value())
                    );
                })
                .toList();

        long total = (long) response.aggregations()
                .get(AGG_TOTAL_COUNT).valueCount().value();

        long errors = response.aggregations()
                .get(AGG_ERROR_COUNT).filter().docCount();

        double avgRt = normalizeDouble(response.aggregations()
                .get(AGG_AVG_RESPONSE_TIME).avg().value());

        double p95 = extractP95(response);

        List<Map<String, Object>> levelDistribution = response.aggregations()
                .get(AGG_LEVEL_DISTRIBUTION)
                .sterms()
                .buckets()
                .array()
                .stream()
                .map(bucket -> Map.<String, Object>of(
                        "level", bucket.key().stringValue(),
                        "count", bucket.docCount()
                ))
                .toList();

        double errorRate = total > 0 ? (errors * 100.0 / total) : 0.0;

        return Stream.of(
                Map.entry("totalLogs",       total),
                Map.entry("errorCount",      errors),
                Map.entry("errorRate",       Math.round(errorRate * 100.0) / 100.0),
                Map.entry("avgResponseTime", avgRt),
                Map.entry("p95Latency",      p95),
                                Map.entry("bucketInterval", interval),
                Map.entry("throughputOverTime", throughputOverTime),
                Map.entry("levelDistribution", levelDistribution)
        ).collect(Collectors.toMap(
                Map.Entry::getKey,
                Map.Entry::getValue
        ));
    }

        private int toIntervalSeconds(String interval) {
                if (interval == null || interval.isBlank()) {
                        return 60;
                }
                if (interval.endsWith("s")) {
                        return Integer.parseInt(interval.substring(0, interval.length() - 1));
                }
                if (interval.endsWith("m")) {
                        return Integer.parseInt(interval.substring(0, interval.length() - 1)) * 60;
                }
                if (interval.endsWith("h")) {
                        return Integer.parseInt(interval.substring(0, interval.length() - 1)) * 3600;
                }
                return 60;
        }

        private String resolveMetricsInterval(TimeBounds bounds, String timePreset) {
                if (timePreset != null && !timePreset.isBlank() && !PRESET_CUSTOM.equalsIgnoreCase(timePreset)) {
                        return switch (timePreset) {
                                case PRESET_5M -> "30s";
                                case PRESET_15M -> "1m";
                                case PRESET_1H -> "5m";
                                case PRESET_24H -> "2h";
                                case PRESET_7D -> "6h";
                                case PRESET_15D -> "1d";
                                default -> DEFAULT_METRICS_INTERVAL;
                        };
                }

                long rangeMs = resolveRangeMillis(bounds.fromIso(), bounds.toIso());
                if (rangeMs <= 0) {
                        return DEFAULT_METRICS_INTERVAL;
                }

                long rawIntervalMs = Math.max(1L, rangeMs / TARGET_BUCKET_COUNT);
                return roundIntervalToStandard(rawIntervalMs);
        }

        private String roundIntervalToStandard(long intervalMs) {
                if (intervalMs <= 30_000L) {
                        return "30s";
                }
                if (intervalMs <= 60_000L) {
                        return "1m";
                }
                if (intervalMs <= 2 * 60_000L) {
                        return "2m";
                }
                if (intervalMs <= 5 * 60_000L) {
                        return "5m";
                }
                if (intervalMs <= 10 * 60_000L) {
                        return "10m";
                }
                if (intervalMs <= 15 * 60_000L) {
                        return "15m";
                }
                if (intervalMs <= 30 * 60_000L) {
                        return "30m";
                }
                if (intervalMs <= 60 * 60_000L) {
                        return "1h";
                }
                if (intervalMs <= 2 * 60 * 60_000L) {
                        return "2h";
                }
                if (intervalMs <= 6 * 60 * 60_000L) {
                        return "6h";
                }
                if (intervalMs <= 12 * 60 * 60_000L) {
                        return "12h";
                }
                return "1d";
        }

        private long resolveRangeMillis(String from, String to) {
                try {
                        if (from == null || from.isBlank() || to == null || to.isBlank()) {
                                return -1L;
                        }
                        Instant fromInstant = Instant.parse(from);
                        Instant toInstant = Instant.parse(to);
                        return Math.max(0L, Duration.between(fromInstant, toInstant).toMillis());
                } catch (Exception ignored) {
                        return -1L;
                }
        }

    private double extractP95(SearchResponse<Void> response) {
        try {
                if (response.aggregations() == null || response.aggregations().get(AGG_P95_LATENCY) == null) {
                        return 0.0;
                }

                Map<String, String> keyed = response.aggregations()
                        .get(AGG_P95_LATENCY)
                        .tdigestPercentiles()
                        .values()
                        .keyed();

                if (keyed == null) {
                        return 0.0;
                }

                String rawP95 = keyed.get("95.0");
                if (rawP95 == null || rawP95.isBlank()) {
                        return 0.0;
                }

                return normalizeDouble(Double.parseDouble(rawP95));
        } catch (Exception ignored) {
                return 0.0;
        }
    }

        private double normalizeDouble(double value) {
                return Double.isFinite(value) ? Math.round(value * 100.0) / 100.0 : 0.0;
        }

        public long countErrorsInWindow(String windowExpression) {
                try {
                        if (!hasAnyLogIndexes()) {
                                return 0L;
                        }

                        BoolQuery boolQuery = BoolQuery.of(b -> b
                                        .filter(buildErrorWindowFilters(windowExpression)));

                        CountRequest request = CountRequest.of(c -> c
                                        .index(INDEX_PATTERN)
                                        .query(boolQuery._toQuery()));

                        CountResponse response = client.count(request);
                        return response.count();
                } catch (Exception e) {
                        logErrorThrottled("countErrorsInWindow", e);
                        return 0L;
                }
        }

        public Map<String, Long> countErrorsByServiceInWindow(String windowExpression, int maxServices) {
                try {
                        if (!hasAnyLogIndexes()) {
                                return new HashMap<>();
                        }

                        BoolQuery boolQuery = BoolQuery.of(b -> b
                                        .filter(buildErrorWindowFilters(windowExpression)));

                        SearchRequest request = SearchRequest.of(s -> s
                                        .index(INDEX_PATTERN)
                                        .query(boolQuery._toQuery())
                                        .size(0)
                                        .aggregations("errors_by_service", a -> a
                                                        .terms(t -> t
                                                                        .field(SERVICE_KEYWORD)
                                                                        .size(maxServices))));

                        SearchResponse<Void> response = client.search(request, Void.class);

                        return response.aggregations()
                                        .get("errors_by_service")
                                        .sterms()
                                        .buckets()
                                        .array()
                                        .stream()
                                        .collect(Collectors.toMap(
                                                        bucket -> bucket.key().stringValue(),
                                                        bucket -> bucket.docCount()
                                        ));
                } catch (Exception e) {
                                                logErrorThrottled("countErrorsByServiceInWindow", e);
                        return new HashMap<>();
                }
        }

        private void logErrorThrottled(String operation, Exception e) {
                long now = System.currentTimeMillis();
                if (now >= nextErrorLogAtMs) {
                        nextErrorLogAtMs = now + ERROR_LOG_THROTTLE_MS;
                        LOGGER.warn("Elasticsearch {} failed: {}", operation, e.getMessage());
                }
        }

    // SHARED FILTER BUILDER

        private List<Query> buildFilters(String service, String environment, String level,
                                                                         String traceId, String message,
                                                                         TimeBounds bounds,
                                                                         AuthenticatedUserContext accessContext) {
        List<Query> filters = Stream.of(
                        buildServiceFilter(service, accessContext),

                        Optional.ofNullable(environment)
                                .filter(env -> !env.isBlank())
                                .map(env -> wildcardKeywordQuery(ENV_KEYWORD, env)),

                        Optional.ofNullable(level)
                                .filter(l -> !l.isEmpty())
                                .map(l -> wildcardKeywordQuery(LEVEL_KEYWORD, l)),

                        Optional.ofNullable(traceId)
                                .filter(tid -> !tid.isBlank())
                                .map(tid -> wildcardKeywordQuery(TRACE_KEYWORD, tid)),

                        Optional.ofNullable(message)
                                .filter(msg -> !msg.isBlank())
                                .map(this::buildMessageQuery)
                )
                .filter(Optional::isPresent)
                .map(Optional::get)
                                .collect(Collectors.toCollection(ArrayList::new));

                buildAccessFilter(accessContext).ifPresent(filters::add);

                filters.add(rangeQuery(bounds));

                return filters;
        }

        private Optional<Query> buildServiceFilter(String service, AuthenticatedUserContext accessContext) {
                if (!hasText(service) || "All Services".equalsIgnoreCase(service)) {
                        return Optional.empty();
                }

                String normalizedService = service.trim().toLowerCase();

                if (!accessContext.isAdmin() && !accessContext.isServiceAllowed(normalizedService)) {
                        LOGGER.warn("DEV user {} requested unauthorized service '{}'", accessContext.email(), service);
                        return Optional.of(noAccessQuery());
                }

                return Optional.of(TermQuery.of(t -> t
                                .field(SERVICE_KEYWORD)
                                .value(normalizedService)
                )._toQuery());
        }

        private Query buildMessageQuery(String message) {
                String sanitized = sanitizeForWildcard(message);
                Query wildcardOnKeyword = WildcardQuery.of(w -> w
                                .field("message.keyword")
                                .value("*" + sanitized + "*")
                                .caseInsensitive(true)
                )._toQuery();

                Query fuzzyText = MatchQuery.of(m -> m
                                .field(MESSAGE_FIELD)
                                .query(message)
                )._toQuery();

                return BoolQuery.of(b -> b
                                .should(wildcardOnKeyword)
                                .should(fuzzyText)
                                .minimumShouldMatch("1")
                )._toQuery();
        }

        private Query wildcardKeywordQuery(String field, String value) {
                String sanitized = sanitizeForWildcard(value);
                return WildcardQuery.of(w -> w
                                .field(field)
                                .value("*" + sanitized + "*")
                                .caseInsensitive(true)
                )._toQuery();
        }

        private String sanitizeForWildcard(String raw) {
                if (raw == null) {
                        return "";
                }
                return raw.trim()
                                .replace("\\", "\\\\")
                                .replace("*", "\\*")
                                .replace("?", "\\?");
        }

        private Optional<Query> buildAccessFilter(AuthenticatedUserContext accessContext) {
                if (accessContext == null || accessContext.isAdmin()) {
                        return Optional.empty();
                }

                List<String> allowedServices = accessContext.allowedServices();
                if (allowedServices == null || allowedServices.isEmpty()) {
                        return Optional.of(noAccessQuery());
                }

                boolean hasWildcardAccess = allowedServices.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .anyMatch(value -> "*".equals(value));

                if (hasWildcardAccess) {
                        return Optional.empty();
                }

                List<FieldValue> fieldValues = allowedServices.stream()
                        .filter(this::hasText)
                        .map(FieldValue::of)
                        .toList();

                if (fieldValues.isEmpty()) {
                        return Optional.of(noAccessQuery());
                }

                return Optional.of(QueryBuilders.terms()
                        .field(SERVICE_KEYWORD)
                        .terms(v -> v.value(fieldValues))
                        .build()._toQuery());
        }

        private Query noAccessQuery() {
                return TermQuery.of(t -> t
                                .field(SERVICE_KEYWORD)
                                .value(NO_ACCESS_SENTINEL)
                )._toQuery();
        }

    private Query rangeQuery(TimeBounds bounds) {
        return BoolQuery.of(b -> b
                .should(RangeQuery.of(r -> r
                        .field("timestamp")
                        .gte(JsonData.of(bounds.fromIso()))
                        .lte(JsonData.of(bounds.toIso())))._toQuery())
                .should(RangeQuery.of(r -> r
                        .field("@timestamp")
                        .gte(JsonData.of(bounds.fromIso()))
                        .lte(JsonData.of(bounds.toIso())))._toQuery())
                .minimumShouldMatch("1")
        )._toQuery();
    }
        private TimeBounds resolveTimeBounds(String requestedFrom, String requestedTo) {
                Instant now = Instant.now();
                Instant retentionStart = now.minus(RETENTION_PERIOD);

                Instant to = parseInstantOrNull(requestedTo);
                if (to == null || to.isAfter(now)) {
                        to = now;
                }

                Instant from = parseInstantOrNull(requestedFrom);
                if (from == null) {
                        from = retentionStart;
                }

                if (from.isBefore(retentionStart)) {
                        from = retentionStart;
                }

                if (from.isAfter(to)) {
                        from = to;
                }

                return new TimeBounds(from.toString(), to.toString());
        }

        private Instant parseInstantOrNull(String value) {
                try {
                        if (value == null || value.isBlank()) {
                                return null;
                        }
                        return Instant.parse(value);
                } catch (Exception ignored) {
                        return null;
                }
        }

        private boolean hasAnyLogIndexes() {
                try {
                        return client.indices().exists(e -> e.index(INDEX_PATTERN)).value();
                } catch (Exception e) {
                        logErrorThrottled("checkIndexes", e);
                        return false;
                }
        }

        private boolean hasText(String value) {
                return value != null && !value.isBlank();
        }

        private List<String> resolveWindowIndexes(String windowExpression) {
                Duration window = parseWindowExpression(windowExpression);
                if (window == null) {
                        return List.of(INDEX_PATTERN);
                }

                Instant now = Instant.now();
                Instant from = now.minus(window);
                ZoneId zone = ZoneId.systemDefault();

                Set<String> indexes = new LinkedHashSet<>();
                LocalDate current = from.atZone(zone).toLocalDate();
                LocalDate end = now.atZone(zone).toLocalDate();

                while (!current.isAfter(end)) {
                        indexes.add("app-logs-" + current);
                        current = current.plusDays(1);
                }

                return new ArrayList<>(indexes);
        }

        private Duration parseWindowExpression(String windowExpression) {
                if (!hasText(windowExpression)) {
                        return null;
                }

                try {
                        String trimmed = windowExpression.trim().toLowerCase();
                        if (trimmed.length() < 2) {
                                return null;
                        }

                        long amount = Long.parseLong(trimmed.substring(0, trimmed.length() - 1));
                        char unit = trimmed.charAt(trimmed.length() - 1);

                        return switch (unit) {
                                case 's' -> Duration.ofSeconds(amount);
                                case 'm' -> Duration.ofMinutes(amount);
                                case 'h' -> Duration.ofHours(amount);
                                case 'd' -> Duration.ofDays(amount);
                                default -> null;
                        };
                } catch (Exception ignored) {
                        return null;
                }
        }

        private record TimeBounds(String fromIso, String toIso) {}

        private List<Query> buildErrorWindowFilters(String windowExpression) {
                return Stream.of(
                                                TermQuery.of(t -> t
                                                                .field(LEVEL_KEYWORD)
                                                                .value("ERROR"))._toQuery(),
                                                RangeQuery.of(r -> r
                                                                .field(TIMESTAMP_FIELD)
                                                                .gte(JsonData.of("now-" + windowExpression))
                                                                .lte(JsonData.of("now")))._toQuery()
                                )
                                .toList();
        }
}