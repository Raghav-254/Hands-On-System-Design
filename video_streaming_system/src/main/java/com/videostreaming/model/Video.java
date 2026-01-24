package com.videostreaming.model;

import java.util.HashMap;
import java.util.Map;

/**
 * Video entity representing a video in the system.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  VIDEO LIFECYCLE                                                             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  UPLOADING → PROCESSING → READY → (optional) FAILED                         ║
 * ║                                                                               ║
 * ║  1. User uploads → status = UPLOADING                                        ║
 * ║  2. Transcoding starts → status = PROCESSING                                 ║
 * ║  3. All encodings complete → status = READY                                  ║
 * ║  4. If transcoding fails → status = FAILED                                   ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class Video {
    
    public enum Status {
        UPLOADING,      // User is uploading the video
        PROCESSING,     // Video is being transcoded
        READY,          // Video is ready for streaming
        FAILED          // Transcoding failed
    }
    
    private final String videoId;
    private final long userId;
    private final String title;
    private final String description;
    private Status status;
    private final long uploadTime;
    private long duration;  // in seconds
    private long originalSizeBytes;
    
    // Map of resolution → CDN URL (e.g., "720p" → "https://cdn.example.com/v123/720p.mp4")
    private Map<String, String> transcodedUrls;
    
    // Thumbnail URL
    private String thumbnailUrl;
    
    public Video(String videoId, long userId, String title, String description) {
        this.videoId = videoId;
        this.userId = userId;
        this.title = title;
        this.description = description;
        this.status = Status.UPLOADING;
        this.uploadTime = System.currentTimeMillis();
        this.transcodedUrls = new HashMap<>();
    }
    
    // Getters and setters
    public String getVideoId() { return videoId; }
    public long getUserId() { return userId; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public long getUploadTime() { return uploadTime; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
    public long getOriginalSizeBytes() { return originalSizeBytes; }
    public void setOriginalSizeBytes(long size) { this.originalSizeBytes = size; }
    public Map<String, String> getTranscodedUrls() { return transcodedUrls; }
    public void addTranscodedUrl(String resolution, String url) { 
        this.transcodedUrls.put(resolution, url); 
    }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String url) { this.thumbnailUrl = url; }
    
    @Override
    public String toString() {
        return String.format("Video[id=%s, title=%s, status=%s, resolutions=%s]", 
            videoId, title, status, transcodedUrls.keySet());
    }
}

