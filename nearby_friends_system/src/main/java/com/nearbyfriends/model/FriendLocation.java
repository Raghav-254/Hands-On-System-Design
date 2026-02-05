package com.nearbyfriends.model;

public class FriendLocation {
    private final long userId;
    private final String name;
    private final Location location;
    private final double distance;
    private final long lastUpdated;
    
    public FriendLocation(long userId, String name, Location location, double distance) {
        this.userId = userId;
        this.name = name;
        this.location = location;
        this.distance = distance;
        this.lastUpdated = location.getTimestamp();
    }
    
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public Location getLocation() { return location; }
    public double getDistance() { return distance; }
    public long getLastUpdated() { return lastUpdated; }
    
    @Override
    public String toString() {
        long secondsAgo = (System.currentTimeMillis() - lastUpdated) / 1000;
        return String.format("%s - %.2f miles away (updated %ds ago)", 
                           name, distance, secondsAgo);
    }
}
