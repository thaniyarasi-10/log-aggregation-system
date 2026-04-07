package com.kovanlabs.logcontroller.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import com.kovanlabs.logcontroller.model.LogEvent;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.TermQuery;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonData;

@Repository
public class ElasticRepository {

    private static final String TIMESTAMP_FIELD = "timestamp";
    private static final String LEVEL_KEYWORD   = "level.keyword";
    private static final String SERVICE_KEYWORD = "service.keyword";
    private static final String INDEX_PATTERN   = "app-logs-*";

    @Autowired
    private ElasticsearchClient client;

    // SAVE
    public void save(LogEvent log) {
        try {
            String index = "app-logs-" + LocalDate.now();
            IndexRequest<LogEvent> request = IndexRequest.of(i -> i
                    .index(index)
                    .document(log)
            );
            client.index(request);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

//SEARCH
    public List<LogEvent> search(String service, String level,
                                 String from, String to,
                                 int page, int size) {
        try {
            String effectiveFrom = (from == null || from.isBlank()) ? "now-24h" : from;
            String effectiveTo = (to == null || to.isBlank()) ? "now" : to;

            BoolQuery boolQuery = BoolQuery.of(b -> b
                    .filter(buildFilters(service, level, effectiveFrom, effectiveTo)));

            SearchRequest request = SearchRequest.of(s -> s
                    .index(INDEX_PATTERN)
                    .query(boolQuery._toQuery())
                    .from(page * size)
                    .size(size)
                    .sort(sort -> sort
                            .field(f -> f
                                    .field(TIMESTAMP_FIELD)
                                    .order(SortOrder.Desc)))
            );

            SearchResponse<LogEvent> response = client.search(request, LogEvent.class);

            return response.hits().hits()
                    .stream()
                    .map(Hit::source)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            e.printStackTrace();
            return new ArrayList<>();
        }
    }

// METRICS

    public Map<String, Object> getMetrics(String service, String from, String to) {
        try {
            BoolQuery boolQuery = BoolQuery.of(b -> b
                    .filter(buildFilters(service, null, from, to)));

            SearchRequest request = SearchRequest.of(s -> s
                    .index(INDEX_PATTERN)
                    .query(boolQuery._toQuery())
                    .size(0)
                    .aggregations("total_count", a -> a
                            .valueCount(v -> v.field(LEVEL_KEYWORD)))
                    .aggregations("error_count", a -> a
                            .filter(f -> f
                                    .term(t -> t
                                            .field(LEVEL_KEYWORD)
                                            .value("ERROR"))))
                    .aggregations("avg_response_time", a -> a
                            .avg(avg -> avg.field("responseTime")))
                    .aggregations("p95_latency", a -> a
                            .percentiles(p -> p
                                    .field("responseTime")
                                    .percents(95.0)))
                    .aggregations("errors_over_time", a -> a
                            .dateHistogram(dh -> dh
                                    .field(TIMESTAMP_FIELD)
                                    .calendarInterval(
                                            co.elastic.clients.elasticsearch._types.aggregations
                                                    .CalendarInterval.Hour)))
            );

            SearchResponse<Void> response = client.search(request, Void.class);

            long total = (long) response.aggregations()
                    .get("total_count").valueCount().value();

            long errors = response.aggregations()
                    .get("error_count").filter().docCount();

            double avgRt = response.aggregations()
                    .get("avg_response_time").avg().value();

            double p95 = response.aggregations()
                    .get("p95_latency")
                    .tdigestPercentiles()
                    .values()
                    .keyed()
                    .entrySet()
                    .stream()
                    .filter(e -> e.getKey().equals("95.0"))
                    .mapToDouble(e -> Double.parseDouble(e.getValue()))
                    .findFirst()
                    .orElse(0.0);

            List<Map<String, Object>> errorsOverTime = response.aggregations()
                    .get("errors_over_time")
                    .dateHistogram()
                    .buckets()
                    .array()
                    .stream()
                    .map(bucket -> Map.<String, Object>of(
                            "time",  Optional.ofNullable(bucket.keyAsString()).orElse(""),
                            "count", bucket.docCount()
                    ))
                    .collect(Collectors.toList());

            double errorRate = total > 0 ? (errors * 100.0 / total) : 0.0;

            return Stream.of(
                    Map.entry("totalLogs",       total),
                    Map.entry("errorCount",      errors),
                    Map.entry("errorRate",       Math.round(errorRate * 100.0) / 100.0 + "%"),
                    Map.entry("avgResponseTime", Math.round(avgRt) + "ms"),
                    Map.entry("p95Latency",      Math.round(p95) + "ms"),
                    Map.entry("errorsOverTime",  errorsOverTime)
            ).collect(Collectors.toMap(
                    Map.Entry::getKey,
                    Map.Entry::getValue
            ));

        } catch (Exception e) {
            e.printStackTrace();
            return new HashMap<>();
        }
    }

        public long countErrorsInWindow(String windowExpression) {
                try {
                        BoolQuery boolQuery = BoolQuery.of(b -> b
                                        .filter(buildErrorWindowFilters(windowExpression)));

                        SearchRequest request = SearchRequest.of(s -> s
                                        .index(INDEX_PATTERN)
                                        .query(boolQuery._toQuery())
                                        .size(0));

                        SearchResponse<Void> response = client.search(request, Void.class);
                        return response.hits().total() != null ? response.hits().total().value() : 0L;
                } catch (Exception e) {
                        e.printStackTrace();
                        return 0L;
                }
        }

        public Map<String, Long> countErrorsByServiceInWindow(String windowExpression, int maxServices) {
                try {
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
                        e.printStackTrace();
                        return new HashMap<>();
                }
        }

    // SHARED FILTER BUILDER

    private List<Query> buildFilters(String service, String level,
                                     String from, String to) {
        List<Query> filters = Stream.of(
                        Optional.ofNullable(service)
                                .filter(s -> !s.isEmpty())
                                .map(s -> TermQuery.of(t -> t
                                        .field(SERVICE_KEYWORD)
                                        .value(s))._toQuery()),

                        Optional.ofNullable(level)
                                .filter(l -> !l.isEmpty())
                                .map(l -> TermQuery.of(t -> t
                                        .field(LEVEL_KEYWORD)
                                        .value(l))._toQuery())
                )
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());

        if ((from != null && !from.isBlank()) || (to != null && !to.isBlank())) {
            filters.add(RangeQuery.of(r -> {
                r.field(TIMESTAMP_FIELD);
                if (from != null && !from.isBlank()) {
                    r.gte(JsonData.of(from));
                }
                if (to != null && !to.isBlank()) {
                    r.lte(JsonData.of(to));
                }
                return r;
            })._toQuery());
        }

        return filters;
    }

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
                                .collect(Collectors.toList());
        }
}