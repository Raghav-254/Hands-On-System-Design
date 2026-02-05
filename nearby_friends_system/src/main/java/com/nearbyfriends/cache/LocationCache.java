package com.nearbyfriends.cache;

import com.nearbyfriends.model.Location;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Location Cache - stores current user locations in memory
 * In production: Redis with TTL (10 minutes)
 * 
 * Key: user_id
 * Value: {latitude, longitude, timestamp}
 */
public class LocationCache {
    private final Map<Long, Location> cache;
    private static final long TTL_MS = 10 * 60 * 1000; // 10 minutes
    
    public LocationCache() {
        this.cache = new ConcurrentHashMap<>();
    }
    
    public void setLocation(long userId, Location location) {
        cache.put(userId, location);
        System.out.println("[LocationCache] Cached location for user " + userId);
    }
    
    public Location getLocation(long userId) {
        Location location = cache.get(userId);
        
        // Check if expired
        if (location != null && isExpired(location)) {
            cache.remove(userId);
            System.out.println("[LocationCache] Expired location for user " + userId);
            return null;
        }
        
        return location;
    }
    
    public Map<Long, Location> getLocations(Collection<Long> userIds) {
        Map<Long, Location> result = new HashMap<>();
        
        for (long userId : userIds) {
            Location location = getLocation(userId);
            if (location != null) {
                result.put(userId, location);
            }
        }
        
        return result;
    }
    
    private boolean isExpired(Location location) {
        return System.currentTimeMillis() - location.getTimestamp() > TTL_MS;
    }
    
    public void removeLocation(long userId) {
        cache.remove(userId);
        System.out.println("[LocationCache] Removed location for user " + userId);
    }
}
