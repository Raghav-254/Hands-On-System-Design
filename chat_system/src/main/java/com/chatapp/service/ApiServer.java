package com.chatapp.service;

import com.chatapp.service.ServiceDiscovery.ChatServerInfo;
import com.chatapp.models.User;
import com.chatapp.models.Channel;
import com.chatapp.storage.MessageStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * API Server - Handles HTTP REST requests.
 * 
 * As shown in Figure 12-8:
 * - Users connect via HTTP to the Load Balancer
 * - Load Balancer routes to API Servers
 * - API Servers handle login, user management, and other REST operations
 */
public class ApiServer {
    
    private final String serverId;
    private final Map<Long, User> users;
    private final Map<String, Long> usernameToId;
    private final Map<Long, Channel> channels;
    private final ServiceDiscovery serviceDiscovery;
    private final MessageStore messageStore;
    
    public ApiServer(String serverId, ServiceDiscovery serviceDiscovery, MessageStore messageStore) {
        this.serverId = serverId;
        this.serviceDiscovery = serviceDiscovery;
        this.messageStore = messageStore;
        this.users = new ConcurrentHashMap<>();
        this.usernameToId = new ConcurrentHashMap<>();
        this.channels = new ConcurrentHashMap<>();
        System.out.printf("[ApiServer-%s] Started%n", serverId);
    }
    
    public LoginResponse login(String username, String password) {
        Long userId = usernameToId.get(username);
        if (userId == null) {
            return new LoginResponse(false, "User not found", null, null);
        }
        
        User user = users.get(userId);
        if (user == null) {
            return new LoginResponse(false, "User not found", null, null);
        }
        
        String sessionToken = UUID.randomUUID().toString();
        ChatServerInfo chatServer = serviceDiscovery.getBestChatServer(userId);
        
        System.out.printf("[ApiServer-%s] Login successful: user=%s, assigned to %s%n",
            serverId, username, chatServer.getServerId());
        
        return new LoginResponse(true, "Login successful", sessionToken, chatServer);
    }
    
    public User registerUser(long userId, String username) {
        User user = new User(userId, username);
        users.put(userId, user);
        usernameToId.put(username, userId);
        System.out.printf("[ApiServer-%s] Registered user: %s (id=%d)%n", serverId, username, userId);
        return user;
    }
    
    public Optional<User> getUser(long userId) {
        return Optional.ofNullable(users.get(userId));
    }
    
    public Channel createChannel(long channelId, String name, long creatorId) {
        Channel channel = new Channel(channelId, name, creatorId);
        channels.put(channelId, channel);
        System.out.printf("[ApiServer-%s] Created channel: %s (id=%d)%n", serverId, name, channelId);
        return channel;
    }
    
    public void addChannelMember(long channelId, long userId) {
        Channel channel = channels.get(channelId);
        if (channel != null) {
            channel.addMember(userId);
        }
    }
    
    public Optional<Channel> getChannel(long channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }
    
    public static class LoginResponse {
        private final boolean success;
        private final String message;
        private final String sessionToken;
        private final ChatServerInfo chatServer;
        
        public LoginResponse(boolean success, String message, String sessionToken, 
                            ChatServerInfo chatServer) {
            this.success = success;
            this.message = message;
            this.sessionToken = sessionToken;
            this.chatServer = chatServer;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public String getSessionToken() { return sessionToken; }
        public ChatServerInfo getChatServer() { return chatServer; }
    }
}

