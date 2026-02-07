package com.googlemaps.service;

import com.googlemaps.model.RoutingTile;
import com.googlemaps.storage.RoutingTileStore;
import com.googlemaps.storage.TrafficDB;
import java.util.*;

/**
 * ETA Service - calculates estimated time of arrival with live traffic.
 *
 * Uses:
 * - Base travel time from routing tiles (distance / speed limit)
 * - Real-time traffic multipliers from Traffic DB
 * - Historical patterns for prediction
 *
 * The ETA Service is also responsible for ADAPTIVE ETA during navigation:
 * - Continuously monitors traffic on the user's route
 * - If conditions change significantly → triggers rerouting
 */
public class ETAService {
    private final RoutingTileStore tileStore;
    private final TrafficDB trafficDB;

    public ETAService(RoutingTileStore tileStore, TrafficDB trafficDB) {
        this.tileStore = tileStore;
        this.trafficDB = trafficDB;
    }

    /**
     * Calculate ETA for a given path with current traffic conditions.
     */
    public ETAResult calculateETA(List<String> path) {
        System.out.printf("  [ETAService] Calculating ETA for: %s%n", path);

        double totalBaseTimeMin = 0;
        double totalTrafficTimeMin = 0;
        double totalDistanceKm = 0;
        List<String> segmentDetails = new ArrayList<>();

        for (int i = 0; i < path.size() - 1; i++) {
            String from = path.get(i);
            String to = path.get(i + 1);

            // Find edge data from routing tiles
            double segmentDist = findEdgeDistance(from, to);
            double segmentSpeed = findEdgeSpeed(from, to);

            double baseTimeMin = (segmentDist / segmentSpeed) * 60;
            double trafficMultiplier = trafficDB.getTrafficMultiplier(from, to);
            double trafficTimeMin = baseTimeMin * trafficMultiplier;
            String condition = trafficDB.getTrafficCondition(from, to);

            totalBaseTimeMin += baseTimeMin;
            totalTrafficTimeMin += trafficTimeMin;
            totalDistanceKm += segmentDist;

            segmentDetails.add(String.format("    %s → %s: %.1fkm, base=%.1fmin, traffic=%.1fmin (%s, x%.1f)",
                    from, to, segmentDist, baseTimeMin, trafficTimeMin, condition, trafficMultiplier));
        }

        System.out.printf("  [ETAService] Base ETA: %.0f min, With traffic: %.0f min%n",
                totalBaseTimeMin, totalTrafficTimeMin);

        return new ETAResult(totalDistanceKm, totalBaseTimeMin, totalTrafficTimeMin, segmentDetails);
    }

    /** Check if rerouting is needed based on updated traffic */
    public boolean shouldReroute(List<String> currentPath, double currentEtaMin) {
        ETAResult newEta = calculateETA(currentPath);
        double increase = newEta.getTrafficEtaMinutes() - currentEtaMin;
        boolean reroute = increase > 5.0; // Reroute if >5 min increase
        if (reroute) {
            System.out.printf("  [ETAService] REROUTE recommended! ETA increased by %.0f min%n", increase);
        }
        return reroute;
    }

    private double findEdgeDistance(String from, String to) {
        for (RoutingTile tile : tileStore.getTilesForNode(from)) {
            for (RoutingTile.Edge edge : tile.getEdges(from)) {
                if (edge.getToNode().equals(to)) return edge.getDistanceKm();
            }
        }
        return 10.0; // default
    }

    private double findEdgeSpeed(String from, String to) {
        for (RoutingTile tile : tileStore.getTilesForNode(from)) {
            for (RoutingTile.Edge edge : tile.getEdges(from)) {
                if (edge.getToNode().equals(to)) return edge.getSpeedLimitKmh();
            }
        }
        return 50.0; // default
    }

    /** Result of ETA calculation */
    public static class ETAResult {
        private final double distanceKm;
        private final double baseEtaMinutes;
        private final double trafficEtaMinutes;
        private final List<String> segmentDetails;

        public ETAResult(double distanceKm, double baseEtaMinutes, double trafficEtaMinutes,
                         List<String> segmentDetails) {
            this.distanceKm = distanceKm;
            this.baseEtaMinutes = baseEtaMinutes;
            this.trafficEtaMinutes = trafficEtaMinutes;
            this.segmentDetails = segmentDetails;
        }

        public double getDistanceKm() { return distanceKm; }
        public double getBaseEtaMinutes() { return baseEtaMinutes; }
        public double getTrafficEtaMinutes() { return trafficEtaMinutes; }
        public List<String> getSegmentDetails() { return segmentDetails; }
    }
}
