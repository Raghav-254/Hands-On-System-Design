package com.logaggregation.event;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Simulates Kafka as a durable log transport buffer.
 * Topic: "raw-logs", partitioned by service_id.
 * Multiple consumer groups can read independently.
 */
public class EventBus {
    private final Queue<String> rawLogs = new ConcurrentLinkedQueue<>();
    private final List<Consumer<String>> consumers = Collections.synchronizedList(new ArrayList<>());
    private long totalPublished = 0;
    private long totalConsumed = 0;

    /**
     * Agent pushes a raw log line to Kafka.
     */
    public void publish(String rawLogLine) {
        rawLogs.add(rawLogLine);
        totalPublished++;
        for (Consumer<String> consumer : consumers) {
            consumer.accept(rawLogLine);
            totalConsumed++;
        }
    }

    /**
     * Publish a batch of raw log lines (agent sends in batches for efficiency).
     */
    public void publishBatch(List<String> batch) {
        for (String line : batch) {
            publish(line);
        }
    }

    /**
     * Register a consumer (consumer group member).
     */
    public void subscribe(Consumer<String> consumer) {
        consumers.add(consumer);
    }

    public long getTotalPublished() { return totalPublished; }
    public long getTotalConsumed() { return totalConsumed; }
    public int getPendingCount() { return rawLogs.size(); }
}
