package com.chatapp;

import com.chatapp.idgen.SnowflakeIdGenerator;
import com.chatapp.models.Message;
import com.chatapp.queue.KafkaStyleMessageQueue;
import com.chatapp.storage.MessageStore;

/**
 * Demo comparing the two message flow approaches.
 * Run this to understand the difference!
 */
public class MessageFlowDemo {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║         MESSAGE FLOW COMPARISON DEMO                         ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝\n");
        
        demoApproach1_DualWrite();
        
        Thread.sleep(500);
        System.out.println("\n" + "═".repeat(70) + "\n");
        
        demoApproach2_KafkaSourceOfTruth();
    }
    
    /**
     * APPROACH 1: Chat Server writes to BOTH KV Store and Queue
     */
    static void demoApproach1_DualWrite() {
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  APPROACH 1: DUAL WRITE (Chat Server writes to both)");
        System.out.println("═══════════════════════════════════════════════════════════════════\n");
        
        System.out.println("Flow:");
        System.out.println("  User A → Chat Server 1 ─┬─→ KV Store (direct write)");
        System.out.println("                          └─→ Message Queue → Chat Server 2 → User B");
        System.out.println();
        
        // Simulate the dual write
        SnowflakeIdGenerator idGen = new SnowflakeIdGenerator(1, 1);
        MessageStore kvStore = new MessageStore();
        
        long messageId = idGen.nextId();
        Message message = new Message(messageId, 1L, 2L, "Hello from User A!");
        
        System.out.println("Step 1: User A sends message");
        System.out.println("Step 2: Chat Server 1 receives message");
        
        // WRITE 1: Direct to KV Store
        System.out.println("\nStep 3: WRITE #1 - Chat Server writes to KV Store directly");
        kvStore.saveDirectMessage(message);
        System.out.println("        ✓ Message persisted (for history, offline sync, search)");
        
        // WRITE 2: To Message Queue
        System.out.println("\nStep 4: WRITE #2 - Chat Server writes to Message Queue");
        System.out.println("        [MessageQueue] Enqueued message " + messageId + " for user 2");
        System.out.println("        ✓ Message queued for delivery");
        
        // ONE CONSUMER
        System.out.println("\nStep 5: ONE CONSUMER - Chat Server 2 pulls from queue");
        System.out.println("        [ChatServer2] Received message from queue");
        System.out.println("        [ChatServer2] Pushing to User B via WebSocket...");
        System.out.println("        ✓ User B receives message!");
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  Summary:                                                    │");
        System.out.println("│  • Chat Server does 2 WRITES (KV Store + Queue)             │");
        System.out.println("│  • Only 1 CONSUMER (for delivery)                           │");
        System.out.println("│  • Simpler, but need to handle partial failure              │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
    }
    
    /**
     * APPROACH 2: Kafka as Source of Truth (Multiple Consumers)
     */
    static void demoApproach2_KafkaSourceOfTruth() throws InterruptedException {
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("  APPROACH 2: KAFKA AS SOURCE OF TRUTH (Multiple Consumers)");
        System.out.println("═══════════════════════════════════════════════════════════════════\n");
        
        System.out.println("Flow:");
        System.out.println("  User A → Chat Server 1 → Kafka ─┬─→ Consumer 1 → KV Store");
        System.out.println("                                  ├─→ Consumer 2 → Chat Server 2 → User B");
        System.out.println("                                  └─→ Consumer 3 → Elasticsearch");
        System.out.println();
        
        // Create Kafka-style queue with multiple consumers
        KafkaStyleMessageQueue kafka = new KafkaStyleMessageQueue();
        SnowflakeIdGenerator idGen = new SnowflakeIdGenerator(1, 1);
        
        // Register MULTIPLE consumer groups (each processes independently)
        System.out.println("Setting up consumer groups...\n");
        
        // Consumer Group 1: Persistence
        kafka.registerConsumerGroup("persistence-group", msg -> {
            System.out.println("        [Consumer 1 - Persistence] Writing to Cassandra...");
            System.out.println("        ✓ Message saved to KV Store");
        });
        
        // Consumer Group 2: Delivery
        kafka.registerConsumerGroup("delivery-group", msg -> {
            System.out.println("        [Consumer 2 - Delivery] Routing to Chat Server 2...");
            System.out.println("        [Chat Server 2] Pushing to User B via WebSocket...");
            System.out.println("        ✓ User B receives message!");
        });
        
        // Consumer Group 3: Search Indexing
        kafka.registerConsumerGroup("search-group", msg -> {
            System.out.println("        [Consumer 3 - Search] Indexing in Elasticsearch...");
            System.out.println("        ✓ Message searchable");
        });
        
        // Simulate sending a message
        long messageId = idGen.nextId();
        Message message = new Message(messageId, 1L, 2L, "Hello from User A!");
        
        System.out.println("\nStep 1: User A sends message");
        System.out.println("Step 2: Chat Server 1 receives message");
        
        // SINGLE WRITE to Kafka
        System.out.println("\nStep 3: SINGLE WRITE - Chat Server writes to Kafka only");
        kafka.produce(message);
        
        // Wait for consumers to process
        Thread.sleep(300);
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│  Summary:                                                    │");
        System.out.println("│  • Chat Server does 1 WRITE (Kafka only)                    │");
        System.out.println("│  • MULTIPLE CONSUMERS process independently:                │");
        System.out.println("│    - Persistence (Cassandra)                                │");
        System.out.println("│    - Delivery (WebSocket)                                   │");
        System.out.println("│    - Search (Elasticsearch)                                 │");
        System.out.println("│  • More scalable, easier to add new consumers               │");
        System.out.println("│  • Kafka guarantees message ordering & durability           │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");
        
        kafka.shutdown();
    }
}

