package com.googlemaps.storage;

import java.util.*;

/**
 * Simulates the Traffic Database.
 *
 * Stores real-time and historical traffic data for road segments.
 * Updated by the Traffic Update Service which consumes location data from Kafka.
 *
 * In production:
 * - Time-series database (e.g., InfluxDB, TimescaleDB)
 * - Aggregated from millions of user location updates
 * - Used by ETA Service to adjust travel time estimates
 */
public class TrafficDB {
    // roadSegment → traffic multiplier (1.0 = normal, 2.0 = 2x slower)
    private final Map<String, Double> currentTraffic = new HashMap<>();
    private final Map<String, String> trafficConditions = new HashMap<>();

    public TrafficDB() {
        seed();
    }

    private void seed() {
        setTraffic("sf_downtown→oakland_bridge", 2.5, "heavy");   // Bay Bridge rush hour
        setTraffic("sf_downtown→sf_mission", 1.3, "moderate");
        setTraffic("sf_downtown→sf_soma", 1.1, "light");
        setTraffic("sf_downtown→daly_city", 1.8, "heavy");
        setTraffic("daly_city→san_mateo", 1.4, "moderate");
        setTraffic("san_mateo→mountain_view", 1.6, "moderate");
        setTraffic("mountain_view→cupertino", 1.0, "clear");
        setTraffic("sf_downtown→mountain_view", 1.9, "heavy");  // Highway 101
        setTraffic("mountain_view→san_jose", 1.2, "light");
    }

    private void setTraffic(String segment, double multiplier, String condition) {
        currentTraffic.put(segment, multiplier);
        trafficConditions.put(segment, condition);
    }

    /**
     * Get traffic multiplier for a road segment.
     * Returns 1.0 if no traffic data available (assume clear).
     */
    public double getTrafficMultiplier(String fromNode, String toNode) {
        String key = fromNode + "→" + toNode;
        return currentTraffic.getOrDefault(key,
                currentTraffic.getOrDefault(toNode + "→" + fromNode, 1.0));
    }

    public String getTrafficCondition(String fromNode, String toNode) {
        String key = fromNode + "→" + toNode;
        return trafficConditions.getOrDefault(key,
                trafficConditions.getOrDefault(toNode + "→" + fromNode, "unknown"));
    }

    /** Update traffic data (called by Traffic Update Service) */
    public void updateTraffic(String fromNode, String toNode, double multiplier, String condition) {
        String key = fromNode + "→" + toNode;
        currentTraffic.put(key, multiplier);
        trafficConditions.put(key, condition);
    }
}
