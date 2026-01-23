package com.chatapp.service;

import com.chatapp.models.Message;

import java.util.*;
import java.util.concurrent.*;

/**
 * Push Notification Service - Sends notifications to offline users.
 * 
 * As shown in Figure 12-12 (step 5.b):
 * When a user is offline, messages are routed to the PN (Push Notification) servers.
 */
public class PushNotificationService {
    
    private final Map<Long, Map<Platform, String>> deviceTokens;
    private final BlockingQueue<Notification> notificationQueue;
    private final ExecutorService executor;
    private final List<Notification> sentNotifications;
    private final Map<Long, RateLimiter> userRateLimiters;
    
    private static final int MAX_NOTIFICATIONS_PER_MINUTE = 10;
    
    public PushNotificationService() {
        this.deviceTokens = new ConcurrentHashMap<>();
        this.notificationQueue = new LinkedBlockingQueue<>();
        this.executor = Executors.newFixedThreadPool(5);
        this.sentNotifications = new CopyOnWriteArrayList<>();
        this.userRateLimiters = new ConcurrentHashMap<>();
        startNotificationProcessor();
    }
    
    public void registerDeviceToken(long userId, Platform platform, String token) {
        deviceTokens.computeIfAbsent(userId, k -> new ConcurrentHashMap<>());
        deviceTokens.get(userId).put(platform, token);
        System.out.printf("[PushNotification] Registered %s token for user %d%n", platform, userId);
    }
    
    public void notifyOfflineUser(Message message) {
        long userId = message.getMessageTo();
        
        RateLimiter limiter = userRateLimiters.computeIfAbsent(userId, 
            k -> new RateLimiter(MAX_NOTIFICATIONS_PER_MINUTE));
        
        if (!limiter.tryAcquire()) {
            System.out.printf("[PushNotification] Rate limited for user %d%n", userId);
            return;
        }
        
        String title = "New message from User " + message.getMessageFrom();
        String body = truncateContent(message.getContent(), 100);
        
        Notification notification = new Notification(
            userId, title, body, NotificationType.NEW_MESSAGE,
            Map.of("messageId", String.valueOf(message.getMessageId()),
                   "senderId", String.valueOf(message.getMessageFrom()))
        );
        
        notificationQueue.offer(notification);
        System.out.printf("[PushNotification] Queued notification for user %d%n", userId);
    }
    
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
    
    private void processNotification(Notification notification) {
        long userId = notification.getUserId();
        Map<Platform, String> tokens = deviceTokens.get(userId);
        
        if (tokens == null || tokens.isEmpty()) {
            System.out.printf("[PushNotification] No device tokens for user %d%n", userId);
            return;
        }
        
        for (Map.Entry<Platform, String> entry : tokens.entrySet()) {
            boolean success = sendToPlatform(entry.getKey(), entry.getValue(), notification);
            notification.setStatus(success ? NotificationStatus.DELIVERED : NotificationStatus.FAILED);
        }
        
        sentNotifications.add(notification);
    }
    
    private boolean sendToPlatform(Platform platform, String token, Notification notification) {
        System.out.printf("[PushNotification] Sending to %s: %s%n", platform, notification.getTitle());
        return true;
    }
    
    private String truncateContent(String content, int maxLength) {
        if (content.length() <= maxLength) return content;
        return content.substring(0, maxLength - 3) + "...";
    }
    
    public void shutdown() { executor.shutdown(); }
    
    public enum Platform { IOS, ANDROID, WEB }
    public enum NotificationType { NEW_MESSAGE, GROUP_MESSAGE, FRIEND_REQUEST, MENTION, SYSTEM }
    public enum NotificationStatus { PENDING, DELIVERED, FAILED }
    
    public static class Notification {
        private final long userId;
        private final String title;
        private final String body;
        private final NotificationType type;
        private final Map<String, String> data;
        private NotificationStatus status;
        
        public Notification(long userId, String title, String body, 
                           NotificationType type, Map<String, String> data) {
            this.userId = userId;
            this.title = title;
            this.body = body;
            this.type = type;
            this.data = data;
            this.status = NotificationStatus.PENDING;
        }
        
        public long getUserId() { return userId; }
        public String getTitle() { return title; }
        public void setStatus(NotificationStatus status) { this.status = status; }
    }
    
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

