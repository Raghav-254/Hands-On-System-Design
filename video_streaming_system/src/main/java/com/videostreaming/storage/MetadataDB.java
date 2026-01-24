package com.videostreaming.storage;

import com.videostreaming.model.VideoMetadata;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Metadata Database - stores video metadata.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  METADATA DB (MySQL / PostgreSQL)                                            ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Store video metadata (title, description, view count, etc.)              ║
 * ║  • Support search and filtering                                             ║
 * ║  • Relational data (user → videos, video → comments, etc.)                  ║
 * ║                                                                               ║
 * ║  WHY SQL (Not NoSQL)?                                                       ║
 * ║  ────────────────────                                                        ║
 * ║  • Complex queries (search by title, filter by category)                    ║
 * ║  • Relationships (user's videos, video's comments)                          ║
 * ║  • ACID for critical operations (view count updates)                        ║
 * ║  • Familiar tooling for analytics                                           ║
 * ║                                                                               ║
 * ║  TABLES:                                                                    ║
 * ║  ─────────                                                                   ║
 * ║  videos           | Core video metadata                                     ║
 * ║  video_stats      | View/like counts (separate for high-write volume)       ║
 * ║  video_encodings  | Available resolutions per video                         ║
 * ║  users            | User profiles                                           ║
 * ║  channels         | Channel information                                     ║
 * ║  comments         | Video comments                                          ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class MetadataDB {
    
    // Simulates MySQL: videoId → metadata
    private final Map<String, VideoMetadata> videos = new HashMap<>();
    
    // Secondary index: userId → list of videoIds
    private final Map<Long, List<String>> userVideos = new HashMap<>();
    
    // Secondary index: channelId → list of videoIds
    private final Map<String, List<String>> channelVideos = new HashMap<>();
    
    /**
     * Save video metadata.
     */
    public void save(VideoMetadata metadata) {
        videos.put(metadata.getVideoId(), metadata);
        
        // Update user index
        userVideos.computeIfAbsent(metadata.getUserId(), k -> new ArrayList<>())
                  .add(metadata.getVideoId());
        
        // Update channel index
        if (metadata.getChannelId() != null) {
            channelVideos.computeIfAbsent(metadata.getChannelId(), k -> new ArrayList<>())
                         .add(metadata.getVideoId());
        }
        
        System.out.println(String.format("  [MetadataDB] Saved metadata for video %s", metadata.getVideoId()));
    }
    
    /**
     * Get video metadata by ID.
     */
    public VideoMetadata get(String videoId) {
        return videos.get(videoId);
    }
    
    /**
     * Update video metadata.
     */
    public void update(VideoMetadata metadata) {
        videos.put(metadata.getVideoId(), metadata);
        System.out.println(String.format("  [MetadataDB] Updated metadata for video %s", metadata.getVideoId()));
    }
    
    /**
     * Get all videos by user.
     */
    public List<VideoMetadata> getVideosByUser(long userId) {
        List<String> videoIds = userVideos.getOrDefault(userId, new ArrayList<>());
        return videoIds.stream()
                      .map(videos::get)
                      .filter(Objects::nonNull)
                      .collect(Collectors.toList());
    }
    
    /**
     * Get all videos in a channel.
     */
    public List<VideoMetadata> getVideosByChannel(String channelId) {
        List<String> videoIds = channelVideos.getOrDefault(channelId, new ArrayList<>());
        return videoIds.stream()
                      .map(videos::get)
                      .filter(Objects::nonNull)
                      .collect(Collectors.toList());
    }
    
    /**
     * Search videos by title.
     */
    public List<VideoMetadata> searchByTitle(String query) {
        String lowerQuery = query.toLowerCase();
        return videos.values().stream()
                    .filter(v -> v.getTitle() != null && v.getTitle().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
    }
    
    /**
     * Increment view count.
     */
    public void incrementViewCount(String videoId) {
        VideoMetadata metadata = videos.get(videoId);
        if (metadata != null) {
            metadata.incrementViewCount();
        }
    }
    
    /**
     * Get trending videos (by view count).
     */
    public List<VideoMetadata> getTrendingVideos(int limit) {
        return videos.values().stream()
                    .sorted((a, b) -> Long.compare(b.getViewCount(), a.getViewCount()))
                    .limit(limit)
                    .collect(Collectors.toList());
    }
    
    /**
     * Check if video exists.
     */
    public boolean exists(String videoId) {
        return videos.containsKey(videoId);
    }
    
    /**
     * Delete video metadata.
     */
    public void delete(String videoId) {
        VideoMetadata metadata = videos.remove(videoId);
        if (metadata != null) {
            List<String> userVideoList = userVideos.get(metadata.getUserId());
            if (userVideoList != null) {
                userVideoList.remove(videoId);
            }
        }
    }
}

