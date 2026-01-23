package com.chatapp.presence;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Presence Service - Tracks online/offline status of users.
 * 
 * As shown in Figure 12-16:
 * - User A publishes their status to presence servers
 * - Users B, C, D subscribe to channels for User A's status
 * - When User A's status changes, all subscribers are notified
 * 
 * Key features:
 * 1. Heartbeat mechanism for online detection
 * 2. Pub/Sub for status updates
 * 3. Status caching with TTL
 * 
 * In production, this would use Redis Pub/Sub or a dedicated presence service.
 */
public class PresenceService {
    
    // User presence data
    private final Map<Long, PresenceInfo> presenceData;
    
    // Pub/Sub channels for presence updates
    // Key: userId being watched, Value: list of subscribers
    private final Map<Long, Set<Long>> subscriptions;
    
    // Callback handlers for status changes
    private final Map<Long, List<Consumer<PresenceUpdate>>> updateHandlers;
    
    // Heartbeat tracking
    private final ScheduledExecutorService heartbeatChecker;
    private static final long HEARTBEAT_INTERVAL_MS = 5000; // 5 seconds
    private static final long OFFLINE_THRESHOLD_MS = 30000; // 30 seconds without heartbeat = offline
    
    public PresenceService() {
        this.presenceData = new ConcurrentHashMap<>();
        this.subscriptions = new ConcurrentHashMap<>();
        this.updateHandlers = new ConcurrentHashMap<>();
        this.heartbeatChecker = Executors.newSingleThreadScheduledExecutor();
        
        // Start heartbeat checker
        startHeartbeatChecker();
    }
    
    // ==================== Status Updates ====================
    
    /**
     * User goes online.
     * Called when user connects via WebSocket.
     */
    public void userOnline(long userId) {
        PresenceInfo info = presenceData.computeIfAbsent(userId, PresenceInfo::new);
        
        boolean wasOffline = !info.isOnline();
        info.setOnline(true);
        info.setLastHeartbeat(System.currentTimeMillis());
        
        if (wasOffline) {
            notifySubscribers(userId, PresenceStatus.ONLINE);
            System.out.printf("[PresenceService] User %d is now ONLINE%n", userId);
        }
    }
    
    /**
     * User goes offline.
     * Called when user disconnects (gracefully or via timeout).
     */
    public void userOffline(long userId) {
        PresenceInfo info = presenceData.get(userId);
        
        if (info != null && info.isOnline()) {
            info.setOnline(false);
            info.setLastSeen(System.currentTimeMillis());
            notifySubscribers(userId, PresenceStatus.OFFLINE);
            System.out.printf("[PresenceService] User %d is now OFFLINE%n", userId);
        }
    }
    
    /**
     * Update heartbeat from user.
     * Clients should send heartbeats periodically to indicate they're still connected.
     */
    public void heartbeat(long userId) {
        PresenceInfo info = presenceData.get(userId);
        if (info != null) {
            info.setLastHeartbeat(System.currentTimeMillis());
        }
    }
    
    /**
     * Set user status (online, away, busy, etc.)
     */
    public void setStatus(long userId, PresenceStatus status) {
        PresenceInfo info = presenceData.computeIfAbsent(userId, PresenceInfo::new);
        PresenceStatus oldStatus = info.getStatus();
        info.setStatus(status);
        
        if (oldStatus != status) {
            notifySubscribers(userId, status);
            System.out.printf("[PresenceService] User %d status changed: %s -> %s%n", 
                userId, oldStatus, status);
        }
    }
    
    // ==================== Subscription (Pub/Sub) ====================
    
    /**
     * Subscribe to another user's presence updates.
     * This creates a channel as shown in Figure 12-16.
     */
    public void subscribe(long subscriberId, long targetUserId) {
        subscriptions.computeIfAbsent(targetUserId, k -> ConcurrentHashMap.newKeySet());
        subscriptions.get(targetUserId).add(subscriberId);
        
        System.out.printf("[PresenceService] User %d subscribed to user %d's presence%n",
            subscriberId, targetUserId);
        
        // Immediately notify subscriber of current status
        PresenceInfo info = presenceData.get(targetUserId);
        if (info != null) {
            notifySubscriber(subscriberId, targetUserId, info.getStatus());
        }
    }
    
    /**
     * Unsubscribe from a user's presence updates.
     */
    public void unsubscribe(long subscriberId, long targetUserId) {
        Set<Long> subs = subscriptions.get(targetUserId);
        if (subs != null) {
            subs.remove(subscriberId);
        }
    }
    
    /**
     * Register a handler for presence updates.
     */
    public void onPresenceUpdate(long subscriberId, Consumer<PresenceUpdate> handler) {
        updateHandlers.computeIfAbsent(subscriberId, k -> new CopyOnWriteArrayList<>());
        updateHandlers.get(subscriberId).add(handler);
    }
    
    private void notifySubscribers(long userId, PresenceStatus status) {
        Set<Long> subs = subscriptions.get(userId);
        if (subs != null) {
            for (Long subscriberId : subs) {
                notifySubscriber(subscriberId, userId, status);
            }
        }
    }
    
    private void notifySubscriber(long subscriberId, long userId, PresenceStatus status) {
        List<Consumer<PresenceUpdate>> handlers = updateHandlers.get(subscriberId);
        if (handlers != null) {
            PresenceUpdate update = new PresenceUpdate(userId, status, System.currentTimeMillis());
            for (Consumer<PresenceUpdate> handler : handlers) {
                try {
                    handler.accept(update);
                } catch (Exception e) {
                    System.err.printf("[PresenceService] Error notifying subscriber: %s%n", e.getMessage());
                }
            }
        }
    }
    
    // ==================== Query Methods ====================
    
    /**
     * Check if a user is online.
     */
    public boolean isOnline(long userId) {
        PresenceInfo info = presenceData.get(userId);
        return info != null && info.isOnline();
    }
    
    /**
     * Get user's current status.
     */
    public PresenceStatus getStatus(long userId) {
        PresenceInfo info = presenceData.get(userId);
        return info != null ? info.getStatus() : PresenceStatus.OFFLINE;
    }
    
    /**
     * Get last seen time for offline user.
     */
    public long getLastSeen(long userId) {
        PresenceInfo info = presenceData.get(userId);
        return info != null ? info.getLastSeen() : 0;
    }
    
    /**
     * Get presence info for multiple users (batch query).
     */
    public Map<Long, PresenceStatus> getPresenceBatch(List<Long> userIds) {
        Map<Long, PresenceStatus> result = new HashMap<>();
        for (Long userId : userIds) {
            result.put(userId, getStatus(userId));
        }
        return result;
    }
    
    // ==================== Heartbeat Checker ====================
    
    /**
     * Periodically check for users who haven't sent heartbeats.
     */
    private void startHeartbeatChecker() {
        heartbeatChecker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (PresenceInfo info : presenceData.values()) {
                if (info.isOnline() && 
                    (now - info.getLastHeartbeat()) > OFFLINE_THRESHOLD_MS) {
                    // User hasn't sent heartbeat, mark as offline
                    userOffline(info.getUserId());
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    public void shutdown() {
        heartbeatChecker.shutdown();
    }
    
    // ==================== Data Classes ====================
    
    public enum PresenceStatus {
        ONLINE,
        AWAY,
        BUSY,
        OFFLINE
    }
    
    /**
     * Internal presence tracking data.
     */
    private static class PresenceInfo {
        private final long userId;
        private volatile boolean online;
        private volatile PresenceStatus status;
        private volatile long lastHeartbeat;
        private volatile long lastSeen;
        
        public PresenceInfo(long userId) {
            this.userId = userId;
            this.online = false;
            this.status = PresenceStatus.OFFLINE;
            this.lastHeartbeat = 0;
            this.lastSeen = 0;
        }
        
        public long getUserId() { return userId; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public PresenceStatus getStatus() { return status; }
        public void setStatus(PresenceStatus status) { 
            this.status = status;
            if (status == PresenceStatus.ONLINE) {
                this.online = true;
            } else if (status == PresenceStatus.OFFLINE) {
                this.online = false;
            }
        }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public long getLastSeen() { return lastSeen; }
        public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    }
    
    /**
     * Presence update notification.
     */
    public static class PresenceUpdate {
        private final long userId;
        private final PresenceStatus status;
        private final long timestamp;
        
        public PresenceUpdate(long userId, PresenceStatus status, long timestamp) {
            this.userId = userId;
            this.status = status;
            this.timestamp = timestamp;
        }
        
        public long getUserId() { return userId; }
        public PresenceStatus getStatus() { return status; }
        public long getTimestamp() { return timestamp; }
        
        @Override
        public String toString() {
            return String.format("PresenceUpdate{user=%d, status=%s}", userId, status);
        }
    }
}

