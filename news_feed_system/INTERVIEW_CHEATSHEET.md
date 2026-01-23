# News Feed System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

> **Your comprehensive reference for news feed system design interviews.**

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Post Service** | Create/manage posts | Post DB + Cache | Publishes event to Kafka |
| **Fanout Service** | Distribute posts | Kafka Consumer | Push (write) vs Pull (read) |
| **Notification Service** | Push notifications | Kafka Consumer | Rate limited, close friends only |
| **News Feed Service** | Retrieve feeds | Feed Cache | Merge cached + celebrity posts |
| **Graph DB** | Social relationships | Disk | Followers/Following edges |
| **Post Cache** | Cache posts | Redis | Hot cache + Normal cache |
| **News Feed Cache** | Pre-computed feeds | Redis | **Separate** sorted set per user! |

---

## 1. Feed Publishing Flow (Fanout on Write - Async/Event-Driven)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FEED PUBLISHING - USER CREATES A POST (Production Architecture)            ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────────────────────── SYNCHRONOUS PATH ─────────────────────────┐║
║  │                                                                           │║
║  │  1. User → POST /v1/me/feed?content=Hello                                │║
║  │                    │                                                      │║
║  │                    ▼                                                      │║
║  │  2. Load Balancer → API Service (Auth + Rate Limiting)                   │║
║  │                    │                                                      │║
║  │                    ▼                                                      │║
║  │  3. Post Service:                                                        │║
║  │     • Save to Post DB (persistent storage)                               │║
║  │     • Add to Post Cache (Redis - for fast reads)                         │║
║  │     • Publish "PostCreated" event to Kafka ──────┐                       │║
║  │                    │                              │                       │║
║  │                    ▼                              │                       │║
║  │  4. Return 201 Created (< 100ms)                 │                       │║
║  │     User sees "Post published!" immediately       │                       │║
║  │                                                   │                       │║
║  └───────────────────────────────────────────────────│───────────────────────┘║
║                                                      │                        ║
║  ┌─────────────────────────────── ASYNC PATH ────────│───────────────────────┐║
║  │                                                   │                       │║
║  │                                                   ▼                       │║
║  │  5. Kafka Topic: "post-events"                                           │║
║  │     (Stores event until consumed, replay if needed)                      │║
║  │                    │                                                      │║
║  │        ┌───────────┴───────────┐                                         │║
║  │        │ (parallel consumers)  │                                         │║
║  │        ▼                       ▼                                         │║
║  │  ┌─────────────────┐   ┌──────────────────────┐                          │║
║  │  │ Fanout Service  │   │ Notification Service │                          │║
║  │  │ (Kafka Consumer)│   │  (Kafka Consumer)    │                          │║
║  │  │ poll() loop     │   │  poll() loop         │                          │║
║  │  └────────┬────────┘   └──────────┬───────────┘                          │║
║  │           │                       │                                       │║
║  │           ▼                       ▼                                       │║
║  │  6a. For each follower:   6b. Send push notification:                    │║
║  │      • Get follower IDs       • Rate limit (1/hour/author)               │║
║  │        from Graph DB          • Filter (close friends only)              │║
║  │      • ZADD to follower's     • APNs (iOS) / FCM (Android)               │║
║  │        feed cache (Redis)     • Deep-link to post_id                     │║
║  │                                                                           │║
║  └───────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
║  KEY POINTS:                                                                 ║
║  • PostService does NOT call FanoutService or NotificationService directly  ║
║  • Services are decoupled via Kafka - can scale/fail independently          ║
║  • Consumers use poll() (pull-based), NOT push                              ║
║  • If fanout fails, post still exists - retry from Kafka                    ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Feed Retrieval Flow

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FEED RETRIEVAL - USER VIEWS THEIR FEED                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. User → GET /v1/me/feed                                                   ║
║                    │                                                          ║
║                    ▼                                                          ║
║  2. Load Balancer → Web Server (Auth + Rate Limiting)                        ║
║                    │                                                          ║
║                    ▼                                                          ║
║  3. News Feed Service:                                                       ║
║                    │                                                          ║
║     ┌──────────────┼──────────────┐                                          ║
║     ▼              ▼              ▼                                          ║
║  4. News Feed   5. Post       5. User                                        ║
║     Cache          Cache         Cache                                        ║
║  (get post IDs) (get posts)  (get authors)                                   ║
║     │              │              │                                          ║
║     └──────────────┴──────────────┘                                          ║
║                    │                                                          ║
║                    ▼                                                          ║
║  6. Merge + Rank posts                                                       ║
║     • Include celebrity posts (fanout on read)                               ║
║     • Sort by score/time                                                      ║
║                    │                                                          ║
║                    ▼                                                          ║
║  7. Return to client (media via CDN)                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 3. FANOUT ON WRITE vs FANOUT ON READ

### Fanout on Write (Push Model)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FANOUT ON WRITE - For Regular Users (< 10K followers)                       ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  When Alice posts → Push to ALL followers' feeds immediately                 ║
║                                                                               ║
║  Alice posts ─────┬────▶ Bob's Feed Cache                                    ║
║                   ├────▶ Charlie's Feed Cache                                ║
║                   ├────▶ Diana's Feed Cache                                  ║
║                   └────▶ Eve's Feed Cache                                    ║
║                                                                               ║
║  PROS:                                                                       ║
║  ✅ Fast reads (feed is pre-computed)                                        ║
║  ✅ Simple read logic                                                        ║
║  ✅ Real-time updates                                                        ║
║                                                                               ║
║  CONS:                                                                       ║
║  ❌ Slow writes for users with many followers                                ║
║  ❌ Wasted work if followers never read                                      ║
║  ❌ Hot key problem for celebrities                                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Fanout on Read (Pull Model)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FANOUT ON READ - For Celebrities (> 10K followers)                          ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  When Elon posts → Just save to his timeline                                ║
║  When followers request feed → Merge Elon's posts at read time               ║
║                                                                               ║
║  Elon posts ─────▶ Elon's Timeline (only)                                   ║
║                                                                               ║
║  Bob requests feed:                                                          ║
║  1. Get pre-computed feed (from regular users)                               ║
║  2. Get celebrities Bob follows                                              ║
║  3. Fetch recent posts from each celebrity's timeline                       ║
║  4. Merge + Sort                                                             ║
║                                                                               ║
║  PROS:                                                                       ║
║  ✅ Fast writes (no fanout)                                                  ║
║  ✅ No wasted work                                                           ║
║  ✅ Solves hot key problem                                                   ║
║                                                                               ║
║  CONS:                                                                       ║
║  ❌ Slow reads (need to merge)                                               ║
║  ❌ Complex read logic                                                       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Hybrid Approach (Best of Both)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  HYBRID APPROACH - What Facebook/Twitter Actually Use                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  DECISION LOGIC:                                                             ║
║                                                                               ║
║  if (followers < 10,000):                                                    ║
║      use FANOUT ON WRITE                                                     ║
║  else:                                                                       ║
║      use FANOUT ON READ                                                      ║
║                                                                               ║
║  AT READ TIME:                                                               ║
║  1. Get pre-computed feed from cache (regular users' posts)                 ║
║  2. Find celebrities this user follows                                       ║
║  3. Fetch celebrity posts from their timelines                              ║
║  4. Merge both lists                                                         ║
║  5. Rank and return                                                          ║
║                                                                               ║
║  This is the optimal balance of read and write performance!                  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. News Feed Cache Deep-Dive (Important!)

### Each User Has Their OWN Sorted Set!

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  NEWS FEED CACHE - SEPARATE SORTED SET PER USER (NOT SHARED!)                ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Redis Structure:                                                            ║
║  ┌────────────────────────────────────────────────────────────────────────┐  ║
║  │  KEY                VALUE (Sorted Set)                                 │  ║
║  │  ─────────────      ────────────────────────────────────────────────── │  ║
║  │  feed:user_1   →   [(post_100, 1705312900), (post_99, 1705312800)]    │  ║
║  │  feed:user_2   →   [(post_100, 1705312900), (post_88, 1705311000)]    │  ║
║  │  feed:user_3   →   [(post_77, 1705310500), (post_66, 1705309000)]     │  ║
║  │  feed:user_4   →   [(post_100, 1705312900), (post_77, 1705310500)]    │  ║
║  │  ...                                                                   │  ║
║  └────────────────────────────────────────────────────────────────────────┘  ║
║                                                                               ║
║  WHEN ALICE (user_1) POSTS:                                                  ║
║                                                                               ║
║  Fanout Service does:                                                        ║
║    → ZADD feed:user_2 1705313000 post_101   (add to Bob's feed)             ║
║    → ZADD feed:user_3 1705313000 post_101   (add to Charlie's feed)         ║
║    → ZADD feed:user_4 1705313000 post_101   (add to Diana's feed)           ║
║                                                                               ║
║  Each follower has Alice's post ADDED to their INDIVIDUAL feed!              ║
║                                                                               ║
║  WHEN BOB (user_2) REQUESTS FEED:                                            ║
║    → ZREVRANGE feed:user_2 0 19   (get top 20 from Bob's feed only)         ║
║                                                                               ║
║  SPACE USAGE:                                                                ║
║  • Each post ID stored N times (once per follower)                          ║
║  • 100M users × 800 posts × 8 bytes = ~640GB (still fits in Redis cluster)  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Cache Layers (from Figure 11-8)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  CACHE STRUCTURE                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐  ║
║  │ NEWS FEED CACHE                                                         │  ║
║  │   feed:{userId} → Sorted Set of (post_id, score)                       │  ║
║  │   Redis: ZREVRANGE feed:123 0 19 (get top 20)                          │  ║
║  └─────────────────────────────────────────────────────────────────────────┘  ║
║                                                                               ║
║  ┌─────────────────────────────────┬───────────────────────────────────────┐  ║
║  │ CONTENT CACHE                   │                                       │  ║
║  │                                 │                                       │  ║
║  │  ┌───────────┐  ┌───────────┐   │  Hot: Viral/trending posts           │  ║
║  │  │ HOT CACHE │  │  NORMAL   │   │  Normal: Regular posts with TTL      │  ║
║  │  │ (viral)   │  │  CACHE    │   │                                       │  ║
║  │  └───────────┘  └───────────┘   │  Promotion: normal → hot if trending │  ║
║  │                                 │                                       │  ║
║  └─────────────────────────────────┴───────────────────────────────────────┘  ║
║                                                                               ║
║  ┌─────────────────────────────────┬───────────────────────────────────────┐  ║
║  │ SOCIAL GRAPH CACHE              │                                       │  ║
║  │                                 │                                       │  ║
║  │  ┌───────────┐  ┌───────────┐   │  Follower: Who follows me            │  ║
║  │  │ FOLLOWER  │  │ FOLLOWING │   │  Following: Who I follow             │  ║
║  │  └───────────┘  └───────────┘   │                                       │  ║
║  │                                 │                                       │  ║
║  └─────────────────────────────────┴───────────────────────────────────────┘  ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐  ║
║  │ ACTION/COUNTER CACHE                                                    │  ║
║  │                                                                         │  ║
║  │  ┌───────────┐  ┌───────────┐  ┌───────────┐                           │  ║
║  │  │   LIKED   │  │  REPLIED  │  │  OTHERS   │                           │  ║
║  │  └───────────┘  └───────────┘  └───────────┘                           │  ║
║  │                                                                         │  ║
║  │  ┌───────────┐  ┌───────────┐  ┌───────────┐                           │  ║
║  │  │   LIKE    │  │   REPLY   │  │   OTHER   │                           │  ║
║  │  │  COUNTER  │  │  COUNTER  │  │  COUNTERS │                           │  ║
║  │  └───────────┘  └───────────┘  └───────────┘                           │  ║
║  │                                                                         │  ║
║  └─────────────────────────────────────────────────────────────────────────┘  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 5. Database Deep-Dive

### All Databases

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATABASE               │  TYPE              │  STORES                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  1. Post DB             │  MySQL/Postgres    │  Posts (content, media)        ║
║  2. User DB             │  MySQL/Postgres    │  User profiles                 ║
║  3. Graph DB            │  Neo4j/Cassandra   │  Follower/Following edges      ║
║  4. Redis (Cache)       │  In-Memory         │  All caches above              ║
║  5. Blob Storage        │  S3/CDN            │  Media files                   ║
║  6. Message Queue       │  Kafka/RabbitMQ    │  Fanout tasks                  ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Data Models

#### Post Table (MySQL)
```sql
CREATE TABLE posts (
    post_id      BIGINT PRIMARY KEY,    -- Snowflake ID
    author_id    BIGINT NOT NULL,
    content      TEXT,
    media_url    VARCHAR(500),
    post_type    ENUM('TEXT', 'IMAGE', 'VIDEO', 'LINK'),
    like_count   INT DEFAULT 0,
    comment_count INT DEFAULT 0,
    share_count  INT DEFAULT 0,
    created_at   TIMESTAMP,
    updated_at   TIMESTAMP,
    INDEX idx_author (author_id, created_at DESC)
);
```

#### User Table (MySQL)
```sql
CREATE TABLE users (
    user_id      BIGINT PRIMARY KEY,
    username     VARCHAR(50) UNIQUE,
    display_name VARCHAR(100),
    avatar_url   VARCHAR(500),
    follower_count INT DEFAULT 0,
    following_count INT DEFAULT 0,
    is_celebrity BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMP
);
```

#### Social Graph (Cassandra or Graph DB)
```sql
-- In Cassandra
CREATE TABLE followers (
    user_id      BIGINT,
    follower_id  BIGINT,
    followed_at  TIMESTAMP,
    PRIMARY KEY ((user_id), follower_id)
);

CREATE TABLE following (
    user_id      BIGINT,
    followee_id  BIGINT,
    followed_at  TIMESTAMP,
    PRIMARY KEY ((user_id), followee_id)
);
```

#### News Feed Cache (Redis)
```
# Sorted Set: score = timestamp (or ranking score)
ZADD feed:{userId} {timestamp} {postId}

# Get latest 20 posts
ZREVRANGE feed:123 0 19

# Add new post to feed
ZADD feed:123 1705312800 post_456

# Trim to keep only recent 800 posts
ZREMRANGEBYRANK feed:123 0 -801
```

---

## 6. Architecture: Sync vs Async Fanout (Critical Interview Topic!)

### Approach 1: Synchronous (Simple but Coupled)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  SYNC APPROACH - PostService directly calls FanoutService                    ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Web Server → PostService → Save to DB/Cache → FanoutService.fanout()        ║
║                                                       │                       ║
║                                             (blocks until done)               ║
║                                                       │                       ║
║                                                       ▼                       ║
║                                               Update Feed Caches              ║
║                                                                               ║
║  PROBLEMS:                                                                   ║
║  ❌ PostService is COUPLED to FanoutService                                  ║
║  ❌ API latency affected by fanout time                                      ║
║  ❌ If fanout fails, what happens to the post?                               ║
║  ❌ Hard to scale fanout independently                                       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Approach 2: Async/Event-Driven (Production - Recommended ✅)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  ASYNC APPROACH - Event-Driven with Kafka (What Facebook/Twitter Use)        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌──────────────┐                                                            ║
║  │ Post Service │ ─── 1. Save to DB/Cache                                    ║
║  │  (Producer)  │ ─── 2. Publish "PostCreated" event to Kafka                ║
║  └──────────────┘                                                            ║
║         │                                                                     ║
║         │ (API returns immediately to user! < 100ms)                         ║
║         ▼                                                                     ║
║  ┌──────────────────────────────────────┐                                    ║
║  │        KAFKA - Topic: "post-events"  │                                    ║
║  │  ┌────────┐ ┌────────┐ ┌────────┐    │   Messages stored on disk         ║
║  │  │ Event1 │ │ Event2 │ │ Event3 │    │   Retained for N days             ║
║  │  └────────┘ └────────┘ └────────┘    │   Replay if needed                ║
║  └──────────────────────────────────────┘                                    ║
║         │                                                                     ║
║         │ (async consumption)                                                ║
║         ▼                                                                     ║
║  ┌──────────────┐                                                            ║
║  │Fanout Service│  ← SEPARATE microservice / consumer group                  ║
║  │  (Consumer)  │                                                            ║
║  └──────────────┘                                                            ║
║         │                                                                     ║
║         ├── Get followers from Graph DB                                      ║
║         ├── Check if celebrity (> 10K followers)                             ║
║         │       ├── Regular: Fanout on Write → push to feed caches          ║
║         │       └── Celebrity: Fanout on Read → skip (merge at read time)   ║
║         ▼                                                                     ║
║  ┌──────────────────────────────────────┐                                    ║
║  │         NEWS FEED CACHE              │                                    ║
║  │  feed:user1, feed:user2, ...         │                                    ║
║  └──────────────────────────────────────┘                                    ║
║                                                                               ║
║  BENEFITS:                                                                   ║
║  ✅ DECOUPLED: PostService doesn't know about FanoutService                  ║
║  ✅ FAST API: Returns immediately (fanout is background)                     ║
║  ✅ RELIABLE: Kafka persists events, retry on failure                        ║
║  ✅ SCALABLE: Add more Fanout consumers independently                        ║
║  ✅ OBSERVABLE: Can add more consumers (analytics, search indexing)          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Comparison Table

| Aspect | Sync (Direct Call) | Async (Event-Driven) |
|--------|-------------------|----------------------|
| **Coupling** | PostService knows FanoutService | Fully decoupled |
| **API Latency** | Slow (waits for fanout) | Fast (< 100ms) |
| **Failure Handling** | Fanout failure = post failure? | Independent, retry from Kafka |
| **Scalability** | Limited | Scale consumers independently |
| **Complexity** | Simple | More infrastructure |
| **Production Ready** | ❌ Not recommended | ✅ What FB/Twitter use |

---

## 7. Message Queue Flow (Detailed)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  COMPLETE EVENT FLOW                                                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. POST SERVICE (Producer)                                                  ║
║     postService.createPost() {                                               ║
║         postDB.save(post);           // Persist                              ║
║         postCache.put(post);         // Cache                                ║
║         kafka.publish("post-events", PostCreatedEvent(post));  // Event!     ║
║         return post;                 // Return immediately                   ║
║     }                                                                         ║
║                                                                               ║
║  2. KAFKA TOPIC: "post-events"                                               ║
║     • Partitioned by author_id (ordering per user)                           ║
║     • Retained for 7 days                                                    ║
║     • Multiple consumer groups can subscribe                                 ║
║                                                                               ║
║  3. FANOUT SERVICE (Consumer)                                                ║
║     @KafkaListener(topics = "post-events")                                   ║
║     handlePostCreated(event) {                                               ║
║         post = event.getPost();                                              ║
║         followers = graphDB.getFollowers(post.authorId);                     ║
║                                                                               ║
║         if (followers.size() > CELEBRITY_THRESHOLD) {                        ║
║             // Fanout on Read - skip, merge at read time                    ║
║         } else {                                                              ║
║             // Fanout on Write - push to each follower's feed               ║
║             for (follower : followers) {                                     ║
║                 feedCache.addToFeed(follower, post.id);                      ║
║             }                                                                 ║
║         }                                                                     ║
║     }                                                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 8. Ranking and Scoring

### Simple Chronological (Time-based)
```
score = post.created_at (Unix timestamp)
```

### Engagement-based Ranking
```
score = likes * 1.0 + comments * 2.0 + shares * 3.0 + recency_bonus
```

### ML-based Ranking (Facebook/Instagram)
```
Features:
- Post type (image, video, text)
- Author relationship strength
- Historical engagement with author
- Time since posted
- User's past behavior

Model: Predict probability of engagement
```

---

## 9. Notification Service (Parallel Consumer)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  NOTIFICATION SERVICE - PARALLEL KAFKA CONSUMER                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  PostService publishes ONE event to Kafka                                    ║
║  TWO consumers process it IN PARALLEL:                                       ║
║                                                                               ║
║  ┌──────────────┐                                                            ║
║  │ Post Service │ ─── publish("PostCreated") ───▶ │                          ║
║  └──────────────┘                                 │                          ║
║                                                   ▼                          ║
║                              ┌──────────────────────────────────┐            ║
║                              │   KAFKA - Topic: "post-events"   │            ║
║                              └──────────────────────────────────┘            ║
║                                          │                                    ║
║                       ┌──────────────────┴──────────────────┐                ║
║                       │                                     │                ║
║                       ▼                                     ▼                ║
║           ┌──────────────────────┐            ┌──────────────────────┐       ║
║           │   FanoutService      │            │  NotificationService │       ║
║           │  (Consumer Group 1)  │            │  (Consumer Group 2)  │       ║
║           └──────────┬───────────┘            └──────────┬───────────┘       ║
║                      │                                   │                   ║
║                      ▼                                   ▼                   ║
║           Update Feed Caches                    Rate Limit Check             ║
║           (fanout on write)                            │                     ║
║                                          ┌─────────────┴─────────────┐       ║
║                                          ▼             ▼             ▼       ║
║                                      ┌──────┐      ┌──────┐      ┌──────┐   ║
║                                      │ APNs │      │ FCM  │      │Email │   ║
║                                      │(iOS) │      │(And) │      │      │   ║
║                                      └──────┘      └──────┘      └──────┘   ║
║                                                                               ║
║  NOTIFICATION FILTERS:                                                       ║
║  • NOT all followers - only "close friends" or "notifications enabled"      ║
║  • Rate limiting: 1 notification per author per hour (don't spam!)          ║
║  • Active users only (seen in last 7 days)                                  ║
║  • Users who haven't muted this author                                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 10. Interview Quick Answers

**Q: Who invokes the fanout service - PostService or the queue?**
> "In production, PostService should NOT directly call FanoutService. Instead, PostService publishes a 'PostCreated' event to Kafka and returns immediately (< 100ms). FanoutService is a SEPARATE consumer that reads from Kafka asynchronously. This decouples the services, makes the API fast, and allows fanout to fail/retry independently. This is what Facebook and Twitter actually use."

**Q: Fanout on write vs fanout on read?**
> "Fanout on write pushes posts to followers' feeds at write time - fast reads but slow writes. Fanout on read saves to author's timeline only, merges at read time - slow reads but fast writes. We use hybrid: write for regular users (<10K followers), read for celebrities."

**Q: How do you handle celebrities with millions of followers?**
> "Celebrities use fanout on read. When Taylor Swift posts, we don't push to 100M feeds. Instead, her posts stay on her timeline. When followers request their feed, we merge her recent posts at read time."

**Q: What caches do you use?**
> "Three main caches: News Feed Cache (pre-computed feeds per user as sorted sets), Post Cache (post content with hot/normal tiers), and Social Graph Cache (follower/following relationships). All in Redis."

**Q: How is the news feed stored? Is it shared or per user?**
> "Each user has their OWN separate sorted set in Redis. Key: `feed:{userId}`, Value: sorted set of `(post_id, score)`. When Alice posts, we ZADD to each follower's individual feed: `ZADD feed:bob {timestamp} post_123`. This is 'fanout on write' - we pre-compute feeds at write time so reads are fast. Yes, post IDs are stored N times (once per follower), but reads are O(1)."

**Q: How do push notifications work with posts?**
> "NotificationService is a SEPARATE Kafka consumer, parallel to FanoutService. Both consume 'PostCreated' events independently. FanoutService updates feeds, NotificationService sends push. We rate-limit (1/hour per author) and only notify 'close friends' or users who enabled notifications - not all followers. Uses APNs for iOS, FCM for Android."

**Q: What if notification arrives before the feed is updated?**
> "The notification contains the `post_id` and deep-links to that specific post, NOT to the feed. When the user clicks, we fetch from PostCache (which was written synchronously before publishing to Kafka). The feed uses eventual consistency - by the time the user scrolls their feed (1-2 seconds later), fanout is complete. We also rate-limit notifications, so not every post triggers one."

**Q: Are FanoutService and NotificationService separate deployables or modules?**
> "At scale, they're separate microservices with independent deployments. FanoutService might need 100 instances during peak hours (celebrity posts), while NotificationService might only need 20. They communicate through Kafka, not direct calls. This gives us independent scaling, fault isolation, and allows different teams to own each service. For smaller systems, they can be modules in the same monolith - the key is they're decoupled via the message queue either way."

**Q: Since services communicate via Kafka, do they use push or pull?**
> "Pull-based using `consumer.poll()`. Kafka does NOT push to consumers. Each service runs a poll loop: `while(true) { records = consumer.poll(Duration.ofSeconds(1)); process(records); }`. The consumer controls the pace, can pause/resume, and tracks its own offset. This is why Kafka is reliable - consumers pull when ready, and if they crash, they resume from their last committed offset."

**Q: How do you avoid JOIN problems in a sharded news feed?**
> "The news feed is designed to avoid JOINs entirely. Feeds are pre-computed during fanout (write time), so reading is just `ZREVRANGE` from Redis. Post and user details are fetched via batch `MGET` calls (key-value lookups, not JOINs). We 'join' in application code using HashMap lookups - this is fast because it's in-memory. All our queries are single-table, key-based lookups - perfect for sharding. We never need `SELECT posts JOIN users WHERE...` across shards."

**Q: How do you rank posts?**
> "Can be simple chronological (score = timestamp) or ML-based. Facebook uses prediction models considering: engagement probability, author relationship strength, content type, recency, user behavior patterns."

**Q: What happens when a user unfollows someone?**
> "For fanout on write users, we need to remove their posts from the unfollower's feed cache. This can be lazy (don't show if author no longer followed) or eager (background job removes). For celebrities, no action needed - they're merged at read time anyway."

---

## 11. Scalability Strategies

| Strategy | How |
|----------|-----|
| **Sharding** | Shard Post DB by post_id, User DB by user_id, Feed Cache by user_id |
| **Caching** | Multi-tier caching (hot/normal), CDN for media |
| **Queue** | Buffer writes, scale workers independently |
| **Celebrity handling** | Hybrid fanout approach |
| **Geographic** | Regional data centers, replicate hot data |

---

## 12. Failure Scenarios

| Scenario | Solution |
|----------|----------|
| Cache miss | Fall back to database, refill cache |
| Worker crash | Queue preserves tasks, other workers continue |
| Database down | Read from cache (stale but available) |
| Celebrity posts flood | Fanout on read prevents write amplification |
| Feed cache full | ZREMRANGEBYRANK to trim old entries |

---

## 13. Visual Architecture

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                 NEWS FEED SYSTEM ARCHITECTURE (Event-Driven)                 │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────┐                                           ┌───────────┐         │
│  │  User   │◄──────────────────────────────────────────│    CDN    │         │
│  │ (App)   │──┐                                        │  (Media)  │         │
│  └─────────┘  │                                        └───────────┘         │
│               │                                                               │
│               ▼                                                               │
│        ┌─────────────┐                                                        │
│        │    Load     │                                                        │
│        │  Balancer   │                                                        │
│        └──────┬──────┘                                                        │
│               │                                                               │
│        ┌──────┴──────┐                                                        │
│        ▼             ▼                                                        │
│  ┌───────────┐ ┌───────────┐                                                  │
│  │ API Serv  │ │ API Serv  │  (Auth + Rate Limiting)                         │
│  └─────┬─────┘ └─────┬─────┘                                                  │
│        │             │                                                        │
│        └──────┬──────┘                                                        │
│               │                                                               │
│   ┌───────────┴───────────┐                                                   │
│   ▼                       ▼                                                   │
│ ┌──────────┐        ┌────────────┐                                           │
│ │  Post    │        │ NewsFeed   │   ← SYNCHRONOUS (user-facing)             │
│ │ Service  │        │  Service   │                                           │
│ └────┬─────┘        └─────┬──────┘                                           │
│      │                    │                                                   │
│      │ 1. Save            │ Read from caches                                 │
│      ▼                    ▼                                                   │
│  ┌───────┐          ┌──────────┐                                             │
│  │PostDB │          │ Caches   │                                             │
│  │+Cache │          │ (Redis)  │                                             │
│  └───┬───┘          └──────────┘                                             │
│      │                                                                        │
│      │ 2. Publish "PostCreated" event                                        │
│      ▼                                                                        │
│  ════════════════════════════════════════════════════════════                │
│  │              KAFKA (post-events topic)                    │                │
│  ════════════════════════════════════════════════════════════                │
│      │                                                                        │
│      │ poll() ─────────────────────────── poll()                             │
│      ▼                                       ▼                                │
│  ┌────────────┐                        ┌──────────────┐                      │
│  │  Fanout    │  ← ASYNC               │ Notification │  ← ASYNC             │
│  │  Service   │    (Kafka Consumer)    │   Service    │    (Kafka Consumer)  │
│  └─────┬──────┘                        └──────┬───────┘                      │
│        │                                      │                               │
│        │ 3. Get followers                     │ 4. Send push                  │
│        ▼                                      ▼                               │
│  ┌──────────┐                          ┌───────────┐                         │
│  │ Graph DB │                          │ APNs/FCM  │                         │
│  └────┬─────┘                          └───────────┘                         │
│       │                                                                       │
│       │ 5. ZADD to each follower's feed                                      │
│       ▼                                                                       │
│  ┌────────────────────────────────────────────────────────────────┐          │
│  │                       CACHE LAYER (Redis)                      │          │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────────────────────────┐ │          │
│  │  │ Post     │  │ User     │  │ NewsFeed Cache               │ │          │
│  │  │ Cache    │  │ Cache    │  │ (Sorted Set PER USER)        │ │          │
│  │  └──────────┘  └──────────┘  │ feed:user1, feed:user2, ...  │ │          │
│  │                              └──────────────────────────────┘ │          │
│  └────────────────────────────────────────────────────────────────┘          │
│                              │                                                │
│                              ▼                                                │
│  ┌────────────────────────────────────────────────────────────────┐          │
│  │                     DATABASE LAYER                             │          │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐                     │          │
│  │  │ Post DB  │  │ User DB  │  │ Graph DB │                     │          │
│  │  │ (MySQL)  │  │ (MySQL)  │  │ (Neo4j)  │                     │          │
│  │  └──────────┘  └──────────┘  └──────────┘                     │          │
│  └────────────────────────────────────────────────────────────────┘          │
│                                                                               │
│  KEY: PostService does NOT call FanoutService directly!                      │
│       They communicate via Kafka (decoupled, async, poll-based)              │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

*Good luck with your interview! 🎯*

