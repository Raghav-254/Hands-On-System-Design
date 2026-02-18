package com.urlshortener.event;

import com.urlshortener.model.ClickEvent;
import java.util.*;
import java.util.function.Consumer;

/**
 * Simulates Kafka — in-memory event bus for redirect events.
 * Redirect Service publishes click events; Analytics Service subscribes.
 * In production: topic "redirect-events", fire-and-forget from Redirect Service.
 */
public class EventBus {

    private final List<ClickEvent> eventLog = new ArrayList<>();
    private final Map<String, Consumer<ClickEvent>> consumers = new LinkedHashMap<>();

    public void subscribe(String name, Consumer<ClickEvent> handler) {
        consumers.put(name, handler);
    }

    /** Fire-and-forget publish: does NOT block the redirect response. */
    public void publish(ClickEvent event) {
        eventLog.add(event);
        for (Map.Entry<String, Consumer<ClickEvent>> entry : consumers.entrySet()) {
            entry.getValue().accept(event);
        }
    }

    public int eventCount() { return eventLog.size(); }
}
