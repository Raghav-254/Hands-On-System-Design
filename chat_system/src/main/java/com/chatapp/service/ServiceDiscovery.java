package com.chatapp.service;

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
 */
public class ServiceDiscovery {
    
    private final Map<String, ChatServerInfo> chatServers;
    private final Map<Long, String> userServerMapping;
    private final AtomicInteger roundRobinCounter;
    
    public ServiceDiscovery() {
        this.chatServers = new ConcurrentHashMap<>();
        this.userServerMapping = new ConcurrentHashMap<>();
        this.roundRobinCounter = new AtomicInteger(0);
    }
    
    public void registerChatServer(String serverId, String host, int port) {
        ChatServerInfo info = new ChatServerInfo(serverId, host, port);
        chatServers.put(serverId, info);
        System.out.printf("[ServiceDiscovery] Registered chat server: %s at %s:%d%n", 
            serverId, host, port);
    }
    
    public void deregisterChatServer(String serverId) {
        chatServers.remove(serverId);
        userServerMapping.entrySet().removeIf(e -> e.getValue().equals(serverId));
    }
    
    public ChatServerInfo getBestChatServer(long userId) {
        if (chatServers.isEmpty()) {
            throw new IllegalStateException("No chat servers available");
        }
        
        String existingServer = userServerMapping.get(userId);
        if (existingServer != null && chatServers.containsKey(existingServer)) {
            return chatServers.get(existingServer);
        }
        
        ChatServerInfo bestServer = chatServers.values().stream()
            .min(Comparator.comparing(ChatServerInfo::getActiveConnections))
            .orElseThrow(() -> new IllegalStateException("No chat servers available"));
        
        return bestServer;
    }
    
    public void registerUserConnection(long userId, String serverId) {
        userServerMapping.put(userId, serverId);
        ChatServerInfo server = chatServers.get(serverId);
        if (server != null) {
            server.incrementConnections();
        }
    }
    
    public void unregisterUserConnection(long userId) {
        String serverId = userServerMapping.remove(userId);
        if (serverId != null) {
            ChatServerInfo server = chatServers.get(serverId);
            if (server != null) {
                server.decrementConnections();
            }
        }
    }
    
    public Optional<ChatServerInfo> findUserServer(long userId) {
        String serverId = userServerMapping.get(userId);
        if (serverId != null) {
            return Optional.ofNullable(chatServers.get(serverId));
        }
        return Optional.empty();
    }
    
    public List<ChatServerInfo> getAllChatServers() {
        return new ArrayList<>(chatServers.values());
    }
    
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
        
        public void incrementConnections() { activeConnections.incrementAndGet(); }
        public void decrementConnections() { activeConnections.decrementAndGet(); }
        
        public String getWebSocketUrl() {
            return String.format("ws://%s:%d/chat", host, port);
        }
    }
}

