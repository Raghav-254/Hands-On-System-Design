package com.newsfeed.storage;

import com.newsfeed.models.User;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UserDB - Simulates the User Database.
 * 
 * In production, this would be MySQL/PostgreSQL.
 * 
 * Stores:
 * - User profiles (username, display name, avatar, etc.)
 */
public class UserDB {
    
    private final Map<Long, User> usersById = new ConcurrentHashMap<>();
    private final Map<String, Long> usersByUsername = new ConcurrentHashMap<>();
    
    /**
     * Save a new user
     */
    public void save(User user) {
        usersById.put(user.getUserId(), user);
        usersByUsername.put(user.getUsername().toLowerCase(), user.getUserId());
    }
    
    /**
     * Get user by ID
     */
    public User getById(long userId) {
        return usersById.get(userId);
    }
    
    /**
     * Get multiple users by IDs (batch fetch)
     */
    public Map<Long, User> getByIds(Set<Long> userIds) {
        Map<Long, User> result = new HashMap<>();
        for (Long userId : userIds) {
            User user = usersById.get(userId);
            if (user != null) {
                result.put(userId, user);
            }
        }
        return result;
    }
    
    /**
     * Get user by username
     */
    public User getByUsername(String username) {
        Long userId = usersByUsername.get(username.toLowerCase());
        return userId != null ? usersById.get(userId) : null;
    }
    
    /**
     * Check if user exists
     */
    public boolean exists(long userId) {
        return usersById.containsKey(userId);
    }
}

