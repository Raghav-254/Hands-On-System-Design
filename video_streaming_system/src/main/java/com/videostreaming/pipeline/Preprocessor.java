package com.videostreaming.pipeline;

import com.videostreaming.model.TranscodeTask;
import java.util.*;

/**
 * Preprocessor - first stage of the transcoding pipeline.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  PREPROCESSOR (Figure 14-10)                                                 ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Split video into components (video stream, audio stream, metadata)       ║
 * ║  • Validate video file integrity                                            ║
 * ║  • Extract video properties (resolution, duration, codec)                   ║
 * ║  • Generate DAG configuration for the scheduler                             ║
 * ║                                                                               ║
 * ║  INPUT:                                                                      ║
 * ║  • Raw video file from Original Storage                                     ║
 * ║                                                                               ║
 * ║  OUTPUT:                                                                     ║
 * ║  • Video stream file                                                        ║
 * ║  • Audio stream file                                                        ║
 * ║  • Metadata JSON                                                            ║
 * ║  • DAG configuration for scheduler                                          ║
 * ║                                                                               ║
 * ║  FLOW:                                                                       ║
 * ║  ──────                                                                      ║
 * ║  Original video ──→ Preprocessor ──┬──→ Video stream                        ║
 * ║                                    ├──→ Audio stream                        ║
 * ║                                    └──→ Metadata                            ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class Preprocessor {
    
    public static class VideoInfo {
        public final String videoId;
        public final int originalWidth;
        public final int originalHeight;
        public final long durationMs;
        public final String codec;
        public final long fileSizeBytes;
        public final String videoStreamPath;
        public final String audioStreamPath;
        public final String metadataPath;
        
        public VideoInfo(String videoId, int width, int height, long duration, 
                        String codec, long fileSize) {
            this.videoId = videoId;
            this.originalWidth = width;
            this.originalHeight = height;
            this.durationMs = duration;
            this.codec = codec;
            this.fileSizeBytes = fileSize;
            this.videoStreamPath = String.format("temp/%s/video.raw", videoId);
            this.audioStreamPath = String.format("temp/%s/audio.raw", videoId);
            this.metadataPath = String.format("temp/%s/metadata.json", videoId);
        }
        
        public String getOriginalResolution() {
            return originalHeight + "p";  // e.g., "1080p"
        }
    }
    
    /**
     * Preprocess video and extract components.
     */
    public VideoInfo process(String videoId, byte[] rawVideo) {
        System.out.println(String.format("\n[Preprocessor] Processing video %s...", videoId));
        
        // Simulate video analysis (in real system, use FFmpeg)
        int width = 1920;  // Simulated
        int height = 1080;
        long duration = 180000;  // 3 minutes
        String codec = "H.264";
        
        System.out.println(String.format("  [Preprocessor] Video info: %dx%d, %s, %.1f sec", 
            width, height, codec, duration / 1000.0));
        
        // Simulate splitting into streams
        System.out.println("  [Preprocessor] Splitting into video/audio/metadata streams...");
        
        VideoInfo info = new VideoInfo(videoId, width, height, duration, codec, rawVideo.length);
        
        System.out.println(String.format("  [Preprocessor] ✓ Preprocessing complete for %s", videoId));
        
        return info;
    }
    
    /**
     * Determine which resolutions to generate based on original quality.
     */
    public List<String> determineTargetResolutions(VideoInfo info) {
        List<String> resolutions = new ArrayList<>();
        
        // Only generate resolutions <= original
        int originalHeight = info.originalHeight;
        
        if (originalHeight >= 360) resolutions.add("360p");
        if (originalHeight >= 480) resolutions.add("480p");
        if (originalHeight >= 720) resolutions.add("720p");
        if (originalHeight >= 1080) resolutions.add("1080p");
        if (originalHeight >= 2160) resolutions.add("4k");
        
        System.out.println(String.format("  [Preprocessor] Target resolutions: %s", resolutions));
        
        return resolutions;
    }
    
    /**
     * Validate video file.
     */
    public boolean validate(byte[] rawVideo) {
        // Check file size
        if (rawVideo == null || rawVideo.length == 0) {
            System.out.println("  [Preprocessor] ✗ Validation failed: Empty file");
            return false;
        }
        
        // Check max size (1GB limit from requirements)
        if (rawVideo.length > 1024L * 1024 * 1024) {
            System.out.println("  [Preprocessor] ✗ Validation failed: File too large (>1GB)");
            return false;
        }
        
        // In real system: check codec support, corruption, etc.
        System.out.println("  [Preprocessor] ✓ Validation passed");
        return true;
    }
}

