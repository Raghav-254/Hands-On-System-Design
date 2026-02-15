package com.digitalwallet.service;

import com.digitalwallet.model.TransferCommand;
import com.digitalwallet.model.TransferEvent;
import com.digitalwallet.storage.EventStore;
import com.digitalwallet.storage.StateStore;
import java.util.ArrayList;
import java.util.List;

/**
 * The core Event Sourcing state machine.
 * Processes commands → validates → generates events → applies to state.
 */
public class EventSourcingEngine {
    private final EventStore eventStore;
    private final StateStore stateStore;
    private final String partitionId;
    private int sequenceCounter = 0;

    public EventSourcingEngine(String partitionId, EventStore eventStore, StateStore stateStore) {
        this.partitionId = partitionId;
        this.eventStore = eventStore;
        this.stateStore = stateStore;
    }

    /**
     * Process a transfer command through the Event Sourcing pipeline:
     * Command → Validate → Generate Events → Append to Event Store → Apply to State
     */
    public List<TransferEvent> processCommand(TransferCommand command) {
        // Step 1: Validate against current state
        double fromBalance = stateStore.getBalance(command.getFromAccount());
        if (fromBalance < command.getAmount()) {
            System.out.println("  [REJECTED] " + command + " — insufficient funds (balance: $" +
                String.format("%.2f", fromBalance) + ")");
            return List.of();
        }

        // Step 2: Generate events
        List<TransferEvent> events = new ArrayList<>();
        events.add(new TransferEvent(
            "evt_" + (++sequenceCounter), sequenceCounter,
            command.getFromAccount(), -command.getAmount(), command.getCommandId()
        ));
        events.add(new TransferEvent(
            "evt_" + (++sequenceCounter), sequenceCounter,
            command.getToAccount(), command.getAmount(), command.getCommandId()
        ));

        // Step 3: Append events to Event Store (source of truth)
        for (TransferEvent event : events) {
            eventStore.append(event);
        }

        // Step 4: Apply events to State (derived cache)
        for (TransferEvent event : events) {
            stateStore.applyEvent(event);
        }

        return events;
    }

    /**
     * Rebuild state from events (e.g., after crash recovery).
     */
    public void rebuildStateFromEvents() {
        System.out.println("  [REBUILD] Replaying " + eventStore.size() + " events...");
        for (TransferEvent event : eventStore.getAllEvents()) {
            stateStore.applyEvent(event);
        }
        System.out.println("  [REBUILD] State rebuilt successfully.");
    }

    public EventStore getEventStore() { return eventStore; }
    public StateStore getStateStore() { return stateStore; }
    public String getPartitionId() { return partitionId; }
}
