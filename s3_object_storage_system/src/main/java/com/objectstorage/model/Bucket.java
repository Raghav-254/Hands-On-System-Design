package com.objectstorage.model;

import java.time.Instant;

/**
 * Represents an S3 bucket — a top-level container for objects.
 * Bucket names are globally unique.
 */
public class Bucket {
    private final String bucketName;
    private final String ownerId;
    private final boolean versioningEnabled;
    private final Instant createdAt;

    public Bucket(String bucketName, String ownerId, boolean versioningEnabled) {
        this.bucketName = bucketName;
        this.ownerId = ownerId;
        this.versioningEnabled = versioningEnabled;
        this.createdAt = Instant.now();
    }

    public String getBucketName() { return bucketName; }
    public String getOwnerId() { return ownerId; }
    public boolean isVersioningEnabled() { return versioningEnabled; }
    public Instant getCreatedAt() { return createdAt; }

    @Override
    public String toString() {
        return String.format("Bucket{name='%s', owner=%s, versioning=%s}",
                bucketName, ownerId, versioningEnabled);
    }
}
