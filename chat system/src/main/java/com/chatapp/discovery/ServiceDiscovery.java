package com.chatapp.discovery;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Service Discovery - Simulates Zookeeper for chat server assignment.
 * 
 * As shown in Figure 12-11:
 * 1. User logs in via API server
 * 2. API server queries Service Discovery for best chat server
 * 3. Service Discovery returns a chat server based on load
 * 4. User connects to assigned chat server via WebSocket
 * 
 * Responsibilities:
 * - Register/deregister chat servers
 * - Track server health and load
 * - Assign users to servers (load balancing)
 * - Track user-to-server mappings
 */
public class ServiceDiscovery {
    
    // Registered chat servers
    private final Map<String, ChatServerInfo> chatServers;
    
    // User to chat server mapping (which server is user connected to)
    private final Map<Long, String> userServerMapping;
    
    // Round-robin counter for load balancing
    private final AtomicInteger roundRobinCounter;
    
    public ServiceDiscovery() {
        this.chatServers = new ConcurrentHashMap<>();
        this.userServerMapping = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
    }
    
    /**
     * Register a chat server with Service Discovery.
     * In production, chat servers would register themselves on startup.
     */
    public void registerChatServer(String serverId, String host, int port) {
        ChatServerInfo info = new ChatServerInfo(serverId, host, port);
        chatServers.put(serverId, info);
        System.out.printf("[ServiceDiscovery] Registered chat server: %s at %s:%d%n", 
            serverId, host, port);
    }
    
    /**
     * Deregister a chat server (e.g., during shutdown or failure).
     */
    public void deregisterChatServer(String serverId) {
        chatServers.remove(serverId);
        System.out.printf("[ServiceDiscovery] Deregistered chat server: %s%n", serverId);
        
        // Users connected to this server need to be reassigned
        userServerMapping.entrySet().removeIf(e -> e.getValue().equals(serverId));
    }
    
    /**
     * Get the best chat server for a user.
     * Uses load-aware selection strategy.
     */
    public ChatServerInfo getBestChatServer(long userId) {
        if (chatServers.isEmpty()) {
            throw new IllegalStateException("No chat servers available");
        }
        
        // Check if user already has an assigned server
        String existingServer = userServerMapping.get(userId);
        if (existingServer != null && chatServers.containsKey(existingServer)) {
            return chatServers.get(existingServer);
        }
        
        // Load balancing: pick server with lowest connection count
        ChatServerInfo bestServer = chatServers.values().stream()
            .min(Comparator.comparing(ChatServerInfo::getActiveConnections))
            .orElseThrow(() -> new IllegalStateException("No chat servers available"));
        
        System.out.printf("[ServiceDiscovery] Assigned user %d to server %s%n", 
            userId, bestServer.getServerId());
        
        return bestServer;
    }
    
    /**
     * Record that a user is connected to a specific chat server.
     */
    public void registerUserConnection(long userId, String serverId) {
        userServerMapping.put(userId, serverId);
        ChatServerInfo server = chatServers.get(serverId);
        if (server != null) {
            server.incrementConnections();
        }
        System.out.printf("[ServiceDiscovery] User %d connected to server %s%n", userId, serverId);
    }
    
    /**
     * Record that a user disconnected.
     */
    public void unregisterUserConnection(long userId) {
        String serverId = userServerMapping.remove(userId);
        if (serverId != null) {
            ChatServerInfo server = chatServers.get(serverId);
            if (server != null) {
                server.decrementConnections();
            }
        }
        System.out.printf("[ServiceDiscovery] User %d disconnected%n", userId);
    }
    
    /**
     * Find which server a user is connected to.
     * Used when sending messages to route to correct server.
     */
    public Optional<ChatServerInfo> findUserServer(long userId) {
        String serverId = userServerMapping.get(userId);
        if (serverId != null) {
            return Optional.ofNullable(chatServers.get(serverId));
        }
        return Optional.empty();
    }
    
    /**
     * Get all registered chat servers.
     */
    public List<ChatServerInfo> getAllChatServers() {
        return new ArrayList<>(chatServers.values());
    }
    
    /**
     * Health check - update server status.
     */
    public void updateServerHealth(String serverId, boolean healthy) {
        ChatServerInfo server = chatServers.get(serverId);
        if (server != null) {
            server.setHealthy(healthy);
            if (!healthy) {
                System.out.printf("[ServiceDiscovery] Server %s marked unhealthy%n", serverId);
            }
        }
    }
    
    public void printStatus() {
        System.out.println("\n=== Service Discovery Status ===");
        System.out.printf("Registered servers: %d%n", chatServers.size());
        System.out.printf("Active user connections: %d%n", userServerMapping.size());
        for (ChatServerInfo server : chatServers.values()) {
            System.out.printf("  %s: %d connections, healthy=%s%n", 
                server.getServerId(), server.getActiveConnections(), server.isHealthy());
        }
    }
    
    /**
     * Information about a chat server.
     */
    public static class ChatServerInfo {
        private final String serverId;
        private final String host;
        private final int port;
        private final AtomicInteger activeConnections;
        private volatile boolean healthy;
        
        public ChatServerInfo(String serverId, String host, int port) {
            this.serverId = serverId;
            this.host = host;
            this.port = port;
            this.activeConnections = new AtomicInteger(0);
            this.healthy = true;
        }
        
        public String getServerId() { return serverId; }
        public String getHost() { return host; }
        public int getPort() { return port; }
        public int getActiveConnections() { return activeConnections.get(); }
        public boolean isHealthy() { return healthy; }
        public void setHealthy(boolean healthy) { this.healthy = healthy; }
        
        public void incrementConnections() { activeConnections.incrementAndGet(); }
        public void decrementConnections() { activeConnections.decrementAndGet(); }
        
        public String getWebSocketUrl() {
            return String.format("ws://%s:%d/chat", host, port);
        }
        
        @Override
        public String toString() {
            return String.format("ChatServer{id='%s', url='%s:%d', connections=%d}", 
                serverId, host, port, activeConnections.get());
        }
    }
}

