package com.kovanlabs.logcontroller.repository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import com.kovanlabs.logcontroller.auth.AuthenticatedUserContext;
import com.kovanlabs.logcontroller.model.LogEvent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.Time;
import co.elastic.clients.elasticsearch._types.aggregations.FieldDateMath;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.QueryBuilders;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.CountRequest;
import co.elastic.clients.elasticsearch.core.CountResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

@Repository
public class ElasticRepository {

        private static final Logger LOGGER = LoggerFactory.getLogger(ElasticRepository.class);

    private static final String LEVEL_FIELD       = "level";       // keyword, lowercase_normalizer
    private static final String SERVICE_FIELD     = "service";     // keyword
    private static final String ENVIRONMENT_FIELD = "environment"; // keyword
    private static final String PROJECT_FIELD     = "project";     // keyword

    // Error level value — must be lowercase to match the lowercase_normalizer on "level"
    private static final String ERROR_LEVEL_VALUE = "error";
    private static final String INDEX_PATTERN   = "app-logs-*";
                private static final String NO_ACCESS_SENTINEL = "__NO_ACCESS__";
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

        // Mute check — visible, counted, never silent
        if (now < writesMutedUntilMs) {
            long remainingMs = writesMutedUntilMs - now;
            LOGGER.warn("ES WRITE MUTED — skipping index. Mute expires in {}ms. " +
                    "Check earlier 'ES SAVE FAILED' log for the root cause.", remainingMs);
            return;
        }

        if (log == null) {
            LOGGER.warn("ES SAVE — received null LogEvent, skipping");
            return;
        }


        if (!isValidForIndexing(log)) {
            return;
        }

        String index = "app-logs-" + resolveIndexDate(log.getTimestamp());
//        LOGGER.info(
//                "INDEX NAME USED = {} | timestamp = {} | service = {}",
//                index,
//                log.getTimestamp(),
//                log.getService()
//        );
//        LOGGER.debug("ES SAVE START — index={} service={} timestamp={}", index, log.getService(), log.getTimestamp());

        try {

            IndexRequest<LogEvent> request = IndexRequest.of(i -> i
                    .index(index)
                    .document(log)
            );
            client.index(request);

            if (writesMutedUntilMs != 0L) {
                writesMutedUntilMs = 0L;
                LOGGER.info("ES SAVE — Elasticsearch connection restored. Resuming log persistence.");
            }

            //LOGGER.debug("ES SAVE SUCCESS — index={} service={}", index, log.getService());

        } catch (co.elastic.clients.elasticsearch._types.ElasticsearchException esEx) {

            int status = esEx.status();
            if (status >= 400 && status < 500) {
                LOGGER.error("ES SAVE FAILED — non-retryable HTTP {} from Elasticsearch. " +
                        "Likely cause: mapping conflict or malformed field. " +
                        "index={} service={} timestamp={} error={}",
                        status, index, log.getService(), log.getTimestamp(), esEx.getMessage(), esEx);
                // Do NOT mute writes for client errors — the next document may be fine.
            } else {
                writesMutedUntilMs = now + WRITE_BACKOFF_MS;
                LOGGER.error("ES SAVE FAILED — HTTP {} from Elasticsearch. " +
                        "Writes muted for {}ms. index={} service={} error={}",
                        status, WRITE_BACKOFF_MS, index, log.getService(), esEx.getMessage(), esEx);
            }
        } catch (Exception e) {
            // Covers connection failures, timeouts, serialization errors, etc.
            writesMutedUntilMs = now + WRITE_BACKOFF_MS;
            LOGGER.error("ES SAVE FAILED — unexpected exception. " +
                    "Writes muted for {}ms. index={} service={} timestamp={} exceptionType={} message={}",
                    WRITE_BACKOFF_MS, index, log.getService(), log.getTimestamp(),
                    e.getClass().getSimpleName(), e.getMessage(), e);
        }
    }

    private boolean isValidForIndexing(LogEvent log) {
        // @timestamp must be a valid ISO-8601 instant — ES will reject anything else
        // against the "strict_date_optional_time||epoch_millis" format in the mapping.
        if (log.getTimestamp() != null && !log.getTimestamp().isBlank()) {
            try {
                Instant.parse(log.getTimestamp());
            } catch (java.time.format.DateTimeParseException e) {
                LOGGER.error("ES SAVE SKIPPED — invalid @timestamp '{}' for service='{}'. " +
                        "Expected ISO-8601 format (e.g. 2026-05-12T10:00:00Z). " +
                        "This document would cause a mapping conflict and has been dropped.",
                        log.getTimestamp(), log.getService());
                return false;
            }
        }

        // service and message are the minimum fields needed for a useful log entry.
        // Indexing a document with both null would pollute the index with useless records.
        if ((log.getService() == null || log.getService().isBlank()) &&
                (log.getMessage() == null || log.getMessage().isBlank())) {
            LOGGER.warn("ES SAVE SKIPPED — document has no service and no message. Dropping to avoid index pollution.");
            return false;
        }

        return true;
    }

    /**
     * Derives the UTC date string (yyyy-MM-dd) to use as the index suffix.
     *
     * <p>The event's own {@code @timestamp} is the source of truth so that replayed
     * or late-arriving Kafka messages land in the correct historical index rather than
     * today's index. Falls back to the current UTC date only when the timestamp is
     * absent or cannot be parsed as an ISO-8601 instant.
     *
     * @param rawTimestamp the value of {@code @timestamp} from the incoming log event
     * @return a UTC date string, e.g. {@code "2026-05-06"}
     */
    private String resolveIndexDate(String rawTimestamp) {
        if (rawTimestamp != null && !rawTimestamp.isBlank()) {
            try {
                return Instant.parse(rawTimestamp)
                        .atZone(java.time.ZoneOffset.UTC)
                        .toLocalDate()
                        .toString();
            } catch (java.time.format.DateTimeParseException e) {
                LOGGER.warn("Unparseable @timestamp '{}' — falling back to current UTC date for index naming", rawTimestamp);
            }
        }
        // Fallback: use current UTC date (covers events with no timestamp set)
        return LocalDate.now(java.time.ZoneOffset.UTC).toString();
    }
    @Scheduled(fixedDelay = 35_000) // runs every 35s, slightly longer than backoff window
    public void resetWriteMuteIfExpired() {
        long now = System.currentTimeMillis();
        if (writesMutedUntilMs != 0L && now >= writesMutedUntilMs) {
            writesMutedUntilMs = 0L;
            LOGGER.info("ES WRITE MUTE — backoff period expired. ES writes re-enabled. " +
                    "If indexing still fails, check ES cluster health and index mappings.");
        }
    }

//SEARCH
        public List<LogEvent> search(String service, String environment, String level,
                                                                 String traceId, String message,
                                 String from, String to,
                                 int page, int size,
                                 AuthenticatedUserContext accessContext) {
        try {
		LOGGER.debug("SEARCH INPUT → service=[{}] environment=[{}] level=[{}] traceId=[{}] message=[{}]",
				service, environment, level, traceId, message);

		TimeBounds bounds = resolveTimeBounds(from, to);
		
		BoolQuery.Builder boolQueryBuilder = QueryBuilders.bool();

		if (service != null && !service.isBlank() && !"All Services".equalsIgnoreCase(service)) {
			boolQueryBuilder.must(
				QueryBuilders.term(t -> t
					.field(SERVICE_FIELD)
					.value(service.trim().toLowerCase(java.util.Locale.ROOT))
				)
			);
		}

		if (environment != null && !environment.isBlank()) {
			// "environment" is a pure keyword field — query directly, no .keyword sub-field
			boolQueryBuilder.must(
				QueryBuilders.term(t -> t
					.field(ENVIRONMENT_FIELD)
					.value(environment.trim().toLowerCase(java.util.Locale.ROOT))
				)
			);
		}

		if (level != null && !level.isBlank()) {
			// "level" is a keyword field with lowercase_normalizer — values must be lowercase.
			boolQueryBuilder.must(
				QueryBuilders.term(t -> t
					.field(LEVEL_FIELD)
					.value(level.trim().toLowerCase(java.util.Locale.ROOT))
				)
			);
		}
		if (traceId != null && !traceId.isBlank()) {
			// traceId is stored as-is — use term for exact match.
			// Try both camelCase and snake_case field names to handle varied log formats.
			boolQueryBuilder.must(
				QueryBuilders.bool(b -> b
					.should(s -> s.term(t -> t.field("traceId").value(traceId.trim())))
					.should(s -> s.term(t -> t.field("trace_id").value(traceId.trim())))
					.minimumShouldMatch("1")
				)
			);
		}

            if (message != null && !message.isBlank()) {
                String trimmed = message.trim();
                // "message" is a full-text analyzed field (type: text).
                // Use match query — NOT term query — so Elasticsearch applies the same
                // analyzer used at index time (tokenization, lowercasing, stemming).
                //
                // Strategy: combine match (token-based) with match_phrase (exact phrase)
                // in a should clause so that:
                //   - Single words match any document containing that token ("error" → hits)
                //   - Multi-word inputs match documents containing all tokens (OR) OR the
                //     exact phrase (boosted), giving phrase matches higher relevance.
                //
                // Operator.OR is used so "database timeout" returns docs with either word,
                // not just docs containing both — this is the expected "contains" behavior
                // for log search. The match_phrase clause boosts exact phrase matches.
                boolQueryBuilder.must(
                    QueryBuilders.bool(inner -> inner
                        .should(s -> s
                            .match(m -> m
                                .field("message")
                                .query(trimmed)
                                .operator(co.elastic.clients.elasticsearch._types.query_dsl.Operator.Or)
                                .minimumShouldMatch("1")
                            )
                        )
                        .should(s -> s
                            .matchPhrase(mp -> mp
                                .field("message")
                                .query(trimmed)
                                .boost(2.0f)
                            )
                        )
                        .minimumShouldMatch("1")
                    )
                );
            }

		if (from != null || to != null) {
			boolQueryBuilder.filter(rangeQuery(bounds));
		}

		// Apply RBAC access filter — always enforced for all roles including admin.
		// buildAccessFilter returns a terms filter scoped to the user's allowed services.
		buildAccessFilter(accessContext).ifPresent(boolQueryBuilder::filter);

		BoolQuery boolQuery = boolQueryBuilder.build();

		SearchRequest request = SearchRequest.of(s -> s
				.index(INDEX_PATTERN)
				.query(boolQuery._toQuery())
				.from(page * size)
				.size(Math.min(size, 500))
				.sort(sort -> sort
						.field(f -> f
								.field("@timestamp")
										.order(SortOrder.Desc)))
		);

		LOGGER.debug("ES LOG SEARCH QUERY={}", boolQuery._toQuery());

		SearchResponse<LogEvent> response = client.search(request, LogEvent.class);
		//LOGGER.error("TOTAL HITS → {}", response.getHits().getTotalHits().value());

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
                                                                        .field(SERVICE_FIELD)
                                                                        .size(Math.max(1, maxServices))
                                                        ))
                                        .aggregations("projects", a -> a
                                                        .terms(t -> t
                                                                        .field(PROJECT_FIELD)
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
        LOGGER.debug("ES METRICS QUERY → {}", boolQuery._toQuery());

        return SearchRequest.of(s -> s
                .index(INDEX_PATTERN)
                .query(boolQuery._toQuery())
                .size(0)
                .aggregations(AGG_TOTAL_COUNT, a -> a
                        .valueCount(v -> v.field("@timestamp")))
                .aggregations(AGG_ERROR_COUNT, a -> a
                        .filter(f -> f
                                .term(t -> t
                                        // "level" is keyword with lowercase_normalizer — use lowercase value
                                        .field(LEVEL_FIELD)
                                        .value(ERROR_LEVEL_VALUE))))
                .aggregations(AGG_AVG_RESPONSE_TIME, a -> a
                        .avg(avg -> avg.field(RESPONSE_TIME_FIELD)))
                .aggregations(AGG_P95_LATENCY, a -> a
                        .percentiles(p -> p
                                .field(RESPONSE_TIME_FIELD)
                                .percents(95.0)))
                .aggregations(AGG_LEVEL_DISTRIBUTION, a -> a
                        .terms(t -> t
                                // "level" is already a keyword field — no .keyword sub-field needed
                                .field(LEVEL_FIELD)
                                .size(10)))
                .aggregations(AGG_THROUGHPUT_OVER_TIME, a -> a
                        .dateHistogram(dh -> dh
                                .field("@timestamp")
                                .fixedInterval(Time.of(t -> t.time(interval)))
                                .minDocCount(0)
                                .extendedBounds(eb -> eb
                                        .min(FieldDateMath.of(f -> f.expr(bounds.getFromInstant().toString())))
                                        .max(FieldDateMath.of(f -> f.expr(bounds.getToInstant().toString()))))
                        )
                        .aggregations(AGG_BUCKET_ERROR_COUNT, sub -> sub
                                .filter(f -> f
                                        .term(t -> t
                                                // "level" keyword with lowercase_normalizer
                                                .field(LEVEL_FIELD)
                                                .value(ERROR_LEVEL_VALUE))))
                        .aggregations(AGG_BUCKET_AVG_RESPONSE_TIME, sub -> sub
                                .avg(avg -> avg.field(RESPONSE_TIME_FIELD))))
        );
    }

    private List<Query> buildMetricFilters(String service, TimeBounds bounds, AuthenticatedUserContext accessContext) {
        List<Query> filters = new ArrayList<>();

        // SERVICE FILTER
        // When a specific service is requested, scope to that service — but only if the user
        // is actually allowed to access it (the access filter below will enforce this anyway,
        // but an explicit term keeps the query intent clear and avoids a full terms scan).
        if (service != null && !service.isBlank() && !"All Services".equalsIgnoreCase(service)) {
            filters.add(
                    TermQuery.of(t -> t
                            // "service" is a pure keyword field — no .keyword sub-field
                            .field(SERVICE_FIELD)
                            .value(service.trim().toLowerCase(java.util.Locale.ROOT))
                    )._toQuery()
            );
        }

        // ACCESS FILTER — always applied, for every role including admin.
        // Admin contexts have their full list of active services in allowedServices (populated
        // by ServiceAccessAuthorizationService.resolveServicesForUser). This ensures that even
        // admins only see logs from services that are registered and active in the database,
        // never raw index documents from unregistered services.
        buildAccessFilter(accessContext).ifPresent(filters::add);

        // TIME FILTER — declared as finals so lambda can capture them
        final String metricFromStr = bounds.getFromInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        final String metricToStr = bounds.getToInstant().truncatedTo(ChronoUnit.SECONDS).toString();
        filters.add(
                RangeQuery.of(r -> r
                        .field("@timestamp")
                        .gte(JsonData.of(metricFromStr))
                        .lte(JsonData.of(metricToStr)))._toQuery()
        );

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

                long rangeMs = resolveRangeMillis(bounds.getFromInstant(), bounds.getToInstant());
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

        private long resolveRangeMillis(Instant from, Instant to) {
                try {
                        if (from == null || to == null) {
                                return -1L;
                        }
                        return Math.max(0L, Duration.between(from, to).toMillis());
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
                                                                        // "service" is a pure keyword field — no .keyword sub-field
                                                                        .field(SERVICE_FIELD)
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
                        LOGGER.error("Elasticsearch {} failed (throttled — next log in {}ms): {} — {}",
                                operation, ERROR_LOG_THROTTLE_MS, e.getClass().getSimpleName(), e.getMessage(), e);
                }
        }

    // SHARED FILTER BUILDER


        /**
         * Builds a terms filter that restricts the Elasticsearch query to only the services
         * the authenticated user is allowed to access.
         *
         * <p>This filter is ALWAYS applied — including for admin users. Admin users have their
         * full list of active services pre-populated in {@code allowedServices} by
         * {@link com.kovanlabs.logcontroller.service.ServiceAccessAuthorizationService}.
         * Skipping the filter for admins would allow unfiltered access to every document in
         * the index, including services that are not registered in the database.
         *
         * <p>The wildcard sentinel {@code "*"} is treated as "all services" only when the
         * context is an admin — in that case the caller should ensure {@code allowedServices}
         * contains the real service names, not a wildcard. If a wildcard is present for a
         * non-admin it is treated as no-access to prevent privilege escalation.
         */
        private Optional<Query> buildAccessFilter(AuthenticatedUserContext accessContext) {
                if (accessContext == null) {
                        // No context at all — deny everything
                        return Optional.of(noAccessQuery());
                }

                List<String> allowedServices = accessContext.allowedServices();
                if (allowedServices == null || allowedServices.isEmpty()) {
                        return Optional.of(noAccessQuery());
                }

                // Wildcard is only meaningful for admin; non-admin wildcard is treated as no-access
                // to prevent privilege escalation via a misconfigured context.
                boolean hasWildcardAccess = allowedServices.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .anyMatch(value -> "*".equals(value));

                if (hasWildcardAccess) {
                        if (accessContext.isAdmin()) {
                                // Admin with wildcard: should not happen in normal flow because
                                // toAuthenticatedContext populates real service names, but if it
                                // does occur we fall through to noAccessQuery to force a DB reload
                                // rather than silently returning unfiltered results.
                                LOGGER.warn(
                                        "Admin context for '{}' has wildcard allowedServices — "
                                        + "expected concrete service names. Returning no-access filter. "
                                        + "Check ServiceAccessAuthorizationService.resolveServicesForUser.",
                                        accessContext.email());
                        }
                        return Optional.of(noAccessQuery());
                }

                List<FieldValue> fieldValues = allowedServices.stream()
                        .filter(this::hasText)
                        .map(String::trim)
                        .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                        .map(FieldValue::of)
                        .toList();

                if (fieldValues.isEmpty()) {
                        return Optional.of(noAccessQuery());
                }

                return Optional.of(QueryBuilders.terms()
                        // "service" is a pure keyword field — no .keyword sub-field
                        .field(SERVICE_FIELD)
                        .terms(v -> v.value(fieldValues))
                        .build()._toQuery());
        }

        private Query noAccessQuery() {
                return TermQuery.of(t -> t
                                // "service" is a pure keyword field
                                .field(SERVICE_FIELD)
                                .value(NO_ACCESS_SENTINEL)
                )._toQuery();
        }

    // In rangeQuery() method, timestamps are always UTC ISO-8601 — no timezone conversion needed
    private Query rangeQuery(TimeBounds bounds) {
        Instant from = bounds.getFromInstant().truncatedTo(ChronoUnit.SECONDS);
        Instant to = bounds.getToInstant()
                .truncatedTo(ChronoUnit.SECONDS)
                .plusSeconds(2);

        return RangeQuery.of(r -> r
                        .field("@timestamp")
                        .gte(JsonData.of(from.toString()))
                        .lte(JsonData.of(to.toString())))
                ._toQuery();
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

                return new TimeBounds(from, to);
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




        private record TimeBounds(Instant fromInstant, Instant toInstant) {
                public Instant getFromInstant() {
                        return fromInstant;
                }

                public Instant getToInstant() {
                        return toInstant;
                }
        }

        private List<Query> buildErrorWindowFilters(String windowExpression) {
                List<Query> filters = Stream.of(
                                // "level" is a keyword field with lowercase_normalizer.
                                // The value MUST be lowercase — "ERROR" would match zero documents.
                                TermQuery.of(t -> t
                                                .field(LEVEL_FIELD)
                                                .value(ERROR_LEVEL_VALUE))._toQuery(),
                                RangeQuery.of(r -> r
                                                .field("@timestamp")
                                                .gte(JsonData.of("now-" + windowExpression))
                                                .lte(JsonData.of("now")))._toQuery()
                                )
                                .toList();
                LOGGER.debug("ES ALERT WINDOW FILTERS → level={} window={}", ERROR_LEVEL_VALUE, windowExpression);
                return filters;
        }
}