package com.objectstorage.storage;

import com.objectstorage.model.DataNode;

import java.util.*;

/**
 * Simulates the Data Store (Data Routing Service + Data Nodes).
 * Handles actual data persistence with replication.
 *
 * Flow: API Service → Data Routing Service → Placement Service
 *       → Primary Data Node → Replication to Secondaries
 */
public class DataStore {
    private final PlacementService placementService;
    private final int replicaCount;
    // objectId → list of node IDs where replicas live
    private final Map<String, List<String>> replicaMap = new LinkedHashMap<>();

    public DataStore(PlacementService placementService, int replicaCount) {
        this.placementService = placementService;
        this.replicaCount = replicaCount;
    }

    /**
     * Store object data with replication.
     * Returns the list of nodes where data was stored.
     */
    public List<DataNode> storeObject(String objectId, byte[] data) {
        System.out.println("  [DATA ROUTING] Consulting Placement Service for placement...");
        List<DataNode> nodes = placementService.choosePlacement(replicaCount);

        // Write to primary
        DataNode primary = nodes.get(0);
        primary.store(objectId, data);
        System.out.println("  [PRIMARY] Stored on " + primary.getNodeId() +
                " (" + primary.getDatacenterId() + ")");

        // Replicate to secondaries
        List<String> nodeIds = new ArrayList<>();
        nodeIds.add(primary.getNodeId());
        for (int i = 1; i < nodes.size(); i++) {
            DataNode secondary = nodes.get(i);
            secondary.store(objectId, data);
            nodeIds.add(secondary.getNodeId());
            System.out.println("  [REPLICA] Replicated to " + secondary.getNodeId() +
                    " (" + secondary.getDatacenterId() + ")");
        }
        replicaMap.put(objectId, nodeIds);
        return nodes;
    }

    /** Retrieve object data (reads from primary first, falls back to replicas) */
    public byte[] getObject(String objectId) {
        List<String> nodeIds = replicaMap.get(objectId);
        if (nodeIds == null) return null;

        for (String nodeId : nodeIds) {
            DataNode node = placementService.getNode(nodeId);
            byte[] data = node.get(objectId);
            if (data != null) {
                System.out.println("  [DATA ROUTING] Read from " + nodeId);
                return data;
            }
        }
        return null;
    }

    /** Mark object for garbage collection (delete from all replicas) */
    public void deleteObject(String objectId) {
        List<String> nodeIds = replicaMap.get(objectId);
        if (nodeIds != null) {
            for (String nodeId : nodeIds) {
                DataNode node = placementService.getNode(nodeId);
                node.delete(objectId);
            }
            replicaMap.remove(objectId);
            System.out.println("  [GC] Deleted object " + objectId + " from " + nodeIds.size() + " nodes");
        }
    }
}
