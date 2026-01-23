package com.chatapp.idgen;

/**
 * Snowflake ID Generator - Generates unique, sortable 64-bit IDs.
 * 
 * Structure (64 bits total):
 * - 1 bit: Sign bit (always 0)
 * - 41 bits: Timestamp (milliseconds since custom epoch) - ~69 years
 * - 5 bits: Datacenter ID (0-31)
 * - 5 bits: Machine ID (0-31)
 * - 12 bits: Sequence number (0-4095 per millisecond)
 * 
 * This ensures:
 * 1. IDs are unique across distributed systems
 * 2. IDs are sortable by time (important for message ordering)
 * 3. High throughput: 4096 IDs per millisecond per machine
 * 
 * In the chat system, the ID Generator component (shown in Figure 12-12)
 * is responsible for generating message_id values.
 */
public class SnowflakeIdGenerator {
    
    // Custom epoch (Jan 1, 2020 00:00:00 UTC)
    private static final long EPOCH = 1577836800000L;
    
    // Bit lengths
    private static final int DATACENTER_ID_BITS = 5;
    private static final int MACHINE_ID_BITS = 5;
    private static final int SEQUENCE_BITS = 12;
    
    // Maximum values
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS); // 31
    private static final long MAX_MACHINE_ID = ~(-1L << MACHINE_ID_BITS);       // 31
    private static final long MAX_SEQUENCE = ~(-1L << SEQUENCE_BITS);           // 4095
    
    // Bit shifts
    private static final int MACHINE_ID_SHIFT = SEQUENCE_BITS;
    private static final int DATACENTER_ID_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;
    private static final int TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS + DATACENTER_ID_BITS;
    
    private final long datacenterId;
    private final long machineId;
    
    private long lastTimestamp = -1L;
    private long sequence = 0L;
    
    public SnowflakeIdGenerator(long datacenterId, long machineId) {
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException(
                String.format("Datacenter ID must be between 0 and %d", MAX_DATACENTER_ID));
        }
        if (machineId > MAX_MACHINE_ID || machineId < 0) {
            throw new IllegalArgumentException(
                String.format("Machine ID must be between 0 and %d", MAX_MACHINE_ID));
        }
        
        this.datacenterId = datacenterId;
        this.machineId = machineId;
    }
    
    /**
     * Generate next unique ID.
     * Thread-safe via synchronization.
     */
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();
        
        // Handle clock going backwards (shouldn't happen, but safety check)
        if (currentTimestamp < lastTimestamp) {
            throw new RuntimeException(
                String.format("Clock moved backwards. Refusing to generate ID for %d milliseconds",
                    lastTimestamp - currentTimestamp));
        }
        
        if (currentTimestamp == lastTimestamp) {
            // Same millisecond: increment sequence
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // Sequence overflow: wait for next millisecond
                currentTimestamp = waitNextMillis(lastTimestamp);
            }
        } else {
            // New millisecond: reset sequence
            sequence = 0L;
        }
        
        lastTimestamp = currentTimestamp;
        
        // Construct the ID
        return ((currentTimestamp - EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (machineId << MACHINE_ID_SHIFT)
                | sequence;
    }
    
    private long waitNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
    
    /**
     * Parse a Snowflake ID back into its components.
     * Useful for debugging and understanding message timing.
     */
    public static ParsedId parse(long id) {
        long timestamp = (id >> TIMESTAMP_SHIFT) + EPOCH;
        long datacenterId = (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
        long machineId = (id >> MACHINE_ID_SHIFT) & MAX_MACHINE_ID;
        long sequence = id & MAX_SEQUENCE;
        
        return new ParsedId(timestamp, datacenterId, machineId, sequence);
    }
    
    public static class ParsedId {
        public final long timestamp;
        public final long datacenterId;
        public final long machineId;
        public final long sequence;
        
        public ParsedId(long timestamp, long datacenterId, long machineId, long sequence) {
            this.timestamp = timestamp;
            this.datacenterId = datacenterId;
            this.machineId = machineId;
            this.sequence = sequence;
        }
        
        @Override
        public String toString() {
            return String.format("ParsedId{timestamp=%d, dc=%d, machine=%d, seq=%d}",
                timestamp, datacenterId, machineId, sequence);
        }
    }
}

