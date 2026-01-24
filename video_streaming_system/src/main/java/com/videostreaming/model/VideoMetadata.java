package com.videostreaming.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Video metadata stored in Metadata DB.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  METADATA STRUCTURE                                                          ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  video_id       | Primary key (UUID or Snowflake ID)                         ║
 * ║  user_id        | Owner of the video                                         ║
 * ║  title          | Video title (searchable)                                   ║
 * ║  description    | Video description                                          ║
 * ║  channel_id     | Channel this video belongs to                              ║
 * ║  view_count     | Number of views (denormalized for fast reads)              ║
 * ║  like_count     | Number of likes                                            ║
 * ║  duration       | Video duration in seconds                                  ║
 * ║  resolution     | Available resolutions ["360p", "720p", "1080p"]            ║
 * ║  thumbnail_url  | Thumbnail image URL                                        ║
 * ║  created_at     | Upload timestamp                                           ║
 * ║  updated_at     | Last update timestamp                                      ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class VideoMetadata {
    
    private final String videoId;
    private String title;
    private String description;
    private long userId;
    private String channelId;
    private long viewCount;
    private long likeCount;
    private long dislikeCount;
    private long duration;
    private List<String> availableResolutions;
    private String thumbnailUrl;
    private List<String> tags;
    private String category;
    private long createdAt;
    private long updatedAt;
    
    public VideoMetadata(String videoId) {
        this.videoId = videoId;
        this.availableResolutions = new ArrayList<>();
        this.tags = new ArrayList<>();
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }
    
    // Getters and setters
    public String getVideoId() { return videoId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; this.updatedAt = System.currentTimeMillis(); }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; this.updatedAt = System.currentTimeMillis(); }
    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public long getViewCount() { return viewCount; }
    public void incrementViewCount() { this.viewCount++; }
    public long getLikeCount() { return likeCount; }
    public void incrementLikeCount() { this.likeCount++; }
    public long getDislikeCount() { return dislikeCount; }
    public void incrementDislikeCount() { this.dislikeCount++; }
    public long getDuration() { return duration; }
    public void setDuration(long duration) { this.duration = duration; }
    public List<String> getAvailableResolutions() { return availableResolutions; }
    public void addResolution(String resolution) { this.availableResolutions.add(resolution); }
    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    
    @Override
    public String toString() {
        return String.format("VideoMetadata[id=%s, title=%s, views=%d, resolutions=%s]",
            videoId, title, viewCount, availableResolutions);
    }
}

