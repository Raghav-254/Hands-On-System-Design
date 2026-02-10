package com.emailservice.model;

/**
 * Represents an email folder (inbox, sent, drafts, trash, or custom).
 */
public class Folder {
    private final String folderId;
    private final String userId;
    private final String name;
    private final boolean isSystem;  // system folders can't be deleted

    public Folder(String folderId, String userId, String name, boolean isSystem) {
        this.folderId = folderId;
        this.userId = userId;
        this.name = name;
        this.isSystem = isSystem;
    }

    public String getFolderId() { return folderId; }
    public String getUserId() { return userId; }
    public String getName() { return name; }
    public boolean isSystem() { return isSystem; }

    @Override
    public String toString() {
        return String.format("Folder{id=%s, user=%s, name='%s', system=%s}",
                folderId, userId, name, isSystem);
    }
}
