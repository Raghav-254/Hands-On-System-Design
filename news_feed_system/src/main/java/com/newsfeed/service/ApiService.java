package com.newsfeed.service;

import com.newsfeed.cache.UserCache;
import com.newsfeed.models.Post;
import com.newsfeed.models.User;
import com.newsfeed.storage.GraphDB;
import com.newsfeed.storage.UserDB;

import java.util.*;

/**
 * ApiService - REST API endpoints for the News Feed System.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  API SERVICE - HTTP REST ENDPOINTS                                           ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  FEED APIs:                                                                   ║
 * ║  • POST /v1/me/feed  → Publish a post (content, images, videos)              ║
 * ║  • GET  /v1/me/feed  → Retrieve news feed                                    ║
 * ║                                                                               ║
 * ║  USER APIs:                                                                   ║
 * ║  • POST /v1/users             → Register new user                            ║
 * ║  • GET  /v1/users/{id}        → Get user profile                             ║
 * ║  • PUT  /v1/users/{id}        → Update user profile                          ║
 * ║                                                                               ║
 * ║  SOCIAL APIs:                                                                 ║
 * ║  • POST /v1/users/{id}/follow   → Follow a user                              ║
 * ║  • DELETE /v1/users/{id}/follow → Unfollow a user                            ║
 * ║  • GET  /v1/users/{id}/followers → Get followers                             ║
 * ║  • GET  /v1/users/{id}/following → Get following                             ║
 * ║                                                                               ║
 * ║  POST ACTIONS:                                                                ║
 * ║  • POST /v1/posts/{id}/like   → Like a post                                  ║
 * ║  • POST /v1/posts/{id}/comment → Comment on a post                           ║
 * ║  • POST /v1/posts/{id}/share  → Share a post                                 ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class ApiService {
    
    private final UserDB userDB;
    private final UserCache userCache;
    private final GraphDB graphDB;
    private final PostService postService;
    private final NewsFeedService newsFeedService;
    
    // Session management (simplified)
    private final Map<String, Long> sessions; // token -> userId
    
    public ApiService(UserDB userDB, UserCache userCache, GraphDB graphDB,
                      PostService postService, NewsFeedService newsFeedService) {
        this.userDB = userDB;
        this.userCache = userCache;
        this.graphDB = graphDB;
        this.postService = postService;
        this.newsFeedService = newsFeedService;
        this.sessions = new HashMap<>();
        
        System.out.println("[ApiService] Initialized");
    }
    
    // ==================== AUTHENTICATION ====================
    
    /**
     * POST /v1/auth/login
     * Authenticate user and return session token
     */
    public ApiResponse login(String username, String password) {
        System.out.println(String.format("\n[API] POST /v1/auth/login (user=%s)", username));
        
        User user = userDB.getByUsername(username);
        if (user == null) {
            return new ApiResponse(401, "Invalid credentials", null);
        }
        
        // Generate session token
        String token = UUID.randomUUID().toString();
        sessions.put(token, user.getUserId());
        
        Map<String, Object> data = new HashMap<>();
        data.put("token", token);
        data.put("user", user);
        
        System.out.println("[API] Login successful, token issued");
        return new ApiResponse(200, "Login successful", data);
    }
    
    // ==================== FEED PUBLISHING ====================
    
    /**
     * POST /v1/me/feed
     * Publish a new post
     * 
     * Flow:
     * 1. User sends POST request with content
     * 2. API validates session and extracts user ID
     * 3. PostService creates post (DB + Cache)
     * 4. FanoutService distributes to followers
     */
    public ApiResponse publishPost(String sessionToken, String content, Post.PostType type) {
        System.out.println("\n[API] POST /v1/me/feed");
        
        // Step 1: Validate session
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            return new ApiResponse(401, "Unauthorized", null);
        }
        
        System.out.println(String.format("[API] Publishing post for user %d", userId));
        System.out.println("[API] Flow: API → PostService → DB/Cache → FanoutService → Followers");
        
        // Step 2: Create post via PostService
        // PostService internally:
        //   1. Saves to PostDB
        //   2. Adds to PostCache  
        //   3. Calls FanoutService.fanout() which:
        //      - Regular users: pushes to all followers' feed caches (fanout on write)
        //      - Celebrities: skips fanout (fanout on read at retrieval time)
        Post post = postService.createPost(userId, content, type);
        
        Map<String, Object> data = new HashMap<>();
        data.put("postId", post.getPostId());
        data.put("status", "published");
        
        return new ApiResponse(201, "Post created", data);
    }
    
    /**
     * GET /v1/me/feed
     * Retrieve user's news feed
     * 
     * Flow:
     * 1. Get post IDs from Feed Cache (pre-computed)
     * 2. Merge celebrity posts (fanout on read)
     * 3. Hydrate with Post Cache and User Cache
     * 4. Return ranked feed
     */
    public ApiResponse getFeed(String sessionToken, int offset, int limit) {
        System.out.println("\n[API] GET /v1/me/feed");
        
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            return new ApiResponse(401, "Unauthorized", null);
        }
        
        System.out.println(String.format("[API] Fetching feed for user %d (offset=%d, limit=%d)",
            userId, offset, limit));
        
        var feed = newsFeedService.getFeed(userId, offset, limit);
        
        Map<String, Object> data = new HashMap<>();
        data.put("feed", feed);
        data.put("count", feed.size());
        data.put("offset", offset);
        
        return new ApiResponse(200, "Feed retrieved", data);
    }
    
    // ==================== USER MANAGEMENT ====================
    
    /**
     * POST /v1/users
     * Register a new user
     */
    public ApiResponse registerUser(String username, String displayName) {
        System.out.println(String.format("\n[API] POST /v1/users (username=%s)", username));
        
        // Check if username exists
        if (userDB.getByUsername(username) != null) {
            return new ApiResponse(409, "Username already exists", null);
        }
        
        // Create user
        long userId = System.currentTimeMillis(); // Simple ID generation
        User user = new User(userId, username, displayName);
        
        userDB.save(user);
        userCache.put(user);
        
        return new ApiResponse(201, "User created", user);
    }
    
    /**
     * GET /v1/users/{id}
     * Get user profile
     */
    public ApiResponse getUser(long userId) {
        System.out.println(String.format("\n[API] GET /v1/users/%d", userId));
        
        User user = userCache.get(userId);
        if (user == null) {
            return new ApiResponse(404, "User not found", null);
        }
        
        Map<String, Object> data = new HashMap<>();
        data.put("user", user);
        data.put("followerCount", graphDB.getFollowerCount(userId));
        data.put("followingCount", graphDB.getFollowingCount(userId));
        
        return new ApiResponse(200, "User found", data);
    }
    
    // ==================== SOCIAL ACTIONS ====================
    
    /**
     * POST /v1/users/{id}/follow
     * Follow a user
     */
    public ApiResponse followUser(String sessionToken, long targetUserId) {
        System.out.println(String.format("\n[API] POST /v1/users/%d/follow", targetUserId));
        
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            return new ApiResponse(401, "Unauthorized", null);
        }
        
        if (userId == targetUserId) {
            return new ApiResponse(400, "Cannot follow yourself", null);
        }
        
        graphDB.follow(userId, targetUserId);
        
        return new ApiResponse(200, "Followed successfully", null);
    }
    
    /**
     * DELETE /v1/users/{id}/follow
     * Unfollow a user
     */
    public ApiResponse unfollowUser(String sessionToken, long targetUserId) {
        System.out.println(String.format("\n[API] DELETE /v1/users/%d/follow", targetUserId));
        
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            return new ApiResponse(401, "Unauthorized", null);
        }
        
        graphDB.unfollow(userId, targetUserId);
        
        return new ApiResponse(200, "Unfollowed successfully", null);
    }
    
    /**
     * GET /v1/users/{id}/followers
     * Get user's followers
     */
    public ApiResponse getFollowers(long userId) {
        System.out.println(String.format("\n[API] GET /v1/users/%d/followers", userId));
        
        var followers = graphDB.getFollowers(userId);
        
        Map<String, Object> data = new HashMap<>();
        data.put("followers", followers);
        data.put("count", followers.size());
        
        return new ApiResponse(200, "Followers retrieved", data);
    }
    
    // ==================== POST ACTIONS ====================
    
    /**
     * POST /v1/posts/{id}/like
     * Like a post
     */
    public ApiResponse likePost(String sessionToken, long postId) {
        System.out.println(String.format("\n[API] POST /v1/posts/%d/like", postId));
        
        Long userId = sessions.get(sessionToken);
        if (userId == null) {
            return new ApiResponse(401, "Unauthorized", null);
        }
        
        postService.likePost(postId, userId);
        
        return new ApiResponse(200, "Post liked", null);
    }
    
    // ==================== HELPER: Create session for testing ====================
    
    public String createTestSession(long userId) {
        String token = "test-token-" + userId;
        sessions.put(token, userId);
        return token;
    }
    
    // ==================== RESPONSE CLASS ====================
    
    public static class ApiResponse {
        private final int statusCode;
        private final String message;
        private final Object data;
        
        public ApiResponse(int statusCode, String message, Object data) {
            this.statusCode = statusCode;
            this.message = message;
            this.data = data;
        }
        
        public int getStatusCode() { return statusCode; }
        public String getMessage() { return message; }
        public Object getData() { return data; }
        
        public boolean isSuccess() { return statusCode >= 200 && statusCode < 300; }
        
        @Override
        public String toString() {
            return String.format("ApiResponse{status=%d, message='%s', data=%s}",
                statusCode, message, data != null ? "..." : "null");
        }
    }
}

