package com.googlemaps.model;

import java.util.*;

/**
 * Represents a routing tile - a geographic partition of the road network graph.
 *
 * Unlike map tiles (images), routing tiles contain GRAPH DATA:
 * - Nodes: Intersections (with lat/lng)
 * - Edges: Road segments (with distance, speed limit, one-way flag)
 *
 * The road network is divided into tiles and stored in Object Storage (S3).
 * During route computation, only relevant tiles are loaded into memory.
 *
 * Key insight: Routing tiles are separate from map tiles!
 * - Map tiles → visual rendering (images, served via CDN)
 * - Routing tiles → path computation (graph data, served from S3)
 */
public class RoutingTile {
    private final String tileId;
    private final Map<String, List<Edge>> adjacencyList; // node → edges

    public RoutingTile(String tileId) {
        this.tileId = tileId;
        this.adjacencyList = new HashMap<>();
    }

    public void addEdge(String fromNode, String toNode, double distanceKm, double speedLimitKmh, boolean oneWay) {
        adjacencyList.computeIfAbsent(fromNode, k -> new ArrayList<>())
                .add(new Edge(toNode, distanceKm, speedLimitKmh, oneWay));
        if (!oneWay) {
            adjacencyList.computeIfAbsent(toNode, k -> new ArrayList<>())
                    .add(new Edge(fromNode, distanceKm, speedLimitKmh, false));
        }
    }

    public List<Edge> getEdges(String node) {
        return adjacencyList.getOrDefault(node, Collections.emptyList());
    }

    public Set<String> getNodes() { return adjacencyList.keySet(); }
    public String getTileId() { return tileId; }

    @Override
    public String toString() {
        return String.format("RoutingTile[%s] nodes=%d", tileId, adjacencyList.size());
    }

    /**
     * Represents a road segment (edge) in the road network graph.
     */
    public static class Edge {
        private final String toNode;
        private final double distanceKm;
        private final double speedLimitKmh;
        private final boolean oneWay;

        public Edge(String toNode, double distanceKm, double speedLimitKmh, boolean oneWay) {
            this.toNode = toNode;
            this.distanceKm = distanceKm;
            this.speedLimitKmh = speedLimitKmh;
            this.oneWay = oneWay;
        }

        public String getToNode() { return toNode; }
        public double getDistanceKm() { return distanceKm; }
        public double getSpeedLimitKmh() { return speedLimitKmh; }
        public boolean isOneWay() { return oneWay; }

        /** Estimated travel time in minutes without traffic */
        public double getBaseTravelTimeMin() {
            return (distanceKm / speedLimitKmh) * 60;
        }

        @Override
        public String toString() {
            return String.format("→ %s (%.1fkm, %dkm/h)", toNode, distanceKm, (int) speedLimitKmh);
        }
    }
}
