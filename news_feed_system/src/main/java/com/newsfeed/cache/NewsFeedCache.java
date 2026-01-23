package com.newsfeed.cache;

import com.newsfeed.models.FeedItem;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * NewsFeedCache - The core cache that stores pre-computed news feeds.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  NEWS FEED CACHE STRUCTURE                                                   ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Key: user_id                                                                 ║
 * ║  Value: Sorted Set of (post_id, score)                                       ║
 * ║                                                                               ║
 * ║  Example:                                                                     ║
 * ║  feed:user123 → [(post_999, 1705312800), (post_998, 1705312700), ...]       ║
 * ║                                                                               ║
 * ║  In Redis: ZSET with score = timestamp (or ML ranking score)                ║
 * ║  Command: ZREVRANGE feed:user123 0 19 (get top 20 posts)                    ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 * 
 * This is what gets populated during FANOUT ON WRITE.
 * When user requests their feed, we just read from this cache!
 */
public class NewsFeedCache {
    
    private static final int MAX_FEED_SIZE = 800; // Keep only recent 800 posts per user
    
    // userId -> List of FeedItems (sorted by score, newest first)
    private final Map<Long, List<FeedItem>> feedCache = new ConcurrentHashMap<>();
    
    /**
     * Add a post to a user's feed (called during fanout)
     * In Redis: ZADD feed:{userId} {score} {postId}
     */
    public void addToFeed(long userId, FeedItem item) {
        feedCache.computeIfAbsent(userId, k -> 
            Collections.synchronizedList(new ArrayList<>())).add(0, item);
        
        // Trim to max size (in Redis: ZREMRANGEBYRANK)
        List<FeedItem> feed = feedCache.get(userId);
        if (feed.size() > MAX_FEED_SIZE) {
            synchronized (feed) {
                while (feed.size() > MAX_FEED_SIZE) {
                    feed.remove(feed.size() - 1);
                }
            }
        }
    }
    
    /**
     * Add a post to multiple users' feeds (batch fanout)
     */
    public void addToFeeds(Set<Long> userIds, FeedItem item) {
        for (Long userId : userIds) {
            addToFeed(userId, item);
        }
    }
    
    /**
     * Get user's feed
     * In Redis: ZREVRANGE feed:{userId} {start} {end}
     */
    public List<FeedItem> getFeed(long userId, int offset, int limit) {
        List<FeedItem> feed = feedCache.getOrDefault(userId, Collections.emptyList());
        
        if (offset >= feed.size()) {
            return Collections.emptyList();
        }
        
        int end = Math.min(offset + limit, feed.size());
        return new ArrayList<>(feed.subList(offset, end));
    }
    
    /**
     * Get just the post IDs from user's feed
     */
    public List<Long> getFeedPostIds(long userId, int offset, int limit) {
        return getFeed(userId, offset, limit).stream()
            .map(FeedItem::getPostId)
            .collect(Collectors.toList());
    }
    
    /**
     * Remove a post from a user's feed (if author deletes post)
     */
    public void removeFromFeed(long userId, long postId) {
        List<FeedItem> feed = feedCache.get(userId);
        if (feed != null) {
            feed.removeIf(item -> item.getPostId() == postId);
        }
    }
    
    /**
     * Remove a post from all feeds (expensive operation, used when post is deleted)
     */
    public void removeFromAllFeeds(long postId) {
        for (List<FeedItem> feed : feedCache.values()) {
            feed.removeIf(item -> item.getPostId() == postId);
        }
    }
    
    /**
     * Clear a user's feed (e.g., when user deactivates)
     */
    public void clearFeed(long userId) {
        feedCache.remove(userId);
    }
    
    /**
     * Check if user has a cached feed
     */
    public boolean hasFeed(long userId) {
        return feedCache.containsKey(userId) && !feedCache.get(userId).isEmpty();
    }
    
    /**
     * Get cache statistics
     */
    public String getStats() {
        int totalFeeds = feedCache.size();
        int totalItems = feedCache.values().stream().mapToInt(List::size).sum();
        return String.format("NewsFeedCache: users=%d, totalItems=%d, avgFeedSize=%.1f",
            totalFeeds, totalItems, totalFeeds > 0 ? (double) totalItems / totalFeeds : 0);
    }
}

