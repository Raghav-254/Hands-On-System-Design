package com.chatapp.models;

import java.time.Instant;

/**
 * GroupMessage model for group chat.
 * Schema from the book:
 * - channel_id (bigint) - Part of composite primary key, partition key
 * - message_id (bigint) - Part of composite primary key
 * - user_id (bigint) - Sender user ID
 * - content (text) - Message content
 * - created_at (timestamp) - When message was created
 * 
 * NOTE: channel_id is the partition key because all queries in a group chat
 * operate within a single channel/group.
 */
public class GroupMessage {
    private final long channelId;  // Group/Channel ID
    private final long messageId;
    private final long userId;     // Sender
    private final String content;
    private final Instant createdAt;

    public GroupMessage(long channelId, long messageId, long userId, String content) {
        this.channelId = channelId;
        this.messageId = messageId;
        this.userId = userId;
        this.content = content;
        this.createdAt = Instant.now();
    }

    // Composite key for lookups
    public String getCompositeKey() {
        return channelId + ":" + messageId;
    }

    public long getChannelId() {
        return channelId;
    }

    public long getMessageId() {
        return messageId;
    }

    public long getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return String.format("GroupMessage{channel=%d, msgId=%d, sender=%d, content='%s', time=%s}",
            channelId, messageId, userId,
            content.length() > 20 ? content.substring(0, 20) + "..." : content,
            createdAt);
    }
}

