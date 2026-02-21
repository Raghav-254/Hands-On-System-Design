package com.logaggregation.service;

import com.logaggregation.model.LogEntry;
import com.logaggregation.model.LogLevel;
import com.logaggregation.storage.LogIndex;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Simulates the Log Processor (Logstash).
 * Consumes raw log lines from Kafka, parses into structured LogEntry, indexes to ES.
 * Supports both structured (JSON-like) and unstructured (plaintext) log parsing.
 */
public class LogProcessorService {
    private final LogIndex logIndex;
    private final List<LogEntry> bulkBuffer = new ArrayList<>();
    private final int bulkSize;
    private long totalProcessed = 0;
    private long parseErrors = 0;

    private static final Pattern STRUCTURED_PATTERN = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2}T[\\d:.]+Z?)\\s+(DEBUG|INFO|WARN|ERROR|FATAL)\\s+(.+?)\\s+host=(\\S+)\\s+service=(\\S+)");

    private static final Pattern KV_PATTERN = Pattern.compile("(\\w+)=([^\\s]+)");

    public LogProcessorService(LogIndex logIndex, int bulkSize) {
        this.logIndex = logIndex;
        this.bulkSize = bulkSize;
    }

    /**
     * Process a raw log line: parse → enrich → buffer for bulk index.
     */
    public LogEntry process(String rawLine) {
        LogEntry entry = parse(rawLine);
        if (entry != null) {
            bulkBuffer.add(entry);
            totalProcessed++;

            if (bulkBuffer.size() >= bulkSize) {
                flushBulk();
            }
        }
        return entry;
    }

    /**
     * Parse a raw log line into a structured LogEntry.
     */
    public LogEntry parse(String rawLine) {
        Matcher m = STRUCTURED_PATTERN.matcher(rawLine);
        if (m.find()) {
            String timestampStr = m.group(1);
            if (!timestampStr.endsWith("Z")) timestampStr += "Z";
            Instant timestamp = Instant.parse(timestampStr);
            LogLevel level = LogLevel.valueOf(m.group(2));
            String message = m.group(3).trim();
            String host = m.group(4);
            String service = m.group(5);

            Map<String, String> metadata = new HashMap<>();
            Matcher kvMatcher = KV_PATTERN.matcher(rawLine);
            while (kvMatcher.find()) {
                String key = kvMatcher.group(1);
                if (!key.equals("host") && !key.equals("service")) {
                    metadata.put(key, kvMatcher.group(2));
                }
            }

            return new LogEntry(timestamp, level, service, host, message, metadata);
        }

        parseErrors++;
        return new LogEntry(Instant.now(), LogLevel.INFO, "unknown", "unknown",
                rawLine, Collections.singletonMap("parse_error", "true"));
    }

    /**
     * Flush bulk buffer to Elasticsearch (simulates bulk index API).
     */
    public void flushBulk() {
        if (!bulkBuffer.isEmpty()) {
            logIndex.bulkIndex(new ArrayList<>(bulkBuffer));
            bulkBuffer.clear();
        }
    }

    public long getTotalProcessed() { return totalProcessed; }
    public long getParseErrors() { return parseErrors; }
    public int getBufferSize() { return bulkBuffer.size(); }
}
