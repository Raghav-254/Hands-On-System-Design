package com.splitwise.event;

import java.util.*;
import java.util.function.Consumer;

/**
 * Simulates Kafka — in-memory event bus.
 * Expense/Settlement services publish events; Notification Service subscribes.
 * In production: transactional outbox → Kafka topic "expense-events".
 */
public class EventBus {

    private final List<SplitwiseEvent> eventLog = new ArrayList<>();
    private final Map<String, Consumer<SplitwiseEvent>> consumers = new LinkedHashMap<>();

    public void subscribe(String name, Consumer<SplitwiseEvent> handler) {
        consumers.put(name, handler);
    }

    public void publish(SplitwiseEvent event) {
        eventLog.add(event);
        System.out.println("  [KAFKA] Published: " + event);
        for (Map.Entry<String, Consumer<SplitwiseEvent>> entry : consumers.entrySet()) {
            entry.getValue().accept(event);
        }
    }

    public int eventCount() { return eventLog.size(); }
}
