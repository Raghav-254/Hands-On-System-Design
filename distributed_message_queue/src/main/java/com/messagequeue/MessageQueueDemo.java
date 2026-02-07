package com.messagequeue;

import com.messagequeue.broker.Broker;
import com.messagequeue.consumer.Consumer;
import com.messagequeue.coordination.CoordinationService;
import com.messagequeue.model.*;
import com.messagequeue.producer.Producer;
import java.util.*;

/**
 * Distributed Message Queue - Demo
 * Based on Alex Xu's System Design Interview Volume 2, Chapter 4
 *
 * Demonstrates:
 * 1. Topic creation with partitions and replication
 * 2. Producer routing (key-based partitioning)
 * 3. Consumer groups with partition assignment
 * 4. Replication (leader-follower)
 * 5. ACK levels (ack=0, ack=1, ack=all)
 * 6. Consumer rebalancing
 * 7. Delivery semantics
 */
public class MessageQueueDemo {

    private static CoordinationService coordinator;
    private static Map<Integer, Broker> brokers;

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║       DISTRIBUTED MESSAGE QUEUE DESIGN DEMO              ║");
        System.out.println("║    Based on Alex Xu Volume 2, Chapter 4                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");

        initializeCluster();
        demoTopicCreation();
        demoProducerRouting();
        demoConsumerGroups();
        demoAckLevels();
        demoConsumerRebalancing();
        demoDeliverySemantics();
        demoReplication();

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("Demo complete! See INTERVIEW_CHEATSHEET.md for full design.");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void initializeCluster() {
        System.out.println("\n--- Initializing Cluster ---");

        coordinator = new CoordinationService();

        // Create 3 brokers
        brokers = new HashMap<>();
        for (int i = 1; i <= 3; i++) {
            brokers.put(i, new Broker(i));
            coordinator.registerBroker(i);
            System.out.printf("  ✓ Broker-%d started%n", i);
        }
        System.out.println("  ✓ CoordinationService (ZooKeeper) ready");
    }

    private static void demoTopicCreation() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 1: TOPIC CREATION WITH PARTITIONS             ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // Create topic "orders" with 3 partitions, replication factor 3
        Topic ordersTopic = new Topic("orders", 3, 3, 14 * 24 * 3600 * 1000L);

        // Assign partitions to brokers (spread leaders across brokers)
        for (int i = 0; i < 3; i++) {
            int leadBroker = (i % 3) + 1; // Partition-0→Broker-1, P-1→Broker-2, P-2→Broker-3
            Partition partition = new Partition("orders", i, leadBroker);
            partition.setReplicaBrokerIds(List.of(1, 2, 3));
            ordersTopic.addPartition(i, partition);

            // Assign to all brokers (leader + replicas)
            for (Broker broker : brokers.values()) {
                broker.assignPartition(partition);
            }
        }

        coordinator.registerTopic(ordersTopic);
        System.out.println("  Created: " + ordersTopic);
        System.out.println("\n  Partition layout:");
        for (int i = 0; i < 3; i++) {
            Partition p = ordersTopic.getPartition(i);
            System.out.printf("    %s%n", p);
        }
    }

    private static void demoProducerRouting() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 2: PRODUCER ROUTING (Key-based Partitioning)  ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nRouting: hash(key) % numPartitions → determines target partition");
        System.out.println("Same key always goes to same partition → guarantees ordering per key\n");

        Producer producer = new Producer("prod-1", coordinator, brokers, 1, "1");

        // Send messages with different keys → different partitions
        producer.send("orders", "user-100", "Order #1001 placed");
        producer.send("orders", "user-200", "Order #1002 placed");
        producer.send("orders", "user-100", "Order #1001 paid");      // Same key → same partition!
        producer.send("orders", "user-300", "Order #1003 placed");
        producer.send("orders", "user-200", "Order #1002 cancelled"); // Same key → same partition!
        producer.flush();

        System.out.println("\n  KEY INSIGHT: user-100's orders always go to same partition");
        System.out.println("  → Guarantees ordering for that user's messages");
    }

    private static void demoConsumerGroups() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 3: CONSUMER GROUPS                            ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nConsumer Group: Multiple consumers share partitions of a topic");
        System.out.println("Rule: Each partition → exactly ONE consumer in the group\n");

        Consumer consumer1 = new Consumer("c1", "order-processors", "orders", coordinator, brokers);
        Consumer consumer2 = new Consumer("c2", "order-processors", "orders", coordinator, brokers);

        // Join group → coordinator assigns partitions
        consumer1.joinGroup();
        consumer2.joinGroup();
        // c1 needs to rejoin to get updated assignments after c2 joined
        consumer1.joinGroup();

        System.out.println("\n  Partition assignment:");
        System.out.printf("    Consumer c1: partitions %s%n", consumer1.getAssignedPartitions());
        System.out.printf("    Consumer c2: partitions %s%n", consumer2.getAssignedPartitions());
    }

    private static void demoAckLevels() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 4: ACK LEVELS (Durability vs Latency)         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\n  ACK=0 (fire-and-forget):");
        System.out.println("    Producer → Broker (no wait). Fastest, may lose data.");
        Producer p0 = new Producer("fast-prod", coordinator, brokers, 1, "0");
        p0.send("orders", "ack0-key", "Fire and forget message");
        p0.flush();

        System.out.println("\n  ACK=1 (leader only):");
        System.out.println("    Producer → Leader persists → ACK. Balanced.");
        Producer p1 = new Producer("balanced-prod", coordinator, brokers, 1, "1");
        p1.send("orders", "ack1-key", "Leader-acked message");
        p1.flush();

        System.out.println("\n  ACK=all (all ISR):");
        System.out.println("    Producer → Leader → All ISR replicas → ACK. Slowest, strongest.");
        Producer pAll = new Producer("safe-prod", coordinator, brokers, 1, "all");
        pAll.send("orders", "ackall-key", "Fully replicated message");
        pAll.flush();

        System.out.println("\n  Tradeoff summary:");
        System.out.println("  ┌──────────┬───────────┬──────────────┬─────────────────────┐");
        System.out.println("  │ ACK      │ Latency   │ Durability   │ Use Case            │");
        System.out.println("  ├──────────┼───────────┼──────────────┼─────────────────────┤");
        System.out.println("  │ ack=0    │ Lowest    │ May lose     │ Metrics, logging    │");
        System.out.println("  │ ack=1    │ Medium    │ Leader crash │ Most applications   │");
        System.out.println("  │ ack=all  │ Highest   │ Strongest    │ Financial, critical │");
        System.out.println("  └──────────┴───────────┴──────────────┴─────────────────────┘");
    }

    private static void demoConsumerRebalancing() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 5: CONSUMER REBALANCING                       ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\n  Rebalancing triggers:");
        System.out.println("  1. New consumer joins the group");
        System.out.println("  2. Consumer crashes/leaves (heartbeat timeout)");
        System.out.println("  3. Topic partitions change\n");

        System.out.println("  BEFORE: 2 consumers, 3 partitions");
        Map<String, List<Integer>> group = coordinator.getConsumerGroup("order-processors");
        for (var entry : group.entrySet()) {
            System.out.printf("    %s → partitions %s%n", entry.getKey(), entry.getValue());
        }

        // New consumer joins → triggers rebalance
        System.out.println("\n  >> Consumer c3 joins group...");
        Consumer consumer3 = new Consumer("c3", "order-processors", "orders", coordinator, brokers);
        consumer3.joinGroup();
        // All consumers rejoin to get updated assignments
        new Consumer("c1", "order-processors", "orders", coordinator, brokers).joinGroup();
        new Consumer("c2", "order-processors", "orders", coordinator, brokers).joinGroup();

        System.out.println("\n  AFTER: 3 consumers, 3 partitions (1 partition each)");
        group = coordinator.getConsumerGroup("order-processors");
        for (var entry : group.entrySet()) {
            System.out.printf("    %s → partitions %s%n", entry.getKey(), entry.getValue());
        }
    }

    private static void demoDeliverySemantics() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 6: DELIVERY SEMANTICS                         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\n  At-most-once:");
        System.out.println("    Consumer commits offset BEFORE processing");
        System.out.println("    If crash during processing → message lost (not redelivered)");
        System.out.println("    Use case: Monitoring metrics (occasional loss OK)");

        System.out.println("\n  At-least-once:");
        System.out.println("    Consumer commits offset AFTER processing");
        System.out.println("    If crash after processing but before commit → message redelivered");
        System.out.println("    Use case: Most applications (handle duplicates with idempotency)");

        System.out.println("\n  Exactly-once:");
        System.out.println("    Requires atomic commit of processing + offset update");
        System.out.println("    Approaches: Idempotent producer + transactional consumer");
        System.out.println("    Use case: Financial transactions, billing");
    }

    private static void demoReplication() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 7: REPLICATION & ISR                          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\n  Replication factor = 3 (1 leader + 2 followers)");
        System.out.println("\n  ISR (In-Sync Replicas):");
        System.out.println("  • Replicas that are fully caught up with the leader");
        System.out.println("  • Only ISR replicas can become the new leader on failover");
        System.out.println("  • If a follower falls behind → removed from ISR");
        System.out.println("  • When it catches up → added back to ISR");

        System.out.println("\n  Example: orders-P0 (replication factor=3)");
        System.out.println("  ┌─────────────────────────────────────────────────┐");
        System.out.println("  │ Broker-1 (Leader)                              │");
        System.out.println("  │   [0][1][2][3][4][5][6][7]                     │");
        System.out.println("  │   committed offset = 5                         │");
        System.out.println("  │                                                 │");
        System.out.println("  │ Broker-2 (Follower, IN ISR)                    │");
        System.out.println("  │   [0][1][2][3][4][5][6]  ← caught up           │");
        System.out.println("  │                                                 │");
        System.out.println("  │ Broker-3 (Follower, NOT IN ISR)                │");
        System.out.println("  │   [0][1][2][3]           ← lagging behind      │");
        System.out.println("  │                                                 │");
        System.out.println("  │ ISR = {Broker-1, Broker-2}                     │");
        System.out.println("  │ If Broker-1 crashes → Broker-2 becomes leader  │");
        System.out.println("  └─────────────────────────────────────────────────┘");
    }
}
