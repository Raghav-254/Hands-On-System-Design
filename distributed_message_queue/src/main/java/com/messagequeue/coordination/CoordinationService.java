package com.messagequeue.coordination;

import com.messagequeue.model.*;
import java.util.*;

/**
 * Coordination Service (simulates ZooKeeper).
 *
 * Responsibilities:
 * 1. Metadata Storage: Topic configs, partition assignments, broker list
 * 2. State Storage: Consumer offsets, consumer group membership
 * 3. Coordination: Leader election, consumer rebalancing, broker health
 *
 * In Kafka's evolution:
 * - Old: ZooKeeper handles metadata + coordination externally
 * - New (KRaft): Kafka manages its own metadata via Raft consensus
 *
 * For consumer group coordination:
 * - One broker is elected as "Coordinator" for each consumer group
 * - Handles JoinGroup, SyncGroup, Heartbeat requests
 * - Triggers rebalance when consumers join/leave
 */
public class CoordinationService {
    private final Map<String, Topic> topics;
    // groupId → consumerId → assigned partitions
    private final Map<String, Map<String, List<Integer>>> consumerGroups;
    private final Map<String, Integer> brokerHealth; // brokerId → last heartbeat

    public CoordinationService() {
        this.topics = new HashMap<>();
        this.consumerGroups = new HashMap<>();
        this.brokerHealth = new HashMap<>();
    }

    /** Register a topic */
    public void registerTopic(Topic topic) {
        topics.put(topic.getName(), topic);
    }

    /** Get topic metadata */
    public Topic getTopic(String topicName) {
        return topics.get(topicName);
    }

    /** Get leader broker for a specific partition */
    public int getLeaderBroker(String topic, int partitionId) {
        Topic t = topics.get(topic);
        if (t == null) throw new RuntimeException("Topic not found: " + topic);
        Partition p = t.getPartition(partitionId);
        if (p == null) throw new RuntimeException("Partition not found: " + partitionId);
        return p.getLeadBrokerId();
    }

    /**
     * Assign partitions to a consumer in a group.
     * Implements simple range-based assignment.
     *
     * Rebalancing happens when:
     * - New consumer joins the group
     * - Existing consumer leaves (heartbeat timeout)
     * - Partitions are added/removed
     */
    public List<Integer> assignPartitions(String groupId, String consumerId, String topicName) {
        Topic topic = topics.get(topicName);
        if (topic == null) throw new RuntimeException("Topic not found: " + topicName);

        Map<String, List<Integer>> group = consumerGroups.computeIfAbsent(groupId, k -> new LinkedHashMap<>());
        group.putIfAbsent(consumerId, new ArrayList<>());

        // Rebalance: redistribute all partitions among all consumers in the group
        List<String> consumers = new ArrayList<>(group.keySet());
        int numPartitions = topic.getNumPartitions();

        // Clear old assignments
        for (String c : consumers) {
            group.get(c).clear();
        }

        // Range-based assignment: partition i → consumer[i % numConsumers]
        for (int i = 0; i < numPartitions; i++) {
            String assignedConsumer = consumers.get(i % consumers.size());
            group.get(assignedConsumer).add(i);
        }

        return group.get(consumerId);
    }

    /** Get all consumers in a group */
    public Map<String, List<Integer>> getConsumerGroup(String groupId) {
        return consumerGroups.getOrDefault(groupId, Collections.emptyMap());
    }

    /** Register broker heartbeat */
    public void registerBroker(int brokerId) {
        brokerHealth.put(String.valueOf(brokerId), brokerId);
    }

    public Map<String, Topic> getAllTopics() { return topics; }
}
