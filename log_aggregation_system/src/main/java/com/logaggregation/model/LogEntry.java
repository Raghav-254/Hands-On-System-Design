package com.logaggregation.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LogEntry {
    private final String logId;
    private final Instant timestamp;
    private final LogLevel level;
    private final String service;
    private final String host;
    private final String message;
    private final Map<String, String> metadata;
    private final Instant ingestedAt;

    public LogEntry(Instant timestamp, LogLevel level, String service,
                    String host, String message, Map<String, String> metadata) {
        this.logId = UUID.randomUUID().toString().substring(0, 12);
        this.timestamp = timestamp;
        this.level = level;
        this.service = service;
        this.host = host;
        this.message = message;
        this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
        this.ingestedAt = Instant.now();
    }

    public String getLogId() { return logId; }
    public Instant getTimestamp() { return timestamp; }
    public LogLevel getLevel() { return level; }
    public String getService() { return service; }
    public String getHost() { return host; }
    public String getMessage() { return message; }
    public Map<String, String> getMetadata() { return metadata; }
    public Instant getIngestedAt() { return ingestedAt; }

    public String getDateKey() {
        return timestamp.toString().substring(0, 10);
    }

    @Override
    public String toString() {
        return String.format("[%s] %s %s@%s: %s",
                timestamp.toString().substring(11, 23), level, service, host, message);
    }
}
