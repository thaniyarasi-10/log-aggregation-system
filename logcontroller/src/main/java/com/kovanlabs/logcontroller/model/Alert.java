package com.kovanlabs.logcontroller.model;

import java.time.LocalDateTime;

public class Alert {

    private String service;
    private String message;
    private int count;
    private String severity;
    private LocalDateTime timestamp;

    public Alert() {
    }

    public Alert(String service, String message, int count, String severity, LocalDateTime timestamp) {
        this.service = service;
        this.message = message;
        this.count = count;
        this.severity = severity;
        this.timestamp = timestamp;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
    }
}
