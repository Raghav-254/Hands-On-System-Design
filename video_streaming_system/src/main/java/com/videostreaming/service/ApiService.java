package com.videostreaming.service;

import com.videostreaming.model.*;
import com.videostreaming.storage.*;
import com.videostreaming.cache.MetadataCache;
import java.util.*;

/**
 * API Service - handles HTTP requests from clients.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  API SERVICE                                                                 ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  ENDPOINTS:                                                                  ║
 * ║  ───────────                                                                 ║
 * ║                                                                               ║
 * ║  POST /upload                                                               ║
 * ║     → Initialize upload, return pre-signed URL                              ║
 * ║                                                                               ║
 * ║  GET /video/{id}                                                            ║
 * ║     → Get video metadata                                                    ║
 * ║                                                                               ║
 * ║  GET /video/{id}/stream?quality={resolution}                                ║
 * ║     → Get streaming URL                                                     ║
 * ║                                                                               ║
 * ║  PUT /video/{id}/metadata                                                   ║
 * ║     → Update video metadata (Figure 14-6)                                   ║
 * ║                                                                               ║
 * ║  GET /search?q={query}                                                      ║
 * ║     → Search videos                                                         ║
 * ║                                                                               ║
 * ║  GET /trending                                                              ║
 * ║     → Get trending videos                                                   ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class ApiService {
    
    private final UploadService uploadService;
    private final StreamingService streamingService;
    private final MetadataDB metadataDB;
    private final MetadataCache metadataCache;
    
    public static class ApiResponse {
        public final int statusCode;
        public final String message;
        public final Object data;
        
        public ApiResponse(int statusCode, String message, Object data) {
            this.statusCode = statusCode;
            this.message = message;
            this.data = data;
        }
    }
    
    public ApiService(UploadService uploadService, StreamingService streamingService,
                     MetadataDB metadataDB, MetadataCache metadataCache) {
        this.uploadService = uploadService;
        this.streamingService = streamingService;
        this.metadataDB = metadataDB;
        this.metadataCache = metadataCache;
    }
    
    /**
     * POST /upload - Initialize video upload
     */
    public ApiResponse initUpload(long userId, String title, String description) {
        System.out.println(String.format("\n[API] POST /upload (user=%d, title='%s')", userId, title));
        
        UploadService.UploadRequest request = new UploadService.UploadRequest(userId, title, description);
        UploadService.UploadResponse response = uploadService.initializeUpload(request);
        
        Map<String, Object> data = new HashMap<>();
        data.put("videoId", response.videoId);
        data.put("presignedUrl", response.presignedUrl);
        
        return new ApiResponse(200, "Upload initialized", data);
    }
    
    /**
     * GET /video/{id} - Get video metadata
     */
    public ApiResponse getVideo(String videoId) {
        System.out.println(String.format("\n[API] GET /video/%s", videoId));
        
        // Try cache first
        VideoMetadata metadata = metadataCache.get(videoId);
        if (metadata == null) {
            metadata = metadataDB.get(videoId);
            if (metadata == null) {
                return new ApiResponse(404, "Video not found", null);
            }
            metadataCache.put(videoId, metadata);
        }
        
        return new ApiResponse(200, "OK", metadata);
    }
    
    /**
     * GET /video/{id}/stream - Get streaming URL
     */
    public ApiResponse getStreamUrl(String videoId, String preferredQuality) {
        System.out.println(String.format("\n[API] GET /video/%s/stream?quality=%s", videoId, preferredQuality));
        
        StreamingService.StreamingInfo info = streamingService.getVideo(videoId, preferredQuality);
        if (info == null) {
            return new ApiResponse(404, "Video not found or processing", null);
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("streamUrl", info.streamUrl);
        data.put("manifestUrl", info.manifestUrl);
        data.put("resolution", info.resolution);
        data.put("fromCdn", info.fromCdn);
        data.put("availableQualities", info.availableQualities);
        
        return new ApiResponse(200, "OK", data);
    }
    
    /**
     * PUT /video/{id}/metadata - Update video metadata (Figure 14-6)
     */
    public ApiResponse updateMetadata(String videoId, String title, String description) {
        System.out.println(String.format("\n[API] PUT /video/%s/metadata", videoId));
        
        VideoMetadata metadata = metadataDB.get(videoId);
        if (metadata == null) {
            return new ApiResponse(404, "Video not found", null);
        }
        
        // Update fields
        if (title != null) metadata.setTitle(title);
        if (description != null) metadata.setDescription(description);
        
        metadataDB.update(metadata);
        
        // Invalidate cache
        metadataCache.invalidate(videoId);
        
        return new ApiResponse(200, "Metadata updated", metadata);
    }
    
    /**
     * GET /search - Search videos
     */
    public ApiResponse search(String query) {
        System.out.println(String.format("\n[API] GET /search?q=%s", query));
        
        List<VideoMetadata> results = metadataDB.searchByTitle(query);
        
        return new ApiResponse(200, "OK", results);
    }
    
    /**
     * GET /trending - Get trending videos
     */
    public ApiResponse getTrending(int limit) {
        System.out.println(String.format("\n[API] GET /trending?limit=%d", limit));
        
        List<VideoMetadata> trending = streamingService.getTrendingVideos(limit);
        
        return new ApiResponse(200, "OK", trending);
    }
    
    /**
     * POST /video/{id}/view - Record view (used internally)
     */
    public ApiResponse recordView(String videoId) {
        metadataDB.incrementViewCount(videoId);
        metadataCache.incrementViewCount(videoId);
        return new ApiResponse(200, "View recorded", null);
    }
}

