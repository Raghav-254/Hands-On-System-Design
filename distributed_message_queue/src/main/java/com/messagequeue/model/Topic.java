package com.messagequeue.model;

import java.util.*;

/**
 * Represents a topic — a logical grouping of related messages.
 *
 * A topic is divided into partitions for parallel processing.
 * Each partition can be on a different broker for distribution.
 *
 * Example:
 *   Topic "orders" with 3 partitions:
 *     Partition-0 → Broker-1 (leader), Broker-2, Broker-3 (replicas)
 *     Partition-1 → Broker-2 (leader), Broker-1, Broker-3 (replicas)
 *     Partition-2 → Broker-3 (leader), Broker-1, Broker-2 (replicas)
 */
public class Topic {
    private final String name;
    private final int numPartitions;
    private final int replicationFactor;
    private final Map<Integer, Partition> partitions;
    private final long retentionMs; // Data retention period

    public Topic(String name, int numPartitions, int replicationFactor, long retentionMs) {
        this.name = name;
        this.numPartitions = numPartitions;
        this.replicationFactor = replicationFactor;
        this.retentionMs = retentionMs;
        this.partitions = new HashMap<>();
    }

    public void addPartition(int partitionId, Partition partition) {
        partitions.put(partitionId, partition);
    }

    public Partition getPartition(int partitionId) {
        return partitions.get(partitionId);
    }

    /** Determine which partition a message goes to based on key */
    public int routeToPartition(String key) {
        if (key == null) {
            // Round-robin for null keys
            return (int) (System.nanoTime() % numPartitions);
        }
        return Math.abs(key.hashCode()) % numPartitions;
    }

    public String getName() { return name; }
    public int getNumPartitions() { return numPartitions; }
    public int getReplicationFactor() { return replicationFactor; }
    public Map<Integer, Partition> getPartitions() { return partitions; }
    public long getRetentionMs() { return retentionMs; }

    @Override
    public String toString() {
        return String.format("Topic[%s] partitions=%d, replication=%d, retention=%dh",
                name, numPartitions, replicationFactor, retentionMs / 3600000);
    }
}
