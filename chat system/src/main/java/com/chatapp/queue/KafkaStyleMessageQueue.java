package com.chatapp.queue;

import com.chatapp.models.Message;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Kafka-Style Message Queue with Multiple Consumer Groups.
 * 
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  APPROACH 2: KAFKA AS SOURCE OF TRUTH (Event Sourcing Pattern)              ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  In this pattern:                                                           ║
 * ║  1. Chat Server writes to Kafka ONLY (single write)                         ║
 * ║  2. Multiple Consumer Groups read from Kafka independently                  ║
 * ║                                                                              ║
 * ║                    ┌──────────────────────────────────────┐                 ║
 * ║                    │           Kafka Topic                │                 ║
 * ║                    │        "user-inbox-{userId}"         │                 ║
 * ║                    └──────────────────┬───────────────────┘                 ║
 * ║                                       │                                      ║
 * ║        ┌──────────────────────────────┼──────────────────────────────┐      ║
 * ║        │                              │                              │      ║
 * ║        ▼                              ▼                              ▼      ║
 * ║  ┌───────────────┐          ┌───────────────┐          ┌───────────────┐   ║
 * ║  │Consumer Group │          │Consumer Group │          │Consumer Group │   ║
 * ║  │"persistence"  │          │  "delivery"   │          │   "search"    │   ║
 * ║  └───────┬───────┘          └───────┬───────┘          └───────┬───────┘   ║
 * ║          │                          │                          │           ║
 * ║          ▼                          ▼                          ▼           ║
 * ║  ┌───────────────┐          ┌───────────────┐          ┌───────────────┐   ║
 * ║  │   Cassandra   │          │ Chat Server   │          │Elasticsearch  │   ║
 * ║  │  (Storage)    │          │  (WebSocket)  │          │   (Index)     │   ║
 * ║  └───────────────┘          └───────────────┘          └───────────────┘   ║
 * ║                                                                              ║
 * ║  KEY CONCEPT: Consumer Groups                                               ║
 * ║  ─────────────────────────────                                              ║
 * ║  • Each consumer group gets ALL messages (independent processing)          ║
 * ║  • Within a group, messages are distributed (parallel processing)          ║
 * ║  • Each group has its own offset (can replay independently)                ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 */
public class KafkaStyleMessageQueue {
    
    // Simulates Kafka topic partitions (one per user for ordering)
    // In real Kafka: partition by recipientId ensures message ordering per user
    private final Map<Long, List<Message>> topics;  // userId -> messages (append-only log)
    
    // Consumer group offsets (which message each group has processed up to)
    // Key: "groupId:userId", Value: offset (index in the message list)
    private final Map<String, Integer> consumerOffsets;
    
    // Registered consumer groups and their handlers
    private final Map<String, Consumer<Message>> consumerGroups;
    
    // Background threads for each consumer group
    private final ExecutorService executor;
    
    public KafkaStyleMessageQueue() {
        this.topics = new ConcurrentHashMap<>();
        this.consumerOffsets = new ConcurrentHashMap<>();
        this.consumerGroups = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // PRODUCER: Chat Server writes here (SINGLE WRITE)
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Produce a message to Kafka.
     * 
     * This is the ONLY write the Chat Server needs to do.
     * All consumers will pick it up independently.
     */
    public void produce(Message message) {
        long recipientId = message.getMessageTo();
        
        // Append to topic (simulates Kafka's append-only log)
        topics.computeIfAbsent(recipientId, k -> new CopyOnWriteArrayList<>());
        topics.get(recipientId).add(message);
        
        int offset = topics.get(recipientId).size() - 1;
        System.out.printf("[Kafka] PRODUCED message %d to topic 'user-%d' at offset %d%n",
            message.getMessageId(), recipientId, offset);
        
        // Notify all consumer groups (in real Kafka, consumers poll)
        notifyConsumerGroups(recipientId, message, offset);
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // CONSUMERS: Multiple independent consumer groups
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Register a consumer group.
     * 
     * Each group processes messages independently:
     * - "persistence-group": Writes to Cassandra
     * - "delivery-group": Delivers to Chat Server 2
     * - "search-group": Indexes in Elasticsearch
     * - "analytics-group": Processes for analytics
     * 
     * @param groupId Unique identifier for the consumer group
     * @param handler Function to process each message
     */
    public void registerConsumerGroup(String groupId, Consumer<Message> handler) {
        consumerGroups.put(groupId, handler);
        System.out.printf("[Kafka] Registered consumer group '%s'%n", groupId);
    }
    
    /**
     * Notify all consumer groups of a new message.
     * In real Kafka, consumers would poll and receive this.
     */
    private void notifyConsumerGroups(long userId, Message message, int offset) {
        for (Map.Entry<String, Consumer<Message>> entry : consumerGroups.entrySet()) {
            String groupId = entry.getKey();
            Consumer<Message> handler = entry.getValue();
            
            // Process async (each group independent)
            executor.submit(() -> {
                try {
                    System.out.printf("[Kafka] Consumer group '%s' processing message %d%n",
                        groupId, message.getMessageId());
                    
                    // Process the message
                    handler.accept(message);
                    
                    // Commit offset (mark as processed)
                    String offsetKey = groupId + ":" + userId;
                    consumerOffsets.put(offsetKey, offset + 1);
                    
                    System.out.printf("[Kafka] Consumer group '%s' committed offset %d%n",
                        groupId, offset + 1);
                    
                } catch (Exception e) {
                    System.err.printf("[Kafka] Consumer group '%s' failed: %s%n",
                        groupId, e.getMessage());
                    // In real Kafka: message would be retried
                }
            });
        }
    }
    
    /**
     * Replay messages from a specific offset (for recovery or new consumers).
     * 
     * This is powerful: new consumer groups can process historical messages!
     */
    public void replayFromOffset(String groupId, long userId, int fromOffset, Consumer<Message> handler) {
        List<Message> messages = topics.get(userId);
        if (messages == null) return;
        
        System.out.printf("[Kafka] Replaying messages for group '%s' from offset %d%n",
            groupId, fromOffset);
        
        for (int i = fromOffset; i < messages.size(); i++) {
            handler.accept(messages.get(i));
        }
        
        // Update offset
        consumerOffsets.put(groupId + ":" + userId, messages.size());
    }
    
    /**
     * Get current offset for a consumer group.
     */
    public int getOffset(String groupId, long userId) {
        return consumerOffsets.getOrDefault(groupId + ":" + userId, 0);
    }
    
    public void shutdown() {
        executor.shutdown();
    }
}

