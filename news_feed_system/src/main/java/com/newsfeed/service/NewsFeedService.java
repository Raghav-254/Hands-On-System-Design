package com.newsfeed.service;

import com.newsfeed.cache.NewsFeedCache;
import com.newsfeed.cache.PostCache;
import com.newsfeed.cache.UserCache;
import com.newsfeed.models.FeedItem;
import com.newsfeed.models.Post;
import com.newsfeed.models.User;
import com.newsfeed.storage.GraphDB;
import com.newsfeed.storage.PostDB;
import java.util.*;
import java.util.stream.Collectors;

/**
 * NewsFeedService - Retrieves and builds the news feed for a user.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  NEWS FEED RETRIEVAL FLOW (from second diagram)                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  User requests feed: GET /v1/me/feed                                         ║
 * ║                                                                               ║
 * ║  1. Load Balancer → Web Server                                               ║
 * ║  2. Web Server → News Feed Service                                           ║
 * ║  3. News Feed Service:                                                       ║
 * ║     a. Get post IDs from News Feed Cache                                     ║
 * ║     b. Get full posts from Post Cache                                        ║
 * ║     c. Get user profiles from User Cache                                     ║
 * ║     d. Merge celebrity posts (fanout on read)                                ║
 * ║     e. Rank and return                                                       ║
 * ║  4. Return to client (via CDN for media)                                     ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class NewsFeedService {
    
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int CELEBRITY_POSTS_LIMIT = 5; // Posts per celebrity to merge
    
    private final NewsFeedCache newsFeedCache;
    private final PostCache postCache;
    private final UserCache userCache;
    private final PostDB postDB;
    private final GraphDB graphDB;
    private FanoutService fanoutService;
    
    public NewsFeedService(NewsFeedCache newsFeedCache, PostCache postCache,
                           UserCache userCache, PostDB postDB, GraphDB graphDB) {
        this.newsFeedCache = newsFeedCache;
        this.postCache = postCache;
        this.userCache = userCache;
        this.postDB = postDB;
        this.graphDB = graphDB;
    }
    
    public void setFanoutService(FanoutService fanoutService) {
        this.fanoutService = fanoutService;
    }
    
    /**
     * Get news feed for a user
     */
    public List<FeedEntry> getFeed(long userId, int offset, int limit) {
        System.out.println(String.format("\n[NewsFeedService] Getting feed for user %d (offset=%d, limit=%d)",
            userId, offset, limit));
        
        // Step 1: Get post IDs from News Feed Cache (pre-computed via fanout on write)
        List<Long> postIds = newsFeedCache.getFeedPostIds(userId, offset, limit);
        System.out.println(String.format("  [Step 1] Got %d post IDs from feed cache", postIds.size()));
        
        // Step 2: Merge celebrity posts (fanout on read)
        List<Post> celebrityPosts = getCelebrityPosts(userId);
        System.out.println(String.format("  [Step 2] Got %d celebrity posts to merge", celebrityPosts.size()));
        
        // Step 3: Get full posts from Post Cache
        List<Post> cachedPosts = postCache.getMultiple(postIds);
        System.out.println(String.format("  [Step 3] Got %d posts from post cache", cachedPosts.size()));
        
        // Step 4: Merge and sort all posts
        List<Post> allPosts = new ArrayList<>();
        allPosts.addAll(cachedPosts);
        allPosts.addAll(celebrityPosts);
        
        // Remove duplicates and sort by time (or ranking score)
        Set<Long> seenIds = new HashSet<>();
        List<Post> uniquePosts = allPosts.stream()
            .filter(p -> seenIds.add(p.getPostId()))
            .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
            .limit(limit)
            .collect(Collectors.toList());
        
        // Step 5: Get user profiles for all authors
        Set<Long> authorIds = uniquePosts.stream()
            .map(Post::getAuthorId)
            .collect(Collectors.toSet());
        Map<Long, User> users = userCache.getMultiple(authorIds);
        System.out.println(String.format("  [Step 4] Got %d user profiles", users.size()));
        
        // Step 6: Build feed entries
        List<FeedEntry> feed = uniquePosts.stream()
            .map(post -> new FeedEntry(post, users.get(post.getAuthorId())))
            .collect(Collectors.toList());
        
        System.out.println(String.format("[NewsFeedService] Returning %d feed entries", feed.size()));
        
        return feed;
    }
    
    /**
     * Get celebrity posts for a user (fanout on read)
     */
    private List<Post> getCelebrityPosts(long userId) {
        if (fanoutService == null) {
            return Collections.emptyList();
        }
        
        Set<Long> celebrities = fanoutService.getCelebritiesFollowedBy(userId);
        if (celebrities.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Get recent posts from celebrities
        return postDB.getRecentPostsByAuthors(celebrities, CELEBRITY_POSTS_LIMIT);
    }
    
    /**
     * Feed entry - combines post with author info for rendering
     */
    public static class FeedEntry {
        private final Post post;
        private final User author;
        
        public FeedEntry(Post post, User author) {
            this.post = post;
            this.author = author;
        }
        
        public Post getPost() { return post; }
        public User getAuthor() { return author; }
        
        @Override
        public String toString() {
            String authorName = author != null ? author.getDisplayName() : "Unknown";
            return String.format("  📝 %s: \"%s\" [❤️ %d]",
                authorName,
                post.getContent().length() > 40 ? post.getContent().substring(0, 40) + "..." : post.getContent(),
                post.getLikeCount());
        }
    }
    
    /**
     * Refresh feed (pull latest)
     */
    public List<FeedEntry> refreshFeed(long userId) {
        return getFeed(userId, 0, DEFAULT_PAGE_SIZE);
    }
}

