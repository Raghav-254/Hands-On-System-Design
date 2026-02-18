package com.bookmyshow.event;

import java.util.*;
import java.util.function.Consumer;

/**
 * Simulates Kafka — in-memory event bus.
 * Booking Service publishes events; consumers (Notification, Analytics) subscribe.
 * In production: transactional outbox → Kafka topic "booking-events".
 */
public class EventBus {

    private final List<BookingEvent> eventLog = new ArrayList<>();
    private final Map<String, Consumer<BookingEvent>> consumers = new LinkedHashMap<>();

    public void subscribe(String name, Consumer<BookingEvent> handler) {
        consumers.put(name, handler);
    }

    public void publish(BookingEvent event) {
        eventLog.add(event);
        System.out.println("  [KAFKA] Published: " + event);
        for (Map.Entry<String, Consumer<BookingEvent>> entry : consumers.entrySet()) {
            System.out.println("  [KAFKA] → " + entry.getKey() + " consuming...");
            entry.getValue().accept(event);
        }
    }

    public int eventCount() { return eventLog.size(); }
}
