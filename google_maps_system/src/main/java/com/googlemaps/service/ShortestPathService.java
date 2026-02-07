package com.googlemaps.service;

import com.googlemaps.model.RoutingTile;
import com.googlemaps.storage.RoutingTileStore;
import java.util.*;

/**
 * Shortest Path Service - finds optimal routes using routing tiles.
 *
 * Uses a variant of Dijkstra's algorithm on the road network graph.
 * Loads routing tiles from Object Storage (S3) as needed.
 *
 * Key insight: The road network is too large to fit in a single machine.
 * Solution: Partition into routing tiles and only load tiles near the route.
 *
 * In production, this would use hierarchical routing:
 * - Local roads → detailed routing tiles
 * - Highways → coarse routing tiles (for long-distance segments)
 */
public class ShortestPathService {
    private final RoutingTileStore tileStore;

    public ShortestPathService(RoutingTileStore tileStore) {
        this.tileStore = tileStore;
    }

    /**
     * Find shortest path between two nodes using Dijkstra's algorithm.
     * Returns the path as a list of nodes and the total distance.
     */
    public PathResult findShortestPath(String origin, String destination) {
        System.out.printf("  [ShortestPath] Computing: %s → %s%n", origin, destination);

        // Load all relevant routing tiles
        Map<String, List<RoutingTile.Edge>> graph = buildGraph(origin, destination);

        // Dijkstra's algorithm
        Map<String, Double> distances = new HashMap<>();
        Map<String, String> previous = new HashMap<>();
        PriorityQueue<String> queue = new PriorityQueue<>(
                Comparator.comparingDouble(n -> distances.getOrDefault(n, Double.MAX_VALUE)));

        distances.put(origin, 0.0);
        queue.add(origin);

        while (!queue.isEmpty()) {
            String current = queue.poll();

            if (current.equals(destination)) break;

            double currentDist = distances.getOrDefault(current, Double.MAX_VALUE);
            List<RoutingTile.Edge> edges = graph.getOrDefault(current, Collections.emptyList());

            for (RoutingTile.Edge edge : edges) {
                double newDist = currentDist + edge.getDistanceKm();
                if (newDist < distances.getOrDefault(edge.getToNode(), Double.MAX_VALUE)) {
                    distances.put(edge.getToNode(), newDist);
                    previous.put(edge.getToNode(), current);
                    queue.add(edge.getToNode());
                }
            }
        }

        // Reconstruct path
        List<String> path = new ArrayList<>();
        String current = destination;
        while (current != null) {
            path.add(0, current);
            current = previous.get(current);
        }

        double totalDistance = distances.getOrDefault(destination, -1.0);
        System.out.printf("  [ShortestPath] Found: %s (%.1f km)%n", path, totalDistance);
        return new PathResult(path, totalDistance);
    }

    /** Build a combined graph from all relevant routing tiles */
    private Map<String, List<RoutingTile.Edge>> buildGraph(String origin, String destination) {
        Map<String, List<RoutingTile.Edge>> graph = new HashMap<>();

        // Load tiles containing origin and destination
        List<RoutingTile> relevantTiles = new ArrayList<>();
        relevantTiles.addAll(tileStore.getTilesForNode(origin));
        relevantTiles.addAll(tileStore.getTilesForNode(destination));

        // Also load highway tiles for long-distance routing
        tileStore.getTile("tile_highway_101").ifPresent(relevantTiles::add);

        for (RoutingTile tile : relevantTiles) {
            for (String node : tile.getNodes()) {
                for (RoutingTile.Edge edge : tile.getEdges(node)) {
                    graph.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
                }
            }
        }
        return graph;
    }

    /** Result of shortest path computation */
    public static class PathResult {
        private final List<String> path;
        private final double distanceKm;

        public PathResult(List<String> path, double distanceKm) {
            this.path = path;
            this.distanceKm = distanceKm;
        }

        public List<String> getPath() { return path; }
        public double getDistanceKm() { return distanceKm; }
    }
}
