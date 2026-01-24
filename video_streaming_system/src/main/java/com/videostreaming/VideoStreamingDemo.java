package com.videostreaming;

import com.videostreaming.model.*;
import com.videostreaming.storage.*;
import com.videostreaming.cache.*;
import com.videostreaming.service.*;
import com.videostreaming.queue.*;
import java.util.*;

/**
 * Video Streaming System Demo - demonstrates end-to-end flows.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  VIDEO STREAMING SYSTEM (YOUTUBE-LIKE)                                       ║
 * ║  Based on Alex Xu's System Design Interview - Chapter 14                     ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  KEY FEATURES DEMONSTRATED:                                                  ║
 * ║  ──────────────────────────                                                  ║
 * ║  1. Video Upload with Pre-signed URL                                        ║
 * ║  2. Transcoding Pipeline (DAG-based)                                        ║
 * ║  3. Multiple Resolution Encoding                                            ║
 * ║  4. Video Streaming (CDN vs Origin)                                         ║
 * ║  5. Adaptive Bitrate Streaming                                              ║
 * ║  6. Metadata Management                                                     ║
 * ║                                                                               ║
 * ║  ARCHITECTURE:                                                              ║
 * ║  ─────────────                                                               ║
 * ║  User ──→ API Servers ──→ Original Storage                                 ║
 * ║                               │                                              ║
 * ║                               ▼                                              ║
 * ║                      Transcoding Pipeline                                   ║
 * ║                      (Preprocessor → DAG → Workers)                         ║
 * ║                               │                                              ║
 * ║                               ▼                                              ║
 * ║                      Transcoded Storage ──→ CDN ──→ Users                   ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class VideoStreamingDemo {
    
    // Storage
    private final OriginalStorage originalStorage;
    private final TranscodedStorage transcodedStorage;
    private final MetadataDB metadataDB;
    private final MetadataCache metadataCache;
    
    // Queues
    private final CompletionQueue completionQueue;
    
    // Services
    private final TranscodingService transcodingService;
    private final UploadService uploadService;
    private final StreamingService streamingService;
    private final ApiService apiService;
    
    public VideoStreamingDemo() {
        // Initialize storage
        this.originalStorage = new OriginalStorage();
        this.transcodedStorage = new TranscodedStorage();
        this.metadataDB = new MetadataDB();
        this.metadataCache = new MetadataCache();
        
        // Initialize queue
        this.completionQueue = new CompletionQueue();
        
        // Initialize services
        this.transcodingService = new TranscodingService(
            transcodedStorage, metadataDB, metadataCache, completionQueue);
        this.uploadService = new UploadService(
            originalStorage, metadataDB, metadataCache, transcodingService);
        this.streamingService = new StreamingService(
            transcodedStorage, metadataDB, metadataCache);
        this.apiService = new ApiService(
            uploadService, streamingService, metadataDB, metadataCache);
    }
    
    public void run() throws InterruptedException {
        printHeader();
        
        // Demo 1: Video Upload Flow
        String videoId = demoUploadFlow();
        
        // Wait for transcoding to complete
        Thread.sleep(1000);
        
        // Demo 2: Video Streaming Flow
        demoStreamingFlow(videoId);
        
        // Demo 3: Metadata Update Flow
        demoMetadataUpdate(videoId);
        
        // Demo 4: Search and Discovery
        demoSearchAndDiscovery();
        
        // Demo 5: Parallel GOP Upload
        demoParallelUpload();
        
        printSummary();
        
        // Cleanup
        completionQueue.stop();
    }
    
    /**
     * Demo 1: Complete video upload flow
     */
    private String demoUploadFlow() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 1: VIDEO UPLOAD FLOW (Figures 14-5, 14-27)");
        System.out.println("=".repeat(80));
        
        // Step 1: Initialize upload (get pre-signed URL)
        ApiService.ApiResponse initResponse = apiService.initUpload(
            1001L, 
            "My Amazing Vacation Video", 
            "Beautiful scenery from my trip to Hawaii"
        );
        
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) initResponse.data;
        String videoId = (String) data.get("videoId");
        String presignedUrl = (String) data.get("presignedUrl");
        
        System.out.println("\n[Client] Received pre-signed URL: " + presignedUrl.substring(0, 50) + "...");
        System.out.println("[Client] Uploading video directly to S3...");
        
        // Step 2: Simulate direct upload to S3
        byte[] videoData = new byte[300 * 1024 * 1024];  // Simulated 300MB video
        uploadService.onUploadComplete(videoId, videoData);
        
        return videoId;
    }
    
    /**
     * Demo 2: Video streaming flow
     */
    private void demoStreamingFlow(String videoId) throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 2: VIDEO STREAMING FLOW (Figures 14-7, 14-28)");
        System.out.println("=".repeat(80));
        
        // Wait for transcoding completion event to be processed
        Thread.sleep(500);
        
        // Request streaming URL
        ApiService.ApiResponse streamResponse = apiService.getStreamUrl(videoId, "720p");
        
        if (streamResponse.statusCode == 200) {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) streamResponse.data;
            
            System.out.println("\n[Client] Received streaming info:");
            System.out.println("  Stream URL: " + data.get("streamUrl"));
            System.out.println("  Resolution: " + data.get("resolution"));
            System.out.println("  From CDN: " + data.get("fromCdn"));
            System.out.println("  Available qualities: " + data.get("availableQualities"));
            
            // Show HLS manifest
            System.out.println("\n[StreamingService] Generated HLS Manifest:");
            String manifest = streamingService.generateHlsManifest(videoId);
            System.out.println(manifest);
        }
    }
    
    /**
     * Demo 3: Metadata update flow (Figure 14-6)
     */
    private void demoMetadataUpdate(String videoId) {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 3: METADATA UPDATE FLOW (Figure 14-6)");
        System.out.println("=".repeat(80));
        
        // Update title and description
        apiService.updateMetadata(videoId, 
            "Hawaii 2024 - Best Vacation Ever!", 
            "Amazing beaches, sunsets, and adventures in Maui!");
        
        // Verify update
        ApiService.ApiResponse getResponse = apiService.getVideo(videoId);
        VideoMetadata metadata = (VideoMetadata) getResponse.data;
        
        System.out.println("\n[Result] Updated metadata:");
        System.out.println("  Title: " + metadata.getTitle());
        System.out.println("  Description: " + metadata.getDescription());
        System.out.println("  Views: " + metadata.getViewCount());
    }
    
    /**
     * Demo 4: Search and discovery
     */
    private void demoSearchAndDiscovery() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 4: SEARCH AND DISCOVERY");
        System.out.println("=".repeat(80));
        
        // Upload a few more videos
        apiService.initUpload(1002L, "Cooking Tutorial - Pasta", "Learn to make perfect pasta");
        apiService.initUpload(1002L, "Hawaii Travel Guide", "Complete guide to visiting Hawaii");
        apiService.initUpload(1003L, "Tech Review - New Phone", "Unboxing the latest smartphone");
        
        // Search
        System.out.println("\n[Search] Query: 'Hawaii'");
        ApiService.ApiResponse searchResponse = apiService.search("Hawaii");
        @SuppressWarnings("unchecked")
        List<VideoMetadata> results = (List<VideoMetadata>) searchResponse.data;
        
        System.out.println("  Found " + results.size() + " videos:");
        for (VideoMetadata m : results) {
            System.out.println("    - " + m.getTitle());
        }
        
        // Trending
        System.out.println("\n[Trending] Top videos:");
        ApiService.ApiResponse trendingResponse = apiService.getTrending(5);
        @SuppressWarnings("unchecked")
        List<VideoMetadata> trending = (List<VideoMetadata>) trendingResponse.data;
        for (VideoMetadata m : trending) {
            System.out.println("    - " + m.getTitle() + " (" + m.getViewCount() + " views)");
        }
    }
    
    /**
     * Demo 5: Parallel GOP upload (Figure 14-23)
     */
    private void demoParallelUpload() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 5: PARALLEL GOP UPLOAD (Figure 14-23)");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Client] Large video detected, splitting into GOPs for parallel upload...");
        
        // Initialize parallel upload
        UploadService.UploadRequest request = new UploadService.UploadRequest(
            1004L, "4K Drone Footage - Grand Canyon", "Stunning aerial views");
        UploadService.UploadResponse response = uploadService.initializeParallelUpload(request, 5);
        
        String videoId = response.videoId;
        
        // Simulate parallel GOP uploads
        System.out.println("\n[Client] Uploading 5 GOPs in parallel:");
        for (int i = 0; i < 5; i++) {
            GOP gop = new GOP(i, videoId, i * 30000, (i + 1) * 30000, 900);  // 30 sec segments
            gop.setData(new byte[60 * 1024 * 1024]);  // 60MB per GOP
            uploadService.onGopUploaded(gop);
        }
    }
    
    private void printHeader() {
        System.out.println("\n" + "╔" + "═".repeat(78) + "╗");
        System.out.println("║" + " ".repeat(20) + "VIDEO STREAMING SYSTEM DEMO" + " ".repeat(31) + "║");
        System.out.println("║" + " ".repeat(15) + "(YouTube-like - Based on Alex Xu Ch.14)" + " ".repeat(23) + "║");
        System.out.println("╚" + "═".repeat(78) + "╝");
    }
    
    private void printSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO COMPLETE - KEY TAKEAWAYS");
        System.out.println("=".repeat(80));
        
        System.out.println("""
            
            ✓ Pre-signed URL: Direct upload to S3, bypasses API servers
            ✓ DAG Pipeline: Parallel video/audio encoding for speed
            ✓ Multiple Resolutions: 360p → 4K for adaptive streaming
            ✓ CDN: Popular videos cached at edge for low latency
            ✓ Completion Queue: Decoupled transcoding notification
            ✓ GOP Upload: Parallel upload for large videos
            
            For interview prep, see: INTERVIEW_CHEATSHEET.md
            """);
    }
    
    public static void main(String[] args) throws InterruptedException {
        VideoStreamingDemo demo = new VideoStreamingDemo();
        demo.run();
    }
}

