package com.newsfeed.service;

import com.newsfeed.cache.PostCache;
import com.newsfeed.models.Post;
import com.newsfeed.queue.FanoutQueue;
import com.newsfeed.storage.PostDB;
import java.util.List;

/**
 * PostService - Handles creating and managing posts.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  POST SERVICE ARCHITECTURE - TWO APPROACHES                                  ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  APPROACH 1: SYNCHRONOUS (Simple, but coupled)                               ║
 * ║  ─────────────────────────────────────────────                               ║
 * ║  Web Server → PostService → DB/Cache → FanoutService.fanout()                ║
 * ║                                              │                                ║
 * ║  • PostService directly calls FanoutService                                  ║
 * ║  • Simple to implement                                                       ║
 * ║  • Coupled: PostService knows about fanout                                   ║
 * ║  • API latency affected by fanout time                                       ║
 * ║                                                                               ║
 * ║  APPROACH 2: ASYNC/EVENT-DRIVEN (Production - Recommended) ✅                ║
 * ║  ───────────────────────────────────────────────────────────                 ║
 * ║                                                                               ║
 * ║  Web Server → PostService → DB/Cache → Kafka ("post-events" topic)           ║
 * ║                                              │                                ║
 * ║                              (API returns immediately to user)               ║
 * ║                                              │                                ║
 * ║                      ┌───────────────────────┴───────────────────────┐       ║
 * ║                      │                                               │       ║
 * ║                      ▼                                               ▼       ║
 * ║         FanoutService (Consumer)                   NotificationService       ║
 * ║                │                                           │                 ║
 * ║                ▼                                           ▼                 ║
 * ║         Feed Caches (Redis)                      Push Notifications          ║
 * ║         (per-user sorted sets)                   (APNs/FCM - filtered)       ║
 * ║                                                                               ║
 * ║  • PostService publishes "PostCreated" event to Kafka                        ║
 * ║  • Multiple consumers process the SAME event in PARALLEL:                    ║
 * ║    - FanoutService → updates followers' feed caches                          ║
 * ║    - NotificationService → sends push notifications (rate-limited)          ║
 * ║  • Fully decoupled: PostService doesn't know about consumers                 ║
 * ║  • Fast API response (< 100ms)                                               ║
 * ║  • If one consumer fails, others still work (retry independently)           ║
 * ║  • Each consumer scales independently                                        ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class PostService {
    
    private final PostDB postDB;
    private final PostCache postCache;
    private final FanoutQueue eventQueue;  // Kafka-like event queue
    
    // For demo: support both sync and async modes
    private FanoutService fanoutService;  // Sync mode (direct call)
    private boolean asyncMode = true;      // Default: use async (production mode)
    
    public PostService(PostDB postDB, PostCache postCache) {
        this.postDB = postDB;
        this.postCache = postCache;
        this.eventQueue = null;
    }
    
    /**
     * Production constructor with event queue (async mode)
     */
    public PostService(PostDB postDB, PostCache postCache, FanoutQueue eventQueue) {
        this.postDB = postDB;
        this.postCache = postCache;
        this.eventQueue = eventQueue;
        this.asyncMode = true;
    }
    
    /**
     * Set FanoutService for sync mode (demo/testing)
     */
    public void setFanoutService(FanoutService fanoutService) {
        this.fanoutService = fanoutService;
    }
    
    /**
     * Toggle between sync and async mode (for demo purposes)
     */
    public void setAsyncMode(boolean asyncMode) {
        this.asyncMode = asyncMode;
    }
    
    /**
     * Create a new post
     * 
     * PRODUCTION FLOW (Async/Event-Driven):
     * 1. Save to PostDB (persistence)
     * 2. Add to PostCache (fast reads)
     * 3. Publish "PostCreated" event to Kafka → returns immediately!
     * 4. FanoutService (separate consumer) processes event asynchronously
     */
    public Post createPost(long authorId, String content, Post.PostType type) {
        System.out.println(String.format("\n[PostService] Creating post for user %d", authorId));
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 1: Create post object with Snowflake-like ID
        // ══════════════════════════════════════════════════════════════════════
        Post post = new Post(authorId, content, type);
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 2: Save to database (persistence)
        // This is the source of truth - if this fails, post doesn't exist
        // ══════════════════════════════════════════════════════════════════════
        postDB.save(post);
        System.out.println(String.format("  [PostService] Saved to PostDB"));
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 3: Add to cache (for fast reads)
        // ══════════════════════════════════════════════════════════════════════
        postCache.put(post);
        System.out.println(String.format("  [PostService] Added to PostCache"));
        
        // ══════════════════════════════════════════════════════════════════════
        // STEP 4: Trigger fanout (SYNC or ASYNC based on mode)
        // ══════════════════════════════════════════════════════════════════════
        if (asyncMode && eventQueue != null) {
            // PRODUCTION: Publish event to Kafka, return immediately
            // FanoutService (separate consumer) will process asynchronously
            System.out.println("  [PostService] ASYNC MODE: Publishing 'PostCreated' event to Kafka");
            System.out.println("  [PostService] → API returns immediately (fanout happens in background)");
            
            // In real Kafka: producer.send(new ProducerRecord<>("post-events", postId, event))
            // FanoutQueue simulates this with a "post created" task
            eventQueue.publishPostCreatedEvent(post);
            
        } else if (fanoutService != null) {
            // SYNC MODE: Direct call to FanoutService (blocks until done)
            System.out.println("  [PostService] SYNC MODE: Calling FanoutService.fanout() directly");
            fanoutService.fanout(post);
        }
        
        System.out.println(String.format("[PostService] Post %d created successfully", post.getPostId()));
        
        return post;
    }
    
    /**
     * Get a single post
     */
    public Post getPost(long postId) {
        return postCache.get(postId);
    }
    
    /**
     * Get posts by a specific author
     */
    public List<Post> getPostsByAuthor(long authorId, int limit) {
        return postDB.getByAuthor(authorId, limit);
    }
    
    /**
     * Delete a post
     */
    public void deletePost(long postId) {
        postDB.delete(postId);
        postCache.invalidate(postId);
        // Also need to remove from all feeds (expensive operation)
    }
    
    /**
     * Like a post
     */
    public void likePost(long postId, long userId) {
        Post post = postCache.get(postId);
        if (post != null) {
            post.incrementLikes();
            postCache.put(post); // Update cache (may promote to hot cache)
        }
    }
}
