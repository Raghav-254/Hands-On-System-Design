package com.newsfeed;

import com.newsfeed.cache.*;
import com.newsfeed.models.*;
import com.newsfeed.queue.FanoutQueue;
import com.newsfeed.service.*;
import com.newsfeed.storage.*;
import com.newsfeed.worker.FanoutWorker;
import java.util.List;

/**
 * News Feed System Demo - Demonstrates all critical flows from Alex Xu's design.
 * 
 * This demo shows:
 * 1. API-based Feed Publishing Flow
 * 2. Fanout on Write (Push Model) for regular users
 * 3. Fanout on Read (Pull Model) for celebrities
 * 4. Feed Retrieval with cache layers
 * 5. Hybrid approach combining both strategies
 */
public class NewsFeedDemo {
    
    // System components
    private PostDB postDB;
    private UserDB userDB;
    private GraphDB graphDB;
    private PostCache postCache;
    private UserCache userCache;
    private NewsFeedCache newsFeedCache;
    private FanoutQueue fanoutQueue;
    private FanoutWorker fanoutWorker;
    private FanoutService fanoutService;
    private PostService postService;
    private NewsFeedService newsFeedService;
    private ApiService apiService;
    
    public static void main(String[] args) throws Exception {
        printHeader();
        
        NewsFeedDemo demo = new NewsFeedDemo();
        demo.initializeComponents();
        demo.setupUsersAndRelationships();
        
        // Run all demo flows
        demo.demoFlow1_FeedPublishingAPI();
        demo.demoFlow2_FanoutOnWrite();
        demo.demoFlow3_FanoutOnRead();
        demo.demoFlow4_FeedRetrieval();
        demo.printStatistics();
        
        printFooter();
    }
    
    /**
     * Initialize all system components
     */
    private void initializeComponents() {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  INITIALIZING COMPONENTS");
        System.out.println("═".repeat(70));
        
        // Storage layer
        postDB = new PostDB();
        userDB = new UserDB();
        graphDB = new GraphDB();
        
        // Cache layer
        postCache = new PostCache(postDB);
        userCache = new UserCache(userDB);
        newsFeedCache = new NewsFeedCache();
        
        // Queue and workers
        fanoutQueue = new FanoutQueue();
        fanoutWorker = new FanoutWorker(newsFeedCache);
        fanoutQueue.registerWorker(fanoutWorker::process);
        
        // Services
        fanoutService = new FanoutService(graphDB, newsFeedCache, userCache, fanoutQueue);
        postService = new PostService(postDB, postCache);
        postService.setFanoutService(fanoutService);
        newsFeedService = new NewsFeedService(newsFeedCache, postCache, userCache, postDB, graphDB);
        newsFeedService.setFanoutService(fanoutService);
        
        // API Service
        apiService = new ApiService(userDB, userCache, graphDB, postService, newsFeedService);
        
        System.out.println("\n✓ Storage Layer: PostDB, UserDB, GraphDB");
        System.out.println("✓ Cache Layer: PostCache, UserCache, NewsFeedCache");
        System.out.println("✓ Queue: FanoutQueue + FanoutWorker");
        System.out.println("✓ Services: ApiService, PostService, FanoutService, NewsFeedService");
        System.out.println("\n✓ All components initialized!");
    }
    
    /**
     * Create users and set up follow relationships
     */
    private void setupUsersAndRelationships() {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  CREATING USERS AND RELATIONSHIPS");
        System.out.println("═".repeat(70));
        
        // Create users
        User alice = new User(1, "alice", "Alice Smith");
        User bob = new User(2, "bob", "Bob Johnson");
        User charlie = new User(3, "charlie", "Charlie Brown");
        User diana = new User(4, "diana", "Diana Prince");
        User elon = new User(5, "elon", "Elon Musk");  // Celebrity
        
        // Save to DB and cache
        for (User user : List.of(alice, bob, charlie, diana, elon)) {
            userDB.save(user);
            userCache.put(user);
        }
        
        System.out.println("\n✓ Created 5 users: Alice, Bob, Charlie, Diana, Elon");
        
        // Set up follow relationships
        // Alice follows Bob, Charlie, and Elon
        graphDB.follow(1, 2);  // Alice → Bob
        graphDB.follow(1, 3);  // Alice → Charlie
        graphDB.follow(1, 5);  // Alice → Elon
        
        // Bob follows Alice
        graphDB.follow(2, 1);  // Bob → Alice
        
        // Charlie follows Alice and Bob
        graphDB.follow(3, 1);  // Charlie → Alice
        graphDB.follow(3, 2);  // Charlie → Bob
        
        // Diana follows everyone
        graphDB.follow(4, 1);  // Diana → Alice
        graphDB.follow(4, 2);  // Diana → Bob
        graphDB.follow(4, 3);  // Diana → Charlie
        graphDB.follow(4, 5);  // Diana → Elon
        
        System.out.println("\n📊 Follow Graph:");
        System.out.println("  Alice follows: Bob, Charlie, Elon");
        System.out.println("  Bob follows: Alice");
        System.out.println("  Charlie follows: Alice, Bob");
        System.out.println("  Diana follows: Alice, Bob, Charlie, Elon");
        
        // Make Elon a celebrity by adding many followers
        System.out.println("\n[Making Elon a celebrity with 150 followers...]");
        for (int i = 100; i < 250; i++) {
            User fakeUser = new User(i, "user" + i, "User " + i);
            userDB.save(fakeUser);
            graphDB.follow(i, 5);  // Fake users follow Elon
        }
        
        System.out.println("✓ Elon now has " + graphDB.getFollowerCount(5) + " followers (celebrity!)");
    }
    
    /**
     * DEMO 1: API-based Feed Publishing
     * Shows the complete flow from API request to fanout
     */
    private void demoFlow1_FeedPublishingAPI() throws InterruptedException {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  DEMO 1: FEED PUBLISHING API FLOW");
        System.out.println("═".repeat(70));
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  FEED PUBLISHING FLOW (POST /v1/me/feed)                         │");
        System.out.println("├──────────────────────────────────────────────────────────────────┤");
        System.out.println("│                                                                  │");
        System.out.println("│  Client → Load Balancer → Web Server → Post Service             │");
        System.out.println("│                                             │                    │");
        System.out.println("│                                             ├──→ Post DB        │");
        System.out.println("│                                             ├──→ Post Cache     │");
        System.out.println("│                                             └──→ Fanout Service │");
        System.out.println("│                                                      │          │");
        System.out.println("│                                      ┌───────────────┴───────┐  │");
        System.out.println("│                                      │                       │  │");
        System.out.println("│                              Regular User            Celebrity  │");
        System.out.println("│                              (Fanout on Write)    (Fanout Read) │");
        System.out.println("│                                      │                       │  │");
        System.out.println("│                              Push to all         Save only,     │");
        System.out.println("│                              followers' feeds    merge on read  │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");
        
        // Simulate API call - Alice publishes a post
        System.out.println("\n--- Step 1: Alice authenticates ---");
        String aliceToken = apiService.createTestSession(1);
        System.out.println("✓ Alice has session token: " + aliceToken);
        
        // Publish post via API
        System.out.println("\n--- Step 2: Alice publishes a post via API ---");
        ApiService.ApiResponse response = apiService.publishPost(
            aliceToken,
            "Just finished reading a great book! 📚 Highly recommend 'System Design Interview'",
            Post.PostType.TEXT
        );
        
        System.out.println("\n[API Response] " + response);
        
        // Process the fanout queue
        fanoutQueue.processAll();
        Thread.sleep(100);
        
        System.out.println("\n✓ Post created and fanned out to Alice's followers (Bob, Charlie, Diana)");
    }
    
    /**
     * DEMO 2: Fanout on Write (Push Model)
     * For regular users with manageable number of followers
     */
    private void demoFlow2_FanoutOnWrite() throws InterruptedException {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  DEMO 2: FANOUT ON WRITE (PUSH MODEL)");
        System.out.println("═".repeat(70));
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  FANOUT ON WRITE - For Regular Users (< 100 followers)          │");
        System.out.println("├──────────────────────────────────────────────────────────────────┤");
        System.out.println("│                                                                  │");
        System.out.println("│  When Bob posts:                                                 │");
        System.out.println("│                                                                  │");
        System.out.println("│       Bob's Post                                                 │");
        System.out.println("│           │                                                      │");
        System.out.println("│           ▼                                                      │");
        System.out.println("│    ┌─────────────┐                                              │");
        System.out.println("│    │  Post DB    │  ← Store post                                │");
        System.out.println("│    │  Post Cache │                                              │");
        System.out.println("│    └──────┬──────┘                                              │");
        System.out.println("│           │                                                      │");
        System.out.println("│           ▼                                                      │");
        System.out.println("│    ┌─────────────┐                                              │");
        System.out.println("│    │   Fanout    │  ← Get followers: [Alice, Charlie]          │");
        System.out.println("│    │   Service   │                                              │");
        System.out.println("│    └──────┬──────┘                                              │");
        System.out.println("│           │                                                      │");
        System.out.println("│     ┌─────┴─────┐                                               │");
        System.out.println("│     ▼           ▼                                               │");
        System.out.println("│  Alice's     Charlie's                                          │");
        System.out.println("│  Feed Cache  Feed Cache                                         │");
        System.out.println("│                                                                  │");
        System.out.println("│  ✓ Fast reads (pre-computed)                                    │");
        System.out.println("│  ✗ Expensive writes (N copies)                                  │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");
        
        System.out.println("\n--- Bob creates a post ---");
        System.out.println("Bob has " + graphDB.getFollowerCount(2) + " followers: Alice, Charlie");
        
        postService.createPost(2,
            "Working on an exciting new project! 🚀 Can't wait to share more.",
            Post.PostType.TEXT);
        
        fanoutQueue.processAll();
        Thread.sleep(100);
        
        System.out.println("\n✓ Post pushed to Alice's and Charlie's feed caches immediately");
        
        System.out.println("\n--- Charlie creates a post ---");
        System.out.println("Charlie has " + graphDB.getFollowerCount(3) + " follower: Diana");
        
        postService.createPost(3,
            "Beautiful sunset today! 🌅",
            Post.PostType.IMAGE);
        
        fanoutQueue.processAll();
        Thread.sleep(100);
        
        System.out.println("\n✓ Post pushed to Diana's feed cache immediately");
    }
    
    /**
     * DEMO 3: Fanout on Read (Pull Model)
     * For celebrities with many followers
     */
    private void demoFlow3_FanoutOnRead() throws InterruptedException {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  DEMO 3: FANOUT ON READ (PULL MODEL) - CELEBRITY");
        System.out.println("═".repeat(70));
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  FANOUT ON READ - For Celebrities (100+ followers)              │");
        System.out.println("├──────────────────────────────────────────────────────────────────┤");
        System.out.println("│                                                                  │");
        System.out.println("│  When Elon (celebrity) posts:                                   │");
        System.out.println("│                                                                  │");
        System.out.println("│       Elon's Post                                               │");
        System.out.println("│           │                                                      │");
        System.out.println("│           ▼                                                      │");
        System.out.println("│    ┌─────────────┐                                              │");
        System.out.println("│    │  Post DB    │  ← Store post (ONLY HERE!)                  │");
        System.out.println("│    │  Post Cache │                                              │");
        System.out.println("│    └─────────────┘                                              │");
        System.out.println("│                                                                  │");
        System.out.println("│  NO immediate fanout to 150+ followers' feeds!                  │");
        System.out.println("│                                                                  │");
        System.out.println("│  Later, when Alice requests her feed:                           │");
        System.out.println("│                                                                  │");
        System.out.println("│    ┌─────────────┐                                              │");
        System.out.println("│    │  Feed Cache │  ← Get regular posts (Bob, Charlie)         │");
        System.out.println("│    └──────┬──────┘                                              │");
        System.out.println("│           │                                                      │");
        System.out.println("│           ▼                                                      │");
        System.out.println("│    ┌─────────────┐                                              │");
        System.out.println("│    │  Merge      │  ← Pull Elon's recent posts                  │");
        System.out.println("│    │  Celebrity  │                                              │");
        System.out.println("│    └─────────────┘                                              │");
        System.out.println("│                                                                  │");
        System.out.println("│  ✓ Efficient writes (1 copy only)                               │");
        System.out.println("│  ✗ Slower reads (merge at read time)                            │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");
        
        System.out.println("\n--- Elon (celebrity) creates a post ---");
        System.out.println("Elon has " + graphDB.getFollowerCount(5) + " followers (celebrity threshold: 100)");
        
        postService.createPost(5,
            "Just launched a rocket to Mars! 🚀🔴 Humanity is becoming multiplanetary!",
            Post.PostType.TEXT);
        
        // Note: No fanout to queue for celebrities
        System.out.println("\n✓ Post saved to Elon's timeline only (NOT fanned out)");
        System.out.println("✓ Followers will merge this post when they request their feed");
    }
    
    /**
     * DEMO 4: Feed Retrieval
     * Shows how feed is assembled from cache + celebrity merge
     */
    private void demoFlow4_FeedRetrieval() throws InterruptedException {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  DEMO 4: FEED RETRIEVAL (GET /v1/me/feed)");
        System.out.println("═".repeat(70));
        
        System.out.println("\n┌──────────────────────────────────────────────────────────────────┐");
        System.out.println("│  FEED RETRIEVAL FLOW                                             │");
        System.out.println("├──────────────────────────────────────────────────────────────────┤");
        System.out.println("│                                                                  │");
        System.out.println("│  Client → Load Balancer → Web Server → News Feed Service        │");
        System.out.println("│                                              │                   │");
        System.out.println("│                            ┌─────────────────┼─────────────────┐│");
        System.out.println("│                            │                 │                 ││");
        System.out.println("│                            ▼                 ▼                 ▼│");
        System.out.println("│                     Feed Cache         Post Cache        User  ││");
        System.out.println("│                     (post IDs)         (hydrate)         Cache ││");
        System.out.println("│                            │                 │                 ││");
        System.out.println("│                            └────────┬────────┘                 ││");
        System.out.println("│                                     ▼                          ││");
        System.out.println("│                              Merge Celebrity                   ││");
        System.out.println("│                              Posts (pull)                      ││");
        System.out.println("│                                     │                          ││");
        System.out.println("│                                     ▼                          ││");
        System.out.println("│                                Rank & Return                    │");
        System.out.println("│                                                                  │");
        System.out.println("└──────────────────────────────────────────────────────────────────┘");
        
        // Alice requests her feed via API
        System.out.println("\n--- Alice requests her feed via API ---");
        System.out.println("Alice follows: Bob, Charlie, Elon (celebrity)");
        
        String aliceToken = apiService.createTestSession(1);
        ApiService.ApiResponse response = apiService.getFeed(aliceToken, 0, 10);
        
        System.out.println("\n📰 Alice's News Feed:");
        @SuppressWarnings("unchecked")
        var feed = (java.util.List<NewsFeedService.FeedEntry>) 
            ((java.util.Map<String, Object>) response.getData()).get("feed");
        for (var entry : feed) {
            System.out.println(entry);
        }
        
        // Diana requests her feed
        System.out.println("\n--- Diana requests her feed ---");
        System.out.println("Diana follows: Alice, Bob, Charlie, Elon (celebrity)");
        
        String dianaToken = apiService.createTestSession(4);
        response = apiService.getFeed(dianaToken, 0, 10);
        
        System.out.println("\n📰 Diana's News Feed:");
        @SuppressWarnings("unchecked")
        var dianaFeed = (java.util.List<NewsFeedService.FeedEntry>) 
            ((java.util.Map<String, Object>) response.getData()).get("feed");
        for (var entry : dianaFeed) {
            System.out.println(entry);
        }
    }
    
    /**
     * Print system statistics
     */
    private void printStatistics() {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("  SYSTEM STATISTICS");
        System.out.println("═".repeat(70));
        
        System.out.println("\n" + postCache.getStats());
        System.out.println(userCache.getStats());
        System.out.println(newsFeedCache.getStats());
        System.out.println(fanoutQueue.getStats());
        System.out.println(fanoutWorker.getStats());
        System.out.println(fanoutService.getStats());
    }
    
    private static void printHeader() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║            NEWS FEED SYSTEM - HANDS-ON DEMONSTRATION                ║");
        System.out.println("║         Based on Alex Xu's System Design Interview Book             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }
    
    private static void printFooter() {
        System.out.println("\n");
        System.out.println("╔══════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      DEMO COMPLETED SUCCESSFULLY                     ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════╣");
        System.out.println("║  Key Takeaways:                                                      ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  FEED PUBLISHING (POST /v1/me/feed):                                ║");
        System.out.println("║  • API → PostService → DB/Cache → FanoutService                     ║");
        System.out.println("║  • Regular users: FANOUT ON WRITE (push to followers' feeds)        ║");
        System.out.println("║  • Celebrities: FANOUT ON READ (merge at read time)                 ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  FEED RETRIEVAL (GET /v1/me/feed):                                  ║");
        System.out.println("║  • Get post IDs from Feed Cache (pre-computed)                      ║");
        System.out.println("║  • Hydrate with Post Cache and User Cache                           ║");
        System.out.println("║  • Merge celebrity posts at read time                               ║");
        System.out.println("║                                                                      ║");
        System.out.println("║  See INTERVIEW_CHEATSHEET.md for comprehensive notes!               ║");
        System.out.println("╚══════════════════════════════════════════════════════════════════════╝");
    }
}
