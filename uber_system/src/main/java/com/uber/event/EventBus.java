package com.uber.event;

import java.util.*;
import java.util.function.Consumer;

/**
 * Simulates Kafka — an in-memory event bus.
 * Trip Service publishes events; consumers (Notification, Payment, Analytics) subscribe.
 *
 * In production: Kafka topics partitioned by ride_id, consumed by consumer groups.
 * Transactional outbox ensures events are never lost (DB + outbox in same transaction).
 */
public class EventBus {

    private final List<TripEvent> eventLog = new ArrayList<>();
    private final Map<String, Consumer<TripEvent>> consumers = new LinkedHashMap<>();

    /** Register a consumer (simulates a Kafka consumer group). */
    public void subscribe(String consumerName, Consumer<TripEvent> handler) {
        consumers.put(consumerName, handler);
    }

    /**
     * Publish event to the bus (simulates outbox → Kafka publish).
     * All subscribed consumers receive the event.
     */
    public void publish(TripEvent event) {
        eventLog.add(event);
        System.out.println("  [KAFKA] Published: " + event);
        for (Map.Entry<String, Consumer<TripEvent>> entry : consumers.entrySet()) {
            System.out.println("  [KAFKA] → " + entry.getKey() + " consuming...");
            entry.getValue().accept(event);
        }
    }

    public List<TripEvent> getEventLog() {
        return Collections.unmodifiableList(eventLog);
    }

    public int eventCount() { return eventLog.size(); }
}
