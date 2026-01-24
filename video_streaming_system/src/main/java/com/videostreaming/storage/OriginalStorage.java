package com.videostreaming.storage;

import com.videostreaming.model.GOP;
import java.util.*;

/**
 * Original Storage - stores raw uploaded videos before transcoding.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  ORIGINAL STORAGE (S3 / Blob Storage)                                        ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Store raw uploaded videos temporarily                                    ║
 * ║  • Videos are deleted after successful transcoding (cost optimization)      ║
 * ║  • Or kept for re-transcoding if needed                                     ║
 * ║                                                                               ║
 * ║  UPLOAD FLOW (Figure 14-27):                                                ║
 * ║  ───────────────────────────                                                ║
 * ║  1. Client → POST /upload → API Server                                      ║
 * ║  2. API Server generates pre-signed URL                                     ║
 * ║  3. Client uploads directly to S3 using pre-signed URL                      ║
 * ║  4. S3 triggers event → Transcoding pipeline starts                         ║
 * ║                                                                               ║
 * ║  WHY PRE-SIGNED URL?                                                        ║
 * ║  • Video files are large (100MB - 1GB)                                      ║
 * ║  • Direct upload to S3 avoids overloading API servers                       ║
 * ║  • Client can upload in parallel (GOP-based) - Figure 14-23                 ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class OriginalStorage {
    
    // Simulates S3 bucket: videoId → raw video data
    private final Map<String, byte[]> storage = new HashMap<>();
    
    // GOP-based storage for parallel upload: videoId → list of GOPs
    private final Map<String, List<GOP>> gopStorage = new HashMap<>();
    
    // Track upload progress
    private final Map<String, UploadProgress> uploadProgress = new HashMap<>();
    
    public static class UploadProgress {
        public final int totalGops;
        public int uploadedGops;
        public boolean complete;
        
        public UploadProgress(int totalGops) {
            this.totalGops = totalGops;
            this.uploadedGops = 0;
            this.complete = false;
        }
    }
    
    /**
     * Generate a pre-signed URL for direct upload.
     * In production, this would be an actual S3 pre-signed URL.
     */
    public String generatePresignedUrl(String videoId) {
        // Simulated pre-signed URL
        String presignedUrl = String.format("https://s3.amazonaws.com/videos/%s?signature=abc123&expires=3600", videoId);
        System.out.println(String.format("  [OriginalStorage] Generated pre-signed URL for video %s", videoId));
        return presignedUrl;
    }
    
    /**
     * Initialize GOP-based upload session.
     */
    public void initializeUpload(String videoId, int totalGops) {
        gopStorage.put(videoId, new ArrayList<>());
        uploadProgress.put(videoId, new UploadProgress(totalGops));
        System.out.println(String.format("  [OriginalStorage] Initialized upload for video %s with %d GOPs", videoId, totalGops));
    }
    
    /**
     * Upload a single GOP (parallel upload support).
     */
    public void uploadGop(GOP gop) {
        String videoId = gop.getVideoId();
        List<GOP> gops = gopStorage.get(videoId);
        if (gops != null) {
            gops.add(gop);
            gop.setUploaded(true);
            
            UploadProgress progress = uploadProgress.get(videoId);
            progress.uploadedGops++;
            
            System.out.println(String.format("  [OriginalStorage] Uploaded GOP %d for video %s (%d/%d)", 
                gop.getGopIndex(), videoId, progress.uploadedGops, progress.totalGops));
            
            if (progress.uploadedGops >= progress.totalGops) {
                progress.complete = true;
                System.out.println(String.format("  [OriginalStorage] ✓ All GOPs uploaded for video %s", videoId));
            }
        }
    }
    
    /**
     * Simple single-file upload (for smaller videos).
     */
    public void upload(String videoId, byte[] data) {
        storage.put(videoId, data);
        System.out.println(String.format("  [OriginalStorage] Uploaded video %s (%d bytes)", videoId, data.length));
    }
    
    /**
     * Check if upload is complete.
     */
    public boolean isUploadComplete(String videoId) {
        UploadProgress progress = uploadProgress.get(videoId);
        return progress != null && progress.complete;
    }
    
    /**
     * Get video data for transcoding.
     */
    public byte[] get(String videoId) {
        return storage.get(videoId);
    }
    
    /**
     * Get all GOPs for a video.
     */
    public List<GOP> getGops(String videoId) {
        return gopStorage.getOrDefault(videoId, new ArrayList<>());
    }
    
    /**
     * Delete original video after successful transcoding (cost optimization).
     */
    public void delete(String videoId) {
        storage.remove(videoId);
        gopStorage.remove(videoId);
        uploadProgress.remove(videoId);
        System.out.println(String.format("  [OriginalStorage] Deleted original video %s (cost optimization)", videoId));
    }
    
    /**
     * Get storage path for a video.
     */
    public String getPath(String videoId) {
        return String.format("s3://original-videos/%s/raw.mp4", videoId);
    }
}

