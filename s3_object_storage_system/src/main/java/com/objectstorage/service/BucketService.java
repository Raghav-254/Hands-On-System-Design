package com.objectstorage.service;

import com.objectstorage.model.Bucket;
import com.objectstorage.storage.MetadataStore;

/**
 * Bucket management service.
 */
public class BucketService {
    private final MetadataStore metadataStore;

    public BucketService(MetadataStore metadataStore) {
        this.metadataStore = metadataStore;
    }

    public Bucket createBucket(String bucketName, String ownerId, boolean versioningEnabled) {
        System.out.println("\n--- Creating bucket ---");
        if (metadataStore.getBucket(bucketName) != null) {
            System.out.println("  [REJECTED] Bucket '" + bucketName + "' already exists");
            return null;
        }
        Bucket bucket = new Bucket(bucketName, ownerId, versioningEnabled);
        metadataStore.createBucket(bucket);
        System.out.println("  [CREATED] " + bucket);
        return bucket;
    }

    public Bucket getBucket(String bucketName) {
        return metadataStore.getBucket(bucketName);
    }
}
