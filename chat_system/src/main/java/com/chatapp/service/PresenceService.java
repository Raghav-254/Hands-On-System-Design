package com.chatapp.service;

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
 */
public class PresenceService {
    
    private final Map<Long, PresenceInfo> presenceData;
    private final Map<Long, Set<Long>> subscriptions;
    private final Map<Long, List<Consumer<PresenceUpdate>>> updateHandlers;
    private final ScheduledExecutorService heartbeatChecker;
    
    private static final long HEARTBEAT_INTERVAL_MS = 5000;
    private static final long OFFLINE_THRESHOLD_MS = 30000;
    
    public PresenceService() {
        this.presenceData = new ConcurrentHashMap<>();
        this.subscriptions = new ConcurrentHashMap<>();
        this.updateHandlers = new ConcurrentHashMap<>();
        this.heartbeatChecker = Executors.newSingleThreadScheduledExecutor();
        startHeartbeatChecker();
    }
    
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
    
    public void userOffline(long userId) {
        PresenceInfo info = presenceData.get(userId);
        if (info != null && info.isOnline()) {
            info.setOnline(false);
            info.setLastSeen(System.currentTimeMillis());
            notifySubscribers(userId, PresenceStatus.OFFLINE);
            System.out.printf("[PresenceService] User %d is now OFFLINE%n", userId);
        }
    }
    
    public void heartbeat(long userId) {
        PresenceInfo info = presenceData.get(userId);
        if (info != null) {
            info.setLastHeartbeat(System.currentTimeMillis());
        }
    }
    
    public void subscribe(long subscriberId, long targetUserId) {
        subscriptions.computeIfAbsent(targetUserId, k -> ConcurrentHashMap.newKeySet());
        subscriptions.get(targetUserId).add(subscriberId);
        
        PresenceInfo info = presenceData.get(targetUserId);
        if (info != null) {
            notifySubscriber(subscriberId, targetUserId, info.getStatus());
        }
    }
    
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
    
    public boolean isOnline(long userId) {
        PresenceInfo info = presenceData.get(userId);
        return info != null && info.isOnline();
    }
    
    public PresenceStatus getStatus(long userId) {
        PresenceInfo info = presenceData.get(userId);
        return info != null ? info.getStatus() : PresenceStatus.OFFLINE;
    }
    
    private void startHeartbeatChecker() {
        heartbeatChecker.scheduleAtFixedRate(() -> {
            long now = System.currentTimeMillis();
            for (PresenceInfo info : presenceData.values()) {
                if (info.isOnline() && (now - info.getLastHeartbeat()) > OFFLINE_THRESHOLD_MS) {
                    userOffline(info.getUserId());
                }
            }
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }
    
    public void shutdown() { heartbeatChecker.shutdown(); }
    
    public enum PresenceStatus { ONLINE, AWAY, BUSY, OFFLINE }
    
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
        }
        
        public long getUserId() { return userId; }
        public boolean isOnline() { return online; }
        public void setOnline(boolean online) { this.online = online; }
        public PresenceStatus getStatus() { return status; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public void setLastHeartbeat(long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
        public void setLastSeen(long lastSeen) { this.lastSeen = lastSeen; }
    }
    
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
    }
}

