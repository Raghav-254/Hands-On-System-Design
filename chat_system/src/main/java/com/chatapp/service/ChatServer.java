package com.chatapp.service;

import com.chatapp.idgen.SnowflakeIdGenerator;
import com.chatapp.models.*;
import com.chatapp.queue.MessageSyncQueue;
import com.chatapp.storage.MessageStore;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Chat Server - Handles WebSocket connections and message routing.
 * 
 * Key responsibilities (from Figure 12-8):
 * 1. Maintain WebSocket connections with users
 * 2. Receive messages from senders
 * 3. Route messages to recipients via message queue
 * 4. Deliver messages to connected users in real-time
 */
public class ChatServer {
    
    private final String serverId;
    private final String host;
    private final int port;
    
    // Connected user sessions (simulating WebSocket connections)
    private final Map<Long, List<UserSession>> userSessions;
    
    // Dependencies
    private final SnowflakeIdGenerator idGenerator;
    private final MessageSyncQueue messageQueue;
    private final MessageStore messageStore;
    private final ServiceDiscovery serviceDiscovery;
    
    // Callback for handling offline user messages
    private Consumer<Message> offlineMessageHandler;
    
    public ChatServer(String serverId, String host, int port,
                      SnowflakeIdGenerator idGenerator,
                      MessageSyncQueue messageQueue,
                      MessageStore messageStore,
                      ServiceDiscovery serviceDiscovery) {
        this.serverId = serverId;
        this.host = host;
        this.port = port;
        this.idGenerator = idGenerator;
        this.messageQueue = messageQueue;
        this.messageStore = messageStore;
        this.serviceDiscovery = serviceDiscovery;
        this.userSessions = new ConcurrentHashMap<>();
        
        serviceDiscovery.registerChatServer(serverId, host, port);
        System.out.printf("[ChatServer-%s] Started at %s:%d%n", serverId, host, port);
    }
    
    public void setOfflineMessageHandler(Consumer<Message> handler) {
        this.offlineMessageHandler = handler;
    }
    
    public UserSession connect(long userId, String deviceId) {
        UserSession session = new UserSession(userId, deviceId, this);
        userSessions.computeIfAbsent(userId, k -> new ArrayList<>());
        userSessions.get(userId).add(session);
        
        serviceDiscovery.registerUserConnection(userId, serverId);
        System.out.printf("[ChatServer-%s] User %d connected from device %s%n", 
            serverId, userId, deviceId);
        
        // PULL: Sync missed messages from KV Store
        syncPendingMessages(session);
        
        // PUSH: Subscribe to Message Queue for real-time
        messageQueue.subscribeUser(userId, msg -> deliverToUser(userId, msg));
        
        return session;
    }
    
    public void disconnect(UserSession session) {
        long userId = session.getUserId();
        List<UserSession> sessions = userSessions.get(userId);
        
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                userSessions.remove(userId);
                serviceDiscovery.unregisterUserConnection(userId);
            }
        }
        
        System.out.printf("[ChatServer-%s] User %d disconnected from device %s%n",
            serverId, userId, session.getDeviceId());
    }
    
    private void syncPendingMessages(UserSession session) {
        long userId = session.getUserId();
        String deviceId = session.getDeviceId();
        
        long lastSeenMessageId = messageStore.getDeviceCursor(userId, deviceId);
        List<Message> newMessages = messageStore.getNewMessages(userId, lastSeenMessageId);
        
        if (!newMessages.isEmpty()) {
            System.out.printf("[ChatServer-%s] Syncing %d pending messages to user %d%n",
                serverId, newMessages.size(), userId);
            
            for (Message msg : newMessages) {
                session.receiveMessage(msg);
            }
        }
    }
    
    public void sendMessage(long senderId, long recipientId, String content) {
        long messageId = idGenerator.nextId();
        Message message = new Message(messageId, senderId, recipientId, content);
        
        System.out.printf("[ChatServer-%s] Processing message %d: %d -> %d%n",
            serverId, messageId, senderId, recipientId);
        
        // Write to KV Store (Persistence)
        messageStore.saveDirectMessage(message);
        
        // Write to Message Queue (Real-time Delivery)
        messageQueue.enqueueDirectMessage(message);
        
        // Handle offline recipient
        if (!isUserOnline(recipientId) && offlineMessageHandler != null) {
            System.out.printf("[ChatServer-%s] User %d offline, triggering push notification%n",
                serverId, recipientId);
            offlineMessageHandler.accept(message);
        }
    }
    
    public void sendGroupMessage(long senderId, long channelId, String content, Channel channel) {
        long messageId = idGenerator.nextId();
        GroupMessage message = new GroupMessage(channelId, messageId, senderId, content);
        
        messageStore.saveGroupMessage(message);
        
        boolean isLargeGroup = channel.getMemberCount() > 100;
        messageQueue.enqueueGroupMessage(message, 
            new ArrayList<>(channel.getMemberIds()), isLargeGroup);
    }
    
    private void deliverToUser(long userId, Message message) {
        List<UserSession> sessions = userSessions.get(userId);
        
        if (sessions != null && !sessions.isEmpty()) {
            message.setStatus(Message.MessageStatus.DELIVERED);
            
            for (UserSession session : sessions) {
                session.receiveMessage(message);
                messageStore.updateDeviceCursor(userId, session.getDeviceId(), 
                    message.getMessageId());
            }
        }
    }
    
    public boolean isUserOnline(long userId) {
        List<UserSession> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }
    
    public int getConnectedUserCount() { return userSessions.size(); }
    public String getServerId() { return serverId; }
    
    public static class UserSession {
        private final long userId;
        private final String deviceId;
        private final ChatServer server;
        private final List<Message> receivedMessages;
        
        public UserSession(long userId, String deviceId, ChatServer server) {
            this.userId = userId;
            this.deviceId = deviceId;
            this.server = server;
            this.receivedMessages = new ArrayList<>();
        }
        
        public long getUserId() { return userId; }
        public String getDeviceId() { return deviceId; }
        
        public void receiveMessage(Message message) {
            receivedMessages.add(message);
            System.out.printf("    [Session %d:%s] Received: %s%n", userId, deviceId, message);
        }
        
        public void sendMessage(long recipientId, String content) {
            server.sendMessage(userId, recipientId, content);
        }
        
        public void disconnect() { server.disconnect(this); }
        public List<Message> getReceivedMessages() { return new ArrayList<>(receivedMessages); }
    }
}

