package com.messagequeue.consumer;

import com.messagequeue.broker.Broker;
import com.messagequeue.coordination.CoordinationService;
import com.messagequeue.model.Message;
import java.util.*;

/**
 * Message consumer with offset tracking.
 *
 * Consumer pull model flow:
 * 1. Consumer joins a consumer group and subscribes to a topic
 * 2. Coordinator assigns partitions to consumers in the group
 * 3. Consumer fetches messages from assigned partitions
 * 4. After processing, consumer commits offset
 *
 * Offset commit determines delivery semantics:
 * - Commit BEFORE processing → at-most-once (may lose messages)
 * - Commit AFTER processing → at-least-once (may duplicate messages)
 * - Commit with processing atomically → exactly-once (hardest to achieve)
 */
public class Consumer {
    private final String consumerId;
    private final String groupId;
    private final CoordinationService coordinator;
    private final Map<Integer, Broker> brokers;

    // partitionId → current offset (how far we've consumed)
    private final Map<Integer, Long> offsets;
    // Which partitions are assigned to this consumer
    private final List<Integer> assignedPartitions;
    private final String topic;

    public Consumer(String consumerId, String groupId, String topic,
                    CoordinationService coordinator, Map<Integer, Broker> brokers) {
        this.consumerId = consumerId;
        this.groupId = groupId;
        this.topic = topic;
        this.coordinator = coordinator;
        this.brokers = brokers;
        this.offsets = new HashMap<>();
        this.assignedPartitions = new ArrayList<>();
    }

    /** Join the consumer group and get partition assignments */
    public void joinGroup() {
        List<Integer> assigned = coordinator.assignPartitions(groupId, consumerId, topic);
        assignedPartitions.clear();
        assignedPartitions.addAll(assigned);
        // Initialize offsets for new partitions
        for (int partId : assigned) {
            offsets.putIfAbsent(partId, 0L);
        }
        System.out.printf("  [Consumer-%s] Joined group '%s', assigned partitions: %s%n",
                consumerId, groupId, assignedPartitions);
    }

    /** Poll for new messages from assigned partitions */
    public List<Message> poll(int maxMessages) {
        List<Message> allMessages = new ArrayList<>();
        for (int partitionId : assignedPartitions) {
            long currentOffset = offsets.getOrDefault(partitionId, 0L);
            int leaderBrokerId = coordinator.getLeaderBroker(topic, partitionId);
            Broker leader = brokers.get(leaderBrokerId);

            List<Message> messages = leader.consume(topic, partitionId, currentOffset, maxMessages);
            allMessages.addAll(messages);
        }
        return allMessages;
    }

    /** Commit offset after processing (at-least-once semantics) */
    public void commitOffset(int partitionId, long offset) {
        offsets.put(partitionId, offset + 1);
        System.out.printf("  [Consumer-%s] Committed offset %d for %s-P%d%n",
                consumerId, offset, topic, partitionId);
    }

    /** Process messages and commit (simulates at-least-once) */
    public int processAndCommit(int maxMessages) {
        List<Message> messages = poll(maxMessages);
        int processed = 0;
        for (Message msg : messages) {
            // Process the message
            System.out.printf("  [Consumer-%s] Processing: %s%n", consumerId, msg);
            // Commit after processing (at-least-once)
            commitOffset((int) (msg.getOffset() % assignedPartitions.size()), msg.getOffset());
            processed++;
        }
        return processed;
    }

    public String getConsumerId() { return consumerId; }
    public String getGroupId() { return groupId; }
    public List<Integer> getAssignedPartitions() { return assignedPartitions; }
    public Map<Integer, Long> getOffsets() { return offsets; }
}
