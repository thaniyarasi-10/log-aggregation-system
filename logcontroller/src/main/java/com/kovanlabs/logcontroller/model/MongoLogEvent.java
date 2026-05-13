package com.kovanlabs.logcontroller.model;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "logs")
@CompoundIndex(name = "service_timestamp_idx", def = "{'serviceName': 1, 'timestamp': -1}")
public class MongoLogEvent {

    @Id
    private String id;

    @Indexed(name = "timestamp_ttl_idx", expireAfter = "15d")
    private Instant timestamp;

    @Indexed(name = "level_idx")
    private String level;

    private String serviceName;

    private String instance;
    private String environment;
    private String message;

    private String traceId;

    private String spanId;
    private String userId;
    private String endpoint;
    private String method;
    private Integer statusCode;
    private Double responseTime;

    private String errorCode;
    private String errorDetails;
    private Object tags;

    private Instant ingestedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Instant timestamp) {
        this.timestamp = timestamp;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getInstance() {
        return instance;
    }

    public void setInstance(String instance) {
        this.instance = instance;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(Integer statusCode) {
        this.statusCode = statusCode;
    }

    public Double getResponseTime() {
        return responseTime;
    }

    public void setResponseTime(Double responseTime) {
        this.responseTime = responseTime;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getErrorDetails() {
        return errorDetails;
    }

    public void setErrorDetails(String errorDetails) {
        this.errorDetails = errorDetails;
    }

    public Object getTags() {
        return tags;
    }

    public void setTags(Object tags) {
        this.tags = tags;
    }

    public Instant getIngestedAt() {
        return ingestedAt;
    }

    public void setIngestedAt(Instant ingestedAt) {
        this.ingestedAt = ingestedAt;
    }

    public static MongoLogEvent from(LogEvent event) {
        MongoLogEvent document = new MongoLogEvent();
        document.setTimestamp(resolveTimestamp(event.getTimestamp()));
        document.setLevel(event.getLevel());
        document.setServiceName(event.getService());
        document.setInstance(event.getInstance());
        document.setEnvironment(event.getEnvironment());
        document.setMessage(event.getMessage());
        document.setTraceId(event.getTraceId());
        document.setSpanId(event.getSpanId());
        document.setUserId(event.getUserId());
        document.setEndpoint(event.getEndpoint());
        document.setMethod(event.getMethod());
        document.setStatusCode(event.getStatusCode());
        document.setResponseTime(event.getResponseTime());
        document.setErrorCode(event.getErrorCode());
        document.setErrorDetails(event.getErrorDetails());
        document.setTags(event.getTags());
        document.setIngestedAt(Instant.now());
        return document;
    }

    private static Instant resolveTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return Instant.now();
        }

        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.now();
        }
    }
}
