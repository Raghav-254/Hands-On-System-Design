# 🏆 Real-time Game Leaderboard - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 10

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Game Service** | Game logic, win events | N/A (stateless) | Publishes score events |
| **Leaderboard Service** | Rank, top-N, nearby | Redis Sorted Set | All reads go here |
| **MySQL (Point table)** | Source of truth for scores | MySQL | Durable, recoverable |
| **Redis Sorted Set** | Real-time leaderboard | Redis | O(log n) operations |
| **Kafka** | Decouple game → leaderboard | Kafka | Also feeds analytics, notifications |
| **Redis (User Profile Cache)** | Cache top-10 user details | Redis | Avoid MySQL hit for display names |
| **Load Balancer** | Distribute traffic | N/A | Routes to Web Servers |

---

## The Story: Building a Real-time Game Leaderboard

Imagine you're building a mobile game. Millions of players compete daily. Every time someone wins, their score goes up — and they want to see their rank **instantly**. Let's design a system that does this at scale.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Top 10 Leaderboard**: Display the top 10 players by score
- **Player Rank**: Show a specific user's rank among all players
- **Nearby Players** (bonus): Show 4 players above and 4 below any given user
- Score updates when a user wins a game

### Non-Functional Requirements

- **Real-time**: Score changes reflect on the leaderboard immediately
- **Scalability**: Support millions of DAU
- **Availability**: Leaderboard is always accessible
- **Reliability**: Scores must not be lost

---

## 2. Back-of-the-Envelope Estimation

| Metric | Calculation | Result |
|--------|------------|--------|
| **DAU** | Given | 5 million |
| **Avg concurrent users** | 5M / 10⁵ seconds in a day | ~50/sec |
| **Peak concurrent** | 50 × 5 (peak factor) | **250/sec** |
| **Score update QPS** | 50 users × 10 games/day | ~500 |
| **Peak score QPS** | 500 × 5 | **2,500** |
| **Top-10 fetch QPS** | ~1 per user per session → ~50 | **50** |

**Key insight**: Score updates (~2,500 peak QPS) are 50× more frequent than leaderboard reads (~50 QPS). But both must be fast.

**Storage**: 5M users × 1KB (user_id, score, metadata) ≈ **5 GB** — easily fits in a single Redis instance (typical Redis: 25-100 GB RAM).

---

## 3. API Design

### Score a Point

```
POST /v1/scores
Body: { "user_id": "u123", "points": 10 }
Response: { "status": "ok", "new_score": 250 }
```

> Called by the Game Service when a user wins a match.

### Get Top 10 Leaderboard

```
GET /v1/leaderboard?top=10
Response: {
  "leaderboard": [
    { "rank": 1, "user_id": "u456", "score": 3800, "display_name": "Alice" },
    { "rank": 2, "user_id": "u789", "score": 3650, "display_name": "Bob" },
    ...
  ]
}
```

### Get Player Rank

```
GET /v1/scores/{user_id}
Response: { "user_id": "u123", "rank": 1042, "score": 250 }
```

### Get Nearby Players

```
GET /v1/scores/{user_id}/nearby?range=4
Response: {
  "nearby": [
    { "rank": 1038, "user_id": "u200", "score": 260 },
    ...
    { "rank": 1042, "user_id": "u123", "score": 250 },  // ← the user
    ...
    { "rank": 1046, "user_id": "u300", "score": 240 }
  ]
}
```

---

## 4. The "Obvious" Approach — MySQL (and Why It Fails)

Before jumping to Redis, let's start simple. A relational database seems natural:

### Schema

```sql
CREATE TABLE leaderboard (
    user_id  BIGINT PRIMARY KEY,
    score    INT NOT NULL,
    INDEX idx_score (score)
);
```

### Top 10

```sql
SELECT * FROM leaderboard ORDER BY score DESC LIMIT 10;
```

This works fine — MySQL uses the `idx_score` index, returns top 10 quickly.

### The Problem: "What's My Rank?"

```sql
SELECT COUNT(*) + 1 AS rank
FROM leaderboard
WHERE score > (SELECT score FROM leaderboard WHERE user_id = 'u123');
```

**This is O(n) in practice**, even though there's an index on `score`. Let's understand why with a concrete example.

### Deep Dive: Why `COUNT(*) WHERE score > X` Is O(n) Even with an Index

Suppose we have 10 players and a B-Tree index on `score`:

```
B-Tree Index (sorted leaf nodes):
┌─────────────────────────────────────────────────────────────────┐
│  50 → 80 → 120 → 150 → 200 → 250 → 300 → 400 → 500 → 600    │
│  Eve  Hank  Kate  Jack   Leo  Alice  Bob  Charlie Diana  Ivy   │
└─────────────────────────────────────────────────────────────────┘
```

**Query**: "What is Alice's rank?" (Alice has score 250)

```sql
SELECT COUNT(*) + 1 FROM leaderboard WHERE score > 250;
```

**Step 1 — FIND where score > 250 starts**: The B-Tree locates "300" (Bob) in **O(log n)** — just a few hops through the tree. This is the fast part.

**Step 2 — COUNT all entries from 300 to the end**: Now MySQL must count: Bob(300), Charlie(400), Diana(500), Ivy(600) = **4 entries**. To get this number, MySQL **walks through each leaf node one-by-one**, incrementing a counter.

```
Step 1 (fast):  B-Tree lookup → jump to "300"           → O(log n)
Step 2 (slow):  300 → 400 → 500 → 600 → count = 4      → O(k) where k = 4
                 ^      ^      ^      ^
                 1      2      3      4   (must touch each entry)
```

**Why can't the B-Tree just tell us "there are 4 entries after 300"?**

Because B-Tree nodes only store **keys and pointers** — they do NOT store "how many entries exist to my right." There's no counter at each node. The only way to count is to walk and count one-by-one.

**At 5M users, this is devastating:**

```
Alice's score = 250, and 3,000,000 players have score > 250

Step 1:  B-Tree lookup → O(log 5M) ≈ 23 hops      → microseconds
Step 2:  Walk 3,000,000 index entries to count them → MILLIONS of reads
```

The total is **O(log n + k)**, where k = number of rows with score > X. In the worst case (a low-ranked player), k ≈ n, so it becomes **O(n)**.

> **Key takeaway**: The B-Tree index helps you *find* a value in O(log n), but it can't *count* how many entries are above it without scanning them one-by-one. We need a data structure where rank is a first-class operation — that's Redis Sorted Sets (covered in the next section).

### Why MySQL Doesn't Scale Here

| Operation | MySQL | At 5M users |
|-----------|-------|-------------|
| Top 10 | `ORDER BY score DESC LIMIT 10` | Fast (index scan, stop at 10) |
| Update score | `UPDATE SET score = score + 1` | Fast (PK lookup) |
| **Get rank** | `COUNT(*) WHERE score > X` | **O(log n + k) ≈ O(n) — must walk index entries to count** |
| **Nearby** | `ORDER BY score DESC LIMIT 9 OFFSET rank-4` | **Slow — needs rank first** |

> **Bottom line**: MySQL is great for storage but terrible for **real-time ranking** at millions of users. The B-Tree index helps you *find* a score in O(log n), but it can't *count* how many entries are above it without scanning. We need a data structure where rank is a first-class operation.

---

## 5. The Right Tool: Redis Sorted Sets

### What Is a Sorted Set?

A Redis Sorted Set is a collection where every member has a **score**. Members are automatically kept in sorted order by score. It's implemented internally as a **skip list + hash table**, giving us:

| Operation | Redis Command | Time Complexity |
|-----------|--------------|-----------------|
| Add/update score | `ZADD` / `ZINCRBY` | **O(log n)** |
| Get rank (descending) | `ZREVRANK` | **O(log n)** |
| Top N players | `ZREVRANGE 0 N-1` | **O(log n + N)** |
| Get score | `ZSCORE` | **O(1)** |
| Count members | `ZCARD` | **O(1)** |
| Range by rank | `ZREVRANGE start stop` | **O(log n + M)** |

> **Why O(log n)?** Skip list is like a "probabilistic balanced tree" — searching, inserting, and ranking all traverse O(log n) levels. For 25M users, log₂(25M) ≈ 25 hops. That's microseconds.

### Why Is `ZREVRANK` O(log n)? (Skip List Internals)

Remember, MySQL's B-Tree needs O(k) to *count* entries above a score because it doesn't store counts. The skip list solves this by storing **span metadata** — each node at each level knows how many level-0 elements it jumps over:

```
Skip List (10 players):

Level 2:  HEAD ────────(span:7)──────→ 300 ────(span:3)────→ TAIL
Level 1:  HEAD ──(span:4)──→ 150 ─(span:3)→ 300 ─(span:2)→ 500 ─(span:1)→ TAIL
Level 0:  50 → 80 → 120 → 150 → 200 → 250 → 300 → 400 → 500 → 600
          #1    #2    #3    #4    #5    #6    #7    #8    #9    #10
```

**What does "span" mean?** The span on an edge tells you "if you follow this pointer, you advance by this many positions at level 0." For example, HEAD→150 at Level 1 has span 4, meaning 150 is the **4th** element from the start (50, 80, 120, 150).

**Query**: "What is Alice's rank?" (Alice has score 250)

Instead of counting entries one-by-one, Redis **sums spans** of every hop it takes while traversing the skip list:

```
Step 1:  At HEAD, Level 2 → span 7 would reach 300.
         300 > 250, too far → DON'T jump. Drop to Level 1.
         (rank so far = 0)

Step 2:  At HEAD, Level 1 → span 4 would reach 150.
         150 ≤ 250 → JUMP! Land on 150.
         (rank so far = 0 + 4 = 4)     ← the "4" comes from span of HEAD→150
                                  ▲
Step 3:  At 150, Level 1 → span 3 would reach 300.
         300 > 250, too far → DON'T jump. Drop to Level 0.
         (rank so far = 4, unchanged)

Step 4:  At 150, Level 0 → next is 200 (span 1).
         200 ≤ 250 → JUMP! Land on 200.
         (rank so far = 4 + 1 = 5)     ← the "1" comes from span of 150→200
                                  ▲
Step 5:  At 200, Level 0 → next is 250 (span 1).
         250 = target → JUMP! Land on 250. FOUND!
         (rank so far = 5 + 1 = 6)     ← the "1" comes from span of 200→250
                                  ▲
```

**Result: rank = 4 + 1 + 1 = 6** (6th from left, i.e., position #6 = 250. Correct!)

**Reverse rank** (for descending leaderboard) = 10 - 6 + 1 = **5th from top**.

**The rule is simple**: every time you take a hop, add that hop's span to the running total. When you reach the target, the running total IS the rank. No counting of individual entries — just 4 hops through the skip list, summing pre-computed spans.

For 5M users, log₂(5M) ≈ 23 levels → at most ~23 hops. That's microseconds, regardless of whether the player is ranked #1 or #4,999,999.

**B-Tree vs Skip List — the fundamental difference:**

| | B-Tree (MySQL) | Skip List (Redis) |
|---|---|---|
| **Stores** | Keys + pointers | Keys + pointers + **span counts** |
| **"Find score 250"** | O(log n) | O(log n) |
| **"Count entries above 250"** | O(k) — must walk each entry | **O(log n) — sum spans** |
| **Rank query** | O(log n + k) ≈ O(n) | **O(log n)** |

### Redis Commands Mapped to Our Use Cases

**1. User wins a game → Increment score**
```
ZINCRBY leaderboard 10 "user:u123"
→ "260"                          // returns new score
```

**2. Top 10 leaderboard**
```
ZREVRANGE leaderboard 0 9 WITHSCORES
→ 1) "user:u456"  2) "3800"
   3) "user:u789"  4) "3650"
   ...
```

**3. What's my rank?**
```
ZREVRANK leaderboard "user:u123"
→ (integer) 1041                 // 0-based, so rank = 1042
```

**4. Nearby players (±4 around rank 1041)**
```
ZREVRANGE leaderboard 1037 1045 WITHSCORES
→ 9 entries: 4 above + me + 4 below
```

**Every single operation is O(log n). That's the entire point.**

---

## 6. High-Level Architecture

```
                    ┌──────────┐
                    │  Client  │
                    │ (Mobile) │
                    └────┬─────┘
                         │
               ┌─────────▼──────────┐
               │   Load Balancer    │
               └─────────┬──────────┘
                         │
               ┌─────────▼──────────┐
               │    Web Servers     │
               └──┬──────────────┬──┘
                  │              │
    ┌─────────────▼───┐   ┌─────▼──────────────┐
    │  Game Service   │──→│ Leaderboard Service │
    │ (win game, etc) │   │ (rank, top-10, etc) │
    └─────────────────┘   └──┬──────────────┬───┘
                             │              │
                      ┌──────▼──────┐  ┌────▼────────┐
                      │   Redis     │  │   MySQL      │
                      │ Sorted Set  │  │ (Point table)│
                      │ (leaderboard│  │ Source of    │
                      │  + user     │  │ truth)       │
                      │  profile    │  │              │
                      │  cache)     │  └──────────────┘
                      └─────────────┘

    (At scale: Game Service → Kafka → Leaderboard Service)
```

### Flow: Scoring a Point

```
① User wins a game
      │
② Game Service calls Leaderboard Service
      │
③ Leaderboard Service executes:
      ├── ZINCRBY leaderboard <points> <user_id>   ← Redis (real-time)
      └── INSERT INTO point_history (user_id, points, timestamp)  ← MySQL (durable)
```

### Flow: Fetching the Leaderboard

```
① Client requests top 10
      │
② Web Server → Leaderboard Service
      │
③ Leaderboard Service:
      ├── ZREVRANGE leaderboard 0 9 WITHSCORES      ← get top 10 user_ids + scores
      └── Fetch display names from Redis user cache  ← (or MySQL if cache miss)
      │
④ Return ranked list with names to client
```

### Why Kafka? (At Scale)

In the simple version, Game Service calls Leaderboard Service directly. But at scale:

```
Game Service  →  Kafka  →  Leaderboard Service
                        →  Analytics Service
                        →  Push Notification Service
```

**Why not direct calls?**
- Multiple consumers need game results (analytics, notifications, leaderboard)
- Kafka decouples producers from consumers
- Burst absorption: if 10K scores arrive in 1 second, Kafka buffers them
- Retry on failure: if Leaderboard Service is down, messages wait in Kafka

---

## 7. Data Model (MySQL — Source of Truth)

### Why do we need MySQL if Redis has the leaderboard?

Redis is **fast but volatile**. Even with persistence (AOF/RDB), Redis is optimized for speed, not durability. MySQL is the **source of truth**:

- If Redis crashes, rebuild the sorted set from MySQL
- Audit trail: who scored what, when
- Complex queries (e.g., "show my score history this month")

### Tables

**`users` table**

| Column | Type | Description |
|--------|------|-------------|
| user_id | BIGINT PK | Unique player ID |
| display_name | VARCHAR(100) | Shown on leaderboard |
| email | VARCHAR(255) | Account info |
| created_at | TIMESTAMP | Registration time |

**`point_history` table (append-only log)**

| Column | Type | Description |
|--------|------|-------------|
| id | BIGINT PK AUTO_INCREMENT | Event ID |
| user_id | BIGINT FK | Who scored |
| points | INT | Points earned in this game |
| timestamp | TIMESTAMP | When the game was won |

**`user_scores` table (materialized total)**

| Column | Type | Description |
|--------|------|-------------|
| user_id | BIGINT PK | Player |
| total_score | INT | Sum of all points |
| last_updated | TIMESTAMP | Last score change |

> `user_scores` can be derived from `point_history` via `SUM(points) GROUP BY user_id`. It exists for fast lookup and Redis rebuild.

---

## 8. Tie-breaking

### The Problem

Two players both have score 500. Who ranks higher?

Without tie-breaking, Redis returns them in **arbitrary order** (actually insertion order for same-score members). That's non-deterministic — a user might see their rank flip-flop.

### Solution: Composite Score with Timestamp

Encode a timestamp into the score so that **earlier achievers rank higher** (or vice versa):

```
composite_score = actual_score * 10^13 + (MAX_TIMESTAMP - current_timestamp)
```

**Example:**
- Alice scores 500 at timestamp 1000 → `500_0000000009000` (MAX=10000)
- Bob scores 500 at timestamp 1005   → `500_0000000008995`

Alice's composite score is higher → Alice ranks above Bob (she got there first).

### Why This Works

- The `actual_score` part dominates (multiplied by 10¹³)
- Within the same actual score, the timestamp part breaks ties
- `(MAX_TIMESTAMP - current_timestamp)` ensures **earlier = higher** (rewarding speed)
- To reverse (latest wins), just use `current_timestamp` directly

### Implementation

```
ZINCRBY leaderboard <composite_score> "user:u123"
```

When displaying to the user, extract the actual score:

```
display_score = floor(composite_score / 10^13)
```

### Alternative: Secondary Sorting

Some systems use a separate "tie-break" field (like timestamp or total games played) and sort by `(score DESC, timestamp ASC)` at the application layer. This works but is slower for rank lookups since Redis can't do multi-field sorting natively.

---

## 9. Faster Retrieval — User Profile Caching

### The Problem

`ZREVRANGE` returns user IDs and scores, but the leaderboard UI needs **display names, avatars, etc.** Fetching these from MySQL for every leaderboard request is wasteful.

### Solution: Redis Hash for User Profiles

Store frequently accessed user info in a separate Redis hash:

```
HSET user:u456 display_name "Alice" avatar_url "https://..." country "US"
HGET user:u456 display_name
→ "Alice"
```

### Full Fetch Flow for Top 10

```
Step 1:  ZREVRANGE leaderboard 0 9 WITHSCORES
         → [(u456, 3800), (u789, 3650), ...]

Step 2:  PIPELINE (batch):
           HGETALL user:u456
           HGETALL user:u789
           ...
         → [{display_name: "Alice", ...}, {display_name: "Bob", ...}, ...]

Step 3:  Merge and return
```

> **Redis pipeline**: Send multiple commands in a single round-trip. Instead of 10 separate requests, we send 1 batch → dramatically reduces latency.

### Cache Miss Strategy

If a user profile isn't in Redis → fetch from MySQL → store in Redis with TTL (e.g., 1 hour). Classic **cache-aside** pattern.

---

## 10. Scaling: From 5M to 500M DAU

At 5M DAU, a single Redis instance handles everything. But what about 500M DAU?

### Updated Numbers

| Metric | 5M DAU | 500M DAU |
|--------|--------|----------|
| Score update QPS (peak) | 2,500 | 250,000 |
| Sorted set size | 5 GB | 500 GB |
| Single Redis? | Yes | **No** — too much data + QPS |

We need to **shard** the leaderboard across multiple Redis instances.

### Option 1: Fixed Partition (by Score Range)

```
Shard 1: scores [1, 100]        ← Sorted Set
Shard 2: scores [101, 200]      ← Sorted Set
Shard 3: scores [201, 300]      ← Sorted Set
...
Shard 10: scores [901, 1000]    ← Sorted Set
```

**How "Get Rank" works:**
1. User has score 250 → belongs to Shard 3
2. Get rank within Shard 3: `ZREVRANK` → e.g., rank 42 within shard
3. Get sizes of all higher shards: Shard 10 has 5K, Shard 9 has 12K, ... Shard 4 has 20K
4. Global rank = sum of sizes of higher shards + local rank = `5K + 12K + ... + 20K + 42`

**Top 10:** Just query the highest shard (Shard 10). If it has fewer than 10 entries, spill into Shard 9.

| Pros | Cons |
|------|------|
| Top 10 is trivial (one shard) | **Uneven distribution** — most players cluster in low/mid scores |
| Rank calculation is fast (sum of shard sizes) | Need to **move users between shards** when score crosses boundary |
| Easy to understand | Rebalancing is complex |

### Option 2: Hash Partition (Redis Cluster)

```
                 ┌───────────┐
    Client  →    │   Proxy   │    Slot = CRC16(key) % 16384
                 └─────┬─────┘
                       │
          ┌────────────┼────────────┐
          ▼            ▼            ▼
    ┌──────────┐ ┌──────────┐ ┌──────────┐
    │ Shard 1  │ │ Shard 2  │ │ Shard 3  │
    │ [0,5500] │ │[5501,    │ │[11001,   │
    │          │ │  11000]  │ │  16383]  │
    │ Primary  │ │ Primary  │ │ Primary  │
    │ Replica  │ │ Replica  │ │ Replica  │
    │ Replica  │ │ Replica  │ │ Replica  │
    └──────────┘ └──────────┘ └──────────┘
            Redis Cluster
```

Each shard holds a **separate sorted set** containing a subset of all players. Users are assigned to shards via `CRC16(user_id) % 16384`.

**How "Get Rank" works:**
1. User "u123" → `CRC16("u123") % 16384` → Shard 2
2. Get rank within Shard 2: `ZREVRANK` → e.g., rank 15 within shard
3. **Problem**: we don't know how many users in other shards have higher scores
4. **Solution**: Query `ZCOUNT` (count users with score > X) on **all shards** in parallel, sum up
5. Global rank = sum of ZCOUNT results from all shards + 1

**Top 10:** Query top 10 from **each shard**, merge, take global top 10. This is a **scatter-gather** pattern.

| Pros | Cons |
|------|------|
| **Even data distribution** | Top 10 and rank need scatter-gather across all shards |
| No user migration between shards | Slightly more complex rank calculation |
| Redis Cluster handles rebalancing | Latency = slowest shard (tail latency) |
| Built-in failover (replicas) | |

### Which to Choose?

| Factor | Fixed Partition | Hash Partition |
|--------|----------------|----------------|
| Data balance | Uneven | Even |
| Top 10 speed | Fastest (single shard) | Slower (scatter-gather) |
| Rank speed | Fast (sum shard sizes) | Moderate (parallel ZCOUNT) |
| Operations | Complex (rebalancing) | Simple (Redis Cluster) |
| **Recommendation** | Works if score range is bounded & known | **Preferred for general case** |

> **For most production systems, hash partition with Redis Cluster is the recommended approach** — even distribution, built-in failover, and operationally simpler despite scatter-gather overhead.

---

## 11. Fault Tolerance

### What If Redis Goes Down?

Redis is in-memory. A crash = data loss unless we have protection:

**Layer 1: Redis Persistence**

| Mode | How It Works | Trade-off |
|------|-------------|-----------|
| **RDB** (Snapshot) | Point-in-time snapshots every N seconds | May lose last few seconds of data |
| **AOF** (Append-Only File) | Logs every write command | Slower recovery, but less data loss |
| **RDB + AOF** | Both | Best durability, recommended |

**Layer 2: Redis Replication**

Each Redis primary has **2 replicas**. If the primary crashes:
1. A replica is promoted to primary (automatic with Redis Sentinel or Redis Cluster)
2. Clients reconnect to the new primary
3. Minimal downtime (~seconds)

**Layer 3: MySQL as the Ultimate Backup**

Even if all Redis instances die:
1. Query MySQL `user_scores` table
2. Rebuild the sorted set:
   ```
   FOR each (user_id, total_score) in MySQL:
       ZADD leaderboard <total_score> <user_id>
   ```
3. Full rebuild of 25M entries ≈ minutes (ZADD is O(log n) × 25M)

### Write Path Resilience

```
Score event → MySQL (durable write) → Redis (fast update)
```

- If Redis write fails: MySQL has the data → retry later or rebuild
- If MySQL write fails: Retry the entire operation (both must succeed)
- If Kafka is used: Kafka retains messages → replay on recovery

### What About Stale Reads?

After a Redis failover, the new replica might be slightly behind. This means:
- A user might see a rank that's 1-2 seconds stale
- For a game leaderboard, this is acceptable (eventual consistency)
- For financial leaderboards, you'd need stronger consistency (not our use case)

---

## 12. Alternative: NoSQL Approach (DynamoDB / Cassandra)

### Can We Avoid Redis?

Some teams prefer a "single storage" approach using a NoSQL database. Let's explore this using DynamoDB as the example — though Cassandra would face the same issues (just with different terminology: "Clustering Key" instead of "Sort Key", same hot-partition and no-rank problems). The core limitation is shared by all NoSQL stores.

### Base Table Design

```
Table: game_scores
  PK (Partition Key): user_id       ← each user is one item
  Attributes: score, display_name, updated_at
```

**Why partition by `user_id`?** Because the most common operation on the base table is "get/update a specific user's score" — a point lookup by user_id.

**Partitioning strategy**: DynamoDB **always uses hash partitioning** — there's no range partitioning option. It computes `hash(user_id)` to decide which physical partition stores the item.

**Does this mean one physical shard per user?** No. Multiple users share the same physical partition:

```
Physical partition 1:  u1 (Alice), u7 (Grace), u12 (Leo), ...
Physical partition 2:  u2 (Bob), u4 (Diana), u9 (Ivy), ...
Physical partition 3:  u3 (Charlie), u5 (Eve), u8 (Hank), ...
```

This is great for **per-user lookups** ("What's Alice's score?") — DynamoDB hashes `user_id`, finds the partition, returns the item in O(1). But it's useless for the leaderboard — items are scattered across partitions by `user_id`, and there's no way to sort ALL users by score.

### Why Not a Local Secondary Index (LSI)?

An LSI must share the **same partition key** as the base table. Since our PK is `user_id`, an LSI would let us sort items **within a single user's partition** (e.g., sort Alice's game history by score). That's not what we want — we need to sort **all users across all partitions** by score.

```
LSI:  PK = user_id, SK = score
      → Only sorts items within ONE user_id partition
      → Useless for "top 10 across all players"
```

### Why GSI (Global Secondary Index)?

A GSI can have a **completely different partition key** from the base table. An LSI can't help us (same PK = `user_id`, only sorts within one user). A GSI lets us re-partition all users by a leaderboard-oriented key.

> **Terminology note**: DynamoDB calls it **Sort Key (SK)** — the key that determines ordering within a partition. Cassandra calls the equivalent concept a **Clustering Key**. Same idea, different names.

### The Core Problem: Sorting vs Distribution

For a leaderboard, we need all users **sorted together by score**. But in DynamoDB, items with the **same partition key (PK) value always go to the same physical partition**. This creates a dilemma:

> **Note**: In DynamoDB, "sharding" and "partitioning" mean the same thing — splitting data across multiple physical nodes. DynamoDB just calls them "partitions."

| GSI PK choice | What happens | Leaderboard? |
|---|---|---|
| `user_id` | Each user in separate partition (good distribution) | Useless — can't sort across partitions |
| `"LEADERBOARD"` (fixed) | ALL users in one partition (sorted by score) | Top-10 works, but **hot partition** — exceeds ~1,000 WCU/partition |

Neither extreme works. The fix? **Hash partitioning across N buckets.**

### The Fix: Hash Partition the GSI (N Buckets)

Simple idea: `partition_number = hash(user_id) % N`. Each user lands in one of N partitions, each sorted by score internally.

The book dresses this up as `game_name#{year-month}#p{partition_number}`, but strip away the labels and it's just:

```
hash(user_id) % 3:

u1 → 1 % 3 = 1 → Partition 1        u4 → 4 % 3 = 1 → Partition 1
u2 → 2 % 3 = 2 → Partition 2        u9 → 9 % 3 = 0 → Partition 0
u3 → 3 % 3 = 0 → Partition 0        u12→ 12% 3 = 0 → Partition 0
```

```
Partition 0 (sorted by score):       Partition 1:            Partition 2:
┌───────┬─────────┐                  ┌───────┬────────┐      ┌───────┬────────┐
│ score │ user_id │                  │ score │ user_id│      │ score │ user_id│
├───────┼─────────┤                  ├───────┼────────┤      ├───────┼────────┤
│  340  │ u9      │                  │  330  │ u1     │      │  250  │ u2     │
│  305  │ u12     │                  │  320  │ u4     │      └───────┴────────┘
│  180  │ u3      │                  └───────┴────────┘
└───────┴─────────┘
```

The book's fancy key `game_name#{year-month}#p{N}` just adds context:

| Part | Purpose | Could you skip it? |
|------|---------|-------------------|
| `game_name` | Multi-game support (chess, poker) | Yes, if single game |
| `year-month` | Monthly leaderboards (old data ages out) | Yes, if all-time leaderboard |
| `p{N}` | **The actual hash partition** = `hash(user_id) % N` | No — this is the core |

### How Reads Work: Scatter-Gather

**Top 10**: Query each partition's top 10, merge, take global top 10.

```
Partition 0 top 10  →  [340, 305, 180]
Partition 1 top 10  →  [330, 320]
Partition 2 top 10  →  [250]

Merge & sort → [340, 330, 320, 305, 250, 180]
Return top 10
```

### Remaining Problems

Even with hash partitioning, DynamoDB can't match Redis for leaderboards:

**Problem 1: Get Rank is still O(n)** — DynamoDB has no `COUNT WHERE score > X`. To find Alice's rank, scan from the top of every partition down to her score, counting entries — same O(n) problem as MySQL.

**Problem 2: Eventually consistent** — The GSI is **eventually consistent** with the base table. Score updates have a slight delay before appearing in the GSI.

**Problem 3: Scatter-gather overhead** — Every Top-10 query hits all N partitions. With N=10, that's 10 parallel queries + merge, vs Redis's single `ZREVRANGE`.

### GSI vs LSI — Summary

| | Local Secondary Index (LSI) | Global Secondary Index (GSI) |
|---|---|---|
| **Partition Key** | Must be same as base table | Can be different |
| **Scope** | Within one partition | Across all partitions |
| **Use case** | Sort a user's items differently | Sort ALL users by a new key |
| **For leaderboard?** | Useless (sorts within one user) | Works for Top-N |
| **Consistency** | Strong (same partition) | Eventually consistent |

### Is DynamoDB Better Than MySQL for Leaderboards?

**No.** Both share the same core problem (O(n) ranking), but DynamoDB actually introduces new issues:

| | MySQL | DynamoDB (GSI) |
|---|---|---|
| **Top 10** | Fast (B-Tree index) | Fast (GSI sort key) |
| **Get Rank** | O(n) — count scan | O(n) — same problem |
| **Update score** | Fast, strongly consistent | Fast, but GSI is **eventually consistent** |
| **Hot partition?** | No — scores spread across index | **Yes** — needs write sharding (`p{partition_number}`) to mitigate |
| **Consistency** | Strong (ACID) | Eventually consistent (GSI lag) |
| **Horizontal scaling** | Manual sharding (you manage shard key, routing, rebalancing) | Auto-managed partitioning (but GSI still needs manual write sharding) |

**Bottom line**: For the leaderboard specifically, MySQL is simpler and more consistent. DynamoDB's advantage (managed horizontal scaling) doesn't help when the GSI has a hot partition bottleneck anyway. Neither solves O(n) ranking — that's why Redis Sorted Set is the answer.

### When NoSQL Could Still Make Sense

| Scenario | Recommendation |
|----------|---------------|
| Only need Top 10 (no individual rank) | GSI works, hot partition manageable at low QPS |
| Already using DynamoDB for game data | Avoids adding Redis to the stack |
| Want fully managed infrastructure | DynamoDB (no Redis ops overhead) |
| Need real-time rank for all users | **Redis Sorted Set — no substitute** |

### Why Redis Sorted Set Wins

The fundamental advantage is that **rank is a first-class operation** in a sorted set — O(log n) via span metadata in the skip list. In every other data store (SQL, NoSQL), rank must be derived by counting, which is O(n). For a leaderboard where rank is the core feature, nothing beats Redis Sorted Sets.

---

## 13. Complete System Design Summary

### The Final Architecture (5M DAU)

```
┌──────────┐
│  Client  │
└────┬─────┘
     │
┌────▼──────────┐
│ Load Balancer │
└────┬──────────┘
     │
┌────▼──────────┐
│  Web Servers  │
└──┬─────────┬──┘
   │         │
┌──▼───┐  ┌──▼────────────────┐
│ Game │  │ Leaderboard       │
│ Svc  │  │ Service           │
└──┬───┘  └──┬──────────────┬─┘
   │         │              │
   │    ┌────▼────────┐  ┌──▼──────┐
   │    │ Redis       │  │ MySQL   │
   │    │ - Sorted Set│  │ - users │
   │    │   (leader-  │  │ - point │
   │    │    board)   │  │   hist  │
   │    │ - User Hash │  │ - user  │
   │    │   (profiles)│  │   scores│
   │    └─────────────┘  └─────────┘
   │
   └──→ (Kafka at scale) ──→ Analytics, Notifications
```

### The Final Architecture (500M DAU)

```
┌──────────┐
│  Client  │
└────┬─────┘
     │
┌────▼──────────┐
│ Load Balancer │
└────┬──────────┘
     │
┌────▼───────────┐
│  Web Servers   │
│  (Clustered)   │
└──┬──────────┬──┘
   │          │
┌──▼───┐   ┌──▼────────────────┐
│ Game │   │ Leaderboard       │
│ Svc  │   │ Service (Cluster) │
└──┬───┘   └──┬──────────────┬─┘
   │          │              │
   │   ┌──────▼────────┐  ┌──▼──────────┐
   │   │ Redis Cluster  │  │ MySQL       │
   │   │ (Hash Partition)│  │ (Sharded)   │
   │   │                │  │             │
   │   │ Shard1  Shard2 │  │ Source of   │
   │   │ Shard3  ShardN │  │ truth       │
   │   │                │  │             │
   │   │ Each: Primary  │  └─────────────┘
   │   │  + 2 Replicas  │
   │   └────────────────┘
   │
   └──→ Kafka ──→ Leaderboard Svc
                → Analytics Svc
                → Push Notification Svc
```

---

## 14. Key Interview Talking Points

### "Walk me through scoring a point"

> When a user wins a game, the Game Service publishes a score event to Kafka. The Leaderboard Service consumes it, executes `ZINCRBY` on the Redis Sorted Set (O(log n)), and writes to MySQL for durability. The leaderboard reflects the new score in real-time.

### "Why not just MySQL?"

> MySQL can do `ORDER BY score DESC LIMIT 10` efficiently, but ranking a specific user requires `COUNT(*) WHERE score > X`, which is O(n) — a full index scan at 5M users. Redis Sorted Set gives us `ZREVRANK` in O(log n), purpose-built for ranking.

### "How do you handle ties?"

> We encode a timestamp into the composite score: `actual_score × 10¹³ + (MAX_TS - current_ts)`. This makes the score unique and deterministic — earlier achievements rank higher among tied players. When displaying, we extract the actual score by dividing.

### "How does it scale to 500M users?"

> We shard the Redis Sorted Set using Redis Cluster (hash partition). Each shard holds a subset of users. For Top 10, we scatter-gather: query top 10 from each shard, merge client-side. For individual rank, we run `ZCOUNT` in parallel across all shards and sum. It's eventual consistency, which is acceptable for gaming.

### "What if Redis dies?"

> Three layers of protection: (1) Redis persistence (AOF+RDB) for fast recovery, (2) replicas for automatic failover, (3) MySQL as the ultimate backup — we can rebuild the entire sorted set from the `user_scores` table. Scores are never lost because MySQL is the source of truth.

### "Why Redis Sorted Set over DynamoDB / other NoSQL?"

> Rank is a **first-class operation** in Redis Sorted Sets — O(log n) via skip list. In DynamoDB/Cassandra, rank must be derived by counting, which is either O(n) or requires materialized views. For a leaderboard where rank is the core feature, Redis is purpose-built.

---

## 15. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Better Approach |
|---------|---------------|-----------------|
| Using only Redis (no MySQL) | Data loss if Redis dies | MySQL as source of truth + Redis as fast cache |
| Using MySQL for ranking | O(n) rank queries don't scale | Redis Sorted Set for real-time ranking |
| Not handling ties | Rank flipping annoys users | Composite score with timestamp |
| Single Redis instance at 500M | Memory + QPS bottleneck | Shard via Redis Cluster |
| Fetching user profiles from MySQL per leaderboard request | Adds latency | Cache user profiles in Redis Hash |
| No Kafka at scale | Game Service tightly coupled | Kafka decouples + buffers + enables analytics |
| Ignoring Redis persistence | "It's in-memory, so it's fast" | AOF + RDB + replicas for durability |
