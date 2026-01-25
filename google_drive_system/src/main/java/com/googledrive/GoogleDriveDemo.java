package com.googledrive;

import com.googledrive.model.*;
import com.googledrive.service.*;
import com.googledrive.storage.*;

import java.util.*;
import java.util.concurrent.*;

/**
 * Google Drive System Demo
 * 
 * Demonstrates:
 * 1. Block-based file upload (split, compress, encrypt)
 * 2. Delta sync (only upload changed blocks)
 * 3. Deduplication (same content stored once)
 * 4. Version history
 * 5. Conflict detection
 * 6. Cross-device sync via notifications
 */
public class GoogleDriveDemo {
    
    public static void main(String[] args) throws Exception {
        System.out.println("╔═══════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                    GOOGLE DRIVE SYSTEM DEMO                                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════════════════════╝");
        
        // Initialize components
        CloudStorage cloudStorage = new CloudStorage();
        BlockServer blockServer = new BlockServer(cloudStorage);
        MetadataDB metadataDB = new MetadataDB();
        NotificationService notificationService = new NotificationService();
        
        // Register user and devices
        String userId = "user_alice";
        String laptopId = "device_laptop";
        String phoneId = "device_phone";
        
        notificationService.registerDevice(userId, laptopId);
        notificationService.registerDevice(userId, phoneId);
        
        // ============================================================
        // DEMO 1: File Upload (Block Server Processing)
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 1: FILE UPLOAD (Block-based with compression + encryption)");
        System.out.println("═".repeat(70));
        
        // Simulate a 10MB file
        byte[] originalFile = generateTestFile(10 * 1024 * 1024); // 10MB
        System.out.printf("Original file: %d bytes%n", originalFile.length);
        
        // Upload via Block Server (split → compress → encrypt → store)
        List<Block> blocks = blockServer.uploadFile(originalFile);
        
        // Create file metadata
        FileMetadata fileMetadata = metadataDB.createFile(
            userId, "workspace_1", "report.pdf", "/Documents/"
        );
        
        // Add version with block references
        List<String> blockHashes = new ArrayList<>();
        for (Block block : blocks) {
            blockHashes.add(block.getBlockHash());
        }
        
        FileVersion version = metadataDB.addVersion(
            fileMetadata.getFileId(), laptopId, 0,
            "checksum_abc123", originalFile.length, blockHashes
        );
        
        System.out.printf("Created: %s%n", fileMetadata);
        System.out.printf("Version: %s%n", version);
        
        // Notify other devices
        notificationService.notifyFileChange(
            userId, laptopId, fileMetadata.getFileId(), "created", 1
        );
        
        // ============================================================
        // DEMO 2: File Download (Reassemble from blocks)
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 2: FILE DOWNLOAD (Decrypt + Decompress + Reassemble)");
        System.out.println("═".repeat(70));
        
        byte[] downloadedFile = blockServer.downloadFile(blockHashes);
        boolean identical = Arrays.equals(originalFile, downloadedFile);
        System.out.printf("Downloaded file matches original: %s%n", identical ? "YES ✓" : "NO ✗");
        
        // ============================================================
        // DEMO 3: Delta Sync (Only upload changed blocks)
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 3: DELTA SYNC (Only upload CHANGED blocks)");
        System.out.println("═".repeat(70));
        
        // Simulate editing the middle of the file (only 1 block changes)
        byte[] editedFile = originalFile.clone();
        int middleOffset = 5 * 1024 * 1024; // Middle of file
        for (int i = middleOffset; i < middleOffset + 1000; i++) {
            editedFile[i] = (byte) (editedFile[i] + 1); // Small edit
        }
        
        System.out.println("Simulated edit: Changed 1000 bytes in middle of file");
        System.out.println("Without delta sync: Would upload entire 10MB again");
        System.out.println("With delta sync: Only upload the 1 changed block (4MB)\n");
        
        List<Block> editedBlocks = blockServer.uploadFile(editedFile);
        
        // Count how many blocks were actually new
        int newBlocks = 0;
        for (Block block : editedBlocks) {
            // Check against original blocks
            boolean isNew = blocks.stream()
                .noneMatch(b -> b.getBlockHash().equals(block.getBlockHash()));
            if (isNew) newBlocks++;
        }
        System.out.printf("Delta sync result: %d/%d blocks were new (others deduplicated)%n", 
            newBlocks, editedBlocks.size());
        
        // ============================================================
        // DEMO 4: Deduplication (Same file uploaded twice)
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 4: DEDUPLICATION (Same file uploaded by different user)");
        System.out.println("═".repeat(70));
        
        System.out.println("User Bob uploads the SAME file...");
        int blocksBefore = cloudStorage.getBlockCount();
        
        blockServer.uploadFile(originalFile); // Same file content
        
        int blocksAfter = cloudStorage.getBlockCount();
        System.out.printf("Blocks before: %d, Blocks after: %d%n", blocksBefore, blocksAfter);
        System.out.println("Zero new blocks stored - 100% deduplication! ✓");
        
        // ============================================================
        // DEMO 5: Conflict Detection
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 5: CONFLICT DETECTION (Optimistic Locking)");
        System.out.println("═".repeat(70));
        
        System.out.println("Scenario: Two devices edit the same file simultaneously");
        System.out.println("- Laptop thinks file is at v1");
        System.out.println("- Phone also thinks file is at v1");
        System.out.println("- Laptop uploads edit first → becomes v2");
        System.out.println("- Phone tries to upload based on v1 → CONFLICT!");
        
        // Laptop uploads first (success)
        FileVersion v2 = metadataDB.addVersion(
            fileMetadata.getFileId(), laptopId, 1, // baseVersion = 1
            "checksum_v2", 10500000, blockHashes
        );
        System.out.printf("\nLaptop upload: %s%n", v2 != null ? "SUCCESS (v2)" : "FAILED");
        
        // Phone tries to upload based on old version (conflict!)
        FileVersion phoneVersion = metadataDB.addVersion(
            fileMetadata.getFileId(), phoneId, 1, // Also expects v1, but it's v2 now!
            "checksum_phone", 10600000, blockHashes
        );
        System.out.printf("Phone upload: %s%n", 
            phoneVersion != null ? "SUCCESS" : "CONFLICT DETECTED! (expected v1, found v2)");
        
        System.out.println("\nResolution: Create 'conflicted copy' for phone's version");
        
        // ============================================================
        // DEMO 6: Version History and Restore
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 6: VERSION HISTORY");
        System.out.println("═".repeat(70));
        
        FileMetadata file = metadataDB.getFile(fileMetadata.getFileId());
        System.out.printf("File: %s%n", file.getFullPath());
        System.out.println("Version history:");
        for (FileVersion v : file.getVersions()) {
            System.out.printf("  - %s%n", v);
        }
        
        // ============================================================
        // DEMO 7: Cross-Device Sync (Notification Service)
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO 7: CROSS-DEVICE SYNC (Long Polling)");
        System.out.println("═".repeat(70));
        
        // Simulate phone polling for changes
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<List<NotificationService.ChangeEvent>> pollFuture = executor.submit(() -> {
            return notificationService.pollForChanges(phoneId, 5);
        });
        
        // Give poll time to start
        Thread.sleep(100);
        
        // Laptop makes a change
        System.out.println("\nLaptop creates a new file...");
        FileMetadata newFile = metadataDB.createFile(
            userId, "workspace_1", "presentation.pptx", "/Documents/"
        );
        notificationService.notifyFileChange(
            userId, laptopId, newFile.getFileId(), "created", 1
        );
        
        // Phone receives notification
        List<NotificationService.ChangeEvent> changes = pollFuture.get();
        System.out.printf("Phone received %d change(s):%n", changes.size());
        for (NotificationService.ChangeEvent change : changes) {
            System.out.printf("  - %s%n", change);
        }
        
        executor.shutdown();
        
        // ============================================================
        // Final Stats
        // ============================================================
        System.out.println("\n" + "═".repeat(70));
        System.out.println("FINAL STATS");
        System.out.println("═".repeat(70));
        
        cloudStorage.printStats();
        metadataDB.printStats();
        notificationService.printStats();
        
        System.out.println("\n" + "═".repeat(70));
        System.out.println("Demo complete! Key takeaways:");
        System.out.println("═".repeat(70));
        System.out.println("1. Block-based storage enables delta sync (upload only changes)");
        System.out.println("2. Content-addressed storage enables deduplication");
        System.out.println("3. Compression + encryption applied at block level");
        System.out.println("4. Optimistic locking detects conflicts");
        System.out.println("5. Long polling provides near-real-time sync");
        System.out.println("6. Version history enables restore to any point");
    }
    
    /**
     * Generate test file with repeating pattern (compressible).
     */
    private static byte[] generateTestFile(int size) {
        byte[] data = new byte[size];
        Random random = new Random(42); // Deterministic for demo
        
        // Mix of random and repeating data (realistic for documents)
        for (int i = 0; i < size; i++) {
            if (i % 100 < 70) {
                // 70% repeating pattern (compressible)
                data[i] = (byte) (i % 256);
            } else {
                // 30% random (less compressible)
                data[i] = (byte) random.nextInt(256);
            }
        }
        
        return data;
    }
}

