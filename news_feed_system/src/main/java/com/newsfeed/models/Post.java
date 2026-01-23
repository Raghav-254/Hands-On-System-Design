package com.newsfeed.models;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Post model representing a post/content in the news feed system.
 * 
 * Posts are the core content that gets distributed to followers' feeds.
 */
public class Post {
    private static final AtomicLong ID_GENERATOR = new AtomicLong(System.currentTimeMillis());
    
    private final long postId;
    private final long authorId;
    private final String content;
    private final PostType type;
    private final String mediaUrl;
    private final Instant createdAt;
    private long likeCount;
    private long commentCount;
    private long shareCount;
    
    public enum PostType {
        TEXT,
        IMAGE,
        VIDEO,
        LINK
    }
    
    public Post(long authorId, String content, PostType type) {
        this.postId = ID_GENERATOR.incrementAndGet();
        this.authorId = authorId;
        this.content = content;
        this.type = type;
        this.mediaUrl = null;
        this.createdAt = Instant.now();
        this.likeCount = 0;
        this.commentCount = 0;
        this.shareCount = 0;
    }
    
    public Post(long authorId, String content, PostType type, String mediaUrl) {
        this.postId = ID_GENERATOR.incrementAndGet();
        this.authorId = authorId;
        this.content = content;
        this.type = type;
        this.mediaUrl = mediaUrl;
        this.createdAt = Instant.now();
        this.likeCount = 0;
        this.commentCount = 0;
        this.shareCount = 0;
    }
    
    // Getters
    public long getPostId() { return postId; }
    public long getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public PostType getType() { return type; }
    public String getMediaUrl() { return mediaUrl; }
    public Instant getCreatedAt() { return createdAt; }
    public long getLikeCount() { return likeCount; }
    public long getCommentCount() { return commentCount; }
    public long getShareCount() { return shareCount; }
    
    // Increment counters
    public void incrementLikes() { likeCount++; }
    public void incrementComments() { commentCount++; }
    public void incrementShares() { shareCount++; }
    
    @Override
    public String toString() {
        return String.format("Post{id=%d, authorId=%d, content='%s', type=%s, likes=%d}", 
            postId, authorId, 
            content.length() > 30 ? content.substring(0, 30) + "..." : content,
            type, likeCount);
    }
}

