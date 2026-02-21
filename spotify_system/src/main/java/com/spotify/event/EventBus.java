package com.spotify.event;

import com.spotify.model.PlayEvent;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Simulates Kafka for play events.
 * Topic: "play-events", partitioned by user_id.
 * Consumers: PlayHistoryStore (Cassandra writer), Recommendation pipeline, Royalty service.
 */
public class EventBus {
    private final Queue<PlayEvent> playEvents = new ConcurrentLinkedQueue<>();
    private final List<Consumer<PlayEvent>> consumers = Collections.synchronizedList(new ArrayList<>());
    private long totalPublished = 0;

    public void publish(PlayEvent event) {
        playEvents.add(event);
        totalPublished++;
        for (Consumer<PlayEvent> consumer : consumers) {
            consumer.accept(event);
        }
    }

    public void subscribe(Consumer<PlayEvent> consumer) {
        consumers.add(consumer);
    }

    public long getTotalPublished() { return totalPublished; }

    public List<PlayEvent> drainAll() {
        List<PlayEvent> drained = new ArrayList<>();
        PlayEvent event;
        while ((event = playEvents.poll()) != null) {
            drained.add(event);
        }
        return drained;
    }
}
