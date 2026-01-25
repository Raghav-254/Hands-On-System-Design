package com.googledrive.storage;

import com.googledrive.model.FileMetadata;
import com.googledrive.model.FileVersion;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Metadata DB (MySQL/PostgreSQL) - Stores file/folder structure.
 * 
 * Tables:
 * - user: User accounts
 * - workspace: Shared spaces
 * - device: Registered devices
 * - file: File/folder metadata
 * - file_version: Version history
 * - block: Block references per version
 * 
 * Sharding: By user_id (all user's files on same shard)
 */
public class MetadataDB {
    // In production: MySQL/PostgreSQL with sharding
    private final Map<String, FileMetadata> files = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> userFiles = new ConcurrentHashMap<>();
    
    // Version tracking for conflict detection (optimistic locking)
    private int globalVersionCounter = 0;
    
    /**
     * Create a new file entry.
     */
    public FileMetadata createFile(String userId, String workspaceId, 
                                    String fileName, String relativePath) {
        String fileId = "file_" + UUID.randomUUID().toString().substring(0, 8);
        FileMetadata file = new FileMetadata(fileId, userId, workspaceId, fileName, relativePath);
        
        files.put(fileId, file);
        userFiles.computeIfAbsent(userId, k -> new HashSet<>()).add(fileId);
        
        return file;
    }
    
    /**
     * Add a new version to a file (with optimistic locking for conflict detection).
     * Returns null if conflict detected (baseVersion doesn't match).
     */
    public FileVersion addVersion(String fileId, String deviceId, int baseVersion,
                                   String checksum, long fileSize, List<String> blockHashes) {
        FileMetadata file = files.get(fileId);
        if (file == null) {
            throw new RuntimeException("File not found: " + fileId);
        }
        
        // Optimistic locking: Check for conflicts
        if (file.getLatestVersion() != baseVersion) {
            System.out.printf("CONFLICT DETECTED! Expected v%d, found v%d%n", 
                baseVersion, file.getLatestVersion());
            return null; // Conflict!
        }
        
        int newVersionNumber = file.getLatestVersion() + 1;
        String versionId = "ver_" + UUID.randomUUID().toString().substring(0, 8);
        
        FileVersion version = new FileVersion(
            versionId, fileId, deviceId, newVersionNumber, 
            checksum, fileSize, blockHashes
        );
        
        file.addVersion(version);
        file.setStatus(FileMetadata.FileStatus.UPLOADED);
        
        return version;
    }
    
    /**
     * Get file by ID.
     */
    public FileMetadata getFile(String fileId) {
        return files.get(fileId);
    }
    
    /**
     * Get all files for a user.
     */
    public List<FileMetadata> getUserFiles(String userId) {
        Set<String> fileIds = userFiles.get(userId);
        if (fileIds == null) return Collections.emptyList();
        
        return fileIds.stream()
            .map(files::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Get files changed since a specific version (for sync).
     */
    public List<FileMetadata> getChangedFiles(String userId, int sinceVersion) {
        return getUserFiles(userId).stream()
            .filter(f -> f.getLatestVersion() > sinceVersion)
            .collect(Collectors.toList());
    }
    
    /**
     * Soft delete file (move to trash).
     */
    public void deleteFile(String fileId) {
        FileMetadata file = files.get(fileId);
        if (file != null) {
            file.setStatus(FileMetadata.FileStatus.DELETED);
        }
    }
    
    /**
     * Restore a file to a previous version.
     */
    public FileVersion restoreVersion(String fileId, String deviceId, int targetVersion) {
        FileMetadata file = files.get(fileId);
        if (file == null) {
            throw new RuntimeException("File not found: " + fileId);
        }
        
        // Find the target version
        FileVersion target = file.getVersions().stream()
            .filter(v -> v.getVersionNumber() == targetVersion)
            .findFirst()
            .orElseThrow(() -> new RuntimeException("Version not found: " + targetVersion));
        
        // Create new version with same blocks as target
        return addVersion(fileId, deviceId, file.getLatestVersion(),
            target.getChecksum(), target.getFileSize(), target.getBlockHashes());
    }
    
    public void printStats() {
        System.out.println("\n=== Metadata DB Stats ===");
        System.out.printf("Total files: %d%n", files.size());
        System.out.printf("Total users: %d%n", userFiles.size());
        int totalVersions = files.values().stream()
            .mapToInt(f -> f.getVersions().size()).sum();
        System.out.printf("Total versions: %d%n", totalVersions);
    }
}

