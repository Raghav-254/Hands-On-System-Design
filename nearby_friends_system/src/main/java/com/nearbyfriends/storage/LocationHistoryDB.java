package com.nearbyfriends.storage;

import com.nearbyfriends.model.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Location History Database - stores historical location data
 * In production: Cassandra (time-series optimized)
 * 
 * Schema:
 *   user_id | timestamp | latitude | longitude
 */
public class LocationHistoryDB {
    private final Map<Long, List<LocationUpdate>> locationHistory;
    
    public LocationHistoryDB() {
        this.locationHistory = new ConcurrentHashMap<>();
    }
    
    public void saveLocation(long userId, Location location) {
        LocationUpdate update = new LocationUpdate(userId, location);
        locationHistory.computeIfAbsent(userId, k -> new ArrayList<>()).add(update);
        
        System.out.println("[LocationHistoryDB] Saved location for user " + userId + 
                         ": " + location);
    }
    
    public List<LocationUpdate> getLocationHistory(long userId, long since) {
        List<LocationUpdate> history = locationHistory.getOrDefault(userId, Collections.emptyList());
        List<LocationUpdate> filtered = new ArrayList<>();
        
        for (LocationUpdate update : history) {
            if (update.getTimestamp() >= since) {
                filtered.add(update);
            }
        }
        
        return filtered;
    }
    
    public Location getLatestLocation(long userId) {
        List<LocationUpdate> history = locationHistory.get(userId);
        if (history == null || history.isEmpty()) {
            return null;
        }
        return history.get(history.size() - 1).getLocation();
    }
}
