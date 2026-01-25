package com.googledrive.model;

import java.time.Instant;
import java.util.List;
import java.util.ArrayList;

/**
 * Represents a specific version of a file.
 * Each edit creates a new version, enabling version history and restore.
 */
public class FileVersion {
    private final String versionId;
    private final String fileId;
    private final String deviceId;          // Which device made this change
    private final int versionNumber;
    private final String checksum;
    private final long fileSize;
    private final Instant modifiedAt;
    private final List<String> blockHashes; // Ordered list of block hashes
    
    public FileVersion(String versionId, String fileId, String deviceId,
                       int versionNumber, String checksum, long fileSize,
                       List<String> blockHashes) {
        this.versionId = versionId;
        this.fileId = fileId;
        this.deviceId = deviceId;
        this.versionNumber = versionNumber;
        this.checksum = checksum;
        this.fileSize = fileSize;
        this.modifiedAt = Instant.now();
        this.blockHashes = new ArrayList<>(blockHashes);
    }
    
    public String getVersionId() { return versionId; }
    public String getFileId() { return fileId; }
    public String getDeviceId() { return deviceId; }
    public int getVersionNumber() { return versionNumber; }
    public String getChecksum() { return checksum; }
    public long getFileSize() { return fileSize; }
    public Instant getModifiedAt() { return modifiedAt; }
    public List<String> getBlockHashes() { return blockHashes; }
    
    @Override
    public String toString() {
        return String.format("Version[v%d, device=%s, blocks=%d, size=%d]",
            versionNumber, deviceId, blockHashes.size(), fileSize);
    }
}

