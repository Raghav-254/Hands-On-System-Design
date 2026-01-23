package com.newsfeed.service;

import com.newsfeed.cache.NewsFeedCache;
import com.newsfeed.cache.UserCache;
import com.newsfeed.models.FeedItem;
import com.newsfeed.models.Post;
import com.newsfeed.queue.FanoutQueue;
import com.newsfeed.storage.GraphDB;
import java.util.Set;
import java.util.HashSet;

/**
 * FanoutService - Distributes posts to followers' feeds.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  FANOUT SERVICE - THE HEART OF NEWS FEED                                    ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  ARCHITECTURE: Event-Driven Kafka Consumer                                   ║
 * ║  ─────────────────────────────────────────                                   ║
 * ║                                                                               ║
 * ║  PostService (Producer) ──▶ Kafka ("post-events") ──▶ FanoutService (Consumer)║
 * ║                                                              │                ║
 * ║                                                              ▼                ║
 * ║                                                     Process PostCreated event ║
 * ║                                                              │                ║
 * ║                                      ┌───────────────────────┴──────────────┐ ║
 * ║                                      ▼                                      ▼ ║
 * ║                              Regular User                             Celebrity║
 * ║                              (< 100 followers)                    (100+ followers)║
 * ║                                      │                                      │ ║
 * ║                                      ▼                                      ▼ ║
 * ║                              FANOUT ON WRITE                      FANOUT ON READ║
 * ║                              Push to all                          Skip fanout ║
 * ║                              followers' feeds                     (merge at read)║
 * ║                                                                               ║
 * ║  WHY THIS ARCHITECTURE?                                                      ║
 * ║  ✅ FanoutService is DECOUPLED from PostService                              ║
 * ║  ✅ PostService doesn't need to know about fanout logic                      ║
 * ║  ✅ Can scale FanoutService consumers independently                          ║
 * ║  ✅ If fanout fails, event is still in Kafka (retry)                         ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class FanoutService {
    
    private static final int CELEBRITY_THRESHOLD = 10000; // Followers count to be a celebrity
    private static final int FANOUT_THRESHOLD = 100;      // Demo: lower threshold for testing
    
    private final GraphDB graphDB;
    private final NewsFeedCache newsFeedCache;
    private final UserCache userCache;
    private final FanoutQueue fanoutQueue;
    
    private long fanoutOnWriteCount = 0;
    private long fanoutOnReadCount = 0;
    private long eventsProcessed = 0;
    
    public FanoutService(GraphDB graphDB, NewsFeedCache newsFeedCache, 
                         UserCache userCache, FanoutQueue fanoutQueue) {
        this.graphDB = graphDB;
        this.newsFeedCache = newsFeedCache;
        this.userCache = userCache;
        this.fanoutQueue = fanoutQueue;
        
        // Register as a Kafka consumer for "post-events" topic
        // In production: @KafkaListener(topics = "post-events")
        registerAsEventConsumer();
    }
    
    /**
     * Register this service as a Kafka consumer for PostCreated events.
     * 
     * In production (Spring Kafka):
     * @KafkaListener(topics = "post-events", groupId = "fanout-service")
     * public void onPostCreated(PostCreatedEvent event) { ... }
     */
    private void registerAsEventConsumer() {
        if (fanoutQueue != null) {
            fanoutQueue.registerEventConsumer(this::handlePostCreatedEvent);
            System.out.println("[FanoutService] Registered as consumer for 'post-events' topic");
        }
    }
    
    /**
     * Handle PostCreated event from Kafka.
     * This is called asynchronously when a new post is published.
     * 
     * In production: This would be a @KafkaListener method
     */
    public void handlePostCreatedEvent(FanoutQueue.PostCreatedEvent event) {
        eventsProcessed++;
        Post post = event.getPost();
        System.out.println(String.format("\n  [FanoutService] Consumed PostCreated event for post %d", 
            post.getPostId()));
        
        // Process the fanout
        fanout(post);
    }
    
    /**
     * Fanout a new post to followers
     * Called either directly (sync mode) or via event consumer (async mode)
     */
    public void fanout(Post post) {
        long authorId = post.getAuthorId();
        
        // Step 1: Get all followers from Graph DB
        Set<Long> followers = graphDB.getFollowers(authorId);
        
        System.out.println(String.format("  [FanoutService] Post %d by user %d has %d followers",
            post.getPostId(), authorId, followers.size()));
        
        if (followers.isEmpty()) {
            System.out.println("  [FanoutService] No followers, skipping fanout");
            return;
        }
        
        // Step 2: Check if author is a celebrity (determines fanout strategy)
        boolean isCelebrity = graphDB.isCelebrity(authorId, FANOUT_THRESHOLD);
        
        if (isCelebrity) {
            // FANOUT ON READ - Just mark that this user has new content
            fanoutOnRead(post, followers);
        } else {
            // FANOUT ON WRITE - Push to all followers' feeds
            fanoutOnWrite(post, followers);
        }
    }
    
    /**
     * FANOUT ON WRITE - Push model
     * Pre-compute feeds by pushing post to each follower's feed cache
     */
    private void fanoutOnWrite(Post post, Set<Long> followers) {
        System.out.println(String.format("  [FanoutService] Using FANOUT ON WRITE for %d followers",
            followers.size()));
        
        fanoutOnWriteCount++;
        
        // For small number of followers, do it synchronously
        if (followers.size() <= 10) {
            FeedItem feedItem = new FeedItem(post.getPostId(), post.getAuthorId());
            newsFeedCache.addToFeeds(followers, feedItem);
            System.out.println(String.format("  [FanoutService] Sync fanout complete for %d followers",
                followers.size()));
        } else {
            // For larger numbers, use the task queue for parallel processing
            FanoutQueue.FanoutTask task = new FanoutQueue.FanoutTask(post, followers);
            fanoutQueue.enqueue(task);
        }
    }
    
    /**
     * FANOUT ON READ - Pull model
     * Don't pre-compute, followers will pull at read time
     */
    private void fanoutOnRead(Post post, Set<Long> followers) {
        System.out.println(String.format("  [FanoutService] Using FANOUT ON READ (celebrity mode) - %d followers",
            followers.size()));
        
        fanoutOnReadCount++;
        
        // In fanout on read, we don't push to followers' feeds
        // Instead, when they request their feed, we'll merge in celebrity posts
        // The post is already saved in PostDB, so we just log this
        
        System.out.println("  [FanoutService] Post saved to author's timeline, followers will pull on read");
    }
    
    /**
     * Get celebrities that a user follows (for fan-out on read merging at retrieval time)
     */
    public Set<Long> getCelebritiesFollowedBy(long userId) {
        Set<Long> following = graphDB.getFollowing(userId);
        Set<Long> celebrities = new HashSet<>();
        
        for (Long followedId : following) {
            if (graphDB.isCelebrity(followedId, FANOUT_THRESHOLD)) {
                celebrities.add(followedId);
            }
        }
        
        return celebrities;
    }
    
    /**
     * Get statistics
     */
    public String getStats() {
        return String.format("FanoutService: eventsProcessed=%d, onWrite=%d, onRead=%d",
            eventsProcessed, fanoutOnWriteCount, fanoutOnReadCount);
    }
}
