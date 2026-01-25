package com.googledrive.model;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

/**
 * Represents file metadata stored in Metadata DB.
 * Actual file content (blocks) stored separately in Cloud Storage.
 */
public class FileMetadata {
    private final String fileId;
    private final String userId;
    private final String workspaceId;
    private String fileName;
    private String relativePath;
    private boolean isDirectory;
    private int latestVersion;
    private String checksum;
    private long fileSize;
    private Instant createdAt;
    private Instant lastModified;
    private FileStatus status;
    private List<FileVersion> versions;
    
    public enum FileStatus {
        PENDING,    // Upload in progress
        UPLOADED,   // Upload complete
        SYNCING,    // Syncing to other devices
        SYNCED,     // All devices synced
        DELETED     // Soft deleted (in trash)
    }
    
    public FileMetadata(String fileId, String userId, String workspaceId, 
                        String fileName, String relativePath) {
        this.fileId = fileId;
        this.userId = userId;
        this.workspaceId = workspaceId;
        this.fileName = fileName;
        this.relativePath = relativePath;
        this.isDirectory = false;
        this.latestVersion = 0;
        this.createdAt = Instant.now();
        this.lastModified = Instant.now();
        this.status = FileStatus.PENDING;
        this.versions = new ArrayList<>();
    }
    
    public void addVersion(FileVersion version) {
        this.versions.add(version);
        this.latestVersion = version.getVersionNumber();
        this.lastModified = version.getModifiedAt();
        this.checksum = version.getChecksum();
        this.fileSize = version.getFileSize();
    }
    
    // Getters
    public String getFileId() { return fileId; }
    public String getUserId() { return userId; }
    public String getWorkspaceId() { return workspaceId; }
    public String getFileName() { return fileName; }
    public String getRelativePath() { return relativePath; }
    public String getFullPath() { return relativePath + fileName; }
    public boolean isDirectory() { return isDirectory; }
    public int getLatestVersion() { return latestVersion; }
    public String getChecksum() { return checksum; }
    public long getFileSize() { return fileSize; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastModified() { return lastModified; }
    public FileStatus getStatus() { return status; }
    public List<FileVersion> getVersions() { return versions; }
    
    // Setters
    public void setFileName(String fileName) { this.fileName = fileName; }
    public void setStatus(FileStatus status) { this.status = status; }
    public void setDirectory(boolean directory) { isDirectory = directory; }
    
    @Override
    public String toString() {
        return String.format("File[%s, path=%s, v%d, size=%d, status=%s]",
            fileName, getFullPath(), latestVersion, fileSize, status);
    }
}

