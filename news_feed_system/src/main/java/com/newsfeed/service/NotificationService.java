package com.newsfeed.service;

import com.newsfeed.models.Post;
import com.newsfeed.models.User;
import com.newsfeed.cache.UserCache;
import com.newsfeed.queue.FanoutQueue;
import com.newsfeed.storage.GraphDB;

import java.util.*;
import java.util.concurrent.*;

/**
 * NotificationService - Sends push notifications when users post.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  NOTIFICATION SERVICE - PUSH NOTIFICATIONS                                   ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  ARCHITECTURE: Also a Kafka Consumer (parallel to FanoutService)            ║
 * ║                                                                               ║
 * ║  PostService ──▶ Kafka ("post-events") ──┬──▶ FanoutService (feed update)   ║
 * ║                                          │                                    ║
 * ║                                          └──▶ NotificationService (push)     ║
 * ║                                                        │                      ║
 * ║                                                        ▼                      ║
 * ║                                               ┌────────────────┐              ║
 * ║                                               │  Rate Limiter  │              ║
 * ║                                               │  (don't spam)  │              ║
 * ║                                               └────────┬───────┘              ║
 * ║                                                        │                      ║
 * ║                                     ┌──────────────────┼──────────────────┐   ║
 * ║                                     ▼                  ▼                  ▼   ║
 * ║                               ┌──────────┐      ┌──────────┐      ┌──────────┐║
 * ║                               │   APNs   │      │   FCM    │      │  Email   │║
 * ║                               │  (iOS)   │      │(Android) │      │          │║
 * ║                               └──────────┘      └──────────┘      └──────────┘║
 * ║                                                                               ║
 * ║  WHO GETS NOTIFIED?                                                          ║
 * ║  • "Close friends" only (not all followers)                                  ║
 * ║  • Users who enabled "post notifications" for this author                    ║
 * ║  • Rate limited: 1 notification per author per hour                          ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class NotificationService {
    
    private final GraphDB graphDB;
    private final UserCache userCache;
    private final FanoutQueue eventQueue;
    
    // Rate limiting: userId -> last notification timestamp
    private final Map<Long, Long> lastNotificationTime = new ConcurrentHashMap<>();
    private static final long RATE_LIMIT_MS = 60 * 60 * 1000; // 1 hour between notifications per author
    
    // Notification queue (simulates async sending to APNs/FCM)
    private final BlockingQueue<Notification> notificationQueue = new LinkedBlockingQueue<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    
    // Stats
    private long notificationsSent = 0;
    private long notificationsRateLimited = 0;
    
    public NotificationService(GraphDB graphDB, UserCache userCache, FanoutQueue eventQueue) {
        this.graphDB = graphDB;
        this.userCache = userCache;
        this.eventQueue = eventQueue;
        
        // Register as Kafka consumer (parallel to FanoutService)
        if (eventQueue != null) {
            registerAsEventConsumer();
        }
        
        // Start notification sender
        startNotificationSender();
    }
    
    /**
     * Register as Kafka consumer for "post-events" topic.
     * This runs IN PARALLEL with FanoutService - different consumer group!
     * 
     * In production:
     * @KafkaListener(topics = "post-events", groupId = "notification-service")
     */
    private void registerAsEventConsumer() {
        eventQueue.registerEventConsumer(this::handlePostCreatedEvent);
        System.out.println("[NotificationService] Registered as consumer for 'post-events' topic");
    }
    
    /**
     * Handle PostCreated event from Kafka.
     * Runs in parallel with FanoutService.handlePostCreatedEvent()
     */
    public void handlePostCreatedEvent(FanoutQueue.PostCreatedEvent event) {
        Post post = event.getPost();
        long authorId = post.getAuthorId();
        
        // Check rate limit (don't spam if author posts frequently)
        if (isRateLimited(authorId)) {
            notificationsRateLimited++;
            System.out.println(String.format("  [NotificationService] Rate limited for author %d", authorId));
            return;
        }
        
        // Get followers who want notifications for this author
        Set<Long> notifyFollowers = getFollowersToNotify(authorId);
        
        if (notifyFollowers.isEmpty()) {
            return;
        }
        
        System.out.println(String.format("  [NotificationService] Sending push notifications to %d followers",
            notifyFollowers.size()));
        
        // Get author info for notification text
        User author = userCache.get(authorId);
        String authorName = author != null ? author.getDisplayName() : "Someone you follow";
        
        // Queue notifications
        for (Long followerId : notifyFollowers) {
            Notification notification = new Notification(
                followerId,
                "New post from " + authorName,
                truncate(post.getContent(), 100),
                post.getPostId()
            );
            notificationQueue.offer(notification);
        }
        
        // Update rate limit
        lastNotificationTime.put(authorId, System.currentTimeMillis());
    }
    
    /**
     * Check if we should rate limit notifications for this author
     */
    private boolean isRateLimited(long authorId) {
        Long lastTime = lastNotificationTime.get(authorId);
        if (lastTime == null) {
            return false;
        }
        return (System.currentTimeMillis() - lastTime) < RATE_LIMIT_MS;
    }
    
    /**
     * Get followers who should receive push notifications.
     * Not ALL followers - only those who enabled "post notifications" or are "close friends"
     */
    private Set<Long> getFollowersToNotify(long authorId) {
        Set<Long> allFollowers = graphDB.getFollowers(authorId);
        
        // In production, you'd filter by:
        // 1. Users who enabled "post notifications" for this author
        // 2. "Close friends" relationship
        // 3. Active users (seen in last 7 days)
        // 4. Users who haven't muted this author
        
        // For demo: Just take first 10 followers
        return allFollowers.stream()
            .limit(10)
            .collect(java.util.stream.Collectors.toSet());
    }
    
    /**
     * Background thread that sends notifications to APNs/FCM
     */
    private void startNotificationSender() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Notification notification = notificationQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (notification != null) {
                        sendNotification(notification);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    /**
     * Send notification to user's device(s)
     * In production: Call APNs (iOS) or FCM (Android)
     */
    private void sendNotification(Notification notification) {
        // In production:
        // - Look up user's device tokens from DB
        // - Send to APNs for iOS devices
        // - Send to FCM for Android devices
        // - Track delivery status
        
        System.out.println(String.format("  [Push] → User %d: \"%s\"",
            notification.userId, notification.title));
        
        notificationsSent++;
    }
    
    private String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) return text;
        return text.substring(0, maxLength - 3) + "...";
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    public String getStats() {
        return String.format("NotificationService: sent=%d, rateLimited=%d, pending=%d",
            notificationsSent, notificationsRateLimited, notificationQueue.size());
    }
    
    /**
     * Notification data class
     */
    private static class Notification {
        final long userId;
        final String title;
        final String body;
        final long postId;
        
        Notification(long userId, String title, String body, long postId) {
            this.userId = userId;
            this.title = title;
            this.body = body;
            this.postId = postId;
        }
    }
}

