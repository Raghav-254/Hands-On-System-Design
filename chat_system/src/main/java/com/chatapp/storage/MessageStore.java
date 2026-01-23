package com.chatapp.storage;

import com.chatapp.models.GroupMessage;
import com.chatapp.models.Message;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Specialized message storage with support for:
 * 1. 1:1 messages - keyed by conversation pair
 * 2. Group messages - keyed by channel_id
 * 3. Per-user message inbox - for syncing new messages
 * 
 * This simulates the KV store shown in the architecture diagram.
 */
public class MessageStore {
    
    // 1:1 messages: key = "conversation:{minUserId}:{maxUserId}", value = sorted messages
    private final Map<String, TreeSet<Message>> directMessages;
    
    // Group messages: key = channel_id
    private final Map<Long, TreeSet<GroupMessage>> groupMessages;
    
    // User inbox: messages pending delivery for each user
    // This is used for syncing when user comes online or switches devices
    private final Map<Long, TreeSet<Message>> userInbox;
    
    // Track the last message ID seen by each user's device
    // Key: "userId:deviceId", Value: last seen message_id
    private final Map<String, Long> deviceCursors;
    
    public MessageStore() {
        this.directMessages = new ConcurrentHashMap<>();
        this.groupMessages = new ConcurrentHashMap<>();
        this.userInbox = new ConcurrentHashMap<>();
        this.deviceCursors = new ConcurrentHashMap<>();
    }
    
    // ==================== 1:1 Message Operations ====================
    
    /**
     * Store a direct message between two users.
     */
    public void saveDirectMessage(Message message) {
        String conversationKey = getConversationKey(message.getMessageFrom(), message.getMessageTo());
        
        directMessages.computeIfAbsent(conversationKey, k -> 
            new TreeSet<>(Comparator.comparing(Message::getMessageId)));
        directMessages.get(conversationKey).add(message);
        
        // Add to recipient's inbox for sync
        userInbox.computeIfAbsent(message.getMessageTo(), k ->
            new TreeSet<>(Comparator.comparing(Message::getMessageId)));
        userInbox.get(message.getMessageTo()).add(message);
        
        System.out.printf("[MessageStore] Saved direct message %d: %d -> %d%n", 
            message.getMessageId(), message.getMessageFrom(), message.getMessageTo());
    }
    
    /**
     * Get conversation history between two users.
     * @param limit Maximum number of messages to return
     * @param beforeMessageId Only return messages before this ID (for pagination)
     */
    public List<Message> getConversation(long user1, long user2, int limit, Long beforeMessageId) {
        String conversationKey = getConversationKey(user1, user2);
        TreeSet<Message> messages = directMessages.get(conversationKey);
        
        if (messages == null) {
            return Collections.emptyList();
        }
        
        return messages.descendingSet().stream()
            .filter(m -> beforeMessageId == null || m.getMessageId() < beforeMessageId)
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    /**
     * Get new messages for a user since their last seen message ID.
     * This is used for syncing when a device reconnects.
     */
    public List<Message> getNewMessages(long userId, long afterMessageId) {
        TreeSet<Message> inbox = userInbox.get(userId);
        if (inbox == null) {
            return Collections.emptyList();
        }
        
        return inbox.stream()
            .filter(m -> m.getMessageId() > afterMessageId)
            .collect(Collectors.toList());
    }
    
    /**
     * Mark messages as delivered/read and remove from inbox.
     */
    public void acknowledgeMessages(long userId, long upToMessageId) {
        TreeSet<Message> inbox = userInbox.get(userId);
        if (inbox != null) {
            inbox.removeIf(m -> m.getMessageId() <= upToMessageId);
        }
    }
    
    // ==================== Group Message Operations ====================
    
    /**
     * Store a group message.
     */
    public void saveGroupMessage(GroupMessage message) {
        groupMessages.computeIfAbsent(message.getChannelId(), k ->
            new TreeSet<>(Comparator.comparing(GroupMessage::getMessageId)));
        groupMessages.get(message.getChannelId()).add(message);
        
        System.out.printf("[MessageStore] Saved group message %d in channel %d%n",
            message.getMessageId(), message.getChannelId());
    }
    
    /**
     * Get messages from a channel.
     */
    public List<GroupMessage> getChannelMessages(long channelId, int limit, Long beforeMessageId) {
        TreeSet<GroupMessage> messages = groupMessages.get(channelId);
        
        if (messages == null) {
            return Collections.emptyList();
        }
        
        return messages.descendingSet().stream()
            .filter(m -> beforeMessageId == null || m.getMessageId() < beforeMessageId)
            .limit(limit)
            .collect(Collectors.toList());
    }
    
    // ==================== Device Cursor Operations ====================
    
    /**
     * Update the cursor for a user's device (last seen message ID).
     * This enables multi-device sync as shown in Figure 12-13.
     */
    public void updateDeviceCursor(long userId, String deviceId, long messageId) {
        String key = userId + ":" + deviceId;
        deviceCursors.put(key, messageId);
        System.out.printf("[MessageStore] Updated cursor for %s to message %d%n", key, messageId);
    }
    
    /**
     * Get the last seen message ID for a device.
     */
    public long getDeviceCursor(long userId, String deviceId) {
        return deviceCursors.getOrDefault(userId + ":" + deviceId, 0L);
    }
    
    // ==================== Helper Methods ====================
    
    private String getConversationKey(long user1, long user2) {
        // Ensure consistent key regardless of who sends
        long minUser = Math.min(user1, user2);
        long maxUser = Math.max(user1, user2);
        return "conversation:" + minUser + ":" + maxUser;
    }
    
    public void printStats() {
        System.out.println("\n=== MessageStore Stats ===");
        System.out.printf("Direct conversations: %d%n", directMessages.size());
        System.out.printf("Group channels: %d%n", groupMessages.size());
        System.out.printf("Device cursors tracked: %d%n", deviceCursors.size());
        
        int totalDirect = directMessages.values().stream().mapToInt(Set::size).sum();
        int totalGroup = groupMessages.values().stream().mapToInt(Set::size).sum();
        System.out.printf("Total direct messages: %d%n", totalDirect);
        System.out.printf("Total group messages: %d%n", totalGroup);
    }
}

