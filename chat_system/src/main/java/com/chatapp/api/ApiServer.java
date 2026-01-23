package com.chatapp.api;

import com.chatapp.discovery.ServiceDiscovery;
import com.chatapp.discovery.ServiceDiscovery.ChatServerInfo;
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
 * 
 * Key flows:
 * 1. Login: Authenticate user and get chat server assignment
 * 2. User management: Create/update user profiles
 * 3. Channel management: Create/manage groups
 * 4. Message history: Fetch past messages (not real-time)
 */
public class ApiServer {
    
    private final String serverId;
    
    // User data store (in production: database)
    private final Map<Long, User> users;
    private final Map<String, Long> usernameToId;
    
    // Channel/Group data store
    private final Map<Long, Channel> channels;
    
    // Dependencies
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
    
    // ==================== Authentication ====================
    
    /**
     * Login endpoint - Authenticates user and returns chat server to connect to.
     * This is step 1-3 in Figure 12-11.
     */
    public LoginResponse login(String username, String password) {
        // Step 1: Validate credentials (simplified)
        Long userId = usernameToId.get(username);
        if (userId == null) {
            return new LoginResponse(false, "User not found", null, null);
        }
        
        User user = users.get(userId);
        if (user == null) {
            return new LoginResponse(false, "User not found", null, null);
        }
        
        // Step 2: Generate session token (simplified)
        String sessionToken = UUID.randomUUID().toString();
        
        // Step 3: Get chat server from Service Discovery
        ChatServerInfo chatServer = serviceDiscovery.getBestChatServer(userId);
        
        System.out.printf("[ApiServer-%s] Login successful: user=%s, assigned to %s%n",
            serverId, username, chatServer.getServerId());
        
        return new LoginResponse(true, "Login successful", sessionToken, chatServer);
    }
    
    // ==================== User Management ====================
    
    /**
     * Register a new user.
     */
    public User registerUser(long userId, String username) {
        User user = new User(userId, username);
        users.put(userId, user);
        usernameToId.put(username, userId);
        
        System.out.printf("[ApiServer-%s] Registered user: %s (id=%d)%n", 
            serverId, username, userId);
        
        return user;
    }
    
    /**
     * Get user by ID.
     */
    public Optional<User> getUser(long userId) {
        return Optional.ofNullable(users.get(userId));
    }
    
    /**
     * Get user by username.
     */
    public Optional<User> getUserByUsername(String username) {
        Long userId = usernameToId.get(username);
        if (userId != null) {
            return Optional.ofNullable(users.get(userId));
        }
        return Optional.empty();
    }
    
    // ==================== Channel/Group Management ====================
    
    /**
     * Create a new channel/group.
     */
    public Channel createChannel(long channelId, String name, long creatorId) {
        Channel channel = new Channel(channelId, name, creatorId);
        channels.put(channelId, channel);
        
        System.out.printf("[ApiServer-%s] Created channel: %s (id=%d) by user %d%n",
            serverId, name, channelId, creatorId);
        
        return channel;
    }
    
    /**
     * Add member to channel.
     */
    public void addChannelMember(long channelId, long userId) {
        Channel channel = channels.get(channelId);
        if (channel != null) {
            channel.addMember(userId);
            System.out.printf("[ApiServer-%s] Added user %d to channel %d%n",
                serverId, userId, channelId);
        }
    }
    
    /**
     * Get channel by ID.
     */
    public Optional<Channel> getChannel(long channelId) {
        return Optional.ofNullable(channels.get(channelId));
    }
    
    /**
     * Get all channels a user is member of.
     */
    public List<Channel> getUserChannels(long userId) {
        List<Channel> result = new ArrayList<>();
        for (Channel channel : channels.values()) {
            if (channel.isMember(userId)) {
                result.add(channel);
            }
        }
        return result;
    }
    
    // ==================== Message History ====================
    
    /**
     * Get conversation history between two users.
     * This is fetched from KV store (not real-time).
     */
    public ApiResponse getConversationHistory(long user1, long user2, int limit) {
        var messages = messageStore.getConversation(user1, user2, limit, null);
        return new ApiResponse(true, "Fetched " + messages.size() + " messages", messages);
    }
    
    /**
     * Get channel message history.
     */
    public ApiResponse getChannelHistory(long channelId, int limit) {
        var messages = messageStore.getChannelMessages(channelId, limit, null);
        return new ApiResponse(true, "Fetched " + messages.size() + " messages", messages);
    }
    
    // ==================== Response Classes ====================
    
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
        
        @Override
        public String toString() {
            if (success) {
                return String.format("LoginResponse{success=true, chatServer=%s}", 
                    chatServer != null ? chatServer.getWebSocketUrl() : "null");
            }
            return String.format("LoginResponse{success=false, message='%s'}", message);
        }
    }
    
    public static class ApiResponse {
        private final boolean success;
        private final String message;
        private final Object data;
        
        public ApiResponse(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }
        
        public boolean isSuccess() { return success; }
        public String getMessage() { return message; }
        public Object getData() { return data; }
    }
}

