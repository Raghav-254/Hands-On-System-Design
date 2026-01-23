package com.newsfeed.cache;

import com.newsfeed.models.User;
import com.newsfeed.storage.UserDB;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UserCache - In-memory cache for user profiles (simulates Redis).
 * 
 * Caches user profile data to avoid repeated DB lookups when
 * rendering posts (need to show author name, avatar, etc.)
 */
public class UserCache {
    
    private final Map<Long, User> cache = new ConcurrentHashMap<>();
    private final UserDB userDB;
    private long cacheHits = 0;
    private long cacheMisses = 0;
    
    public UserCache(UserDB userDB) {
        this.userDB = userDB;
    }
    
    /**
     * Get user from cache
     */
    public User get(long userId) {
        User user = cache.get(userId);
        if (user != null) {
            cacheHits++;
            return user;
        }
        
        // Cache miss - fetch from DB
        cacheMisses++;
        user = userDB.getById(userId);
        if (user != null) {
            cache.put(userId, user);
        }
        
        return user;
    }
    
    /**
     * Get multiple users (batch)
     */
    public Map<Long, User> getMultiple(Set<Long> userIds) {
        Map<Long, User> result = new HashMap<>();
        Set<Long> missingIds = new HashSet<>();
        
        for (Long userId : userIds) {
            User cached = cache.get(userId);
            if (cached != null) {
                result.put(userId, cached);
                cacheHits++;
            } else {
                missingIds.add(userId);
                cacheMisses++;
            }
        }
        
        // Batch fetch missing from DB
        if (!missingIds.isEmpty()) {
            Map<Long, User> fromDB = userDB.getByIds(missingIds);
            cache.putAll(fromDB);
            result.putAll(fromDB);
        }
        
        return result;
    }
    
    /**
     * Put user in cache
     */
    public void put(User user) {
        cache.put(user.getUserId(), user);
    }
    
    /**
     * Invalidate cache entry
     */
    public void invalidate(long userId) {
        cache.remove(userId);
    }
    
    /**
     * Get cache statistics
     */
    public String getStats() {
        double hitRate = (cacheHits + cacheMisses) > 0 
            ? (double) cacheHits / (cacheHits + cacheMisses) * 100 : 0;
        return String.format("UserCache: hits=%d, misses=%d, hitRate=%.1f%%, size=%d",
            cacheHits, cacheMisses, hitRate, cache.size());
    }
}

