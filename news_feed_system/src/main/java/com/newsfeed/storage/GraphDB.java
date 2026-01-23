package com.newsfeed.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GraphDB - Simulates a Graph Database for storing social relationships.
 * 
 * In production, this would be:
 * - Neo4j
 * - Amazon Neptune
 * - JanusGraph
 * - Or a specialized adjacency list in Redis/Cassandra
 * 
 * Stores:
 * - Follower relationships (who follows whom)
 * - Following relationships (who a user follows)
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  GRAPH STRUCTURE                                                             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║    Alice ──follows──▶ Bob                                                    ║
 * ║      │                  │                                                     ║
 * ║      │                  ▼                                                     ║
 * ║      └──follows──▶ Charlie                                                   ║
 * ║                                                                               ║
 * ║  Followers of Bob: [Alice]                                                   ║
 * ║  Following by Alice: [Bob, Charlie]                                          ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class GraphDB {
    
    // userId -> Set of userIds they follow
    private final Map<Long, Set<Long>> following = new ConcurrentHashMap<>();
    
    // userId -> Set of userIds who follow them
    private final Map<Long, Set<Long>> followers = new ConcurrentHashMap<>();
    
    /**
     * User A follows User B
     */
    public void follow(long followerId, long followeeId) {
        following.computeIfAbsent(followerId, k -> ConcurrentHashMap.newKeySet()).add(followeeId);
        followers.computeIfAbsent(followeeId, k -> ConcurrentHashMap.newKeySet()).add(followerId);
        
        System.out.println(String.format("  [GraphDB] User %d now follows User %d", followerId, followeeId));
    }
    
    /**
     * User A unfollows User B
     */
    public void unfollow(long followerId, long followeeId) {
        Set<Long> userFollowing = following.get(followerId);
        if (userFollowing != null) {
            userFollowing.remove(followeeId);
        }
        
        Set<Long> userFollowers = followers.get(followeeId);
        if (userFollowers != null) {
            userFollowers.remove(followerId);
        }
    }
    
    /**
     * Get all followers of a user (people who follow this user)
     * This is used in FANOUT ON WRITE - to push posts to followers
     */
    public Set<Long> getFollowers(long userId) {
        return followers.getOrDefault(userId, Collections.emptySet());
    }
    
    /**
     * Get all users that this user follows
     * This is used in FANOUT ON READ - to pull posts from following
     */
    public Set<Long> getFollowing(long userId) {
        return following.getOrDefault(userId, Collections.emptySet());
    }
    
    /**
     * Get follower count
     */
    public int getFollowerCount(long userId) {
        return followers.getOrDefault(userId, Collections.emptySet()).size();
    }
    
    /**
     * Get following count
     */
    public int getFollowingCount(long userId) {
        return following.getOrDefault(userId, Collections.emptySet()).size();
    }
    
    /**
     * Check if user A follows user B
     */
    public boolean isFollowing(long followerId, long followeeId) {
        Set<Long> userFollowing = following.get(followerId);
        return userFollowing != null && userFollowing.contains(followeeId);
    }
    
    /**
     * Check if user is a celebrity (high follower count)
     * Celebrities use FANOUT ON READ instead of FANOUT ON WRITE
     */
    public boolean isCelebrity(long userId, int threshold) {
        return getFollowerCount(userId) >= threshold;
    }
}

