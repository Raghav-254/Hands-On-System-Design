package com.googledrive.service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Notification Service - Real-time sync via Long Polling.
 * 
 * Why Long Polling (not WebSocket)?
 * - File sync is NOT real-time like chat
 * - Seconds of delay is acceptable
 * - Works better through firewalls/proxies
 * - Simpler than maintaining persistent connections
 */
public class NotificationService {
    
    // Pending long-poll requests per device
    private final Map<String, CompletableFuture<List<ChangeEvent>>> pendingPolls = 
        new ConcurrentHashMap<>();
    
    // Changes that occurred while device was offline
    private final Map<String, Queue<ChangeEvent>> offlineQueues = 
        new ConcurrentHashMap<>();
    
    // Registered devices per user
    private final Map<String, Set<String>> userDevices = new ConcurrentHashMap<>();
    
    public static class ChangeEvent {
        public final String fileId;
        public final String action;  // created, modified, deleted, shared
        public final int version;
        public final Instant timestamp;
        
        public ChangeEvent(String fileId, String action, int version) {
            this.fileId = fileId;
            this.action = action;
            this.version = version;
            this.timestamp = Instant.now();
        }
        
        @Override
        public String toString() {
            return String.format("Change[file=%s, action=%s, v%d]", fileId, action, version);
        }
    }
    
    /**
     * Register a device for notifications.
     */
    public void registerDevice(String userId, String deviceId) {
        userDevices.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                   .add(deviceId);
        offlineQueues.putIfAbsent(deviceId, new ConcurrentLinkedQueue<>());
        System.out.printf("Device registered: %s for user %s%n", deviceId, userId);
    }
    
    /**
     * Long polling endpoint - blocks until changes available or timeout.
     */
    public List<ChangeEvent> pollForChanges(String deviceId, int timeoutSeconds) {
        System.out.printf("Long poll started: device=%s, timeout=%ds%n", deviceId, timeoutSeconds);
        
        // First, return any queued changes from when device was offline
        Queue<ChangeEvent> offlineQueue = offlineQueues.get(deviceId);
        if (offlineQueue != null && !offlineQueue.isEmpty()) {
            List<ChangeEvent> changes = new ArrayList<>();
            ChangeEvent event;
            while ((event = offlineQueue.poll()) != null) {
                changes.add(event);
            }
            System.out.printf("Returning %d queued changes for device %s%n", 
                changes.size(), deviceId);
            return changes;
        }
        
        // Otherwise, wait for new changes
        CompletableFuture<List<ChangeEvent>> future = new CompletableFuture<>();
        pendingPolls.put(deviceId, future);
        
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // No changes within timeout - that's OK, client will reconnect
            pendingPolls.remove(deviceId);
            return Collections.emptyList();
        } catch (Exception e) {
            throw new RuntimeException("Poll failed", e);
        }
    }
    
    /**
     * Notify all devices (except source) about a file change.
     */
    public void notifyFileChange(String userId, String sourceDeviceId, 
                                  String fileId, String action, int version) {
        ChangeEvent event = new ChangeEvent(fileId, action, version);
        System.out.printf("Notifying change: %s (excluding device %s)%n", event, sourceDeviceId);
        
        Set<String> devices = userDevices.get(userId);
        if (devices == null) return;
        
        for (String deviceId : devices) {
            if (deviceId.equals(sourceDeviceId)) {
                continue; // Don't notify the source device
            }
            
            // Check if device has pending long poll
            CompletableFuture<List<ChangeEvent>> poll = pendingPolls.remove(deviceId);
            if (poll != null) {
                // Device is connected - send immediately
                poll.complete(Collections.singletonList(event));
                System.out.printf("  → Sent to device %s (long poll)%n", deviceId);
            } else {
                // Device is offline - queue for later
                offlineQueues.computeIfAbsent(deviceId, k -> new ConcurrentLinkedQueue<>())
                             .add(event);
                System.out.printf("  → Queued for device %s (offline)%n", deviceId);
            }
        }
    }
    
    public void printStats() {
        System.out.println("\n=== Notification Service Stats ===");
        System.out.printf("Active long polls: %d%n", pendingPolls.size());
        System.out.printf("Registered users: %d%n", userDevices.size());
        int totalOfflineEvents = offlineQueues.values().stream()
            .mapToInt(Queue::size).sum();
        System.out.printf("Queued offline events: %d%n", totalOfflineEvents);
    }
}

