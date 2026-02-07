package com.messagequeue.broker;

import com.messagequeue.model.*;
import java.util.*;

/**
 * Represents a message broker node.
 *
 * Each broker:
 * - Stores partitions (leader or replica)
 * - Handles produce requests (append to leader partition)
 * - Handles consume requests (read from partition at offset)
 * - Replicates data to/from other brokers
 *
 * Internally, data is stored in segment files on disk:
 *   data_storage/
 *     Topic-A/
 *       Partition-0/ segment-0, segment-1, ...
 *       Partition-1/ segment-0, segment-1, ...
 *   state_storage/
 *     consumer offsets, partition assignments, etc.
 */
public class Broker {
    private final int brokerId;
    private final Map<String, Map<Integer, Partition>> partitions; // topic → partitionId → partition
    private boolean isAlive;

    public Broker(int brokerId) {
        this.brokerId = brokerId;
        this.partitions = new HashMap<>();
        this.isAlive = true;
    }

    /** Assign a partition to this broker */
    public void assignPartition(Partition partition) {
        partitions
                .computeIfAbsent(partition.getTopic(), k -> new HashMap<>())
                .put(partition.getPartitionId(), partition);
    }

    /** Produce: Append message to partition (only if this broker is the leader) */
    public long produce(Message message, int partitionId) {
        Partition partition = getPartition(message.getTopic(), partitionId);
        if (partition == null) {
            throw new RuntimeException("Partition not found on broker " + brokerId);
        }
        if (partition.getLeadBrokerId() != brokerId) {
            throw new RuntimeException("Not leader for " + message.getTopic() + "-" + partitionId);
        }
        return partition.append(message);
    }

    /** Consume: Read messages from partition starting at offset */
    public List<Message> consume(String topic, int partitionId, long fromOffset, int maxMessages) {
        Partition partition = getPartition(topic, partitionId);
        if (partition == null) return Collections.emptyList();
        return partition.read(fromOffset, maxMessages);
    }

    /** Replicate: Fetch messages from leader (follower pulls from leader) */
    public List<Message> fetchForReplication(String topic, int partitionId, long fromOffset) {
        Partition partition = getPartition(topic, partitionId);
        if (partition == null) return Collections.emptyList();
        return partition.read(fromOffset, 100);
    }

    public Partition getPartition(String topic, int partitionId) {
        Map<Integer, Partition> topicPartitions = partitions.get(topic);
        if (topicPartitions == null) return null;
        return topicPartitions.get(partitionId);
    }

    public int getBrokerId() { return brokerId; }
    public boolean isAlive() { return isAlive; }
    public void shutdown() { this.isAlive = false; }
    public void start() { this.isAlive = true; }

    public Set<String> getTopics() { return partitions.keySet(); }

    @Override
    public String toString() {
        int totalPartitions = partitions.values().stream().mapToInt(Map::size).sum();
        return String.format("Broker[%d] %s, partitions=%d", brokerId,
                isAlive ? "ALIVE" : "DOWN", totalPartitions);
    }
}
