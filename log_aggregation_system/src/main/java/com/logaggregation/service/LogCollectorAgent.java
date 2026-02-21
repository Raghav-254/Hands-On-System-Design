package com.logaggregation.service;

import com.logaggregation.event.EventBus;

import java.util.ArrayList;
import java.util.List;

/**
 * Simulates a log collection agent (Filebeat/Fluentd) running on each host.
 * Tails log files and pushes raw lines to Kafka in batches.
 * Includes local spool buffer for resilience if Kafka is unreachable.
 */
public class LogCollectorAgent {
    private final String hostId;
    private final String service;
    private final EventBus kafka;
    private final int batchSize;
    private final List<String> buffer = new ArrayList<>();
    private final List<String> spoolFile = new ArrayList<>();
    private boolean kafkaAvailable = true;
    private long totalShipped = 0;
    private long totalSpooled = 0;

    public LogCollectorAgent(String hostId, String service, EventBus kafka, int batchSize) {
        this.hostId = hostId;
        this.service = service;
        this.kafka = kafka;
        this.batchSize = batchSize;
    }

    /**
     * Simulate tailing a new log line from the application's log file.
     */
    public void tailLogLine(String rawLine) {
        String enriched = rawLine + " host=" + hostId + " service=" + service;
        buffer.add(enriched);

        if (buffer.size() >= batchSize) {
            flush();
        }
    }

    /**
     * Flush buffered log lines to Kafka (or spool file if Kafka is down).
     */
    public void flush() {
        if (buffer.isEmpty() && spoolFile.isEmpty()) return;

        if (kafkaAvailable) {
            if (!spoolFile.isEmpty()) {
                kafka.publishBatch(new ArrayList<>(spoolFile));
                totalShipped += spoolFile.size();
                spoolFile.clear();
            }
            kafka.publishBatch(new ArrayList<>(buffer));
            totalShipped += buffer.size();
            buffer.clear();
        } else {
            spoolFile.addAll(buffer);
            totalSpooled += buffer.size();
            buffer.clear();
        }
    }

    public void setKafkaAvailable(boolean available) {
        this.kafkaAvailable = available;
    }

    public long getTotalShipped() { return totalShipped; }
    public long getTotalSpooled() { return totalSpooled; }
    public int getSpoolSize() { return spoolFile.size(); }
    public String getHostId() { return hostId; }
    public String getService() { return service; }
}
