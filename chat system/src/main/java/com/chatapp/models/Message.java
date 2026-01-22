package com.chatapp.models;

import java.time.Instant;

/**
 * Message model for 1:1 chat.
 * Schema from the book:
 * - message_id (bigint) - Primary key
 * - message_from (bigint) - Sender user ID
 * - message_to (bigint) - Recipient user ID  
 * - content (text) - Message content
 * - created_at (timestamp) - When message was created
 */
public class Message {
    private final long messageId;
    private final long messageFrom;
    private final long messageTo;
    private final String content;
    private final Instant createdAt;
    
    // Delivery status tracking
    private MessageStatus status;

    public enum MessageStatus {
        SENT,       // Message sent to server
        DELIVERED,  // Message delivered to recipient's device
        READ        // Message read by recipient
    }

    public Message(long messageId, long messageFrom, long messageTo, String content) {
        this.messageId = messageId;
        this.messageFrom = messageFrom;
        this.messageTo = messageTo;
        this.content = content;
        this.createdAt = Instant.now();
        this.status = MessageStatus.SENT;
    }

    public long getMessageId() {
        return messageId;
    }

    public long getMessageFrom() {
        return messageFrom;
    }

    public long getMessageTo() {
        return messageTo;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    @Override
    public String toString() {
        return String.format("Message{id=%d, from=%d, to=%d, content='%s', status=%s, time=%s}",
            messageId, messageFrom, messageTo, 
            content.length() > 20 ? content.substring(0, 20) + "..." : content,
            status, createdAt);
    }
}

