package com.objectstorage.model;

import java.time.Instant;
import java.util.*;

/**
 * Metadata for a stored object.
 * Stored in the Metadata DB. Points to the actual data via object_id (UUID).
 */
public class ObjectMetadata {
    private final String bucketName;
    private final String objectName;     // key, e.g., "photos/cat.jpg"
    private final String objectId;       // UUID — unique ID for this version
    private final long sizeBytes;
    private final String contentType;
    private final int versionId;
    private final boolean isDeleteMarker; // soft delete for versioned buckets
    private final String checksum;       // for correctness verification
    private final Instant createdAt;

    public ObjectMetadata(String bucketName, String objectName, String objectId,
                          long sizeBytes, String contentType, int versionId,
                          boolean isDeleteMarker) {
        this.bucketName = bucketName;
        this.objectName = objectName;
        this.objectId = objectId;
        this.sizeBytes = sizeBytes;
        this.contentType = contentType;
        this.versionId = versionId;
        this.isDeleteMarker = isDeleteMarker;
        this.checksum = "sha256:" + Integer.toHexString(Objects.hash(objectId, sizeBytes));
        this.createdAt = Instant.now();
    }

    public String getBucketName() { return bucketName; }
    public String getObjectName() { return objectName; }
    public String getObjectId() { return objectId; }
    public long getSizeBytes() { return sizeBytes; }
    public String getContentType() { return contentType; }
    public int getVersionId() { return versionId; }
    public boolean isDeleteMarker() { return isDeleteMarker; }
    public String getChecksum() { return checksum; }
    public Instant getCreatedAt() { return createdAt; }

    public String getSizeFormatted() {
        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return (sizeBytes / 1024) + " KB";
        if (sizeBytes < 1024 * 1024 * 1024) return (sizeBytes / (1024 * 1024)) + " MB";
        return (sizeBytes / (1024 * 1024 * 1024)) + " GB";
    }

    @Override
    public String toString() {
        if (isDeleteMarker) {
            return String.format("  [DELETE MARKER] %s (v%d)", objectName, versionId);
        }
        return String.format("  %s  %s  v%d  [%s]", objectName, getSizeFormatted(), versionId, objectId);
    }
}
