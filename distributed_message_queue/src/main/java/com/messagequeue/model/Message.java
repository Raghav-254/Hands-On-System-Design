package com.messagequeue.model;

/**
 * Represents a message in the queue.
 * Messages are immutable once created and stored as append-only in segment files.
 */
public class Message {
    private final String key;       // Used for partition routing (hash(key) % numPartitions)
    private final String value;     // The actual message payload
    private final long timestamp;
    private final String topic;
    private long offset;            // Assigned by broker when written to partition

    public Message(String key, String value, String topic) {
        this.key = key;
        this.value = value;
        this.topic = topic;
        this.timestamp = System.currentTimeMillis();
        this.offset = -1; // Not yet assigned
    }

    public void setOffset(long offset) { this.offset = offset; }

    public String getKey() { return key; }
    public String getValue() { return value; }
    public long getTimestamp() { return timestamp; }
    public String getTopic() { return topic; }
    public long getOffset() { return offset; }

    @Override
    public String toString() {
        return String.format("Message[topic=%s, key=%s, offset=%d, value='%s']",
                topic, key, offset, value);
    }
}
