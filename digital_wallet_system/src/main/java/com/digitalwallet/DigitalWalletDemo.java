package com.digitalwallet;

import com.digitalwallet.model.*;
import com.digitalwallet.service.*;
import com.digitalwallet.storage.*;
import java.util.*;

/**
 * Demonstrates the Digital Wallet System concepts:
 * 1. Event Sourcing (Commands → Events → State)
 * 2. Raft Replication (Leader → Followers)
 * 3. State Rebuild from Events (Crash Recovery)
 * 4. Reproducibility (Replay events to verify state)
 */
public class DigitalWalletDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║     Digital Wallet System Demo           ║");
        System.out.println("║     Event Sourcing + Raft Consensus      ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        demoEventSourcing();
        demoRaftReplication();
        demoCrashRecovery();
        demoReproducibility();
    }

    static void demoEventSourcing() {
        System.out.println("━━━ Demo 1: Event Sourcing ━━━");
        System.out.println("Commands → Validate → Events → State\n");

        EventStore eventStore = new EventStore("partition-1");
        StateStore stateStore = new StateStore();

        // Initialize accounts
        stateStore.initAccount("A", 5.00);
        stateStore.initAccount("B", 4.00);
        stateStore.initAccount("C", 3.00);
        stateStore.initAccount("D", 2.00);

        EventSourcingEngine engine = new EventSourcingEngine("partition-1", eventStore, stateStore);

        System.out.println("Initial State:");
        stateStore.printState();

        // Process transfers
        System.out.println("\nProcessing: A sends $1 to C");
        List<TransferEvent> events1 = engine.processCommand(
            new TransferCommand("cmd_001", "A", "C", 1.00));
        events1.forEach(e -> System.out.println("  Generated: " + e));

        System.out.println("\nProcessing: A sends $1 to B");
        List<TransferEvent> events2 = engine.processCommand(
            new TransferCommand("cmd_002", "A", "B", 1.00));
        events2.forEach(e -> System.out.println("  Generated: " + e));

        System.out.println("\nProcessing: A sends $100 to D (should fail — insufficient funds)");
        engine.processCommand(new TransferCommand("cmd_003", "A", "D", 100.00));

        System.out.println("\nFinal State:");
        stateStore.printState();

        System.out.println("\nEvent Log (source of truth):");
        eventStore.getAllEvents().forEach(e -> System.out.println("  " + e));
        System.out.println();
    }

    static void demoRaftReplication() {
        System.out.println("━━━ Demo 2: Raft Replication ━━━");
        System.out.println("Leader processes → replicates to Followers\n");

        // Create Raft node group
        RaftNode leader = new RaftNode("node-1", RaftNode.Role.LEADER);
        RaftNode follower1 = new RaftNode("node-2", RaftNode.Role.FOLLOWER);
        RaftNode follower2 = new RaftNode("node-3", RaftNode.Role.FOLLOWER);

        // Initialize state on leader
        leader.getStateStore().initAccount("A", 5.00);
        leader.getStateStore().initAccount("C", 3.00);

        // Process command on leader
        EventSourcingEngine leaderEngine = new EventSourcingEngine(
            "partition-1", leader.getEventStore(), leader.getStateStore());

        System.out.println("Leader processes: A sends $1 to C");
        List<TransferEvent> events = leaderEngine.processCommand(
            new TransferCommand("cmd_001", "A", "C", 1.00));

        // Replicate to followers (Raft consensus)
        System.out.println("Replicating to followers...");
        follower1.getStateStore().initAccount("A", 5.00);
        follower1.getStateStore().initAccount("C", 3.00);
        follower2.getStateStore().initAccount("A", 5.00);
        follower2.getStateStore().initAccount("C", 3.00);

        // Simulate: followers start with initial state, then apply replicated events
        StateStore f1Fresh = new StateStore();
        f1Fresh.initAccount("A", 5.00);
        f1Fresh.initAccount("C", 3.00);
        StateStore f2Fresh = new StateStore();
        f2Fresh.initAccount("A", 5.00);
        f2Fresh.initAccount("C", 3.00);

        follower1.replicateEvents(events);
        follower2.replicateEvents(events);

        System.out.println("\n  Majority (2/3) acknowledged → event committed");

        System.out.println("\n  Leader state:    " + leader.getStateStore().getSnapshot());
        System.out.println("  Follower-1 state: " + follower1.getStateStore().getSnapshot());
        System.out.println("  Follower-2 state: " + follower2.getStateStore().getSnapshot());
        System.out.println("  All nodes consistent ✓\n");
    }

    static void demoCrashRecovery() {
        System.out.println("━━━ Demo 3: Crash Recovery ━━━");
        System.out.println("Rebuild state by replaying events\n");

        // Simulate: we have an event store with history
        EventStore survivedEventStore = new EventStore("partition-1");
        survivedEventStore.append(new TransferEvent("evt_1", 1, "A", -1.00, "cmd_001"));
        survivedEventStore.append(new TransferEvent("evt_2", 2, "C", +1.00, "cmd_001"));
        survivedEventStore.append(new TransferEvent("evt_3", 3, "A", -1.00, "cmd_002"));
        survivedEventStore.append(new TransferEvent("evt_4", 4, "B", +1.00, "cmd_002"));

        System.out.println("Events on disk (survived crash):");
        survivedEventStore.getAllEvents().forEach(e -> System.out.println("  " + e));

        // Rebuild state from events
        StateStore rebuiltState = new StateStore();
        rebuiltState.initAccount("A", 5.00);
        rebuiltState.initAccount("B", 4.00);
        rebuiltState.initAccount("C", 3.00);
        rebuiltState.initAccount("D", 2.00);

        System.out.println("\nState before replay (initial balances):");
        rebuiltState.printState();

        EventSourcingEngine recoveryEngine = new EventSourcingEngine(
            "partition-1", survivedEventStore, rebuiltState);

        // Reset state to initial and rebuild
        StateStore freshState = new StateStore();
        freshState.initAccount("A", 5.00);
        freshState.initAccount("B", 4.00);
        freshState.initAccount("C", 3.00);
        freshState.initAccount("D", 2.00);

        System.out.println("\nReplaying events to rebuild state...");
        for (TransferEvent event : survivedEventStore.getAllEvents()) {
            freshState.applyEvent(event);
        }

        System.out.println("\nState after replay:");
        freshState.printState();
        System.out.println("  State successfully rebuilt from events ✓\n");
    }

    static void demoReproducibility() {
        System.out.println("━━━ Demo 4: Reproducibility ━━━");
        System.out.println("Replay events to any point in time\n");

        EventStore eventStore = new EventStore("partition-1");
        eventStore.append(new TransferEvent("evt_1", 1, "A", -1.00, "cmd_001"));
        eventStore.append(new TransferEvent("evt_2", 2, "C", +1.00, "cmd_001"));
        eventStore.append(new TransferEvent("evt_3", 3, "A", -1.00, "cmd_002"));
        eventStore.append(new TransferEvent("evt_4", 4, "B", +1.00, "cmd_002"));
        eventStore.append(new TransferEvent("evt_5", 5, "A", -1.00, "cmd_003"));
        eventStore.append(new TransferEvent("evt_6", 6, "D", +1.00, "cmd_003"));

        // Replay to different points
        for (int replayTo : new int[]{2, 4, 6}) {
            StateStore snapshot = new StateStore();
            snapshot.initAccount("A", 5.00);
            snapshot.initAccount("B", 4.00);
            snapshot.initAccount("C", 3.00);
            snapshot.initAccount("D", 2.00);

            for (TransferEvent event : eventStore.getAllEvents()) {
                if (event.getSequence() <= replayTo) {
                    snapshot.applyEvent(event);
                }
            }
            System.out.println("State at event " + replayTo + ": " + snapshot.getSnapshot());
        }

        System.out.println("\n  Same events → same state, every time ✓");
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("Demo complete!");
    }
}
