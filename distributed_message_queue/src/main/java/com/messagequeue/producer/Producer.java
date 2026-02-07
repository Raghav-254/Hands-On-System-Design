package com.messagequeue.producer;

import com.messagequeue.broker.Broker;
import com.messagequeue.coordination.CoordinationService;
import com.messagequeue.model.*;
import java.util.*;

/**
 * Message producer with buffering and routing.
 *
 * Flow:
 * 1. Producer creates a message with key + value
 * 2. Message goes into a buffer (batching for throughput)
 * 3. Routing layer determines: hash(key) % numPartitions → target partition
 * 4. Looks up leader broker for that partition (from coordination service)
 * 5. Sends to leader broker
 * 6. Waits for ACK based on configured ack level:
 *    - ack=0: No wait (fire-and-forget, fastest, may lose data)
 *    - ack=1: Wait for leader ACK (leader persists, may lose if leader crashes before replication)
 *    - ack=all: Wait for ALL ISR ACKs (slowest, strongest durability)
 */
public class Producer {
    private final String producerId;
    private final CoordinationService coordinator;
    private final Map<Integer, Broker> brokers;
    private final List<Message> buffer;
    private final int batchSize;
    private String ackLevel; // "0", "1", "all"

    public Producer(String producerId, CoordinationService coordinator,
                    Map<Integer, Broker> brokers, int batchSize, String ackLevel) {
        this.producerId = producerId;
        this.coordinator = coordinator;
        this.brokers = brokers;
        this.buffer = new ArrayList<>();
        this.batchSize = batchSize;
        this.ackLevel = ackLevel;
    }

    /** Send a message to a topic */
    public long send(String topic, String key, String value) {
        Message message = new Message(key, value, topic);
        buffer.add(message);

        // Flush when buffer reaches batch size
        if (buffer.size() >= batchSize) {
            return flush();
        }
        return -1; // Buffered, not yet sent
    }

    /** Flush all buffered messages to brokers */
    public long flush() {
        long lastOffset = -1;
        for (Message message : buffer) {
            lastOffset = sendToBroker(message);
        }
        buffer.clear();
        return lastOffset;
    }

    private long sendToBroker(Message message) {
        // Step 1: Determine target partition
        Topic topic = coordinator.getTopic(message.getTopic());
        int partitionId = topic.routeToPartition(message.getKey());

        // Step 2: Find leader broker for this partition
        int leaderBrokerId = coordinator.getLeaderBroker(message.getTopic(), partitionId);
        Broker leader = brokers.get(leaderBrokerId);

        // Step 3: Send to leader
        long offset = leader.produce(message, partitionId);

        // Step 4: ACK handling
        switch (ackLevel) {
            case "0":
                // Fire and forget — don't wait for any ACK
                break;
            case "1":
                // Leader has persisted — good enough
                break;
            case "all":
                // In production: wait for all ISR replicas to confirm
                // Simulated here: just log it
                break;
        }

        System.out.printf("  [Producer-%s] Sent to %s-P%d (broker-%d) offset=%d, ack=%s, key=%s%n",
                producerId, message.getTopic(), partitionId, leaderBrokerId, offset, ackLevel, message.getKey());
        return offset;
    }

    public String getProducerId() { return producerId; }
    public void setAckLevel(String ackLevel) { this.ackLevel = ackLevel; }
}
