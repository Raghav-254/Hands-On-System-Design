package com.newsfeed.models;

import java.time.Instant;

/**
 * User model representing a user in the news feed system.
 */
public class User {
    private final long userId;
    private final String username;
    private final String displayName;
    private final String avatarUrl;
    private final Instant createdAt;
    
    public User(long userId, String username, String displayName) {
        this.userId = userId;
        this.username = username;
        this.displayName = displayName;
        this.avatarUrl = "https://avatar.example.com/" + userId;
        this.createdAt = Instant.now();
    }
    
    public long getUserId() { return userId; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getAvatarUrl() { return avatarUrl; }
    public Instant getCreatedAt() { return createdAt; }
    
    @Override
    public String toString() {
        return String.format("User{id=%d, username='%s', displayName='%s'}", 
            userId, username, displayName);
    }
}

