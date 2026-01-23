package com.chatapp.notification;

import com.chatapp.models.Message;

import java.util.*;
import java.util.concurrent.*;

/**
 * Push Notification Service - Sends notifications to offline users.
 * 
 * As shown in Figure 12-12 (step 5.b):
 * When a user is offline, messages are routed to the PN (Push Notification) servers.
 * 
 * Responsibilities:
 * 1. Queue notifications for offline users
 * 2. Integrate with platform-specific services (APNs, FCM)
 * 3. Handle rate limiting and batching
 * 4. Track notification delivery status
 * 
 * In production, this would integrate with:
 * - Apple Push Notification Service (APNs) for iOS
 * - Firebase Cloud Messaging (FCM) for Android
 * - Web Push for browsers
 */
public class PushNotificationService {
    
    // Device tokens for each user (platform -> token)
    private final Map<Long, Map<Platform, String>> deviceTokens;
    
    // Notification queue
    private final BlockingQueue<Notification> notificationQueue;
    
    // Worker thread pool
    private final ExecutorService executor;
    
    // Notification history (for demo/debugging)
    private final List<Notification> sentNotifications;
    
    // Rate limiting per user (max notifications per minute)
    private final Map<Long, RateLimiter> userRateLimiters;
    private static final int MAX_NOTIFICATIONS_PER_MINUTE = 10;
    
    public PushNotificationService() {
        this.deviceTokens = new ConcurrentHashMap<>();
        this.notificationQueue = new LinkedBlockingQueue<>();
        this.executor = Executors.newFixedThreadPool(5);
        this.sentNotifications = new CopyOnWriteArrayList<>();
        this.userRateLimiters = new ConcurrentHashMap<>();
        
        // Start notification processor
        startNotificationProcessor();
    }
    
    // ==================== Device Token Management ====================
    
    /**
     * Register a device token for push notifications.
     * Called when user's device registers for push notifications.
     */
    public void registerDeviceToken(long userId, Platform platform, String token) {
        deviceTokens.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        deviceTokens.get(userId).put(platform, token);
        
        System.out.printf("[PushNotification] Registered %s token for user %d%n", 
            platform, userId);
    }
    
    /**
     * Unregister device token (e.g., on logout).
     */
    public void unregisterDeviceToken(long userId, Platform platform) {
        Map<Platform, String> tokens = deviceTokens.get(userId);
        if (tokens != null) {
            tokens.remove(platform);
        }
    }
    
    // ==================== Notification Sending ====================
    
    /**
     * Queue a notification for an offline user.
     * This is called when a message can't be delivered in real-time.
     */
    public void notifyOfflineUser(Message message) {
        long userId = message.getMessageTo();
        
        // Check rate limit
        RateLimiter limiter = userRateLimiters.computeIfAbsent(userId, 
            k -> new RateLimiter(MAX_NOTIFICATIONS_PER_MINUTE));
        
        if (!limiter.tryAcquire()) {
            System.out.printf("[PushNotification] Rate limited for user %d%n", userId);
            return;
        }
        
        // Create notification
        String title = "New message from User " + message.getMessageFrom();
        String body = truncateContent(message.getContent(), 100);
        
        Notification notification = new Notification(
            userId,
            title,
            body,
            NotificationType.NEW_MESSAGE,
            Map.of("messageId", String.valueOf(message.getMessageId()),
                   "senderId", String.valueOf(message.getMessageFrom()))
        );
        
        notificationQueue.offer(notification);
        System.out.printf("[PushNotification] Queued notification for user %d%n", userId);
    }
    
    /**
     * Send a custom notification.
     */
    public void sendNotification(long userId, String title, String body, 
                                  NotificationType type, Map<String, String> data) {
        Notification notification = new Notification(userId, title, body, type, data);
        notificationQueue.offer(notification);
    }
    
    // ==================== Notification Processing ====================
    
    /**
     * Background processor that sends notifications from the queue.
     */
    private void startNotificationProcessor() {
        executor.submit(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Notification notification = notificationQueue.poll(1, TimeUnit.SECONDS);
                    if (notification != null) {
                        processNotification(notification);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    /**
     * Process and send a single notification.
     */
    private void processNotification(Notification notification) {
        long userId = notification.getUserId();
        Map<Platform, String> tokens = deviceTokens.get(userId);
        
        if (tokens == null || tokens.isEmpty()) {
            System.out.printf("[PushNotification] No device tokens for user %d%n", userId);
            return;
        }
        
        // Send to all registered platforms
        for (Map.Entry<Platform, String> entry : tokens.entrySet()) {
            Platform platform = entry.getKey();
            String token = entry.getValue();
            
            boolean success = sendToPlatform(platform, token, notification);
            notification.setStatus(success ? NotificationStatus.DELIVERED : NotificationStatus.FAILED);
        }
        
        sentNotifications.add(notification);
    }
    
    /**
     * Send notification to a specific platform.
     * In production, this would make actual API calls to APNs/FCM.
     */
    private boolean sendToPlatform(Platform platform, String token, Notification notification) {
        // Simulate sending to push notification provider
        System.out.printf("[PushNotification] Sending to %s (token=%s...): %s%n",
            platform, token.substring(0, Math.min(10, token.length())), notification.getTitle());
        
        // Simulate some latency
        try {
            Thread.sleep(50);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // In production, you would:
        // - For APNs: Use Apple's HTTP/2 API
        // - For FCM: Use Firebase Admin SDK
        // - Handle failures, retries, token invalidation
        
        return true; // Simulated success
    }
    
    // ==================== Helper Methods ====================
    
    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength - 3) + "...";
    }
    
    public List<Notification> getSentNotifications() {
        return new ArrayList<>(sentNotifications);
    }
    
    public void shutdown() {
        executor.shutdown();
    }
    
    // ==================== Data Classes ====================
    
    public enum Platform {
        IOS,     // Apple Push Notification Service
        ANDROID, // Firebase Cloud Messaging
        WEB      // Web Push
    }
    
    public enum NotificationType {
        NEW_MESSAGE,
        GROUP_MESSAGE,
        FRIEND_REQUEST,
        MENTION,
        SYSTEM
    }
    
    public enum NotificationStatus {
        PENDING,
        DELIVERED,
        FAILED
    }
    
    public static class Notification {
        private final long userId;
        private final String title;
        private final String body;
        private final NotificationType type;
        private final Map<String, String> data;
        private final long createdAt;
        private NotificationStatus status;
        
        public Notification(long userId, String title, String body, 
                           NotificationType type, Map<String, String> data) {
            this.userId = userId;
            this.title = title;
            this.body = body;
            this.type = type;
            this.data = data;
            this.createdAt = System.currentTimeMillis();
            this.status = NotificationStatus.PENDING;
        }
        
        public long getUserId() { return userId; }
        public String getTitle() { return title; }
        public String getBody() { return body; }
        public NotificationType getType() { return type; }
        public Map<String, String> getData() { return data; }
        public NotificationStatus getStatus() { return status; }
        public void setStatus(NotificationStatus status) { this.status = status; }
        
        @Override
        public String toString() {
            return String.format("Notification{user=%d, title='%s', type=%s, status=%s}",
                userId, title, type, status);
        }
    }
    
    /**
     * Simple rate limiter for notifications.
     */
    private static class RateLimiter {
        private final int maxPerMinute;
        private final Queue<Long> timestamps;
        
        public RateLimiter(int maxPerMinute) {
            this.maxPerMinute = maxPerMinute;
            this.timestamps = new ConcurrentLinkedQueue<>();
        }
        
        public synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long oneMinuteAgo = now - 60000;
            
            // Remove old timestamps
            while (!timestamps.isEmpty() && timestamps.peek() < oneMinuteAgo) {
                timestamps.poll();
            }
            
            if (timestamps.size() < maxPerMinute) {
                timestamps.offer(now);
                return true;
            }
            return false;
        }
    }
}

