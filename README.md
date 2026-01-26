# Hands-on System Design

> **Your one-stop resource for system design interview preparation.**

This repository contains hands-on implementations of popular system design problems. Each project includes working code, architecture diagrams, and comprehensive interview cheatsheets.

---

## 📚 Projects

| # | System | Description | Key Concepts | Cheatsheet |
|---|--------|-------------|--------------|------------|
| 1 | [Chat System](./chat_system/) | WhatsApp/Discord-like messaging | WebSocket, Kafka, Cassandra, Presence | [View](./chat_system/INTERVIEW_CHEATSHEET.md) |
| 2 | [News Feed System](./news_feed_system/) | Facebook/Twitter-like feed | Fanout, Ranking, Caching, Graph DB | [View](./news_feed_system/INTERVIEW_CHEATSHEET.md) |
| 3 | [Autocomplete System](./autocomplete_system/) | Google/Amazon search suggestions | Trie, Top-K Caching, Sharding | [View](./autocomplete_system/INTERVIEW_CHEATSHEET.md) |
| 4 | [Video Streaming System](./video_streaming_system/) | YouTube-like video platform | Pre-signed URL, DAG Pipeline, CDN, Adaptive Bitrate | [View](./video_streaming_system/INTERVIEW_CHEATSHEET.md) |
| 5 | [Google Drive System](./google_drive_system/) | Dropbox/Google Drive file sync | Block Server, Delta Sync, Deduplication, Versioning | [View](./google_drive_system/INTERVIEW_CHEATSHEET.md) |

### 📖 Database Fundamentals

| # | Topic | Description | Deep Dive |
|---|-------|-------------|-----------|
| 📚 | [Database Fundamentals](./database_fundamentals/) | Master guide bridging database theory with system design | [View Guide](./database_fundamentals/README.md) |

Covers: **Storage Internals** (B-Tree, LSM-Tree, WAL) → **Database Logic** (Indexing, MVCC, Transactions) → **Distributed Systems** (Replication, Sharding, CAP) → **Real-Time Updates** (CDC, WebSockets) → **Architectural Mapping** (When to use what)

---

## 🎯 How to Use This Repo

### For Interview Prep
1. **Read the cheatsheet first** - Get the big picture and key interview answers
2. **Run the demo** - See the concepts in action
3. **Study the code** - Understand implementation details
4. **Practice explaining** - Use the ready-made interview answers

### Study Order (Recommended)
```
1. INTERVIEW_CHEATSHEET.md  → Quick reference, diagrams, interview Q&A
2. README.md (project)      → Architecture overview, key flows
3. Demo files               → Run and see output
4. Individual classes       → Deep-dive into components
```

---

## 🔑 Common System Design Patterns

### Pattern 1: Message Queue
```
Producer → Queue (Kafka/SQS) → Consumer Groups → Multiple Destinations
```
**Used in:** Chat, Notifications, Event-driven systems, Order processing

### Pattern 2: Cache-Aside
```
Read: Check Cache → Miss? → Read DB → Update Cache
Write: Write DB → Invalidate Cache
```
**Used in:** Almost everything! User profiles, sessions, frequently accessed data

### Pattern 3: Fan-Out
```
Write: Copy to each recipient (small scale, <100)
Read: Single copy, recipients read from source (large scale, 100+)
```
**Used in:** Chat groups, News Feed, Notifications, Social media

### Pattern 4: Service Discovery
```
Services register → Discovery tracks health → Clients query for endpoints
```
**Used in:** Microservices, Load balancing, Stateful connections

### Pattern 5: Sharding
```
Data partitioned by key (user_id, order_id) across multiple DB nodes
```
**Used in:** High-scale databases, Distributed storage

### Pattern 6: Rate Limiting
```
Token Bucket / Sliding Window → Reject excess requests
```
**Used in:** API protection, DDoS prevention, Fair usage

---

## 📂 Repository Structure

```
Hands on System Design/
├── README.md                    ← You are here (main entry point)
├── .gitignore                   ← Git ignore for all projects
│
├── database_fundamentals/       ← 📚 DATABASE THEORY DEEP DIVE (NEW!)
│   ├── README.md                   Master guide overview
│   ├── 01_STORAGE_INTERNALS.md     B-Tree, LSM-Tree, WAL, Buffer Pool
│   ├── 02_DATABASE_LOGIC.md        Indexing, MVCC, Transactions
│   ├── 03_DISTRIBUTED_SYSTEMS.md   Replication, Sharding, CAP
│   ├── 04_REALTIME_UPDATES.md      CDC, WebSockets, Fan-out
│   ├── 05_ARCHITECTURAL_MAPPING.md When to use which database
│   ├── 06_SENIOR_GOTCHAS.md        Edge cases for Staff+ prep
│   └── QUICK_REFERENCE_CARD.md     1-page cheatsheet
│
├── chat_system/                 ← Chat System
│   ├── README.md
│   ├── INTERVIEW_CHEATSHEET.md
│   └── src/...
│
├── news_feed_system/            ← News Feed System
│   ├── README.md
│   ├── INTERVIEW_CHEATSHEET.md
│   └── src/...
│
├── autocomplete_system/         ← Search Autocomplete System
│   ├── README.md
│   ├── INTERVIEW_CHEATSHEET.md
│   └── src/...
│
├── video_streaming_system/      ← Video Streaming (YouTube)
│   ├── README.md
│   ├── INTERVIEW_CHEATSHEET.md
│   └── src/...
│
├── google_drive_system/         ← Google Drive / Dropbox
│   ├── README.md
│   ├── INTERVIEW_CHEATSHEET.md
│   └── src/...
│
├── (rate_limiter)/              ← Coming soon
├── (url_shortener)/             ← Coming soon
└── ...
```

---

## 🗺️ Roadmap

| System | Key Concepts | Status |
|--------|--------------|--------|
| Chat System | WebSocket, Kafka, Cassandra, Presence, Fan-out | ✅ Complete |
| News Feed System | Fanout on Write/Read, Ranking, Caching, Graph DB | ✅ Complete |
| Search Autocomplete | Trie, Top-K Caching, Sharding, Data Pipeline | ✅ Complete |
| Video Streaming | Pre-signed URL, DAG Pipeline, CDN, Adaptive Bitrate | ✅ Complete |
| Google Drive | Block Server, Delta Sync, Deduplication, Versioning | ✅ Complete |
| Rate Limiter | Token bucket, Sliding window, Redis, Distributed | 📋 Planned |
| URL Shortener | Base62 encoding, Caching, Analytics, Redirection | 📋 Planned |
| Notification System | Priority queues, Multi-channel, Rate limiting | 📋 Planned |
| Distributed Cache | Consistent hashing, Eviction, Replication | 📋 Planned |
| Web Crawler | URL frontier, Politeness, Deduplication | 📋 Planned |
| Unique ID Generator | Snowflake, UUID, Database sequences | 📋 Planned |

---

## ⭐ Tips for Success

1. **Don't just read - implement!** Running the code solidifies understanding.

2. **Practice the interview answers out loud.** Speaking helps retention.

3. **Draw diagrams while explaining.** Interviewers love visual thinkers.

4. **Know your trade-offs.** Every design decision has pros and cons.

5. **Start broad, then deep-dive.** Begin with high-level architecture, then zoom in.

6. **Ask clarifying questions.** Requirements drive the design.

---

## 📖 References

- **Alex Xu** - System Design Interview Vol 1 & 2
- **Designing Data-Intensive Applications** - Martin Kleppmann
- Engineering blogs: Discord, Slack, WhatsApp, LinkedIn, Netflix, Uber

---

*Good luck with your interviews! 🎯*
