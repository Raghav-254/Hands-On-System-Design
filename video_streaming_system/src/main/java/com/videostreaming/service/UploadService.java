package com.videostreaming.service;

import com.videostreaming.model.*;
import com.videostreaming.storage.*;
import com.videostreaming.cache.MetadataCache;
import java.util.*;

/**
 * Upload Service - handles video upload flow.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  UPLOAD SERVICE                                                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  UPLOAD FLOW (Figure 14-5, 14-27):                                          ║
 * ║  ────────────────────────────────                                            ║
 * ║                                                                               ║
 * ║  1. Client → POST /upload (with metadata)                                   ║
 * ║  2. API Server generates pre-signed URL                                     ║
 * ║  3. Client uploads directly to S3 (Original Storage)                        ║
 * ║  4. S3 triggers transcoding pipeline                                        ║
 * ║                                                                               ║
 * ║  WHY PRE-SIGNED URL?                                                        ║
 * ║  ─────────────────────                                                       ║
 * ║  • Large files (up to 1GB)                                                  ║
 * ║  • Direct upload to S3 = no API server bottleneck                           ║
 * ║  • Resumable uploads possible                                               ║
 * ║  • Parallel GOP upload for faster completion                                ║
 * ║                                                                               ║
 * ║  PARALLEL UPLOAD (Figure 14-23):                                            ║
 * ║  ────────────────────────────────                                            ║
 * ║  For large videos, client splits into GOPs:                                 ║
 * ║  • GOP = Group of Pictures (video segment)                                  ║
 * ║  • Each GOP uploaded independently                                          ║
 * ║  • Server reassembles after all GOPs received                              ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class UploadService {
    
    private final OriginalStorage originalStorage;
    private final MetadataDB metadataDB;
    private final MetadataCache metadataCache;
    private final TranscodingService transcodingService;
    
    private long videoIdCounter = 1;
    
    public static class UploadRequest {
        public final String title;
        public final String description;
        public final long userId;
        public final String channelId;
        public final List<String> tags;
        
        public UploadRequest(long userId, String title, String description) {
            this.userId = userId;
            this.title = title;
            this.description = description;
            this.channelId = null;
            this.tags = new ArrayList<>();
        }
    }
    
    public static class UploadResponse {
        public final String videoId;
        public final String presignedUrl;
        public final boolean success;
        public final String message;
        
        public UploadResponse(String videoId, String presignedUrl, boolean success, String message) {
            this.videoId = videoId;
            this.presignedUrl = presignedUrl;
            this.success = success;
            this.message = message;
        }
    }
    
    public UploadService(OriginalStorage originalStorage, MetadataDB metadataDB, 
                        MetadataCache metadataCache, TranscodingService transcodingService) {
        this.originalStorage = originalStorage;
        this.metadataDB = metadataDB;
        this.metadataCache = metadataCache;
        this.transcodingService = transcodingService;
    }
    
    /**
     * Initialize upload - generates pre-signed URL.
     */
    public UploadResponse initializeUpload(UploadRequest request) {
        System.out.println(String.format("\n[UploadService] Initializing upload for user %d: '%s'", 
            request.userId, request.title));
        
        // Generate video ID
        String videoId = "vid_" + (videoIdCounter++);
        
        // Create metadata entry
        VideoMetadata metadata = new VideoMetadata(videoId);
        metadata.setTitle(request.title);
        metadata.setDescription(request.description);
        metadata.setUserId(request.userId);
        metadata.setChannelId(request.channelId);
        metadata.setTags(request.tags);
        
        // Save to DB
        metadataDB.save(metadata);
        
        // Generate pre-signed URL
        String presignedUrl = originalStorage.generatePresignedUrl(videoId);
        
        System.out.println(String.format("[UploadService] ✓ Upload initialized: videoId=%s", videoId));
        
        return new UploadResponse(videoId, presignedUrl, true, "Upload initialized");
    }
    
    /**
     * Handle upload completion (called after S3 upload finishes).
     * In production, this would be triggered by S3 event notification.
     */
    public void onUploadComplete(String videoId, byte[] videoData) {
        System.out.println(String.format("\n[UploadService] Upload complete for video %s (%d bytes)", 
            videoId, videoData.length));
        
        // Store in original storage
        originalStorage.upload(videoId, videoData);
        
        // Trigger transcoding
        System.out.println("[UploadService] Triggering transcoding pipeline...");
        transcodingService.startTranscoding(videoId, videoData);
    }
    
    /**
     * Initialize GOP-based parallel upload.
     */
    public UploadResponse initializeParallelUpload(UploadRequest request, int totalGops) {
        System.out.println(String.format("\n[UploadService] Initializing parallel upload (%d GOPs) for user %d", 
            totalGops, request.userId));
        
        String videoId = "vid_" + (videoIdCounter++);
        
        // Create metadata
        VideoMetadata metadata = new VideoMetadata(videoId);
        metadata.setTitle(request.title);
        metadata.setDescription(request.description);
        metadata.setUserId(request.userId);
        metadataDB.save(metadata);
        
        // Initialize GOP storage
        originalStorage.initializeUpload(videoId, totalGops);
        
        // Generate multiple pre-signed URLs (one per GOP)
        String presignedUrl = originalStorage.generatePresignedUrl(videoId);
        
        System.out.println(String.format("[UploadService] ✓ Parallel upload initialized: %d GOPs", totalGops));
        
        return new UploadResponse(videoId, presignedUrl, true, "Parallel upload initialized");
    }
    
    /**
     * Handle single GOP upload.
     */
    public void onGopUploaded(GOP gop) {
        originalStorage.uploadGop(gop);
        
        // Check if all GOPs uploaded
        if (originalStorage.isUploadComplete(gop.getVideoId())) {
            System.out.println(String.format("[UploadService] All GOPs uploaded for %s, starting transcoding", 
                gop.getVideoId()));
            // Reassemble and transcode
            // In real system: combine GOPs and trigger transcoding
        }
    }
    
    /**
     * Get upload status.
     */
    public String getUploadStatus(String videoId) {
        VideoMetadata metadata = metadataDB.get(videoId);
        if (metadata == null) {
            return "NOT_FOUND";
        }
        return metadata.getAvailableResolutions().isEmpty() ? "PROCESSING" : "READY";
    }
}

