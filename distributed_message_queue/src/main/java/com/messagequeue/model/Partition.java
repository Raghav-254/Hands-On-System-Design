package com.messagequeue.model;

import java.util.*;

/**
 * Represents a topic partition.
 *
 * A partition is an ordered, immutable, append-only log of messages.
 * Messages within a partition are assigned sequential offsets (0, 1, 2, ...).
 *
 * On disk, a partition is stored as multiple segment files:
 *   Partition-0/
 *     segment-0  (offsets 0-999)
 *     segment-1  (offsets 1000-1999)
 *     segment-2  (offsets 2000-...)
 *
 * Old segments are deleted when retention period expires.
 */
public class Partition {
    private final String topic;
    private final int partitionId;
    private final List<Message> log;       // The append-only message log
    private long nextOffset;
    private int leadBrokerId;              // Which broker is the leader for this partition
    private List<Integer> replicaBrokerIds; // ISR (In-Sync Replicas)

    public Partition(String topic, int partitionId, int leadBrokerId) {
        this.topic = topic;
        this.partitionId = partitionId;
        this.log = new ArrayList<>();
        this.nextOffset = 0;
        this.leadBrokerId = leadBrokerId;
        this.replicaBrokerIds = new ArrayList<>();
    }

    /** Append a message and assign it the next offset */
    public synchronized long append(Message message) {
        message.setOffset(nextOffset);
        log.add(message);
        return nextOffset++;
    }

    /** Read messages starting from a given offset */
    public List<Message> read(long fromOffset, int maxMessages) {
        int startIdx = (int) fromOffset;
        if (startIdx >= log.size()) return Collections.emptyList();
        int endIdx = Math.min(startIdx + maxMessages, log.size());
        return new ArrayList<>(log.subList(startIdx, endIdx));
    }

    public void setLeadBrokerId(int id) { this.leadBrokerId = id; }
    public void setReplicaBrokerIds(List<Integer> ids) { this.replicaBrokerIds = ids; }

    public String getTopic() { return topic; }
    public int getPartitionId() { return partitionId; }
    public long getNextOffset() { return nextOffset; }
    public int getLeadBrokerId() { return leadBrokerId; }
    public List<Integer> getReplicaBrokerIds() { return replicaBrokerIds; }
    public int size() { return log.size(); }

    @Override
    public String toString() {
        return String.format("Partition[%s-%d] leader=broker-%d, msgs=%d, ISR=%s",
                topic, partitionId, leadBrokerId, log.size(), replicaBrokerIds);
    }
}
