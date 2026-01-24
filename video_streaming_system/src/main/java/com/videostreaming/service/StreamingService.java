package com.videostreaming.service;

import com.videostreaming.model.VideoMetadata;
import com.videostreaming.storage.*;
import com.videostreaming.cache.MetadataCache;
import java.util.*;

/**
 * Streaming Service - handles video playback.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  STREAMING SERVICE                                                           ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  VIDEO STREAMING (Figure 14-7, 14-28):                                      ║
 * ║  ─────────────────────────────────────                                       ║
 * ║                                                                               ║
 * ║  1. POPULAR VIDEOS → CDN (edge servers)                                     ║
 * ║     • Cached at edge locations                                              ║
 * ║     • Low latency (< 50ms)                                                  ║
 * ║     • Expensive, used for hot content                                       ║
 * ║                                                                               ║
 * ║  2. LESS POPULAR VIDEOS → Origin servers                                    ║
 * ║     • Fetched directly from storage                                         ║
 * ║     • Higher latency but lower cost                                         ║
 * ║                                                                               ║
 * ║  ADAPTIVE BITRATE STREAMING:                                                ║
 * ║  ────────────────────────────                                                ║
 * ║  • Client monitors bandwidth                                                ║
 * ║  • Switches resolution based on network conditions                          ║
 * ║  • Manifest file (HLS: .m3u8, DASH: .mpd) lists available qualities        ║
 * ║                                                                               ║
 * ║  PROTOCOLS:                                                                 ║
 * ║  ───────────                                                                 ║
 * ║  • HLS (HTTP Live Streaming) - Apple, most compatible                       ║
 * ║  • DASH (Dynamic Adaptive Streaming over HTTP) - Open standard              ║
 * ║  • RTMP - Legacy, low latency (live streaming)                              ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class StreamingService {
    
    private final TranscodedStorage transcodedStorage;
    private final MetadataDB metadataDB;
    private final MetadataCache metadataCache;
    
    // CDN simulation: tracks popular videos
    private final Map<String, Long> viewCounts = new HashMap<>();
    private static final long CDN_THRESHOLD = 100;  // Videos with >100 views go to CDN
    
    public static class StreamingInfo {
        public final String videoId;
        public final String streamUrl;
        public final String resolution;
        public final boolean fromCdn;
        public final String manifestUrl;
        public final Map<String, String> availableQualities;
        
        public StreamingInfo(String videoId, String url, String resolution, boolean cdn,
                            String manifest, Map<String, String> qualities) {
            this.videoId = videoId;
            this.streamUrl = url;
            this.resolution = resolution;
            this.fromCdn = cdn;
            this.manifestUrl = manifest;
            this.availableQualities = qualities;
        }
    }
    
    public StreamingService(TranscodedStorage transcodedStorage, MetadataDB metadataDB, 
                           MetadataCache metadataCache) {
        this.transcodedStorage = transcodedStorage;
        this.metadataDB = metadataDB;
        this.metadataCache = metadataCache;
    }
    
    /**
     * Get video for streaming.
     */
    public StreamingInfo getVideo(String videoId, String preferredResolution) {
        System.out.println(String.format("\n[StreamingService] Streaming request: video=%s, preferred=%s", 
            videoId, preferredResolution));
        
        // Get metadata (cache-aside pattern)
        VideoMetadata metadata = metadataCache.get(videoId);
        if (metadata == null) {
            metadata = metadataDB.get(videoId);
            if (metadata == null) {
                System.out.println("  [StreamingService] Video not found");
                return null;
            }
            metadataCache.put(videoId, metadata);
        }
        
        // Check available resolutions
        List<String> available = transcodedStorage.getAvailableResolutions(videoId);
        if (available.isEmpty()) {
            System.out.println("  [StreamingService] Video still processing");
            return null;
        }
        
        // Select resolution
        String selectedResolution = selectResolution(preferredResolution, available);
        
        // Increment view count
        long views = viewCounts.merge(videoId, 1L, Long::sum);
        metadataDB.incrementViewCount(videoId);
        
        // Determine if CDN or origin
        boolean fromCdn = views >= CDN_THRESHOLD;
        String baseUrl = fromCdn ? "https://cdn.example.com" : "https://origin.example.com";
        String streamUrl = String.format("%s/videos/%s/%s.mp4", baseUrl, videoId, selectedResolution);
        String manifestUrl = String.format("%s/videos/%s/manifest.m3u8", baseUrl, videoId);
        
        // Build available qualities map
        Map<String, String> qualities = new HashMap<>();
        for (String res : available) {
            qualities.put(res, String.format("%s/videos/%s/%s.mp4", baseUrl, videoId, res));
        }
        
        System.out.println(String.format("  [StreamingService] Serving %s at %s from %s (views: %d)", 
            videoId, selectedResolution, fromCdn ? "CDN" : "Origin", views));
        
        return new StreamingInfo(videoId, streamUrl, selectedResolution, fromCdn, manifestUrl, qualities);
    }
    
    /**
     * Select best resolution based on preference and availability.
     */
    private String selectResolution(String preferred, List<String> available) {
        // Priority order
        List<String> priority = Arrays.asList("4k", "1080p", "720p", "480p", "360p");
        
        // If preferred is available, use it
        if (preferred != null && available.contains(preferred)) {
            return preferred;
        }
        
        // Otherwise, select best available
        for (String res : priority) {
            if (available.contains(res)) {
                return res;
            }
        }
        
        return available.get(0);  // Fallback to first available
    }
    
    /**
     * Generate HLS manifest file content.
     */
    public String generateHlsManifest(String videoId) {
        List<String> resolutions = transcodedStorage.getAvailableResolutions(videoId);
        
        StringBuilder manifest = new StringBuilder();
        manifest.append("#EXTM3U\n");
        manifest.append("#EXT-X-VERSION:3\n");
        
        // Map resolution to bandwidth
        Map<String, Integer> bandwidths = new HashMap<>();
        bandwidths.put("360p", 800000);    // 800 Kbps
        bandwidths.put("480p", 1400000);   // 1.4 Mbps
        bandwidths.put("720p", 2800000);   // 2.8 Mbps
        bandwidths.put("1080p", 5000000);  // 5 Mbps
        bandwidths.put("4k", 14000000);    // 14 Mbps
        
        for (String res : resolutions) {
            int bandwidth = bandwidths.getOrDefault(res, 1000000);
            manifest.append(String.format("#EXT-X-STREAM-INF:BANDWIDTH=%d,RESOLUTION=%s\n", 
                bandwidth, getResolutionDimensions(res)));
            manifest.append(String.format("%s.m3u8\n", res));
        }
        
        return manifest.toString();
    }
    
    private String getResolutionDimensions(String resolution) {
        switch (resolution) {
            case "360p": return "640x360";
            case "480p": return "854x480";
            case "720p": return "1280x720";
            case "1080p": return "1920x1080";
            case "4k": return "3840x2160";
            default: return "1280x720";
        }
    }
    
    /**
     * Get trending videos.
     */
    public List<VideoMetadata> getTrendingVideos(int limit) {
        return metadataDB.getTrendingVideos(limit);
    }
}

