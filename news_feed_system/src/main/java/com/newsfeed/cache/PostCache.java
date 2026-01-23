package com.newsfeed.cache;

import com.newsfeed.models.Post;
import com.newsfeed.storage.PostDB;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PostCache - In-memory cache for posts (simulates Redis).
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  POST CACHE STRUCTURE (from Figure 11-8)                                     ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Content Cache:                                                              ║
 * ║  ┌─────────────────────┬─────────────────────┐                               ║
 * ║  │     HOT CACHE       │      NORMAL         │                               ║
 * ║  │  (viral/trending)   │   (regular posts)   │                               ║
 * ║  └─────────────────────┴─────────────────────┘                               ║
 * ║                                                                               ║
 * ║  Hot cache: Posts with high engagement (likes, shares, comments)            ║
 * ║  Normal cache: Regular posts with standard TTL                               ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 * 
 * Cache-Aside Pattern:
 * 1. Read: Check cache → Miss? → Read DB → Update cache
 * 2. Write: Write DB → Invalidate cache
 */
public class PostCache {
    
    private static final int HOT_THRESHOLD = 100; // Likes threshold for hot cache
    
    // Simulates Redis cache
    private final Map<Long, Post> hotCache = new ConcurrentHashMap<>();   // Viral posts
    private final Map<Long, Post> normalCache = new ConcurrentHashMap<>(); // Regular posts
    
    private final PostDB postDB;
    private long cacheHits = 0;
    private long cacheMisses = 0;
    
    public PostCache(PostDB postDB) {
        this.postDB = postDB;
    }
    
    /**
     * Get post from cache (Cache-Aside pattern)
     */
    public Post get(long postId) {
        // 1. Check hot cache first
        Post post = hotCache.get(postId);
        if (post != null) {
            cacheHits++;
            return post;
        }
        
        // 2. Check normal cache
        post = normalCache.get(postId);
        if (post != null) {
            cacheHits++;
            // Promote to hot cache if it became viral
            if (post.getLikeCount() >= HOT_THRESHOLD) {
                hotCache.put(postId, post);
                normalCache.remove(postId);
            }
            return post;
        }
        
        // 3. Cache miss - fetch from DB
        cacheMisses++;
        post = postDB.getById(postId);
        if (post != null) {
            put(post);
        }
        
        return post;
    }
    
    /**
     * Get multiple posts (batch)
     */
    public List<Post> getMultiple(List<Long> postIds) {
        List<Post> result = new ArrayList<>();
        List<Long> missingIds = new ArrayList<>();
        
        for (Long postId : postIds) {
            Post cached = hotCache.get(postId);
            if (cached == null) {
                cached = normalCache.get(postId);
            }
            
            if (cached != null) {
                result.add(cached);
                cacheHits++;
            } else {
                missingIds.add(postId);
                cacheMisses++;
            }
        }
        
        // Batch fetch missing from DB
        if (!missingIds.isEmpty()) {
            List<Post> fromDB = postDB.getByIds(missingIds);
            for (Post post : fromDB) {
                put(post);
                result.add(post);
            }
        }
        
        return result;
    }
    
    /**
     * Put post in cache
     */
    public void put(Post post) {
        if (post.getLikeCount() >= HOT_THRESHOLD) {
            hotCache.put(post.getPostId(), post);
        } else {
            normalCache.put(post.getPostId(), post);
        }
    }
    
    /**
     * Invalidate cache entry
     */
    public void invalidate(long postId) {
        hotCache.remove(postId);
        normalCache.remove(postId);
    }
    
    /**
     * Get cache statistics
     */
    public String getStats() {
        double hitRate = (cacheHits + cacheMisses) > 0 
            ? (double) cacheHits / (cacheHits + cacheMisses) * 100 : 0;
        return String.format("PostCache: hits=%d, misses=%d, hitRate=%.1f%%, hot=%d, normal=%d",
            cacheHits, cacheMisses, hitRate, hotCache.size(), normalCache.size());
    }
}

