# Nearby Friends System - Hands-on System Design

This is a hands-on implementation of the nearby friends system from **Alex Xu's System Design Interview Volume 2** book (Chapter 2). The code demonstrates all critical flows you should understand for system design interviews.

## 🎯 Purpose

This implementation is designed to help you:
- **Understand the architecture** through working code
- **Practice explaining flows** in interviews
- **Get hands-on experience** with real-time location-based systems

## 📐 Architecture Overview

```
┌─────────────┐         ┌──────────────────────────────────────┐
│    Mobile   │◄───ws───┤      WebSocket Servers               │
│    Users    │         │  (Stateful, Sticky Sessions)         │
└──────┬──────┘         └────────┬────────────┬────────────────┘
       │                         │            │
       │http                     ▼            ▼
       │                  ┌─────────────────────────────────────┐
       ▼                  │       Redis Pub/Sub                 │
┌─────────────┐          │  (channel:userId for each user)     │
│    Load     │          └─────────────────┬───────────────────┘
│  Balancer   │                            │
└──────┬──────┘                            │
       │                        ┌───────────┴──────────┐
       ▼                        ▼                      ▼
┌─────────────┐         ┌─────────────┐      ┌──────────────┐
│    API      │────────→│  Location   │      │   Location   │
│   Servers   │         │   Cache     │      │   History    │
│ (Stateless) │         │  (Redis)    │      │  (Cassandra) │
└──────┬──────┘         └─────────────┘      └──────────────┘
       │
       ▼
┌─────────────┐
│    User     │
│  Database   │
│ (PostgreSQL)│
└─────────────┘
```

## 📁 Project Structure

```
src/main/java/com/nearbyfriends/
├── NearbyFriendsDemo.java          # Main demo - run this!
│
├── model/                          # Data Models
│   ├── User.java                  # User with friends list
│   ├── Location.java              # Lat/lng with distance calculation
│   ├── FriendLocation.java        # Friend location with distance
│   └── LocationUpdate.java        # Location update message
│
├── service/                        # All Services
│   ├── ApiService.java            # RESTful API handlers
│   ├── LocationService.java       # Location update processing
│   ├── RedisPubSub.java           # Pub/Sub message broker
│   └── Geohash.java               # Geospatial optimization
│
├── storage/                        # Storage Layer
│   ├── UserDB.java                # User profiles & friendships
│   └── LocationHistoryDB.java     # Historical location data
│
├── cache/                          # Caching Layer
│   └── LocationCache.java         # Current location cache
│
└── websocket/                      # WebSocket Layer
    ├── WebSocketServer.java       # WebSocket connection manager
    └── WebSocketConnection.java   # Individual client connection
```

## 🚀 Running the Demo

### Option 1: Using Maven
```bash
cd nearby_friends_system
mvn compile exec:java
```

### Option 2: Using Java directly
```bash
cd nearby_friends_system
javac -d target/classes src/main/java/com/nearbyfriends/**/*.java
java -cp target/classes com.nearbyfriends.NearbyFriendsDemo
```

### Option 3: Using an IDE
Open the project in IntelliJ IDEA or Eclipse and run `NearbyFriendsDemo.java`

## 📚 Key Flows Demonstrated

### 1. RESTful API Request Flow (Figure 5)
```
User → Load Balancer → API Server → Location Cache
                                  ↓
                           Location History DB
                                  ↓
                              User Database
```

**Interview talking points:**
- HTTP APIs for non-real-time operations (add friend, auth, settings)
- Stateless API servers (easy to scale horizontally)
- Cache-first approach for location queries

### 2. Periodic Location Update (Figure 7)
```
Mobile Client (every 30 sec) → WebSocket → WebSocket Server
                                                  │
                        ┌─────────────────────────┼───────────────┐
                        ▼                         ▼               ▼
                 Location Cache          Location History    Redis Pub/Sub
```

**Interview talking points:**
- 30-second update interval balances battery life vs freshness
- WebSocket enables bidirectional real-time communication
- Location stored in both cache (fast reads) and history (analytics)

### 3. Send Location Update to Friends (Figure 8)
```
User 1 publishes location → Redis Pub/Sub (channel:user1)
                                    │
                    ┌───────────────┴─────────────┬─────────────┐
                    ▼                             ▼             ▼
            Friend 2's WS Server          Friend 3's WS      Friend 4's WS
                    │                             │             │
                    ▼                             ▼             ▼
               Friend 2                      Friend 3       Friend 4
```

**Interview talking points:**
- Each user has their own Redis Pub/Sub channel
- Friends subscribe to each other's channels
- Only friends within radius get notified (filtered at WebSocket server)

### 4. Geohash Optimization (Figure 13)
```
Without optimization: Check all 400 friends' locations
With Geohash: 
  User location → Geohash (e.g., "9q8znzd")
               → Subscribe to 9 cells (center + 8 neighbors)
               → Only ~20-30 users in nearby cells
```

**Interview talking points:**
- Geohash encodes 2D location into 1D string
- Common prefix = nearby locations
- Subscribe to geohash channels instead of friend channels
- Reduces Redis Pub/Sub fan-out significantly at scale

## 🔑 Key Design Decisions

### Why WebSocket instead of HTTP?
- **Bidirectional**: Server can push updates to client
- **Real-time**: Sub-second latency for location updates
- **Efficient**: Single persistent connection vs polling overhead
- **Battery-friendly**: Less frequent connections = less battery drain

### Why Redis Pub/Sub?
- **Decoupled**: Publishers don't need to know subscribers
- **Scalable**: Handles high message throughput
- **Real-time**: Sub-millisecond latency
- **Simple**: No complex message queue semantics needed

### Why Redis Cache for Location?
- **Fast**: In-memory, <1ms latency
- **TTL**: Auto-expire old locations (10 minutes)
- **Scalable**: Can handle millions of reads/writes per second

### Why Cassandra for Location History?
- **Time-series optimized**: Efficient for append-heavy workload
- **Scalable**: Linear scalability with more nodes
- **High availability**: Multi-datacenter replication

### Why 30-second update interval?
- **Battery life**: Balance between freshness and power consumption
- **Human speed**: Average walking speed is 3-4 mph
  - In 30 seconds: ~50-100 meters traveled
  - Not significant for "nearby friends" feature
- **Scale**: 30s interval keeps QPS manageable (334K updates/sec)

## 📊 Scale Calculations

```
DAU: 100 million users
Concurrent users: 10% = 10 million
Update interval: 30 seconds
Location update QPS: 10M / 30 = ~334,000 QPS

Each user has ~400 friends
Without optimization: 400 location checks per user
With Geohash: ~20-30 location checks per user
```

## 📋 Interview Cheatsheet

**👉 See [`INTERVIEW_CHEATSHEET.md`](./INTERVIEW_CHEATSHEET.md) for a comprehensive reference!**

The cheatsheet covers:
- Complete requirements and scale estimation
- API design with request/response formats
- Detailed architecture diagrams
- WebSocket vs HTTP trade-offs
- Redis Pub/Sub deep-dive
- Geohash optimization strategies
- Database schema and data models
- Ready-to-use interview answers

---

## 💡 Interview Tips

1. **Start with requirements**: "How many users? Update frequency? Radius?"

2. **Clarify real-time needs**: This determines WebSocket vs HTTP

3. **Draw the high-level diagram first**: Mobile → Load Balancer → Services → Storage

4. **Discuss trade-offs**:
   - WebSocket vs HTTP polling
   - Redis Pub/Sub vs message queue
   - Friend channels vs Geohash channels
   - 30-second vs 5-second updates

5. **Mention scalability strategies**:
   - Horizontal scaling of WebSocket servers (stateful, use consistent hashing)
   - Redis Pub/Sub sharding by user_id
   - Database sharding strategies
   - CDN for static assets

6. **Privacy and security**:
   - Location data is sensitive (GDPR/CCPA compliance)
   - Users can disable location sharing
   - Friends-only visibility (not public)
   - Encrypted communication (TLS)

## 🔧 Extending the Demo

Ideas for further exploration:
- Add actual WebSocket implementation (Netty/Spring)
- Implement location history visualization
- Add privacy controls (sharing radius, time limits)
- Implement battery-saving strategies (dynamic update intervals)
- Add location prediction (reduce server load)
- Implement geofencing (notify when friend enters area)

## 📖 References

- Alex Xu's "System Design Interview Volume 2" - Chapter 2
- Redis Pub/Sub documentation
- Geohash algorithm and implementations
- Life360 and Find My Friends architecture
- WebSocket protocol specification

---

## 🌟 Key Differences from Other Location Services

| Feature | Nearby Friends | Proximity Service (Yelp) |
|---------|----------------|--------------------------|
| **Data Type** | Real-time user locations | Static business locations |
| **Update Frequency** | Every 30 seconds | Rarely (business hours change) |
| **Read/Write Ratio** | Balanced | Read-heavy (100:1) |
| **Privacy** | Friends-only | Public data |
| **Communication** | WebSocket (bidirectional) | HTTP REST (request/response) |
| **Optimization** | Geohash channels | Geohash + QuadTree indexing |

This helps you understand when to apply which techniques in different scenarios!
