# News Feed System - Hands-on System Design

This is a hands-on implementation of the news feed system from **Alex Xu's System Design Interview** book (Chapter 11). The code demonstrates fanout on write, fanout on read, and the hybrid approach used by Facebook/Twitter.

## 🎯 Purpose

This implementation is designed to help you:
- **Understand fanout strategies** through working code
- **Practice explaining trade-offs** in interviews
- **Get hands-on experience** with feed generation and caching

## 📐 Architecture Overview

```
┌─────────────┐         ┌─────────────────────────────────────┐
│    User     │◄───────▶│          Web Servers                │
│  (Client)   │         │    (Auth + Rate Limiting)           │
└──────┬──────┘         └─────────────┬───────────────────────┘
       │                              │
       │                 ┌────────────┼────────────┐
       │                 ▼            ▼            ▼
       │          ┌───────────┐ ┌───────────┐ ┌───────────┐
       │          │   Post    │ │  Fanout   │ │ NewsFeed  │
       │          │  Service  │ │  Service  │ │  Service  │
       │          └─────┬─────┘ └─────┬─────┘ └─────┬─────┘
       │                │             │             │
       │                ▼             ▼             ▼
       │         ┌────────────────────────────────────────────┐
       │         │              CACHE LAYER                   │
       │         │  Post Cache │ User Cache │ Feed Cache      │
       │         └────────────────────────────────────────────┘
       │                              │
       │                              ▼
       │         ┌────────────────────────────────────────────┐
       │         │            DATABASE LAYER                  │
       │         │  Post DB │ User DB │ Graph DB              │
       │         └────────────────────────────────────────────┘
       │
       ▼
┌─────────────┐
│     CDN     │  (for media: images, videos)
└─────────────┘
```

## 📁 Project Structure

```
src/main/java/com/newsfeed/
├── NewsFeedDemo.java           # Main demo - run this!
│
├── models/                     # Data Models
│   ├── User.java              # User model
│   ├── Post.java              # Post/content model
│   └── FeedItem.java          # Feed entry (post_id, score)
│
├── service/                    # Business Logic Services
│   ├── ApiService.java        # REST API endpoints (auth, publish, feed)
│   ├── PostService.java       # Create/manage posts (publishes to Kafka)
│   ├── FanoutService.java     # Kafka consumer - distribute to feeds
│   ├── NotificationService.java # Kafka consumer - push notifications
│   └── NewsFeedService.java   # Retrieve news feed
│
├── storage/                    # Database Layer
│   ├── PostDB.java            # Post database
│   ├── UserDB.java            # User database
│   └── GraphDB.java           # Social graph (followers/following)
│
├── cache/                      # Cache Layer
│   ├── PostCache.java         # Post cache (hot + normal)
│   ├── UserCache.java         # User profile cache
│   └── NewsFeedCache.java     # Pre-computed feed cache
│
├── queue/                      # Message Queue
│   └── FanoutQueue.java       # Message queue for fanout
│
└── worker/                     # Background Workers
    └── FanoutWorker.java      # Processes fanout tasks
```

## 🚀 Running the Demo

### Option 1: Using Maven
```bash
cd news_feed_system
mvn compile exec:java
```

### Option 2: Using Java directly
```bash
cd news_feed_system
javac -d target/classes src/main/java/com/newsfeed/**/*.java
java -cp target/classes com.newsfeed.NewsFeedDemo
```

### Option 3: Using an IDE
Open the project in IntelliJ IDEA or Eclipse and run `NewsFeedDemo.java`

## 📋 Interview Cheatsheet

**👉 See [`INTERVIEW_CHEATSHEET.md`](./INTERVIEW_CHEATSHEET.md) for a comprehensive reference!**

The cheatsheet covers:
- Feed publishing flow (fanout on write)
- Feed retrieval flow
- Fanout on write vs fanout on read comparison
- Cache architecture (5 cache types)
- Database models
- Message queue flow
- Ranking strategies
- Ready-to-use interview answers

## 📚 Key Flows Demonstrated

### 1. Feed Publishing API Flow (POST /v1/me/feed)
```
Client → Load Balancer → API Service → Post Service → Save to DB & Cache
                                            │
                                            ▼
                                      Fanout Service
                                            │
                            ┌───────────────┴───────────────┐
                            ▼                               ▼
                    Regular User                       Celebrity
                    (Fanout on Write)              (Fanout on Read)
                            │                               │
                    Push to all                     Save only,
                    followers' feeds               merge on read
```

### 2. Feed Publishing - Fanout on Write (Regular Users)
```
User posts → Post Service → Save to DB & Cache
                 │
                 ▼
           Fanout Service → Get followers from Graph DB
                 │
                 ▼
           Message Queue → Fanout Workers → Add to each follower's feed cache
```

### 3. Feed Publishing - Fanout on Read (Celebrities)
```
Celebrity posts → Post Service → Save to DB & Cache only
                                 (No fanout - 100K+ followers!)
                 
When followers request feed:
                 │
                 ▼
           News Feed Service → Get pre-computed feed
                 │             + Merge celebrity posts
                 ▼
           Return combined feed
```

### 4. Feed Retrieval (GET /v1/me/feed)
```
Client → Load Balancer → API Service → News Feed Service
                                             │
          ┌──────────────────────────────────┼──────────────────────────────────┐
          ▼                                  ▼                                  ▼
     Feed Cache                        Post Cache                         User Cache
    (get post IDs)                    (get content)                     (get authors)
          │                                  │                                  │
          └──────────────────────────────────┼──────────────────────────────────┘
                                             ▼
                                  Merge Celebrity Posts
                                             │
                                             ▼
                                    Rank + Return Feed
```

## 🔑 Key Design Decisions

### Why Fanout on Write for regular users?
- Fast reads (feed is pre-computed)
- Simple read logic
- Good for users with reasonable follower counts

### Why Fanout on Read for celebrities?
- Avoids write amplification (no 10M writes per post)
- Efficient storage
- Worth the read-time cost for rare celebrity posts

### Why use a Message Queue?
- Decouple post creation from fanout
- Handle traffic spikes
- Retry failed fanouts
- Scale workers independently

### Cache Structure
- **News Feed Cache**: Sorted sets per user
- **Post Cache**: Two-tier (hot for viral, normal for rest)
- **User Cache**: Profile data for rendering

## 💡 Interview Tips

1. **Start with hybrid approach**: "We use fanout on write for regular users, fanout on read for celebrities"

2. **Explain the threshold**: "Typically 10K followers is the cutoff"

3. **Discuss ranking**: "Simple is chronological, complex uses ML-based prediction"

4. **Mention cache tiers**: "Hot cache for viral content, normal cache for rest"

---

## 📖 References

- Alex Xu's "System Design Interview" - Chapter 11
- Facebook Engineering Blog
- Twitter Engineering Blog
- Instagram Engineering Blog

