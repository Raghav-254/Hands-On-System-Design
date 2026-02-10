package com.emailservice.storage;

import com.emailservice.model.Email;
import com.emailservice.model.Folder;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the Metadata Database (Cassandra/Bigtable in production).
 *
 * Stores email metadata (headers, flags, folder assignments).
 * Partitioned by user_id for data locality.
 *
 * Key access patterns:
 * - Get all emails for a user in a folder (sorted by time)
 * - Get a specific email by ID
 * - Filter by read/unread status
 */
public class MetadataDB {
    // user_id → list of emails (simulates partition per user)
    private final Map<String, List<Email>> userEmails = new LinkedHashMap<>();
    // user_id → list of folders
    private final Map<String, List<Folder>> userFolders = new LinkedHashMap<>();
    // email_id → email (global lookup)
    private final Map<String, Email> emailIndex = new LinkedHashMap<>();

    public void initUser(String userId) {
        userEmails.putIfAbsent(userId, new ArrayList<>());
        // Create system folders
        List<Folder> folders = new ArrayList<>();
        folders.add(new Folder("inbox", userId, "Inbox", true));
        folders.add(new Folder("sent", userId, "Sent", true));
        folders.add(new Folder("drafts", userId, "Drafts", true));
        folders.add(new Folder("trash", userId, "Trash", true));
        folders.add(new Folder("spam", userId, "Spam", true));
        userFolders.put(userId, folders);
    }

    public void storeEmail(String userId, Email email) {
        userEmails.computeIfAbsent(userId, k -> new ArrayList<>()).add(email);
        emailIndex.put(email.getEmailId(), email);
    }

    public Email getEmail(String emailId) {
        return emailIndex.get(emailId);
    }

    /** Get all emails for a user in a specific folder, sorted by time (newest first) */
    public List<Email> getEmailsByFolder(String userId, String folderId) {
        List<Email> emails = userEmails.getOrDefault(userId, List.of());
        return emails.stream()
                .filter(e -> e.getFolderId().equals(folderId))
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .collect(Collectors.toList());
    }

    /** Filter emails by read/unread status */
    public List<Email> getEmailsByReadStatus(String userId, String folderId, boolean isRead) {
        return getEmailsByFolder(userId, folderId).stream()
                .filter(e -> e.isRead() == isRead)
                .collect(Collectors.toList());
    }

    public List<Folder> getFolders(String userId) {
        return userFolders.getOrDefault(userId, List.of());
    }

    public void addFolder(String userId, Folder folder) {
        userFolders.computeIfAbsent(userId, k -> new ArrayList<>()).add(folder);
    }

    public int getEmailCount(String userId) {
        return userEmails.getOrDefault(userId, List.of()).size();
    }
}
