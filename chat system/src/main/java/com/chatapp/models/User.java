package com.chatapp.models;

import java.util.HashSet;
import java.util.Set;

/**
 * User model representing a chat user.
 * In production, this would be stored in a database.
 */
public class User {
    private final long userId;
    private final String username;
    private boolean online;
    private long lastActiveAt;
    private Set<String> deviceIds; // Support multiple devices (phone, laptop, etc.)

    public User(long userId, String username) {
        this.userId = userId;
        this.username = username;
        this.online = false;
        this.lastActiveAt = System.currentTimeMillis();
        this.deviceIds = new HashSet<>();
    }

    public long getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
        if (online) {
            this.lastActiveAt = System.currentTimeMillis();
        }
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(long lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }

    public Set<String> getDeviceIds() {
        return deviceIds;
    }

    public void addDevice(String deviceId) {
        this.deviceIds.add(deviceId);
    }

    public void removeDevice(String deviceId) {
        this.deviceIds.remove(deviceId);
        if (this.deviceIds.isEmpty()) {
            this.online = false;
        }
    }

    @Override
    public String toString() {
        return String.format("User{id=%d, username='%s', online=%s, devices=%d}", 
            userId, username, online, deviceIds.size());
    }
}

