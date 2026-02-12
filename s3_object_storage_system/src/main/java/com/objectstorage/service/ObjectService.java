package com.objectstorage.service;

import com.objectstorage.model.Bucket;
import com.objectstorage.model.ObjectMetadata;
import com.objectstorage.storage.DataStore;
import com.objectstorage.storage.MetadataStore;

import java.util.*;

/**
 * Object upload/download/versioning/listing service.
 *
 * Upload flow:
 *   ① Auth check → ② Upload data to Data Store → ③ Store metadata
 * Download flow:
 *   ① Auth check → ② Query metadata for object UUID → ③ Fetch from Data Store
 */
public class ObjectService {
    private final MetadataStore metadataStore;
    private final DataStore dataStore;
    private int nextObjectId = 1;
    private final Map<String, Integer> versionCounters = new HashMap<>();

    public ObjectService(MetadataStore metadataStore, DataStore dataStore) {
        this.metadataStore = metadataStore;
        this.dataStore = dataStore;
    }

    /**
     * Upload (PUT) an object.
     * If versioning enabled: creates a new version.
     * If versioning disabled: overwrites the existing object.
     */
    public ObjectMetadata putObject(String bucketName, String objectName,
                                     byte[] data, String contentType) {
        System.out.println("\n--- Uploading object ---");
        Bucket bucket = metadataStore.getBucket(bucketName);
        if (bucket == null) {
            System.out.println("  [REJECTED] Bucket '" + bucketName + "' not found");
            return null;
        }

        // Generate unique object ID (UUID)
        String objectId = "OBJ-" + UUID.randomUUID().toString().substring(0, 8);
        String versionKey = bucketName + ":" + objectName;
        int version = versionCounters.merge(versionKey, 1, Integer::sum);

        System.out.println("  Bucket: " + bucketName + ", Key: " + objectName +
                ", Size: " + data.length + " bytes, Version: " + version);

        // Step 1: Upload data to Data Store (replicated)
        System.out.println("  [STEP 1] Persisting data to Data Store...");
        dataStore.storeObject(objectId, data);

        // Step 2: Store metadata (after data is safely persisted)
        System.out.println("  [STEP 2] Storing metadata...");
        ObjectMetadata metadata = new ObjectMetadata(bucketName, objectName, objectId,
                data.length, contentType, version, false);
        metadataStore.putObjectMetadata(metadata);
        System.out.println("  [COMPLETE] " + objectName + " → " + objectId +
                " (v" + version + ", " + metadata.getSizeFormatted() + ")");
        System.out.println("  [CHECKSUM] " + metadata.getChecksum());

        return metadata;
    }

    /** Download (GET) an object — returns the bytes */
    public byte[] getObject(String bucketName, String objectName) {
        System.out.println("\n--- Downloading object ---");
        // Step 1: Get metadata (find object UUID)
        ObjectMetadata metadata = metadataStore.getLatestObject(bucketName, objectName);
        if (metadata == null) {
            System.out.println("  [NOT FOUND] " + bucketName + "/" + objectName);
            return null;
        }
        System.out.println("  [METADATA] Found " + objectName + " → " + metadata.getObjectId());

        // Step 2: Fetch data from Data Store using UUID
        byte[] data = dataStore.getObject(metadata.getObjectId());
        if (data != null) {
            System.out.println("  [DOWNLOADED] " + data.length + " bytes");
        }
        return data;
    }

    /** Delete an object (adds delete marker for versioned, hard delete for non-versioned) */
    public void deleteObject(String bucketName, String objectName) {
        System.out.println("\n--- Deleting object ---");
        Bucket bucket = metadataStore.getBucket(bucketName);

        if (bucket != null && bucket.isVersioningEnabled()) {
            // Versioned: add delete marker (soft delete)
            String versionKey = bucketName + ":" + objectName;
            int version = versionCounters.merge(versionKey, 1, Integer::sum);
            ObjectMetadata marker = new ObjectMetadata(bucketName, objectName,
                    "DELETED", 0, "", version, true);
            metadataStore.putObjectMetadata(marker);
            System.out.println("  [DELETE MARKER] Added for " + objectName + " (v" + version + ")");
            System.out.println("  Previous versions still accessible by version ID");
        } else {
            // Non-versioned: hard delete
            ObjectMetadata existing = metadataStore.getLatestObject(bucketName, objectName);
            if (existing != null) {
                dataStore.deleteObject(existing.getObjectId());
                System.out.println("  [HARD DELETE] " + objectName + " permanently removed");
            }
        }
    }

    /** List all objects in a bucket */
    public List<ObjectMetadata> listObjects(String bucketName) {
        return metadataStore.listObjects(bucketName);
    }

    /** List objects with prefix */
    public List<ObjectMetadata> listObjectsWithPrefix(String bucketName, String prefix) {
        return metadataStore.listObjectsWithPrefix(bucketName, prefix);
    }

    /** Get all versions of an object */
    public List<ObjectMetadata> listVersions(String bucketName, String objectName) {
        return metadataStore.getAllVersions(bucketName, objectName);
    }

    /** Simulate multipart upload */
    public ObjectMetadata multipartUpload(String bucketName, String objectName,
                                           byte[][] parts, String contentType) {
        System.out.println("\n--- Multipart Upload ---");
        System.out.println("  Object: " + bucketName + "/" + objectName);
        System.out.println("  Parts: " + parts.length);

        // Simulate uploading parts
        long totalSize = 0;
        for (int i = 0; i < parts.length; i++) {
            System.out.println("  [PART " + (i + 1) + "/" + parts.length + "] " +
                    parts[i].length + " bytes uploaded");
            totalSize += parts[i].length;
        }

        // Combine parts
        byte[] combined = new byte[(int) totalSize];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, combined, offset, part.length);
            offset += part.length;
        }

        System.out.println("  [ASSEMBLED] Total size: " + totalSize + " bytes");
        return putObject(bucketName, objectName, combined, contentType);
    }
}
