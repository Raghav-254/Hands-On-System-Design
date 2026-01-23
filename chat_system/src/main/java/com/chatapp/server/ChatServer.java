package com.chatapp.server;

import com.chatapp.discovery.ServiceDiscovery;
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
 * 
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  HOW CHAT SERVER USES BOTH PUSH AND PULL PATTERNS                           ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  WHEN SENDING A MESSAGE (User A → User B):                                  ║
 * ║  ─────────────────────────────────────────                                  ║
 * ║  1. Generate unique message ID (Snowflake)                                  ║
 * ║  2. Write to KV Store (persistence) ──────────────┐                         ║
 * ║  3. Write to Message Queue ──────────────────────┐│                         ║
 * ║                                                  ││                         ║
 * ║  Both happen in PARALLEL for reliability:        ││                         ║
 * ║  - If queue fails, message still in DB           ││                         ║
 * ║  - If DB slow, message still delivered real-time ││                         ║
 * ║                                                  ▼▼                         ║
 * ║  WHEN USER CONNECTS (HYBRID APPROACH):                                      ║
 * ║  ─────────────────────────────────────                                      ║
 * ║                                                                              ║
 * ║  Step 1: PULL - Sync missed messages from KV Store                          ║
 * ║          Uses device cursor (cur_max_message_id)                            ║
 * ║          Gets all messages where id > cursor                                ║
 * ║                                                                              ║
 * ║  Step 2: PUSH - Subscribe to Message Queue for real-time                    ║
 * ║          Registers callback with queue                                      ║
 * ║          New messages pushed automatically                                  ║
 * ║                                                                              ║
 * ║  This hybrid ensures:                                                       ║
 * ║  ✓ No missed messages (pull from DB covers offline period)                  ║
 * ║  ✓ Real-time delivery (push via queue subscription)                         ║
 * ║  ✓ Multi-device sync (each device has own cursor)                          ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 * 
 * In production, this would use a real WebSocket library (Netty, Spring WebSocket, etc.)
 */
public class ChatServer {
    
    private final String serverId;
    private final String host;
    private final int port;
    
    // Connected user sessions (simulating WebSocket connections)
    // Key: userId, Value: list of sessions (one per device)
    private final Map<Long, List<UserSession>> userSessions;
    
    // Dependencies
    private final SnowflakeIdGenerator idGenerator;
    private final MessageSyncQueue messageQueue;
    private final MessageStore messageStore;
    private final ServiceDiscovery serviceDiscovery;
    
    // Callback for handling offline user messages (to push notification service)
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
        
        // Register with Service Discovery
        serviceDiscovery.registerChatServer(serverId, host, port);
        
        System.out.printf("[ChatServer-%s] Started at %s:%d%n", serverId, host, port);
    }
    
    /**
     * Set handler for offline messages (to trigger push notifications).
     */
    public void setOfflineMessageHandler(Consumer<Message> handler) {
        this.offlineMessageHandler = handler;
    }
    
    // ==================== Connection Management ====================
    
    /**
     * Handle new WebSocket connection from user.
     * 
     * This uses HYBRID approach (PULL then PUSH):
     * 1. PULL: Fetch missed messages from KV Store (covers offline period)
     * 2. PUSH: Subscribe to queue for real-time delivery going forward
     */
    public UserSession connect(long userId, String deviceId) {
        UserSession session = new UserSession(userId, deviceId, this);
        
        userSessions.computeIfAbsent(userId, k -> new ArrayList<>());
        userSessions.get(userId).add(session);
        
        // Register with service discovery
        serviceDiscovery.registerUserConnection(userId, serverId);
        
        System.out.printf("[ChatServer-%s] User %d connected from device %s%n", 
            serverId, userId, deviceId);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 1: PULL - Sync missed messages from KV Store
        // This catches up on any messages received while user was offline
        // Uses device cursor (cur_max_message_id) to know where to start
        // ══════════════════════════════════════════════════════════════════════
        System.out.printf("[ChatServer-%s] PULL: Syncing missed messages from KV Store...%n", serverId);
        syncPendingMessages(session);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 2: PUSH - Subscribe to Message Queue for real-time delivery
        // From now on, new messages are pushed automatically via callback
        // No polling needed - broker notifies us immediately
        // ══════════════════════════════════════════════════════════════════════
        System.out.printf("[ChatServer-%s] PUSH: Subscribing to message queue for real-time...%n", serverId);
        messageQueue.subscribeUser(userId, msg -> deliverToUser(userId, msg));
        
        return session;
    }
    
    /**
     * Handle WebSocket disconnection.
     */
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
    
    /**
     * Sync pending messages when user reconnects.
     * This implements the multi-device sync shown in Figure 12-13.
     */
    private void syncPendingMessages(UserSession session) {
        long userId = session.getUserId();
        String deviceId = session.getDeviceId();
        
        // Get cursor for this device
        long lastSeenMessageId = messageStore.getDeviceCursor(userId, deviceId);
        
        // Get new messages since cursor
        List<Message> newMessages = messageStore.getNewMessages(userId, lastSeenMessageId);
        
        if (!newMessages.isEmpty()) {
            System.out.printf("[ChatServer-%s] Syncing %d pending messages to user %d device %s%n",
                serverId, newMessages.size(), userId, deviceId);
            
            for (Message msg : newMessages) {
                session.receiveMessage(msg);
            }
        }
    }
    
    // ==================== Message Sending ====================
    
    /**
     * Handle incoming message from user.
     * This is the flow shown in Figure 12-12 (steps 1-3).
     * 
     * ┌─────────────────────────────────────────────────────────────────────────┐
     * │  MESSAGE SEND FLOW - TWO PARALLEL WRITES                               │
     * ├─────────────────────────────────────────────────────────────────────────┤
     * │                                                                         │
     * │  User A sends "Hello Bob"                                              │
     * │         │                                                               │
     * │         ▼                                                               │
     * │  ┌─────────────┐                                                       │
     * │  │ Chat Server │                                                       │
     * │  └──────┬──────┘                                                       │
     * │         │                                                               │
     * │         ├────────────────────────┬──────────────────────┐              │
     * │         │                        │                      │              │
     * │         ▼                        ▼                      ▼              │
     * │  ┌─────────────┐         ┌─────────────┐        ┌─────────────┐        │
     * │  │ ID Generator│         │  KV Store   │        │Message Queue│        │
     * │  │ (Snowflake) │         │ (Cassandra) │        │  (Kafka)    │        │
     * │  └─────────────┘         └─────────────┘        └──────┬──────┘        │
     * │                           (Persistence)                │               │
     * │                                                        │               │
     * │                                              ┌─────────┴─────────┐     │
     * │                                              │                   │     │
     * │                                              ▼                   ▼     │
     * │                                      (If User B online)  (If User B offline)
     * │                                      Push via WebSocket  Push Notification │
     * │                                                                         │
     * └─────────────────────────────────────────────────────────────────────────┘
     */
    public void sendMessage(long senderId, long recipientId, String content) {
        // ══════════════════════════════════════════════════════════════════════
        // STEP 1: Generate unique message ID using Snowflake
        // This ensures globally unique, time-sortable IDs
        // ══════════════════════════════════════════════════════════════════════
        long messageId = idGenerator.nextId();
        Message message = new Message(messageId, senderId, recipientId, content);
        
        System.out.printf("[ChatServer-%s] Processing message %d: %d -> %d%n",
            serverId, messageId, senderId, recipientId);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 2: Write to KV Store (Persistence)
        // This ensures message is never lost, even if queue fails
        // Used for: message history, offline sync, search
        // ══════════════════════════════════════════════════════════════════════
        messageStore.saveDirectMessage(message);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 3: Write to Message Queue (Real-time Delivery)
        // Queue handles routing to recipient's chat server
        // If recipient subscribed (online): PUSH notification immediate
        // If recipient not subscribed: message waits in queue for PULL
        // ══════════════════════════════════════════════════════════════════════
        messageQueue.enqueueDirectMessage(message);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 4: Handle offline recipient (Push Notification)
        // If user not connected, send push notification to their device
        // ══════════════════════════════════════════════════════════════════════
        if (!isUserOnline(recipientId) && offlineMessageHandler != null) {
            System.out.printf("[ChatServer-%s] User %d offline, triggering push notification%n",
                serverId, recipientId);
            offlineMessageHandler.accept(message);
        }
    }
    
    /**
     * Send a group message.
     */
    public void sendGroupMessage(long senderId, long channelId, String content, Channel channel) {
        long messageId = idGenerator.nextId();
        GroupMessage message = new GroupMessage(channelId, messageId, senderId, content);
        
        System.out.printf("[ChatServer-%s] Processing group message %d in channel %d%n",
            serverId, messageId, channelId);
        
        // Store in KV store
        messageStore.saveGroupMessage(message);
        
        // Determine fan-out strategy based on group size
        boolean isLargeGroup = channel.getMemberCount() > 100;
        
        // Enqueue for delivery
        messageQueue.enqueueGroupMessage(message, 
            new ArrayList<>(channel.getMemberIds()), isLargeGroup);
    }
    
    // ==================== Message Delivery ====================
    
    /**
     * Deliver message to a user's connected sessions.
     */
    private void deliverToUser(long userId, Message message) {
        List<UserSession> sessions = userSessions.get(userId);
        
        if (sessions != null && !sessions.isEmpty()) {
            message.setStatus(Message.MessageStatus.DELIVERED);
            
            for (UserSession session : sessions) {
                session.receiveMessage(message);
                
                // Update device cursor
                messageStore.updateDeviceCursor(userId, session.getDeviceId(), 
                    message.getMessageId());
            }
            
            System.out.printf("[ChatServer-%s] Delivered message %d to user %d (%d devices)%n",
                serverId, message.getMessageId(), userId, sessions.size());
        }
    }
    
    // ==================== Status Checks ====================
    
    public boolean isUserOnline(long userId) {
        List<UserSession> sessions = userSessions.get(userId);
        return sessions != null && !sessions.isEmpty();
    }
    
    public int getConnectedUserCount() {
        return userSessions.size();
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public void printStatus() {
        System.out.printf("\n=== ChatServer %s Status ===%n", serverId);
        System.out.printf("Connected users: %d%n", userSessions.size());
        int totalSessions = userSessions.values().stream()
            .mapToInt(List::size).sum();
        System.out.printf("Total sessions (devices): %d%n", totalSessions);
    }
    
    /**
     * Represents a user's connection (WebSocket session).
     * In production, this would wrap an actual WebSocket connection.
     */
    public static class UserSession {
        private final long userId;
        private final String deviceId;
        private final ChatServer server;
        private final List<Message> receivedMessages; // For demo purposes
        
        public UserSession(long userId, String deviceId, ChatServer server) {
            this.userId = userId;
            this.deviceId = deviceId;
            this.server = server;
            this.receivedMessages = new ArrayList<>();
        }
        
        public long getUserId() { return userId; }
        public String getDeviceId() { return deviceId; }
        
        /**
         * Simulate receiving a message over WebSocket.
         */
        public void receiveMessage(Message message) {
            receivedMessages.add(message);
            System.out.printf("    [Session %d:%s] Received: %s%n", 
                userId, deviceId, message);
        }
        
        /**
         * Send a message (delegates to server).
         */
        public void sendMessage(long recipientId, String content) {
            server.sendMessage(userId, recipientId, content);
        }
        
        /**
         * Disconnect from server.
         */
        public void disconnect() {
            server.disconnect(this);
        }
        
        public List<Message> getReceivedMessages() {
            return new ArrayList<>(receivedMessages);
        }
    }
}

