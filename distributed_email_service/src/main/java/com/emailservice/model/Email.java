package com.emailservice.model;

import java.time.Instant;
import java.util.*;

/**
 * Represents an email message.
 * Metadata is stored in the Metadata DB; body/attachments stored separately.
 */
public class Email {
    private final String emailId;
    private final String fromUser;
    private final List<String> toUsers;
    private final List<String> ccUsers;
    private final List<String> bccUsers;
    private final String subject;
    private final String body;
    private final Instant timestamp;
    private final List<String> attachmentIds;
    private boolean isRead;
    private boolean isSpam;
    private String folderId;   // inbox, sent, drafts, trash, custom

    public Email(String emailId, String fromUser, List<String> toUsers,
                 String subject, String body) {
        this.emailId = emailId;
        this.fromUser = fromUser;
        this.toUsers = toUsers;
        this.ccUsers = new ArrayList<>();
        this.bccUsers = new ArrayList<>();
        this.subject = subject;
        this.body = body;
        this.timestamp = Instant.now();
        this.attachmentIds = new ArrayList<>();
        this.isRead = false;
        this.isSpam = false;
        this.folderId = "inbox";
    }

    public String getEmailId() { return emailId; }
    public String getFromUser() { return fromUser; }
    public List<String> getToUsers() { return toUsers; }
    public List<String> getCcUsers() { return ccUsers; }
    public List<String> getBccUsers() { return bccUsers; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getTimestamp() { return timestamp; }
    public List<String> getAttachmentIds() { return attachmentIds; }
    public boolean isRead() { return isRead; }
    public boolean isSpam() { return isSpam; }
    public String getFolderId() { return folderId; }

    public void markAsRead() { this.isRead = true; }
    public void markAsSpam() { this.isSpam = true; this.folderId = "spam"; }
    public void moveToFolder(String folderId) { this.folderId = folderId; }
    public void addAttachment(String attachmentId) { attachmentIds.add(attachmentId); }

    /** Size estimate in KB (for back-of-envelope) */
    public int estimatedSizeKB() {
        return 50; // average metadata size per book
    }

    @Override
    public String toString() {
        return String.format("Email{id=%s, from=%s, to=%s, subject='%s', folder=%s, read=%s, spam=%s}",
                emailId, fromUser, toUsers, subject, folderId, isRead, isSpam);
    }
}
