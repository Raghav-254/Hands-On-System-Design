package com.nearbyfriends.model;

public class LocationUpdate {
    private final long userId;
    private final Location location;
    private final long timestamp;
    
    public LocationUpdate(long userId, Location location) {
        this.userId = userId;
        this.location = location;
        this.timestamp = System.currentTimeMillis();
    }
    
    public long getUserId() { return userId; }
    public Location getLocation() { return location; }
    public long getTimestamp() { return timestamp; }
}
