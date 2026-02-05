package com.nearbyfriends.model;

import java.util.*;

public class User {
    private final long userId;
    private final String name;
    private Location currentLocation;
    private long lastLocationUpdate;
    private boolean sharingLocation;
    private Set<Long> friends;
    
    public User(long userId, String name) {
        this.userId = userId;
        this.name = name;
        this.friends = new HashSet<>();
        this.sharingLocation = true;
    }
    
    public long getUserId() { return userId; }
    public String getName() { return name; }
    public Location getCurrentLocation() { return currentLocation; }
    public long getLastLocationUpdate() { return lastLocationUpdate; }
    public boolean isSharingLocation() { return sharingLocation; }
    public Set<Long> getFriends() { return friends; }
    
    public void setCurrentLocation(Location location) {
        this.currentLocation = location;
        this.lastLocationUpdate = System.currentTimeMillis();
    }
    
    public void setSharingLocation(boolean sharing) {
        this.sharingLocation = sharing;
    }
    
    public void addFriend(long friendId) {
        friends.add(friendId);
    }
    
    public void removeFriend(long friendId) {
        friends.remove(friendId);
    }
}
