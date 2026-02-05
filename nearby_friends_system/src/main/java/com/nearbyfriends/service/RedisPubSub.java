package com.nearbyfriends.service;

import com.nearbyfriends.model.*;
import com.nearbyfriends.cache.LocationCache;
import com.nearbyfriends.storage.*;
import java.util.*;

/**
 * Redis Pub/Sub - message broker for real-time location updates
 * In production: Redis Pub/Sub or message queue
 * 
 * Channel naming: channel:{userId}
 * Each user has their own channel that friends subscribe to
 */
public class RedisPubSub {
    private final Map<String, Set<Subscriber>> subscriptions;
    
    public RedisPubSub() {
        this.subscriptions = new HashMap<>();
    }
    
    /**
     * Publish location update to a user's channel
     */
    public void publish(String channel, LocationUpdate update) {
        Set<Subscriber> subscribers = subscriptions.get(channel);
        
        if (subscribers != null && !subscribers.isEmpty()) {
            System.out.println("[Redis Pub/Sub] Publishing to channel '" + channel + 
                             "' → " + subscribers.size() + " subscribers");
            
            for (Subscriber subscriber : subscribers) {
                subscriber.onMessage(update);
            }
        }
    }
    
    /**
     * Subscribe to a user's channel
     */
    public void subscribe(String channel, Subscriber subscriber) {
        subscriptions.computeIfAbsent(channel, k -> new HashSet<>()).add(subscriber);
        System.out.println("[Redis Pub/Sub] Subscriber added to channel: " + channel);
    }
    
    /**
     * Unsubscribe from a user's channel
     */
    public void unsubscribe(String channel, Subscriber subscriber) {
        Set<Subscriber> subs = subscriptions.get(channel);
        if (subs != null) {
            subs.remove(subscriber);
            System.out.println("[Redis Pub/Sub] Subscriber removed from channel: " + channel);
        }
    }
    
    public static String getUserChannel(long userId) {
        return "channel:" + userId;
    }
    
    /**
     * Subscribe to multiple channels (all friends' channels)
     */
    public void subscribeToFriends(long userId, Set<Long> friendIds, Subscriber subscriber) {
        System.out.println("[Redis Pub/Sub] User " + userId + " subscribing to " + 
                         friendIds.size() + " friend channels");
        
        for (long friendId : friendIds) {
            subscribe(getUserChannel(friendId), subscriber);
        }
    }
    
    public interface Subscriber {
        void onMessage(LocationUpdate update);
    }
}
