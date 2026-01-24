package com.videostreaming.storage;

import java.util.*;

/**
 * Transcoded Storage - stores encoded video files ready for CDN distribution.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRANSCODED STORAGE (S3 / Blob Storage)                                      ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Store encoded videos in multiple resolutions                             ║
 * ║  • Source for CDN to pull from (origin server)                              ║
 * ║  • Long-term storage (videos stored forever)                                ║
 * ║                                                                               ║
 * ║  STORAGE STRUCTURE:                                                         ║
 * ║  ─────────────────────                                                       ║
 * ║  s3://transcoded-videos/                                                    ║
 * ║    └── {video_id}/                                                          ║
 * ║        ├── 360p.mp4                                                         ║
 * ║        ├── 480p.mp4                                                         ║
 * ║        ├── 720p.mp4                                                         ║
 * ║        ├── 1080p.mp4                                                        ║
 * ║        ├── 4k.mp4 (optional)                                                ║
 * ║        ├── thumbnail.jpg                                                    ║
 * ║        └── manifest.m3u8 (HLS) or manifest.mpd (DASH)                       ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TranscodedStorage {
    
    // Simulates S3: videoId → resolution → data
    private final Map<String, Map<String, byte[]>> storage = new HashMap<>();
    
    // Thumbnails: videoId → thumbnail data
    private final Map<String, byte[]> thumbnails = new HashMap<>();
    
    // Available resolutions (sorted by quality)
    public static final List<String> SUPPORTED_RESOLUTIONS = Arrays.asList(
        "360p", "480p", "720p", "1080p", "4k"
    );
    
    /**
     * Store encoded video for a specific resolution.
     */
    public void store(String videoId, String resolution, byte[] data) {
        storage.computeIfAbsent(videoId, k -> new HashMap<>()).put(resolution, data);
        System.out.println(String.format("  [TranscodedStorage] Stored %s/%s.mp4 (%d bytes)", 
            videoId, resolution, data.length));
    }
    
    /**
     * Store thumbnail.
     */
    public void storeThumbnail(String videoId, byte[] data) {
        thumbnails.put(videoId, data);
        System.out.println(String.format("  [TranscodedStorage] Stored thumbnail for %s", videoId));
    }
    
    /**
     * Get encoded video data.
     */
    public byte[] get(String videoId, String resolution) {
        Map<String, byte[]> resolutions = storage.get(videoId);
        return resolutions != null ? resolutions.get(resolution) : null;
    }
    
    /**
     * Get all available resolutions for a video.
     */
    public List<String> getAvailableResolutions(String videoId) {
        Map<String, byte[]> resolutions = storage.get(videoId);
        return resolutions != null ? new ArrayList<>(resolutions.keySet()) : new ArrayList<>();
    }
    
    /**
     * Get CDN URL for a specific resolution.
     * In production, this would return actual CDN URL.
     */
    public String getCdnUrl(String videoId, String resolution) {
        return String.format("https://cdn.example.com/videos/%s/%s.mp4", videoId, resolution);
    }
    
    /**
     * Get thumbnail URL.
     */
    public String getThumbnailUrl(String videoId) {
        return String.format("https://cdn.example.com/videos/%s/thumbnail.jpg", videoId);
    }
    
    /**
     * Get storage path for transcoded video.
     */
    public String getPath(String videoId, String resolution) {
        return String.format("s3://transcoded-videos/%s/%s.mp4", videoId, resolution);
    }
    
    /**
     * Check if video exists in storage.
     */
    public boolean exists(String videoId) {
        return storage.containsKey(videoId);
    }
    
    /**
     * Get total storage used for a video.
     */
    public long getStorageUsed(String videoId) {
        Map<String, byte[]> resolutions = storage.get(videoId);
        if (resolutions == null) return 0;
        return resolutions.values().stream().mapToLong(b -> b.length).sum();
    }
}

