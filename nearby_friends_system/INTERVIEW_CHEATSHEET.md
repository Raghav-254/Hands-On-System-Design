# Nearby Friends System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview Volume 2 - Chapter 2

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **WebSocket Servers** | Real-time bidirectional | Stateful (connection state) | Sticky sessions, heartbeat every 30s |
| **Redis Pub/Sub** | Location broadcast | In-memory (ephemeral) | Channel per user, fire-and-forget |
| **Location Cache** | Current locations | Redis (TTL 10min) | Fast reads (<1ms), auto-expire stale data |
| **Location History** | Time-series data | Cassandra | Append-only, 7-day retention, 334K writes/sec |
| **User Database** | User profiles, friendships | PostgreSQL | ACID transactions for friend operations |
| **API Servers** | Non-realtime operations | Stateless | Add friend, settings, initial load |

---

## The Story: Building Nearby Friends (Like Find My Friends)

Let me walk you through how we'd build a real-time location-sharing system step by step.

---

## 1. What Are We Building? (Requirements)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. Display nearby friends within 5-mile radius                             ║
║  2. Show distance and last update timestamp for each friend                 ║
║  3. List updates automatically every few seconds (real-time)                ║
║  4. Users can enable/disable location sharing                               ║
║  5. Support for mobile apps (iOS/Android)                                   ║
║                                                                               ║
║  OUT OF SCOPE:                                                              ║
║  • Location history visualization                                           ║
║  • Geofencing / location-based alerts                                       ║
║  • Group location sharing                                                   ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  • Low Latency: < 1 second for location updates to reach friends           ║
║  • Reliability: Occasional data point loss is acceptable                    ║
║  • Eventual Consistency: Few seconds delay in replicas is OK               ║
║  • Battery Efficient: Balance update frequency with power consumption       ║
║  • Privacy: Friends-only visibility, location data is sensitive            ║
║  • High Availability: 99.9%                                                 ║
║  • Scalability: Support 10M concurrent users                                ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Users:                                                                     ║
║  • 100M daily active users (DAU)                                            ║
║  • 10M concurrent users (10% of DAU)                                        ║
║  • Average 400 friends per user                                             ║
║  • Display 20 nearby friends per page                                       ║
║                                                                               ║
║  Location Updates:                                                          ║
║  • Update interval: Every 30 seconds                                        ║
║  • Update QPS: 10M / 30 = 334,000 updates/second                           ║
║  • Peak QPS: ~660,000 (2x average)                                          ║
║                                                                               ║
║  Storage:                                                                   ║
║  • Location data: 100 bytes (user_id, lat, lng, timestamp)                 ║
║  • 334K updates/sec × 100 bytes = 33.4 MB/sec                              ║
║  • Daily: 33.4 MB/sec × 86,400 sec = 2.9 TB/day                            ║
║  • 7-day retention: 2.9 TB × 7 = 20.3 TB                                   ║
║                                                                               ║
║  Bandwidth:                                                                 ║
║  • Inbound: 334K updates/sec × 100 bytes = ~33 MB/sec                      ║
║  • Outbound: Each update fans out to ~20 nearby friends                    ║
║    334K × 20 × 100 bytes = ~670 MB/sec                                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. How Do Users Interact? (API Design)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  HTTP REST APIs (For non-realtime operations)                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /api/v1/nearby-friends                                                  ║
║  Use: Initial load when app opens (before WebSocket)                        ║
║  Response: List of nearby friends with distances                            ║
║                                                                               ║
║  POST /api/v1/friends                                                        ║
║  Use: Add friend                                                            ║
║                                                                               ║
║  PUT /api/v1/settings/location-sharing                                       ║
║  Use: Enable/disable location sharing                                       ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  WEBSOCKET APIs (For real-time updates)                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  wss://ws.nearby-friends.com/ws?token=jwt...                                ║
║                                                                               ║
║  Client → Server (every 30 sec):                                            ║
║  {                                                                           ║
║    "type": "location_update",                                               ║
║    "lat": 37.7749,                                                           ║
║    "lng": -122.4194                                                          ║
║  }                                                                           ║
║                                                                               ║
║  Server → Client (when friend moves):                                       ║
║  {                                                                           ║
║    "type": "friend_location_update",                                        ║
║    "friend_id": 1002,                                                        ║
║    "name": "Bob",                                                            ║
║    "distance": 1.2,                                                          ║
║    "lat": 37.7759,                                                           ║
║    "lng": -122.4184                                                          ║
║  }                                                                           ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 3. The Big Picture (Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                        NEARBY FRIENDS ARCHITECTURE                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║                          📱 Mobile Users                                     ║
║                    (Alice, Bob, Charlie...)                                  ║
║                                │                                             ║
║                    ┌───────────┴───────────┐                                ║
║                    │                       │                                ║
║           ① WebSocket (real-time)    HTTP (occasional)                      ║
║              location updates         add friend, auth                       ║
║                    │                       │                                ║
║                    │                       │                                ║
║                    ▼                       ▼                                ║
║           ┌─────────────────┐    ┌─────────────────┐                        ║
║           │  Load Balancer  │    │  Load Balancer  │                        ║
║           │   (Sticky)      │    │  (Round-robin)  │                        ║
║           └────────┬────────┘    └────────┬────────┘                        ║
║                    │                      │                                 ║
║                    │                      │                                 ║
║         ② Route    ▼                      ▼                                 ║
║           ┌──────────────────┐   ┌──────────────────┐                       ║
║           │  WebSocket       │   │   API Servers    │                       ║
║           │  Servers         │   │   (Stateless)    │                       ║
║           │  (Stateful)      │   └─────────┬────────┘                       ║
║           └────────┬─────────┘             │                                ║
║                    │                       │                                ║
║                    │                       │③ Read                          ║
║                    │                       │                                ║
║     ③ Get friends  │                       │                                ║
║     ④ Save history │                       ▼                                ║
║     ⑤ Update cache │              ┌──────────────────┐                      ║
║     ⑥ Publish msg  │              │   User Database  │                      ║
║                    │              │   (PostgreSQL)   │                      ║
║         ┌──────────┼──────┬───────┤   • User profile │                      ║
║         │          │      │       │   • Friend list  │                      ║
║         ▼          ▼      ▼       └──────────────────┘                      ║
║   ┌──────────┐┌────────┐┌─────────────┐                                    ║
║   │  User    ││Location││  Location   │                                    ║
║   │ Database ││ Cache  ││  History    │                                    ║
║   │(Postgres)││(Redis) ││ (Cassandra) │                                    ║
║   └──────────┘└────────┘└─────────────┘                                    ║
║                                                                               ║
║                    │                                                         ║
║         ⑥ Publish  │                                                         ║
║                    ▼                                                         ║
║           ┌──────────────────┐                                               ║
║           │  Redis Pub/Sub   │                                               ║
║           │  • channel:alice │                                               ║
║           │  • channel:bob   │                                               ║
║           └────────┬─────────┘                                               ║
║                    │                                                         ║
║         ⑦ Callback │ (to subscribed friends' handlers)                      ║
║                    │                                                         ║
║                    └─────────┐                                               ║
║                              │                                               ║
║                              ▼                                               ║
║                    ┌──────────────────┐                                      ║
║                    │  WebSocket       │                                      ║
║                    │  Servers         │                                      ║
║                    │  (Other servers  │                                      ║
║                    │   notify friends)│                                      ║
║                    └────────┬─────────┘                                      ║
║                             │                                                ║
║                 ⑧ Push      │                                                ║
║                             ▼                                                ║
║                      📱 Friends' Apps                                        ║
║                    (Bob, Charlie...)                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝

THE COMPLETE FLOW (When Alice Updates Her Location):
─────────────────────────────────────────────────────

① Alice's app sends location update via WebSocket
② Load Balancer routes to WebSocket Server 1 (sticky session)
③ Server 1 queries User Database → gets Alice's friend list [Bob, Charlie]
④ Server 1 saves to Location History (Cassandra) → long-term storage
⑤ Server 1 updates Location Cache (Redis) → fast lookups
⑥ Server 1 publishes message to Redis Pub/Sub on "channel:alice"
⑦ Redis broadcasts to ALL subscribers of "channel:alice":
   • Server 2 (Bob's handler) receives the message
   • Server 3 (Charlie's handler) receives the message
⑧ Each friend's server:
   • Calculates distance between friend and Alice
   • If within 5 miles → pushes update to friend's mobile app via WebSocket

KEY INSIGHTS:
• Two separate traffic flows: WebSocket (real-time) + HTTP (occasional)
• WebSocket servers are STATEFUL: Each maintains persistent connections
• Redis Pub/Sub solves the "cross-server" problem (friends on different servers)
• Location Cache (Redis) used for fast distance calculations
• Location History (Cassandra) used for analytics/debugging
```

---

## 4. Why These Choices? (Design Decisions)

### Decision #1: WebSocket Instead of HTTP Polling

**The Problem:** We need real-time, bidirectional communication.

**Why HTTP Polling Doesn't Work:**
- High latency: 5-second average delay
- Battery drain: Constant reconnections
- Server overload: 10M users × 12 requests/min = 2M requests/sec
- Wasteful: 95% of requests return "no changes"

**Why WebSocket Wins:**
- ✓ Bidirectional: Server can push instantly to client
- ✓ Low latency: <100ms for updates
- ✓ Battery efficient: Single persistent connection
- ✓ Real-time: Perfect for location sharing

### Decision #2: Redis Pub/Sub for Broadcasting

**The Problem:** Friends are connected to DIFFERENT WebSocket servers!
- Alice on Server 1 updates location
- Bob (Alice's friend) is on Server 2
- How does Server 1 notify Server 2?

**Why Redis Pub/Sub:**
- Each user has channel: `channel:{user_id}`
- Bob's server subscribes to `channel:alice`
- When Alice updates → Server 1 publishes to `channel:alice`
- Redis broadcasts to ALL subscribers (including Bob's server)
- <1ms latency, fire-and-forget (perfect for ephemeral location data)

**Why Not Kafka:**
- Kafka: 10-100ms latency, persistent, complex
- We need: <1ms, ephemeral is fine (next update in 30 seconds)

### Decision #3: 30-Second Update Interval

**The Trade-off:** Freshness vs Battery Life vs Cost

| Interval | Battery | Freshness | Server QPS | Verdict |
|----------|---------|-----------|------------|---------|
| 5 sec | Very poor | Excellent | 2M | ❌ Too expensive |
| 30 sec | Good | Good | 334K | ✓ Sweet spot |
| 60 sec | Excellent | Fair | 167K | ❌ Too stale |

**Why 30 seconds works:**
- Human walking speed: 3-4 mph
- In 30 seconds: ~50-100 meters
- For 5-mile radius: Small movement doesn't matter much
- Acceptable battery drain for location-sharing app

---

## 5. The Complete Story: What Happens When Alice and Bob Use the App

Let me walk you through the **complete end-to-end flow** with a real example.

### Chapter 1: Alice Opens the App

```
① Alice opens app
   │
   ▼
② HTTP GET /api/v1/nearby-friends
   │
   ├─→ API server queries User DB: "Who are Alice's friends?"
   ├─→ API server queries Location Cache: "Where are they?"
   ├─→ Calculate distances, filter by 5-mile radius
   │
   ▼
③ App displays:
   "Bob - 1.2 miles away"
   "Charlie - 2.5 miles away"
   "Diana - 4.8 miles away"

WHY HTTP? WebSocket not connected yet. Need to show something immediately.
```

### Chapter 2: Alice Establishes WebSocket Connection

```
① App creates WebSocket:
   ws = new WebSocket('wss://ws-server-42.com?user_id=1001')
   │
   ▼
② Load Balancer (sticky session):
   → Uses consistent hashing on user_id
   → Routes Alice to WebSocket Server 1
   → Alice will ALWAYS go to Server 1 (stateful!)
   │
   ▼
③ Server 1 creates "Connection Handler" for Alice:
   │
   ├─→ Query User DB: "Alice's friends?"
   │   Result: [Bob (1002), Charlie (1003), Diana (1004)]
   │
   ├─→ Subscribe to Redis Pub/Sub:
   │   redis.subscribe("channel:1002", callback)  // Bob
   │   redis.subscribe("channel:1003", callback)  // Charlie
   │   redis.subscribe("channel:1004", callback)  // Diana
   │
   └─→ Send ACK: "Connected!"

KEY: Alice's handler now "listens" to her friends' channels!
```

### Chapter 3: Alice Sends Location Update (Every 30 Seconds)

```
① Alice's app (30 seconds later):
   ws.send({ type: "location_update", lat: 37.7749, lng: -122.4194 })
   │
   ▼
② WebSocket Server 1 (Alice's handler):
   │
   ├─→ ③ Save to Location Cache:
   │   SET location:1001 '{"lat":37.7749,"lng":-122.4194}'
   │   EXPIRE location:1001 600  (10-min TTL)
   │
   ├─→ ④ Save to Location History:
   │   INSERT INTO location_history ...
   │
   └─→ ⑤ Publish to Redis Pub/Sub:
       redis.publish("channel:1001", '{"user_id":1001, "lat":37.7749, ...}')

⑥ Redis broadcasts to ALL subscribers of channel:1001:
   → Bob's server (if Bob is Alice's friend)
   → Charlie's server
   → Diana's server
   → Anyone subscribed to Alice's channel

KEY: ONE publish reaches all friends' servers simultaneously!
```

### Chapter 4: Bob Receives Alice's Update (The Magic!)

```
① Bob's Connection Handler (on Server 2) receives from Redis:
   Message: { user_id: 1001, lat: 37.7749, lng: -122.4194 }
   │
   ▼
② Callback function executes:
   │
   ├─→ Get Bob's location from cache:
   │   GET location:1002 → { lat: 37.7849, lng: -122.4094 }
   │
   ├─→ Calculate distance (Haversine formula):
   │   distance = 1.2 miles
   │
   ├─→ Check if within 5-mile radius:
   │   1.2 < 5.0 → YES! Alice is nearby
   │
   └─→ ③ Send to Bob via WebSocket:
       ws.send({
         type: "friend_location_update",
         friend_id: 1001,
         name: "Alice",
         distance: 1.2,
         lat: 37.7749,
         lng: -122.4194
       })

④ Bob's app updates UI:
   "Alice - 1.2 miles away (updated just now)"

THE MAGIC:
• Total latency: <100ms (Alice's phone → Bob's phone)
• They're on DIFFERENT servers (Server 1 vs Server 2)
• Completely decoupled (neither knows about the other)
```

### Chapter 5: Alice Closes the App

```
① Alice closes app
   │
   ▼
② WebSocket connection closes
   │
   ▼
③ Connection Handler cleanup:
   redis.unsubscribe("channel:1002")  // Bob
   redis.unsubscribe("channel:1003")  // Charlie
   redis.unsubscribe("channel:1004")  // Diana
   │
   ▼
④ Alice's location expires from cache after 10 minutes (TTL)
   → Friends stop seeing Alice in nearby list
   
⑤ Alice's location history remains in Cassandra for 7 days
   → For analytics, compliance, debugging
```

---

## 6. The Connection Handler: The Glue That Holds Everything Together

Now that you understand the complete flow, let's dive into the **Connection Handler** - the component that makes everything work.

**What is it?**
- A server-side object that manages ONE user's WebSocket connection
- Created when user connects, destroyed when disconnected
- The "glue" between WebSocket (client communication) and Redis Pub/Sub (server communication)

**Pseudocode:**

```javascript
class ConnectionHandler {
    constructor(userId, websocket) {
        this.userId = userId;
        this.ws = websocket;
        this.friendIds = [];
    }
    
    // Called when user connects
    async onConnect() {
        // Get friends from database
        this.friendIds = await userDB.getFriends(this.userId);
        
        // Subscribe to each friend's Redis channel
        for (let friendId of this.friendIds) {
            await redis.subscribe(`channel:${friendId}`, this.onFriendUpdate);
        }
        
        // Send ACK to mobile app: "You're connected and ready!"
        this.ws.send({ type: "connected", status: "ready" });
    }
    
    // Called when THIS user sends location
    async onLocationUpdate(location) {
        // Save to cache
        await redis.set(`location:${this.userId}`, location, 'EX', 600);
        
        // Save to history
        await cassandra.insert('location_history', { userId, ...location });
        
        // Publish so friends receive it
        // This triggers onFriendUpdate() on ALL friends' handlers
        // (Bob's handler, Charlie's handler, etc. - NOT this user's handler)
        await redis.publish(`channel:${this.userId}`, {
            user_id: this.userId,
            lat: location.lat,
            lng: location.lng
        });
    }
    
    // Called when FRIEND updates location (Redis callback)
    async onFriendUpdate(friendUpdate) {
        // Get THIS user's current location
        const myLocation = await redis.get(`location:${this.userId}`);
        if (!myLocation) return;
        
        // Calculate distance
        const distance = haversine(myLocation, friendUpdate);
        
        // Only send if within radius
        if (distance <= 5.0) {
            const friend = await userDB.getUser(friendUpdate.user_id);
            
            // Send to THIS user's mobile app via WebSocket
            // (Remember: This is Bob's handler, so this.ws = Bob's app)
            this.ws.send({
                type: "friend_location_update",
                friend_id: friendUpdate.user_id,
                name: friend.name,
                distance: distance,
                lat: friendUpdate.lat,
                lng: friendUpdate.lng
            });
        }
    }
    
    // Called when user disconnects
    async onDisconnect() {
        // Unsubscribe from all friends' channels
        for (let friendId of this.friendIds) {
            await redis.unsubscribe(`channel:${friendId}`);
        }
    }
}
```

**Visual Flow: How Connection Handlers Communicate**

```
Alice's Handler          Redis           Bob's Handler           Bob's Mobile App
(Server 1)             Pub/Sub           (Server 2)              (Client)
     │                    │                   │                        │
     │ onLocationUpdate() │                   │                        │
     │ (Alice sends loc)  │                   │                        │
     │                    │                   │                        │
     │─ publish() ───────>│                   │                        │
     │  channel:1001      │                   │                        │
     │                    │                   │                        │
     │                    │─── broadcast ────>│                        │
     │                    │                   │                        │
     │                    │              onFriendUpdate()               │
     │                    │              (callback invoked)             │
     │                    │                   │                        │
     │                    │                   │─ Calculate distance    │
     │                    │                   │─ Filter by radius      │
     │                    │                   │                        │
     │                    │                   │─ this.ws.send() ──────>│
     │                    │                   │                        │
     │                    │                   │                   Bob sees:
     │                    │                   │              "Alice - 1.2 mi away"

KEY INSIGHT:
• Alice's handler calls: onLocationUpdate() → publish to channel:1001
• Bob's handler receives: onFriendUpdate() callback (from Redis)
• Bob's handler sends to: Bob's mobile app via this.ws.send()
```

**Who Calls These Methods?**

```javascript
// ═══════════════════════════════════════════════════════════════════════════
// HOW THE WEBSOCKET SERVER USES THE CONNECTION HANDLER
// ═══════════════════════════════════════════════════════════════════════════

// WebSocket Server Main Loop:
webSocketServer.on('connection', (ws, request) => {
    const userId = extractUserIdFromRequest(request);
    
    // Create handler for this user
    const handler = new ConnectionHandler(userId, ws);
    await handler.onConnect();  // ← SERVER calls this when user connects
    
    // ═══ CLIENT → SERVER (Client sends to server) ═══
    ws.on('message', (data) => {
        const msg = JSON.parse(data);
        if (msg.type === 'location_update') {
            handler.onLocationUpdate(msg);  // ← WS Server calls this method
        }
    });
    
    // ═══ SERVER → CLIENT (Server sends to client) ═══
    // Happens inside handler methods via this.ws.send():
    // • onConnect() → sends "connected" ACK
    // • onFriendUpdate() → sends friend location updates
    //
    // Note: onFriendUpdate() is OUR method, but REDIS calls it
    // We registered it as callback: redis.subscribe(channel, this.onFriendUpdate)
    
    // When connection closes
    ws.on('close', () => {
        handler.onDisconnect();  // ← WS Server calls this
    });
});
```

**Method Call Summary:**

| Method | Defined In | Called By | Trigger | Does What |
|--------|------------|-----------|---------|-----------|
| `onConnect()` | Handler | WebSocket Server | User connects | Subscribe to friends' channels |
| `onLocationUpdate()` | Handler | WebSocket Server | Client sends location | Publish to own channel |
| `onFriendUpdate()` | Handler | **Redis Pub/Sub** | Friend publishes | Calculate distance, send to client |
| `onDisconnect()` | Handler | WebSocket Server | Connection closes | Unsubscribe from channels |

**Key Insight:** All methods are YOUR code, but different systems trigger them!

**Redis Channel Lifecycle:**

- Channels are **virtual** - no explicit creation needed
- Channel "exists" when there's at least 1 subscriber
- Channel auto-removed when last subscriber unsubscribes
- Publishing to channel with no subscribers = message discarded (no error)

---

## 7. How We Store Data (Database Design)

### User Database (PostgreSQL)

```sql
-- Users table
CREATE TABLE users (
    user_id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    email VARCHAR(255) UNIQUE,
    sharing_location BOOLEAN DEFAULT true,
    created_at TIMESTAMP
);

-- Friendships (bidirectional)
CREATE TABLE friendships (
    user_id BIGINT,
    friend_id BIGINT,
    created_at TIMESTAMP,
    PRIMARY KEY (user_id, friend_id)
);
CREATE INDEX idx_friendships_user ON friendships(user_id);
```

**Why PostgreSQL?**
- ACID transactions for friendship operations
- Joins for querying friend relationships
- Well-understood, mature technology

### Location Cache (Redis)

```
Key: location:{user_id}
Value: {"lat": 37.7749, "lng": -122.4194, "ts": 1641234567890}
TTL: 600 seconds (10 minutes)

Commands:
SET location:1001 '{"lat":37.7749,"lng":-122.4194}' EX 600
GET location:1001
```

**Why Redis?**
- In-memory: <1ms reads
- TTL: Auto-expire stale locations
- Geospatial: Built-in GEORADIUS support

**Capacity:**
- 10M users × 100 bytes = 1GB
- With overhead: 1.2GB

### Location History (Cassandra)

```sql
CREATE TABLE location_history (
    user_id BIGINT,
    timestamp BIGINT,
    latitude DOUBLE,
    longitude DOUBLE,
    PRIMARY KEY ((user_id), timestamp)
) WITH CLUSTERING ORDER BY (timestamp DESC)
  AND default_time_to_live = 604800;  -- 7 days
```

**Why Cassandra?**
- Time-series optimized (append-only writes)
- High write throughput (334K writes/sec)
- Linear scalability
- TTL for automatic cleanup

**Capacity:**
- 334K writes/sec × 100 bytes = 33.4 MB/sec
- Per day: 2.9 TB
- 7 days: 20.3 TB
- With RF=3: 60 nodes × 1TB = 60TB

---

## 8. How We Scale (Scaling Each Component)

### WebSocket Servers (Stateful - Tricky!)

**Challenge:** Stateful servers maintain connections

**Solution:** Sticky load balancing with consistent hashing

```
Capacity per server: 100K connections
Total concurrent: 10M users
Servers needed: 10M / 100K = 100 servers

Load balancer config (Nginx):
upstream websocket_backends {
    hash $arg_user_id consistent;
    server ws1.example.com:8080;
    server ws2.example.com:8080;
    ...
}
```

**Auto-scaling:**
- Metric: Connections per server
- Scale up when: Avg > 80K connections/server
- Scale down when: Avg < 40K connections/server

### Redis Pub/Sub (Broadcast Layer)

**Challenge:** Single instance can't handle 334K updates/sec

**Solution:** Distributed Redis Pub/Sub with Consistent Hashing

**How it works:**
- Use consistent hashing: `hash(user_id)` → determines which Redis Pub/Sub server
- Service Discovery (etcd/Zookeeper) maintains list of active servers
- WebSocket servers read hash ring and connect to correct server
- When `channel:alice` needed → both publisher and subscribers hash to same server

**Scaling:**
- 100 Redis Pub/Sub servers
- Each handles ~3.3K updates/sec
- 100K active channels per server
- High availability: 1 master + 1 standby per server

**Benefits:**
- Add/remove servers dynamically
- Auto-discovery of topology changes
- Minimal rehashing when servers fail

### Location Cache (Redis)

**Challenge:** 334K updates/sec is too high for single Redis instance

**Solution:** Shard by user_id across multiple Redis instances

**Sharding:**
- 3 shards using `hash(user_id) % 3`
- Each shard: 3.3M users, 330MB memory, 111K updates/sec
- Location data is independent (no cross-shard queries needed)

**TTL Strategy:**
- 10-minute TTL per location entry
- Auto-renewed every 30 seconds on update
- Offline users auto-expire → automatic memory cap

**High Availability:**
- Each shard: 1 primary + 2 replicas
- Total: 9 Redis instances
- Redis Sentinel for auto-failover

### Location History (Cassandra)

**Architecture:**
- 60-node cluster
- Replication factor: 3
- Each node: 1TB storage

**Distribution:**
- Partition key: user_id
- Data distributed via consistent hashing
- Write: 334K/sec / 60 nodes = 5.5K writes/sec per node

### User Database (PostgreSQL)

**Architecture:**
- 4 shards (25M users each)
- Each shard: 1 master + 3 read replicas
- Total: 16 instances

**Sharding:**
- Shard by: hash(user_id) % 4
- Co-locate friendships with user data
- Avoid cross-shard joins

---

## 9. What Can Go Wrong? (Failure Handling & Fault Tolerance)

### 1. WebSocket Server Crash (Critical - Affects Thousands of Users)

**Scenario:** Entire WebSocket server goes down

**Impact:**
- All users connected to that server lose their WebSocket connections
- In-memory state is LOST (connection objects, Redis subscriptions)
- Users can't send/receive location updates

**Solution:**
```
Detection:
• Health checks (ping every 5 seconds)
• Load balancer detects server is down
• Remove from routing pool immediately

Client Recovery:
• Client detects connection loss via heartbeat timeout
• Auto-reconnect with exponential backoff: 1s, 2s, 4s, 8s, 16s (max 30s)
• Load balancer routes to healthy WebSocket server

Server Rebuilds State:
• New server receives reconnection request
• Validates auth token → gets user_id
• Creates new WebSocket connection object
• Queries User Database → gets friend list
• Subscribes to friends' Redis channels (channel:bob, channel:charlie)
• State fully rebuilt! User back online in <5 seconds

Missed Updates:
• Users may miss 1-2 location updates during downtime
• Acceptable: Next update comes in 30 seconds anyway
```

**Prevention:**
- Run multiple WebSocket servers (horizontal scaling)
- Each server handles 50K-100K connections
- If 1 server fails, only 1% of users affected

---

### 2. Client Network Disconnection (Most Common)

**Scenario:** User loses WiFi/cellular signal

**Solution:**
- Client detects via heartbeat timeout (10 seconds of no response)
- Exponential backoff reconnection: 1s, 2s, 4s, 8s...
- Upon reconnect: Re-authenticate, request latest friend locations
- May miss updates (acceptable - next update in 30s)

---

### 3. Redis Pub/Sub Crash (Cross-Server Communication Lost)

**Scenario:** Redis Pub/Sub instance crashes

**Impact:**
- WebSocket servers can't broadcast location updates to friends
- Users still connected, but don't receive friend updates

**Solution:**
```
High Availability Setup:
┌──────────────────┐
│ Redis Sentinel   │ ← Monitors Redis instances
│ (Master)         │
└──────────────────┘
        │
        │ monitors
        ▼
┌──────────────────┐     ┌──────────────────┐
│ Redis Pub/Sub    │────▶│ Redis Replica    │
│ (Primary)        │     │ (Standby)        │
└──────────────────┘     └──────────────────┘

Auto-Failover:
• Redis Sentinel detects primary is down (3 seconds)
• Promotes replica to primary (5 seconds)
• Updates DNS/config → WebSocket servers reconnect
• Total downtime: ~10 seconds
• Missed updates during failover are acceptable
```

**Alternative:** For mission-critical apps, use Kafka (persistent, more reliable, but higher latency)

---

### 4. Redis Location Cache Crash

**Scenario:** Redis cache instance crashes (location data lost)

**Impact:**
- Can't quickly look up user locations for distance calculations
- Location updates still coming in via WebSocket

**Solution:**
```
Fallback Strategy:
1. Try Location Cache (Redis) → MISS
2. Fallback to Location History DB (Cassandra)
   • Query last 10 minutes of data
   • Populate cache with latest location
3. If not found → Mark friend as "location unavailable"
4. Wait for next WebSocket update (30 seconds)

Cache Warming:
• After Redis restarts, gradually rebuild cache from hot data
• Prioritize active users (those with recent updates)
```

---

### 5. Database Failures

**User Database (PostgreSQL) Failure:**
- **Impact:** Can't validate friends, auth tokens, user profiles
- **Solution:** 
  - Master-Replica setup (synchronous replication)
  - Read replicas for friend list queries
  - Auto-failover to replica in <30 seconds
  - Clients retry requests with exponential backoff

**Location History (Cassandra) Failure:**
- **Impact:** Can't save location history (analytics data)
- **Solution:**
  - Replication factor = 3 (data on 3 nodes)
  - If 1 node fails, read from other 2 replicas
  - Self-healing: Cassandra automatically rebalances
  - Worst case: Lose some historical data (acceptable - cache still works)

---

### 6. Load Balancer Failure

**Scenario:** Load balancer crashes

**Solution:**
- **Primary + Backup** load balancers (active-standby)
- Virtual IP (VIP) floats between them
- Heartbeat between LBs (every 1 second)
- If primary fails → backup takes VIP in <2 seconds
- Stateless failover (no user impact)

---

### 7. Split Brain Scenario (WebSocket + Redis Pub/Sub)

**Scenario:** WebSocket server loses connection to Redis, but clients still connected

**Detection:**
- WebSocket server monitors Redis connection health
- If Redis connection lost → stop accepting new location updates
- Return error to clients: "Service temporarily unavailable"

**Recovery:**
- Reconnect to Redis with exponential backoff
- Once reconnected → resume normal operation
- Clients automatically retry failed requests

---

## 10. Interview Pro Tips

### Opening Statement
"This is a real-time location-sharing system like Find My Friends. The key challenges are handling millions of concurrent WebSocket connections, efficiently broadcasting location updates to friends across different servers, and balancing battery life with update freshness. I'll use WebSocket for bidirectional communication and Redis Pub/Sub for server-to-server broadcasting."

### Key Talking Points
1. **WebSocket vs HTTP:** Real-time, bidirectional, battery-efficient
2. **Redis Pub/Sub:** Channel per user, <1ms latency, perfect for ephemeral data
3. **30-second interval:** Sweet spot for battery vs freshness
4. **Stateful servers:** Need sticky load balancing
5. **Connection Handler:** The glue between WebSocket and Redis

### Common Follow-ups

**Q: How handle WebSocket server failures?**
A: Health checks detect failure in 5 seconds. Clients auto-reconnect to different server with exponential backoff. May miss updates briefly (acceptable - next update in 30s).

**Q: What if user has 10,000 friends?**
A: Pagination (show top 20 nearby first), priority (close friends subset), rate limiting (cap at 100 most relevant), or geohash-based channels to reduce Redis load.

**Q: How ensure privacy?**
A: Authentication (JWT tokens), authorization (friends-only), encryption (TLS), audit logs, GDPR/CCPA compliance, granular controls (user can disable sharing).

**Q: How reduce battery?**
A: Adaptive intervals (5s when moving, 60s when stationary), motion detection (only update if moved >50m), WiFi vs cellular (reduce on cellular), background location APIs.

---

## 11. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║           NEARBY FRIENDS COMPLETE ARCHITECTURE                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  📱 Alice (User)                                                             ║
║      │                                                                        ║
║      │ ① Send location update                                               ║
║      │    (lat, lng, timestamp)                                             ║
║      ▼                                                                        ║
║  ┌────────────────┐                                                          ║
║  │ Load Balancer  │  (Sticky: hash(user_id) → same server)                 ║
║  └───────┬────────┘                                                          ║
║          │                                                                    ║
║          │ ② Route to WebSocket Server 1                                    ║
║          ▼                                                                    ║
║  ┌──────────────────────────────────────────────┐                           ║
║  │     WebSocket Server 1 (Stateful)            │                           ║
║  │                                               │                           ║
║  │  ┌────────────────────────────────────────┐  │                           ║
║  │  │ ConnectionHandler (Alice)              │  │                           ║
║  │  │ • ws connection to Alice's phone       │  │                           ║
║  │  │ • subscribed to channel:bob            │  │                           ║
║  │  │ • subscribed to channel:charlie        │  │                           ║
║  │  └────────────────────────────────────────┘  │                           ║
║  └──────┬─────────┬─────────┬──────────┬────────┘                           ║
║         │         │         │          │                                     ║
║         │③        │④        │⑤         │⑥                                    ║
║         │Get      │Save     │Update    │Publish                             ║
║         │friends  │history  │cache     │to Redis                            ║
║         │         │         │          │                                     ║
║         ▼         ▼         ▼          ▼                                     ║
║  ┌──────────┐┌──────────┐┌────────┐┌─────────────────────────┐             ║
║  │   User   ││ Location ││Location││  Redis Pub/Sub          │             ║
║  │ Database ││ History  ││ Cache  ││  (Consistent Hashing)   │             ║
║  │(Postgres)││(Cassand.)││(Redis) ││                         │             ║
║  │          ││          ││        ││  hash(alice_id)         │             ║
║  │friends:  ││7-day TTL ││10m TTL ││  → Pub/Sub Server #47   │             ║
║  │[bob,     ││time-     ││fast    ││                         │             ║
║  │charlie]  ││series    ││lookups ││  channel:alice          │             ║
║  └──────────┘└──────────┘└────────┘└────────────┬────────────┘             ║
║                                                  │                           ║
║                                      ⑦ Broadcast│to subscribers             ║
║                                                  │                           ║
║                     ┌────────────────────────────┴───────────┐              ║
║                     │                                        │              ║
║                     ▼                                        ▼              ║
║          ┌──────────────────────┐              ┌──────────────────────┐    ║
║          │ WebSocket Server 2   │              │ WebSocket Server 3   │    ║
║          │                      │              │                      │    ║
║          │ ConnectionHandler:   │              │ ConnectionHandler:   │    ║
║          │ • Bob                │              │ • Charlie            │    ║
║          │ • subscribed to      │              │ • subscribed to      │    ║
║          │   channel:alice      │              │   channel:alice      │    ║
║          └──────┬───────────────┘              └──────┬───────────────┘    ║
║                 │                                     │                     ║
║        ⑧ Calculate distance                 ⑧ Calculate distance          ║
║          If < 5 miles, push                   If < 5 miles, push          ║
║                 │                                     │                     ║
║                 ▼                                     ▼                     ║
║          📱 Bob's Phone                        📱 Charlie's Phone          ║
║          "Alice is 2.3 miles away"            "Alice is 4.1 miles away"   ║
║                                                                               ║
║─────────────────────────────────────────────────────────────────────────────║
║                                                                               ║
║  KEY FLOWS:                                                                  ║
║  ① Location Update: Alice's phone → WebSocket → Server 1                   ║
║  ② Routing: Load Balancer uses sticky sessions (hash(user_id))             ║
║  ③ Friend List: Server 1 → User Database (get Alice's friends)             ║
║  ④ Persistence: Server 1 → Location History (Cassandra, 7-day TTL)         ║
║  ⑤ Cache: Server 1 → Location Cache (Redis, 10-min TTL)                    ║
║  ⑥ Broadcast: Server 1 → Redis Pub/Sub (channel:alice)                     ║
║  ⑦ Distribution: Redis → WebSocket Server 2 & 3 (Bob & Charlie's handlers) ║
║  ⑧ Delivery: Calculate distance → Push to friends' phones via WebSocket    ║
║                                                                               ║
║─────────────────────────────────────────────────────────────────────────────║
║                                                                               ║
║  CRITICAL DESIGN DECISIONS:                                                  ║
║                                                                               ║
║  • WebSocket (NOT HTTP polling): Bidirectional, persistent, battery-save    ║
║  • Redis Pub/Sub (NOT Kafka): <1ms latency, ephemeral data, fire-and-forget║
║  • Stateful WebSocket servers: Sticky sessions required, consistent hashing ║
║  • Per-user channels: channel:alice, channel:bob (friends subscribe)        ║
║  • 30-second update interval: Balance between freshness & battery life      ║
║  • Distributed Redis: Consistent hashing for both Pub/Sub & Cache           ║
║  • Service Discovery: etcd/Zookeeper tracks Redis servers for auto-failover ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

**Good luck with your interview!** 🚀
