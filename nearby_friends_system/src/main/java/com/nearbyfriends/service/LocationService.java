package com.nearbyfriends.service;

import com.nearbyfriends.model.*;
import com.nearbyfriends.storage.*;
import com.nearbyfriends.cache.*;
import com.nearbyfriends.websocket.*;
import java.util.*;

/**
 * Location Service - handles location updates and real-time broadcasting
 * In production: Stateful service co-located with WebSocket servers
 */
public class LocationService implements RedisPubSub.Subscriber {
    private final UserDB userDB;
    private final LocationCache locationCache;
    private final LocationHistoryDB locationHistoryDB;
    private final RedisPubSub redisPubSub;
    private final WebSocketServer webSocketServer;
    private final long userId; // The user this service instance handles
    
    public LocationService(long userId, UserDB userDB, LocationCache locationCache,
                          LocationHistoryDB locationHistoryDB, RedisPubSub redisPubSub,
                          WebSocketServer webSocketServer) {
        this.userId = userId;
        this.userDB = userDB;
        this.locationCache = locationCache;
        this.locationHistoryDB = locationHistoryDB;
        this.redisPubSub = redisPubSub;
        this.webSocketServer = webSocketServer;
    }
    
    /**
     * Initialize - subscribe to all friends' channels
     */
    public void initialize() {
        User user = userDB.getUser(userId);
        if (user != null) {
            Set<Long> friendIds = user.getFriends();
            redisPubSub.subscribeToFriends(userId, friendIds, this);
            System.out.println("[LocationService] Initialized for user " + userId);
        }
    }
    
    /**
     * Handle incoming location update from client via WebSocket
     */
    public void handleLocationUpdate(Location location) {
        System.out.println("\n[LocationService] Processing location update for user " + userId);
        
        User user = userDB.getUser(userId);
        if (user == null || !user.isSharingLocation()) {
            return;
        }
        
        // Update location in cache (step 5 in Figure 7)
        locationCache.setLocation(userId, location);
        
        // Save to history database (step 4 in Figure 7)
        locationHistoryDB.saveLocation(userId, location);
        
        // Publish to Redis Pub/Sub (step 6 in Figure 7)
        String channel = RedisPubSub.getUserChannel(userId);
        LocationUpdate update = new LocationUpdate(userId, location);
        redisPubSub.publish(channel, update);
        
        System.out.println("[LocationService] Published location update to channel: " + channel);
    }
    
    /**
     * Callback when receiving location update from Redis Pub/Sub (step 7 in Figure 8)
     */
    @Override
    public void onMessage(LocationUpdate update) {
        System.out.println("[LocationService] Received update from friend " + update.getUserId());
        
        // Get current user's location
        Location userLocation = locationCache.getLocation(userId);
        if (userLocation == null) {
            return;
        }
        
        // Calculate distance
        Location friendLocation = update.getLocation();
        double distance = userLocation.distanceTo(friendLocation);
        
        // Only send if within 5 miles (or configured radius)
        if (distance <= 5.0) {
            User friend = userDB.getUser(update.getUserId());
            FriendLocation friendLoc = new FriendLocation(
                update.getUserId(), 
                friend.getName(), 
                friendLocation, 
                distance
            );
            
            // Send to client via WebSocket
            webSocketServer.sendLocationUpdate(userId, friendLoc);
        }
    }
    
    /**
     * Get nearby friends list (for initial load or refresh)
     */
    public List<FriendLocation> getNearbyFriends(double radiusMiles) {
        User user = userDB.getUser(userId);
        Location userLocation = locationCache.getLocation(userId);
        
        if (user == null || userLocation == null) {
            return Collections.emptyList();
        }
        
        Set<Long> friendIds = user.getFriends();
        Map<Long, Location> friendLocations = locationCache.getLocations(friendIds);
        
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
        
        nearbyFriends.sort(Comparator.comparingDouble(FriendLocation::getDistance));
        return nearbyFriends;
    }
}
