package com.nearbyfriends.storage;

import com.nearbyfriends.model.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * User Database - stores user profiles and friendship graph
 * In production: PostgreSQL/MySQL with indexed friendship table
 */
public class UserDB {
    private final Map<Long, User> users;
    private final Map<Long, Set<Long>> friendships; // bidirectional
    
    public UserDB() {
        this.users = new ConcurrentHashMap<>();
        this.friendships = new ConcurrentHashMap<>();
    }
    
    public void saveUser(User user) {
        users.put(user.getUserId(), user);
        System.out.println("[UserDB] Saved user: " + user.getName());
    }
    
    public User getUser(long userId) {
        return users.get(userId);
    }
    
    public Set<Long> getFriends(long userId) {
        return friendships.getOrDefault(userId, Collections.emptySet());
    }
    
    public void addFriendship(long userId1, long userId2) {
        friendships.computeIfAbsent(userId1, k -> ConcurrentHashMap.newKeySet()).add(userId2);
        friendships.computeIfAbsent(userId2, k -> ConcurrentHashMap.newKeySet()).add(userId1);
        
        User user1 = users.get(userId1);
        User user2 = users.get(userId2);
        if (user1 != null) user1.addFriend(userId2);
        if (user2 != null) user2.addFriend(userId1);
        
        System.out.println("[UserDB] Added friendship: " + userId1 + " <-> " + userId2);
    }
}
