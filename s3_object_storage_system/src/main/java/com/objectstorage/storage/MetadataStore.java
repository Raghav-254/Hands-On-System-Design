package com.objectstorage.storage;

import com.objectstorage.model.Bucket;
import com.objectstorage.model.ObjectMetadata;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the Metadata Store (Metadata Service + Metadata DB).
 * Stores bucket info and object metadata (name, size, location UUID, versions).
 *
 * In production: Sharded database (e.g., vitess/MySQL or Cassandra).
 */
public class MetadataStore {
    private final Map<String, Bucket> buckets = new LinkedHashMap<>();
    // bucket:objectName → list of versions (latest last)
    private final Map<String, List<ObjectMetadata>> objectVersions = new LinkedHashMap<>();

    public void createBucket(Bucket bucket) {
        buckets.put(bucket.getBucketName(), bucket);
    }

    public Bucket getBucket(String bucketName) {
        return buckets.get(bucketName);
    }

    public void putObjectMetadata(ObjectMetadata metadata) {
        String key = metadata.getBucketName() + ":" + metadata.getObjectName();
        objectVersions.computeIfAbsent(key, k -> new ArrayList<>()).add(metadata);
    }

    /** Get latest version of an object */
    public ObjectMetadata getLatestObject(String bucketName, String objectName) {
        String key = bucketName + ":" + objectName;
        List<ObjectMetadata> versions = objectVersions.get(key);
        if (versions == null || versions.isEmpty()) return null;
        // Return latest non-delete-marker
        for (int i = versions.size() - 1; i >= 0; i--) {
            if (!versions.get(i).isDeleteMarker()) return versions.get(i);
        }
        return null; // all versions are delete markers
    }

    /** Get all versions of an object */
    public List<ObjectMetadata> getAllVersions(String bucketName, String objectName) {
        String key = bucketName + ":" + objectName;
        return objectVersions.getOrDefault(key, List.of());
    }

    /** List objects in a bucket (latest version of each, no delete markers) */
    public List<ObjectMetadata> listObjects(String bucketName) {
        Map<String, ObjectMetadata> latest = new LinkedHashMap<>();
        for (var entry : objectVersions.entrySet()) {
            List<ObjectMetadata> versions = entry.getValue();
            if (!versions.isEmpty()) {
                ObjectMetadata last = versions.get(versions.size() - 1);
                if (last.getBucketName().equals(bucketName) && !last.isDeleteMarker()) {
                    latest.put(last.getObjectName(), last);
                }
            }
        }
        return new ArrayList<>(latest.values());
    }

    /** List objects with a prefix (like ls with path) */
    public List<ObjectMetadata> listObjectsWithPrefix(String bucketName, String prefix) {
        return listObjects(bucketName).stream()
                .filter(o -> o.getObjectName().startsWith(prefix))
                .collect(Collectors.toList());
    }
}
