package com.nearbyfriends;

import com.nearbyfriends.model.*;
import com.nearbyfriends.storage.*;
import com.nearbyfriends.cache.*;
import com.nearbyfriends.service.*;
import com.nearbyfriends.websocket.*;
import java.util.*;

/**
 * Nearby Friends System Demo - demonstrates end-to-end flows.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  NEARBY FRIENDS SYSTEM (LIKE FIND MY FRIENDS / LIFE360)                      ║
 * ║  Based on Alex Xu's System Design Interview Volume 2 - Chapter 2             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  KEY FEATURES DEMONSTRATED:                                                  ║
 * ║  ──────────────────────────                                                  ║
 * ║  1. Real-time Location Updates (via WebSocket)                              ║
 * ║  2. Redis Pub/Sub for Friend Notifications                                  ║
 * ║  3. Nearby Friends Discovery (within radius)                                ║
 * ║  4. RESTful API for Non-Real-time Operations                                ║
 * ║  5. Geohash-based Optimization (for scale)                                  ║
 * ║  6. Periodic Location Updates (30-second intervals)                         ║
 * ║                                                                               ║
 * ║  ARCHITECTURE:                                                              ║
 * ║  ─────────────                                                               ║
 * ║  Mobile Users ──(WebSocket)──→ Load Balancer ──→ WebSocket Servers         ║
 * ║                      │                                │                      ║
 * ║                      │                                ├──→ Redis Pub/Sub     ║
 * ║                      │                                ├──→ Location Cache    ║
 * ║                      │                                └──→ Location History  ║
 * ║                      │                                                        ║
 * ║                     (HTTP)──→ API Servers ──→ User Database                 ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class NearbyFriendsDemo {
    
    // Storage
    private final UserDB userDB;
    private final LocationHistoryDB locationHistoryDB;
    
    // Cache
    private final LocationCache locationCache;
    
    // Services
    private final RedisPubSub redisPubSub;
    private final WebSocketServer wsServer;
    private final ApiService apiService;
    private final Map<Long, LocationService> locationServices;
    
    public NearbyFriendsDemo() {
        // Initialize storage
        this.userDB = new UserDB();
        this.locationHistoryDB = new LocationHistoryDB();
        
        // Initialize cache
        this.locationCache = new LocationCache();
        
        // Initialize services
        this.redisPubSub = new RedisPubSub();
        this.wsServer = new WebSocketServer("ws-server-1");
        this.apiService = new ApiService(userDB, locationHistoryDB, locationCache);
        this.locationServices = new HashMap<>();
    }
    
    public void run() throws InterruptedException {
        printHeader();
        
        // Setup: Create users and friendships
        setupUsersAndFriendships();
        
        // Demo 1: RESTful API Request Flow (Figure 5)
        demoRestfulApiFlow();
        
        // Demo 2: WebSocket Connection & Periodic Updates (Figure 7)
        demoWebSocketAndPeriodicUpdates();
        
        // Demo 3: Real-time Location Update Broadcasting (Figure 8)
        demoRealTimeLocationBroadcast();
        
        // Demo 4: Geohash Optimization (Figure 13)
        demoGeohashOptimization();
        
        // Demo 5: Get Nearby Friends List
        demoGetNearbyFriendsList();
        
        printSummary();
    }
    
    /**
     * Setup: Create users and establish friendships
     */
    private void setupUsersAndFriendships() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("SETUP: Creating Users and Friendships");
        System.out.println("=".repeat(80));
        
        // Create users
        User alice = new User(1001, "Alice");
        User bob = new User(1002, "Bob");
        User charlie = new User(1003, "Charlie");
        User diana = new User(1004, "Diana");
        User eve = new User(1005, "Eve");
        User frank = new User(1006, "Frank");
        
        userDB.saveUser(alice);
        userDB.saveUser(bob);
        userDB.saveUser(charlie);
        userDB.saveUser(diana);
        userDB.saveUser(eve);
        userDB.saveUser(frank);
        
        // Establish friendships
        // Alice is friends with Bob, Charlie, Diana, Eve
        apiService.addFriend(1001, 1002);
        apiService.addFriend(1001, 1003);
        apiService.addFriend(1001, 1004);
        apiService.addFriend(1001, 1005);
        
        // Bob is friends with Charlie
        apiService.addFriend(1002, 1003);
        
        System.out.println("\n[Setup] Created 6 users with friendships");
    }
    
    /**
     * Demo 1: RESTful API Request Flow (Figure 5)
     * HTTP calls for non-real-time operations
     */
    private void demoRestfulApiFlow() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 1: RESTful API Request Flow (Figure 5)");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Scenario] Alice uses HTTP API to update location and query friends");
        
        // Step 1: Update location via HTTP
        apiService.updateLocation(1001, 37.7749, -122.4194); // San Francisco
        
        // Step 2: Query nearby friends via HTTP
        ApiService.ApiResponse response = apiService.getNearbyFriends(1001, 5.0);
        
        System.out.println("\n[Result] HTTP Response:");
        if (response.statusCode == 200) {
            @SuppressWarnings("unchecked")
            List<FriendLocation> nearbyFriends = (List<FriendLocation>) response.data;
            System.out.println("  Found " + nearbyFriends.size() + " nearby friends");
        } else {
            System.out.println("  " + response.data);
        }
    }
    
    /**
     * Demo 2: WebSocket Connection & Periodic Updates (Figure 7)
     */
    private void demoWebSocketAndPeriodicUpdates() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 2: WebSocket Connection & Periodic Updates (Figure 7)");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Scenario] Users connect via WebSocket and send periodic location updates");
        
        // Step 1: Users connect via WebSocket
        connectUserToWebSocket(1001); // Alice
        connectUserToWebSocket(1002); // Bob
        connectUserToWebSocket(1003); // Charlie
        connectUserToWebSocket(1004); // Diana
        connectUserToWebSocket(1005); // Eve
        
        System.out.println("\n[Client] Mobile apps sending periodic location updates every 30 seconds...");
        
        // Set initial locations
        sendLocationViaWebSocket(1001, 37.7749, -122.4194);  // Alice - San Francisco
        sendLocationViaWebSocket(1002, 37.7849, -122.4094);  // Bob - ~1 mile from Alice
        sendLocationViaWebSocket(1003, 37.8049, -122.4294);  // Charlie - ~2 miles from Alice
        sendLocationViaWebSocket(1004, 37.7649, -122.4094);  // Diana - ~1.5 miles from Alice
        sendLocationViaWebSocket(1005, 40.7128, -74.0060);   // Eve - New York (far away)
        
        Thread.sleep(500); // Let updates propagate
    }
    
    /**
     * Demo 3: Real-time Location Update Broadcasting (Figure 8)
     */
    private void demoRealTimeLocationBroadcast() throws InterruptedException {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 3: Real-time Location Update Broadcasting (Figure 8)");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Scenario] Bob moves closer to Alice, triggering real-time updates");
        System.out.println("[Flow] Bob → WebSocket Server → Redis Pub/Sub → Alice's WebSocket");
        
        // Bob moves closer to Alice
        System.out.println("\n[Bob] Moving from (37.7849, -122.4094) to (37.7759, -122.4184)");
        sendLocationViaWebSocket(1002, 37.7759, -122.4184);  // Now ~0.5 miles from Alice
        
        Thread.sleep(500);
        
        System.out.println("\n[Result] Alice receives real-time notification about Bob's new location");
    }
    
    /**
     * Demo 4: Geohash Optimization (Figure 13)
     */
    private void demoGeohashOptimization() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 4: Geohash Optimization (Figure 13)");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Problem] With 100M users and 400 friends each, naive approach checks 400 locations");
        System.out.println("[Solution] Use Geohash to limit search space to nearby grid cells");
        
        // Example geohash encoding
        double lat = 37.7749;
        double lon = -122.4194;
        
        System.out.println("\n[Geohash Encoding]");
        System.out.println("  Location: (" + lat + ", " + lon + ")");
        System.out.println("  Precision 4: " + Geohash.encode(lat, lon, 4) + " (~20km grid)");
        System.out.println("  Precision 5: " + Geohash.encode(lat, lon, 5) + " (~5km grid)");
        System.out.println("  Precision 6: " + Geohash.encode(lat, lon, 6) + " (~1.2km grid)");
        System.out.println("  Precision 7: " + Geohash.encode(lat, lon, 7) + " (~150m grid)");
        
        System.out.println("\n[Optimization Strategy]");
        System.out.println("  1. User publishes location → Update to geohash channel (e.g., '9q8znzd')");
        System.out.println("  2. Subscribe to geohash channels of user's current cell + 8 neighbors");
        System.out.println("  3. Only receive updates from users in same/adjacent geohash cells");
        System.out.println("  4. Reduces Redis Pub/Sub fan-out from 400 friends → ~20-30 nearby users");
        
        String geohash = Geohash.encode(lat, lon, 6);
        List<String> neighbors = Geohash.getNeighbors(geohash);
        System.out.println("\n[Search Space] Geohash: " + geohash);
        System.out.println("  Need to check 9 cells total (center + 8 neighbors)");
    }
    
    /**
     * Demo 5: Get Nearby Friends List
     */
    private void demoGetNearbyFriendsList() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO 5: Get Nearby Friends List");
        System.out.println("=".repeat(80));
        
        System.out.println("\n[Scenario] Alice opens the app and wants to see all nearby friends");
        
        // Get Alice's location service
        LocationService aliceService = locationServices.get(1001L);
        if (aliceService != null) {
            List<FriendLocation> nearbyFriends = aliceService.getNearbyFriends(5.0);
            
            System.out.println("\n[Result] Nearby Friends (within 5 miles):");
            if (nearbyFriends.isEmpty()) {
                System.out.println("  No friends nearby");
            } else {
                for (int i = 0; i < Math.min(20, nearbyFriends.size()); i++) {
                    System.out.println("  " + (i + 1) + ". " + nearbyFriends.get(i));
                }
            }
        }
    }
    
    private void connectUserToWebSocket(long userId) {
        WebSocketConnection conn = new WebSocketConnection(userId);
        wsServer.connect(userId, conn);
        
        // Create location service for this user
        LocationService locationService = new LocationService(
            userId, userDB, locationCache, locationHistoryDB, redisPubSub, wsServer
        );
        locationService.initialize();
        locationServices.put(userId, locationService);
    }
    
    private void sendLocationViaWebSocket(long userId, double lat, double lon) {
        Location location = new Location(lat, lon);
        LocationService service = locationServices.get(userId);
        if (service != null) {
            service.handleLocationUpdate(location);
        }
    }
    
    private void printHeader() {
        System.out.println("\n" + "╔" + "═".repeat(78) + "╗");
        System.out.println("║" + " ".repeat(20) + "NEARBY FRIENDS SYSTEM DEMO" + " ".repeat(32) + "║");
        System.out.println("║" + " ".repeat(12) + "(Find My Friends - Based on Alex Xu Vol.2 Ch.2)" + " ".repeat(19) + "║");
        System.out.println("╚" + "═".repeat(78) + "╝");
    }
    
    private void printSummary() {
        System.out.println("\n" + "=".repeat(80));
        System.out.println("DEMO COMPLETE - KEY TAKEAWAYS");
        System.out.println("=".repeat(80));
        
        System.out.println("""
            
            ✓ WebSocket: Bidirectional real-time communication (not HTTP polling)
            ✓ Redis Pub/Sub: Efficient friend-to-friend notification broadcast
            ✓ Location Cache: Redis stores current locations with 10-min TTL
            ✓ Location History: Cassandra stores time-series location data
            ✓ Periodic Updates: 30-second intervals balance battery vs freshness
            ✓ Geohash Optimization: Reduces search space from 400 friends → 20-30 nearby
            ✓ Stateful WebSocket Servers: Sticky sessions maintain connections
            ✓ RESTful APIs: For non-real-time operations (add friend, settings)
            
            Scale achieved:
            - 10M concurrent users
            - 334,000 location updates/second
            - Sub-second latency for real-time updates
            
            For interview prep, see: INTERVIEW_CHEATSHEET.md
            """);
    }
    
    public static void main(String[] args) throws InterruptedException {
        NearbyFriendsDemo demo = new NearbyFriendsDemo();
        demo.run();
    }
}
