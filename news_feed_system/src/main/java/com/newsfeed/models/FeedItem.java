package com.newsfeed.models;

import java.time.Instant;

/**
 * FeedItem represents an entry in a user's news feed.
 * 
 * This is what gets stored in the News Feed Cache - a lightweight
 * reference that points to the actual post.
 * 
 * Structure: <post_id, score/timestamp>
 * 
 * We store minimal data here and fetch full post details from Post Cache
 * when rendering the feed.
 */
public class FeedItem {
    private final long postId;
    private final long authorId;
    private final double score;      // For ranking (could be timestamp-based or ML-based)
    private final Instant addedAt;   // When this was added to user's feed
    
    public FeedItem(long postId, long authorId) {
        this.postId = postId;
        this.authorId = authorId;
        this.score = System.currentTimeMillis(); // Simple: use timestamp as score
        this.addedAt = Instant.now();
    }
    
    public FeedItem(long postId, long authorId, double score) {
        this.postId = postId;
        this.authorId = authorId;
        this.score = score;
        this.addedAt = Instant.now();
    }
    
    public long getPostId() { return postId; }
    public long getAuthorId() { return authorId; }
    public double getScore() { return score; }
    public Instant getAddedAt() { return addedAt; }
    
    @Override
    public String toString() {
        return String.format("FeedItem{postId=%d, authorId=%d, score=%.0f}", 
            postId, authorId, score);
    }
}

