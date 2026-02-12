package com.objectstorage.storage;

import com.objectstorage.model.DataNode;

import java.util.*;

/**
 * Simulates the Placement Service.
 * Manages the virtual cluster map: knows which data nodes exist,
 * their health (via heartbeats), and decides where to place data.
 *
 * Selects: 1 Primary + N Secondaries across different data centers.
 */
public class PlacementService {
    private final List<DataNode> allNodes = new ArrayList<>();
    private final Map<String, DataNode> nodeMap = new LinkedHashMap<>();

    public void registerNode(DataNode node) {
        allNodes.add(node);
        nodeMap.put(node.getNodeId(), node);
    }

    /**
     * Choose placement for a new object.
     * Returns [primary, secondary1, secondary2] — across different data centers.
     */
    public List<DataNode> choosePlacement(int replicaCount) {
        List<DataNode> placement = new ArrayList<>();
        Set<String> usedDatacenters = new HashSet<>();

        for (DataNode node : allNodes) {
            if (!usedDatacenters.contains(node.getDatacenterId())) {
                placement.add(node);
                usedDatacenters.add(node.getDatacenterId());
                if (placement.size() >= replicaCount) break;
            }
        }
        // If not enough unique DCs, fill from any node
        if (placement.size() < replicaCount) {
            for (DataNode node : allNodes) {
                if (!placement.contains(node)) {
                    placement.add(node);
                    if (placement.size() >= replicaCount) break;
                }
            }
        }
        return placement;
    }

    public DataNode getNode(String nodeId) {
        return nodeMap.get(nodeId);
    }

    public List<DataNode> getAllNodes() {
        return allNodes;
    }

    /** Simulate heartbeat check */
    public void printClusterStatus() {
        System.out.println("  Cluster status:");
        for (DataNode node : allNodes) {
            System.out.println("    " + node);
        }
    }
}
