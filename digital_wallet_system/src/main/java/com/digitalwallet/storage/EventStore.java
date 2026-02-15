package com.digitalwallet.storage;

import com.digitalwallet.model.TransferEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Simulates the append-only Event Store (Event File on disk).
 * In production: local append-only file with mmap, replicated via Raft.
 */
public class EventStore {
    private final List<TransferEvent> events = new ArrayList<>();
    private final String nodeId;

    public EventStore(String nodeId) {
        this.nodeId = nodeId;
    }

    public void append(TransferEvent event) {
        events.add(event);
    }

    public List<TransferEvent> getEventsAfter(int sequenceNumber) {
        List<TransferEvent> result = new ArrayList<>();
        for (TransferEvent event : events) {
            if (event.getSequence() > sequenceNumber) {
                result.add(event);
            }
        }
        return result;
    }

    public List<TransferEvent> getAllEvents() {
        return Collections.unmodifiableList(events);
    }

    public int getLastSequence() {
        return events.isEmpty() ? 0 : events.get(events.size() - 1).getSequence();
    }

    public int size() {
        return events.size();
    }

    @Override
    public String toString() {
        return String.format("EventStore[%s]: %d events, last_seq=%d", nodeId, events.size(), getLastSequence());
    }
}
