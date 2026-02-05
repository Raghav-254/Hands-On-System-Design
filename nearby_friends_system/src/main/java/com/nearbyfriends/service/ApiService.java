package com.nearbyfriends.service;

import com.nearbyfriends.model.*;
import com.nearbyfriends.storage.*;
import com.nearbyfriends.cache.*;
import java.util.*;

/**
 * API Service - handles RESTful API requests
 * In production: Stateless API servers
 * 
 * Endpoints:
 * - POST /api/v1/location - Update user location
 * - GET /api/v1/nearby-friends - Get nearby friends list
 * - POST /api/v1/friends - Add friend
 */
public class ApiService {
    private final UserDB userDB;
    private final LocationHistoryDB locationHistoryDB;
    private final LocationCache locationCache;
    
    public ApiService(UserDB userDB, LocationHistoryDB locationHistoryDB, 
                     LocationCache locationCache) {
        this.userDB = userDB;
        this.locationHistoryDB = locationHistoryDB;
        this.locationCache = locationCache;
    }
    
    /**
     * Update user location (HTTP REST call)
     * POST /api/v1/location
     */
    public ApiResponse updateLocation(long userId, double latitude, double longitude) {
        System.out.println("\n[ApiService] HTTP POST /api/v1/location");
        System.out.println("  User: " + userId + ", Location: (" + latitude + ", " + longitude + ")");
        
        User user = userDB.getUser(userId);
        if (user == null || !user.isSharingLocation()) {
            return new ApiResponse(400, "User not found or not sharing location");
        }
        
        Location location = new Location(latitude, longitude);
        
        // Save to cache (step 4 in Figure 7)
        locationCache.setLocation(userId, location);
        
        // Save to history database (step 3 in Figure 7)
        locationHistoryDB.saveLocation(userId, location);
        
        return new ApiResponse(200, "Location updated successfully");
    }
    
    /**
     * Get nearby friends
     * GET /api/v1/nearby-friends?user_id={userId}&radius={radius}
     */
    public ApiResponse getNearbyFriends(long userId, double radiusMiles) {
        System.out.println("\n[ApiService] HTTP GET /api/v1/nearby-friends");
        System.out.println("  User: " + userId + ", Radius: " + radiusMiles + " miles");
        
        User user = userDB.getUser(userId);
        if (user == null) {
            return new ApiResponse(404, "User not found");
        }
        
        Location userLocation = locationCache.getLocation(userId);
        if (userLocation == null) {
            return new ApiResponse(400, "User location not available");
        }
        
        // Get all friends' locations from cache
        Set<Long> friendIds = userDB.getFriends(userId);
        Map<Long, Location> friendLocations = locationCache.getLocations(friendIds);
        
        // Filter by distance
        List<FriendLocation> nearbyFriends = new ArrayList<>();
        for (Map.Entry<Long, Location> entry : friendLocations.entrySet()) {
            long friendId = entry.getKey();
            Location friendLocation = entry.getValue();
            
            double distance = userLocation.distanceTo(friendLocation);
            if (distance <= radiusMiles) {
                User friend = userDB.getUser(friendId);
                nearbyFriends.add(new FriendLocation(friendId, friend.getName(), 
                                                    friendLocation, distance));
            }
        }
        
        // Sort by distance
        nearbyFriends.sort(Comparator.comparingDouble(FriendLocation::getDistance));
        
        return new ApiResponse(200, nearbyFriends);
    }
    
    /**
     * Add friend
     * POST /api/v1/friends
     */
    public ApiResponse addFriend(long userId, long friendId) {
        System.out.println("\n[ApiService] HTTP POST /api/v1/friends");
        System.out.println("  User: " + userId + " → Friend: " + friendId);
        
        userDB.addFriendship(userId, friendId);
        return new ApiResponse(200, "Friend added successfully");
    }
    
    public static class ApiResponse {
        public final int statusCode;
        public final Object data;
        
        public ApiResponse(int statusCode, Object data) {
            this.statusCode = statusCode;
            this.data = data;
        }
    }
}
