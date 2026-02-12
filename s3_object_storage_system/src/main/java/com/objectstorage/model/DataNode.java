package com.objectstorage.model;

import java.util.*;

/**
 * Represents a storage node in the data store.
 * Each node has a role: PRIMARY or SECONDARY (replica).
 */
public class DataNode {
    public enum Role { PRIMARY, SECONDARY }

    private final String nodeId;
    private final String datacenterId;
    private final Role role;
    private final Map<String, byte[]> data = new LinkedHashMap<>(); // objectId → bytes
    private long usedBytes = 0;
    private final long capacityBytes;

    public DataNode(String nodeId, String datacenterId, Role role, long capacityBytes) {
        this.nodeId = nodeId;
        this.datacenterId = datacenterId;
        this.role = role;
        this.capacityBytes = capacityBytes;
    }

    public String getNodeId() { return nodeId; }
    public String getDatacenterId() { return datacenterId; }
    public Role getRole() { return role; }
    public long getUsedBytes() { return usedBytes; }
    public long getCapacityBytes() { return capacityBytes; }

    public boolean store(String objectId, byte[] bytes) {
        data.put(objectId, bytes);
        usedBytes += bytes.length;
        return true;
    }

    public byte[] get(String objectId) {
        return data.get(objectId);
    }

    public boolean delete(String objectId) {
        byte[] removed = data.remove(objectId);
        if (removed != null) {
            usedBytes -= removed.length;
            return true;
        }
        return false;
    }

    public int getObjectCount() { return data.size(); }

    @Override
    public String toString() {
        return String.format("DataNode{id=%s, dc=%s, role=%s, objects=%d, used=%dMB/%dMB}",
                nodeId, datacenterId, role, data.size(),
                usedBytes / (1024 * 1024), capacityBytes / (1024 * 1024));
    }
}
