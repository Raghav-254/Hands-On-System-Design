package com.googlemaps.storage;

import com.googlemaps.model.Location;
import java.util.*;

/**
 * Simulates the User Location Database.
 *
 * Stores user location history from GPS updates.
 * The Location Service writes here and also publishes to Kafka.
 *
 * In production:
 * - Write-heavy database (millions of writes/sec)
 * - Could use Cassandra or a time-series database
 * - Data used by downstream services (traffic, routing, analytics)
 */
public class UserLocationDB {
    private final Map<String, List<Location>> locationHistory = new HashMap<>();

    /** Save a batch of location updates for a user */
    public void saveBatch(String userId, List<Location> locations) {
        locationHistory.computeIfAbsent(userId, k -> new ArrayList<>()).addAll(locations);
    }

    /** Get recent locations for a user */
    public List<Location> getRecentLocations(String userId, int limit) {
        List<Location> history = locationHistory.getOrDefault(userId, Collections.emptyList());
        int start = Math.max(0, history.size() - limit);
        return history.subList(start, history.size());
    }

    /** Get total number of stored location points */
    public int getTotalLocationPoints() {
        return locationHistory.values().stream().mapToInt(List::size).sum();
    }
}
