package com.objectstorage;

import com.objectstorage.model.*;
import com.objectstorage.service.*;
import com.objectstorage.storage.*;

import java.util.*;

/**
 * Demo: S3-like Object Storage System
 *
 * Demonstrates:
 * 1. Bucket creation (with/without versioning)
 * 2. Object upload (data persistence + metadata)
 * 3. Object download (metadata lookup + data fetch)
 * 4. Replication across data nodes
 * 5. Object versioning (multiple versions, delete markers)
 * 6. Listing objects (with prefix filtering)
 * 7. Multipart upload (large files)
 * 8. Garbage collection (delete + cleanup)
 * 9. Back-of-envelope estimation
 */
public class ObjectStorageDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       S3-like Object Storage Demo               ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // Initialize storage components
        PlacementService placementService = new PlacementService();
        setupDataNodes(placementService);

        MetadataStore metadataStore = new MetadataStore();
        DataStore dataStore = new DataStore(placementService, 3); // 3 replicas

        // Initialize services
        BucketService bucketService = new BucketService(metadataStore);
        ObjectService objectService = new ObjectService(metadataStore, dataStore);

        // ============================================
        // Demo 1: Create Buckets
        // ============================================
        System.out.println("\n========== DEMO 1: Create Buckets ==========");
        bucketService.createBucket("my-photos", "user-alice", false);
        bucketService.createBucket("project-docs", "user-alice", true); // versioning ON

        // ============================================
        // Demo 2: Upload Objects (with replication)
        // ============================================
        System.out.println("\n========== DEMO 2: Upload Objects ==========");
        objectService.putObject("my-photos", "vacation/beach.jpg",
                new byte[2_500_000], "image/jpeg"); // 2.5 MB

        objectService.putObject("my-photos", "vacation/sunset.jpg",
                new byte[1_800_000], "image/jpeg");

        objectService.putObject("my-photos", "profile.png",
                new byte[500_000], "image/png");

        // ============================================
        // Demo 3: Download Object
        // ============================================
        System.out.println("\n========== DEMO 3: Download Object ==========");
        byte[] data = objectService.getObject("my-photos", "vacation/beach.jpg");
        System.out.println("  Downloaded: " + (data != null ? data.length + " bytes" : "NOT FOUND"));

        // ============================================
        // Demo 4: List Objects
        // ============================================
        System.out.println("\n========== DEMO 4: List Objects ==========");
        System.out.println("\n--- All objects in 'my-photos' ---");
        for (ObjectMetadata obj : objectService.listObjects("my-photos")) {
            System.out.println(obj);
        }

        System.out.println("\n--- Objects with prefix 'vacation/' ---");
        for (ObjectMetadata obj : objectService.listObjectsWithPrefix("my-photos", "vacation/")) {
            System.out.println(obj);
        }

        // ============================================
        // Demo 5: Object Versioning
        // ============================================
        System.out.println("\n========== DEMO 5: Object Versioning ==========");

        // Upload v1
        objectService.putObject("project-docs", "readme.md",
                "# Version 1\nInitial readme.".getBytes(), "text/markdown");

        // Upload v2 (same key, new version)
        objectService.putObject("project-docs", "readme.md",
                "# Version 2\nUpdated readme with more details.".getBytes(), "text/markdown");

        // Upload v3
        objectService.putObject("project-docs", "readme.md",
                "# Version 3\nFinal readme for release.".getBytes(), "text/markdown");

        System.out.println("\n--- All versions of 'readme.md' ---");
        for (ObjectMetadata v : objectService.listVersions("project-docs", "readme.md")) {
            System.out.println(v);
        }

        // Delete (adds delete marker, doesn't destroy old versions)
        objectService.deleteObject("project-docs", "readme.md");

        System.out.println("\n--- Versions after delete (note delete marker) ---");
        for (ObjectMetadata v : objectService.listVersions("project-docs", "readme.md")) {
            System.out.println(v);
        }

        // ============================================
        // Demo 6: Multipart Upload
        // ============================================
        System.out.println("\n========== DEMO 6: Multipart Upload ==========");
        byte[][] parts = {
                new byte[5_000_000],  // Part 1: 5 MB
                new byte[5_000_000],  // Part 2: 5 MB
                new byte[3_000_000],  // Part 3: 3 MB
        };
        objectService.multipartUpload("my-photos", "video/holiday.mp4",
                parts, "video/mp4");

        // ============================================
        // Demo 7: Cluster Status
        // ============================================
        System.out.println("\n========== DEMO 7: Cluster Status ==========");
        placementService.printClusterStatus();

        // ============================================
        // Demo 8: Garbage Collection
        // ============================================
        System.out.println("\n========== DEMO 8: Garbage Collection ==========");
        System.out.println("Non-versioned bucket — hard delete:");
        objectService.deleteObject("my-photos", "profile.png");

        // ============================================
        // Demo 9: Back-of-Envelope
        // ============================================
        System.out.println("\n========== DEMO 9: Back-of-Envelope ==========");
        System.out.println("┌──────────────────────────────────────────────────────────┐");
        System.out.println("│  Storage capacity:     100 PB                            │");
        System.out.println("│  Durability:           6 nines (99.9999%)                │");
        System.out.println("│  Availability:         4 nines (99.99%)                  │");
        System.out.println("│                                                          │");
        System.out.println("│  Object distribution:                                    │");
        System.out.println("│    20% small  (<1MB,  median 0.5MB)                      │");
        System.out.println("│    60% medium (1-64MB, median 32MB)                      │");
        System.out.println("│    20% large  (>64MB, median 200MB)                      │");
        System.out.println("│                                                          │");
        System.out.println("│  At 40% storage utilization:                             │");
        System.out.println("│  Objects = (100PB × 0.4) / weighted_avg_size             │");
        System.out.println("│         = 40PB / (0.2×0.5 + 0.6×32 + 0.2×200) MB        │");
        System.out.println("│         = ~0.68 billion objects                          │");
        System.out.println("│                                                          │");
        System.out.println("│  Metadata: 0.68B × 1KB = 0.68 TB                        │");
        System.out.println("│  → Metadata is tiny compared to actual data!             │");
        System.out.println("│  → Bottleneck is DISK CAPACITY and DISK IOPS            │");
        System.out.println("└──────────────────────────────────────────────────────────┘");

        System.out.println("\n✓ Demo complete!");
    }

    private static void setupDataNodes(PlacementService ps) {
        long capacity = 1024L * 1024 * 1024 * 100; // 100 GB per node
        ps.registerNode(new DataNode("node-1", "DC-1", DataNode.Role.PRIMARY, capacity));
        ps.registerNode(new DataNode("node-2", "DC-2", DataNode.Role.SECONDARY, capacity));
        ps.registerNode(new DataNode("node-3", "DC-3", DataNode.Role.SECONDARY, capacity));
    }
}
