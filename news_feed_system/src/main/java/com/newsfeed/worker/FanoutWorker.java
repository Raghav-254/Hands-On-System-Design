package com.newsfeed.worker;

import com.newsfeed.cache.NewsFeedCache;
import com.newsfeed.models.FeedItem;
import com.newsfeed.models.Post;
import com.newsfeed.queue.FanoutQueue;
import java.util.Set;

/**
 * FanoutWorker - Processes fanout tasks from the queue.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  FANOUT WORKER FLOW (Step 4-5 in diagram)                                    ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Message Queue → (4) → Fanout Workers → (5) → News Feed Cache               ║
 * ║                                                                               ║
 * ║  Worker responsibilities:                                                    ║
 * ║  1. Consume tasks from queue                                                 ║
 * ║  2. For each follower, add post to their feed cache                         ║
 * ║  3. Handle failures and retries                                              ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class FanoutWorker {
    
    private final NewsFeedCache newsFeedCache;
    private long postsDistributed = 0;
    private long feedsUpdated = 0;
    
    public FanoutWorker(NewsFeedCache newsFeedCache) {
        this.newsFeedCache = newsFeedCache;
    }
    
    /**
     * Process a fanout task - distribute post to all followers' feeds
     */
    public void process(FanoutQueue.FanoutTask task) {
        Post post = task.getPost();
        Set<Long> followerIds = task.getFollowerIds();
        
        System.out.println(String.format("  [FanoutWorker] Processing post %d for %d followers",
            post.getPostId(), followerIds.size()));
        
        // Create feed item
        FeedItem feedItem = new FeedItem(post.getPostId(), post.getAuthorId());
        
        // Add to each follower's feed
        for (Long followerId : followerIds) {
            newsFeedCache.addToFeed(followerId, feedItem);
            feedsUpdated++;
        }
        
        postsDistributed++;
        
        System.out.println(String.format("  [FanoutWorker] Distributed post %d to %d feeds",
            post.getPostId(), followerIds.size()));
    }
    
    /**
     * Get statistics
     */
    public String getStats() {
        return String.format("FanoutWorker: postsDistributed=%d, feedsUpdated=%d",
            postsDistributed, feedsUpdated);
    }
}

