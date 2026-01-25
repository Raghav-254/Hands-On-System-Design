# Level 3: Distributed Systems (The Scalability Layer)

> When a single database server isn't enough, you enter the world of distributed systems—where network partitions, consistency trade-offs, and careful architectural decisions become critical.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🗺️  HOW THIS LEVEL CONNECTS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LEVEL 3: DISTRIBUTED SYSTEMS ◄── YOU ARE HERE                             │
│  ════════════════════════════════════════════════════════════════════════════│
│  Scope: MULTI-NODE, scaling beyond one machine                              │
│  Focus: Replication, sharding, consistency trade-offs, failure handling    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         BUILDS ON LEVEL 1 & 2                           ││
│  │                                                                         ││
│  │  Level 1 (Storage)         Level 3 (This)                               ││
│  │  ─────────────────         ──────────────                               ││
│  │  WAL                  ───► Replication USES WAL streaming!              ││
│  │  Pages/Buffer Pool    ───► Each node has its own buffer pool            ││
│  │  B-Tree/LSM-Tree      ───► Each shard uses same storage engine          ││
│  │                                                                         ││
│  │  Level 2 (Logic)           Level 3 (This)                               ││
│  │  ───────────────           ──────────────                               ││
│  │  MVCC (local)         ───► Distributed MVCC (global timestamps)         ││
│  │  Isolation levels     ───► Distributed transactions are HARDER          ││
│  │  Indexes              ───► Global vs Local secondary indexes            ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         EXTENDS TO LEVEL 4                              ││
│  │                                                                         ││
│  │  Level 3 (This)            Level 4 (Real-time)                          ││
│  │  ──────────────            ───────────────────                          ││
│  │  Replication          ───► CDC taps into replication stream             ││
│  │  Sharding             ───► Fan-out must consider shard locations        ││
│  │  Consistency          ───► Real-time updates need causal ordering       ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  WHEN DO YOU NEED THIS?                                                     │
│  • Single node can't handle the load (scale)                               │
│  • Need high availability (replicas for failover)                          │
│  • Users are globally distributed (geo-replication)                        │
│  • Data too large for one machine (sharding)                               │
│                                                                              │
│  APPLIES TO:                                                                │
│  ✅ SQL: PostgreSQL replication, Vitess, CockroachDB, Spanner              │
│  ✅ NoSQL: Cassandra, DynamoDB, MongoDB (designed for distributed)         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents
1. [Replication Strategies](#1-replication-strategies)
2. [Sharding (Horizontal Partitioning)](#2-sharding-horizontal-partitioning)
3. [CAP Theorem and PACELC](#3-cap-theorem-and-pacelc)
4. [Consensus and Coordination](#4-consensus-and-coordination)
5. [Conflict Resolution & Anti-Entropy](#5-conflict-resolution--anti-entropy)
6. [Interview Checklist](#6-interview-checklist)

---

## 1. Replication Strategies

### Why Replicate?

```
┌─────────────────────────────────────────────────────────────────────┐
│                    REPLICATION GOALS                                 │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. HIGH AVAILABILITY                                               │
│     └── If primary fails, replica takes over                        │
│                                                                      │
│  2. READ SCALABILITY                                                │
│     └── Distribute read load across multiple servers                │
│                                                                      │
│  3. GEOGRAPHIC DISTRIBUTION                                         │
│     └── Reduce latency by placing replicas near users               │
│                                                                      │
│  4. DISASTER RECOVERY                                               │
│     └── Replicas in different data centers survive outages          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

### Synchronous vs Asynchronous Replication

#### Synchronous Replication

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SYNCHRONOUS REPLICATION                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Client         Primary           Replica 1         Replica 2        │
│    │               │                  │                │             │
│    │──── WRITE ───►│                  │                │             │
│    │               │                  │                │             │
│    │               │── Replicate ────►│                │             │
│    │               │                  │                │             │
│    │               │── Replicate ─────────────────────►│             │
│    │               │                  │                │             │
│    │               │◄──── ACK ────────│                │             │
│    │               │                  │                │             │
│    │               │◄──── ACK ─────────────────────────│             │
│    │               │                  │                │             │
│    │◄──── OK ──────│  (only after    │                │             │
│    │               │   ALL acks!)    │                │             │
│                                                                      │
│  PROPERTIES:                                                         │
│  ✅ Strong consistency (all replicas have same data)                 │
│  ✅ No data loss on failover                                         │
│  ❌ Higher write latency (wait for slowest replica)                  │
│  ❌ Reduced availability (if replica down, writes blocked)           │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Asynchronous Replication

```
┌─────────────────────────────────────────────────────────────────────┐
│                   ASYNCHRONOUS REPLICATION                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Client         Primary           Replica 1         Replica 2        │
│    │               │                  │                │             │
│    │──── WRITE ───►│                  │                │             │
│    │               │                  │                │             │
│    │◄──── OK ──────│  (immediate!)    │                │             │
│    │               │                  │                │             │
│    │               │── Replicate ────►│                │             │
│    │               │        (background, best-effort)  │             │
│    │               │── Replicate ─────────────────────►│             │
│    │               │                  │                │             │
│                                                                      │
│  PROPERTIES:                                                         │
│  ✅ Low write latency (don't wait for replicas)                      │
│  ✅ High availability (replica failure doesn't block writes)         │
│  ❌ Potential data loss on failover (uncommitted writes on primary)  │
│  ❌ Replication lag (replicas may be seconds/minutes behind)         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Semi-Synchronous Replication

```
┌─────────────────────────────────────────────────────────────────────┐
│                  SEMI-SYNCHRONOUS REPLICATION                        │
│                    (Quorum-based approach)                           │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  CONFIGURATION: 3 replicas, require ACK from 2 (majority)            │
│                                                                      │
│  Client         Primary           Replica 1         Replica 2        │
│    │               │                  │                │             │
│    │──── WRITE ───►│                  │                │             │
│    │               │                  │                │             │
│    │               │── Replicate ────►│                │             │
│    │               │                  │                │             │
│    │               │── Replicate ─────────────────────►│             │
│    │               │                  │                │             │
│    │               │◄──── ACK ────────│                │             │
│    │               │                  │  (Replica 2    │             │
│    │◄──── OK ──────│  (1 ACK +        │   may be slow) │             │
│    │               │   primary = 2!)  │                │             │
│    │               │                  │                │             │
│    │               │◄──── ACK ─────────────────────────│             │
│    │               │       (arrives later, logged)     │             │
│                                                                      │
│  PROPERTIES:                                                         │
│  ✅ Balance of consistency and latency                               │
│  ✅ Tolerates minority failures                                      │
│  ⚠️  Quorum must be configured correctly                             │
│                                                                      │
│  QUORUM FORMULA:                                                     │
│  Write Quorum (W) + Read Quorum (R) > N (total replicas)             │
│  For strict consistency: W + R > N                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Replication Topologies

```
SINGLE-LEADER (Primary-Replica)
┌──────────┐         ┌──────────┐
│ PRIMARY  │────────►│ REPLICA  │
│ (writes) │         │ (reads)  │
└──────────┘         └──────────┘
      │              ┌──────────┐
      └─────────────►│ REPLICA  │
                     │ (reads)  │
                     └──────────┘

✅ Simple consistency model
✅ No write conflicts
❌ Single point of failure for writes
❌ All writes through one node (bottleneck)


MULTI-LEADER (Active-Active)
┌──────────┐         ┌──────────┐
│ LEADER 1 │◄───────►│ LEADER 2 │
│  (DC-A)  │         │  (DC-B)  │
└──────────┘         └──────────┘

✅ Low latency writes in each DC
✅ Writes continue if one DC fails
❌ CONFLICT RESOLUTION REQUIRED
❌ Complex (last-write-wins, merge, custom logic)


LEADERLESS (Dynamo-style)
    ┌──────────┐
    │  Node A  │
    └──────────┘
        ▲  ▲
       /    \
      /      \
┌────────┐  ┌────────┐
│ Node B │──│ Node C │
└────────┘  └────────┘

✅ No single point of failure
✅ Any node can accept writes
❌ Requires quorum reads/writes
❌ Conflict resolution via vector clocks
```

### Handling Replication Lag

```
SCENARIO: User updates profile, then immediately views it

┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  User ──── UPDATE ───► Primary ──── replicate ───► Replica          │
│    │                                                 (delayed)       │
│    │                                                    │            │
│    └──── READ (load balanced to replica) ───────────────┘            │
│                                                                      │
│  RESULT: User sees OLD data! 😱                                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

SOLUTIONS:

1. READ YOUR WRITES (Read-after-write consistency)
   • After a write, read from primary for X seconds
   • Or track "last write timestamp" and route accordingly

2. MONOTONIC READS
   • User always reads from same replica
   • Prevents "going back in time"

3. CAUSAL CONSISTENCY  
   • Track happens-before relationships
   • Ensure causally-related writes seen in order

4. STICKY SESSIONS
   • Route user to same replica consistently
   • Simple but reduces load balancing flexibility
```

---

## 2. Sharding (Horizontal Partitioning)

### Why Shard?

```
SINGLE DATABASE LIMITS:
• Storage: Disk is finite (~10-100 TB practical limit)
• Write throughput: Single node can only handle so many IOPS
• RAM: Buffer pool limited to one machine's memory
• CPU: Query processing bound by one machine

SHARDING SOLUTION:
• Distribute data across multiple independent databases
• Each shard handles a subset of the data
• Scale horizontally by adding more shards
```

### Sharding Strategies

#### Range-Based Sharding

```
┌─────────────────────────────────────────────────────────────────────┐
│                    RANGE-BASED SHARDING                              │
│              (Partition by value ranges)                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Shard Key: user_id                                                  │
│                                                                      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                │
│  │   SHARD 1    │ │   SHARD 2    │ │   SHARD 3    │                │
│  │  user_id     │ │  user_id     │ │  user_id     │                │
│  │  1 - 1M      │ │  1M - 2M     │ │  2M - 3M     │                │
│  └──────────────┘ └──────────────┘ └──────────────┘                │
│                                                                      │
│  ✅ Efficient range queries (scan single shard)                      │
│  ✅ Easy to understand and debug                                     │
│  ❌ HOTSPOT RISK: New users all go to latest shard                   │
│  ❌ Uneven distribution over time                                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Hash-Based Sharding

```
┌─────────────────────────────────────────────────────────────────────┐
│                    HASH-BASED SHARDING                               │
│              (Partition by hash of key)                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  shard_id = hash(user_id) % num_shards                               │
│                                                                      │
│  Example: user_id = 12345                                            │
│           hash(12345) = 8472391                                      │
│           8472391 % 3 = 1  → Goes to Shard 1                         │
│                                                                      │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐                │
│  │   SHARD 0    │ │   SHARD 1    │ │   SHARD 2    │                │
│  │  user_ids:   │ │  user_ids:   │ │  user_ids:   │                │
│  │  3,6,9,12... │ │  1,4,7,10... │ │  2,5,8,11... │                │
│  └──────────────┘ └──────────────┘ └──────────────┘                │
│                                                                      │
│  ✅ Even distribution (no hotspots)                                  │
│  ✅ Works with any key type                                          │
│  ❌ Range queries require scatter-gather                             │
│  ❌ Adding shards requires rehashing (unless consistent hashing)     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### Consistent Hashing

```
┌─────────────────────────────────────────────────────────────────────┐
│                    CONSISTENT HASHING                                │
│           (Minimize reshuffling when adding/removing nodes)          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│                    Hash Ring (0 to 2^32-1)                           │
│                                                                      │
│                         0/MAX                                        │
│                          │                                           │
│                    ┌─────┴─────┐                                    │
│                   /             \                                    │
│                  /               \                                   │
│           Shard A                 Shard B                            │
│            (2^30)                  (2^31)                            │
│                  \               /                                   │
│                   \             /                                    │
│                    └─────┬─────┘                                    │
│                          │                                           │
│                       Shard C                                        │
│                      (3*2^30)                                        │
│                                                                      │
│  Key placement: hash(key), find next shard clockwise                │
│                                                                      │
│  ADDING A NEW SHARD:                                                │
│  • Only keys between new shard and predecessor move                 │
│  • ~1/N of data migrates (not all data like simple hash mod)        │
│                                                                      │
│  VIRTUAL NODES:                                                      │
│  • Each physical shard has multiple positions on ring               │
│  • Improves load balancing                                          │
│  • Handles heterogeneous hardware                                   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Choosing a Shard Key

```
GOOD SHARD KEY PROPERTIES:

1. HIGH CARDINALITY
   ✅ user_id (millions of values)
   ❌ country (only ~200 values)

2. EVEN DISTRIBUTION  
   ✅ user_id (users distributed evenly)
   ❌ celebrity_id (some have millions of followers)

3. QUERY ISOLATION
   ✅ user_id for user data (queries usually filter by user)
   ❌ status (queries rarely filter by status alone)

4. WRITE DISTRIBUTION
   ✅ user_id (writes spread across users)
   ❌ created_date (all new writes to "today" shard)
```

### The Hotspot Problem

```
SCENARIO: Social media with celebrity users

Shard Key: user_id
Celebrity Taylor Swift: user_id = 12345

┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  PROBLEM:                                                            │
│                                                                      │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐                       │
│  │  Shard 0   │ │  Shard 1   │ │  Shard 2   │                       │
│  │   10 QPS   │ │  10 QPS    │ │ 10,000 QPS │ ← Taylor's shard!    │
│  │            │ │            │ │  🔥🔥🔥    │                       │
│  └────────────┘ └────────────┘ └────────────┘                       │
│                                                                      │
│  SOLUTIONS:                                                          │
│                                                                      │
│  1. SHARD BY CONTENT, NOT USER                                       │
│     • Shard posts by post_id                                         │
│     • Taylor's posts spread across shards                            │
│     • But: Fetching "all Taylor's posts" is scatter-gather           │
│                                                                      │
│  2. COMPOUND SHARD KEY                                               │
│     • Shard by (user_id, timestamp)                                  │
│     • Taylor's posts spread by time                                  │
│     • Range queries by time still work                               │
│                                                                      │
│  3. APPLICATION-LEVEL CACHING                                        │
│     • Cache hot users in Redis                                       │
│     • Only cold reads hit the database                               │
│                                                                      │
│  4. READ REPLICAS FOR HOT SHARDS                                     │
│     • More replicas for shard 2                                      │
│     • Doesn't help write hotspots                                    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Cross-Shard Operations

```
PROBLEM: JOINs and transactions across shards are expensive

QUERY: SELECT * FROM orders o 
       JOIN users u ON o.user_id = u.id 
       WHERE o.status = 'pending'

If orders and users are sharded by user_id:
✅ Join within same shard (co-located data)

If orders sharded by order_id, users by user_id:
❌ Every order needs a cross-shard lookup for user data!

SOLUTIONS:

1. CO-LOCATE RELATED DATA
   • Shard orders AND users by user_id
   • Trade-off: Some queries become scatter-gather

2. DENORMALIZATION
   • Store user name in orders table
   • Trade-off: Data duplication, update complexity

3. APPLICATION-LEVEL JOINS
   • Fetch orders, then batch fetch users
   • Trade-off: More round trips, application complexity

4. AVOID CROSS-SHARD TRANSACTIONS
   • Saga pattern for distributed transactions
   • Eventually consistent where possible
```

### SQL Sharding: Add-On vs Native

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SQL SHARDING OPTIONS                                      │
│              (Referenced from Level 2: Database Logic)                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DESIGN PHILOSOPHY:                                                          │
│  • Traditional SQL (PostgreSQL, MySQL): Single-node by default              │
│  • NoSQL (Cassandra, DynamoDB): Distributed by default                      │
│  • BUT: SQL CAN be sharded! It's just not the default.                      │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  OPTION 1: APPLICATION-LEVEL SHARDING                                       │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  Your application code decides which database to query:                     │
│                                                                              │
│    def get_user(user_id):                                                   │
│        shard_id = user_id % NUM_SHARDS                                      │
│        db = connections[f"shard_{shard_id}"]                                │
│        return db.query("SELECT * FROM users WHERE id = ?", user_id)         │
│                                                                              │
│  ✅ Pros: Full control, no middleware                                       │
│  ❌ Cons: Cross-shard queries are YOUR problem, resharding is painful       │
│                                                                              │
│  Used by: Instagram, Pinterest (early days)                                 │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  OPTION 2: MIDDLEWARE / PROXY SHARDING                                      │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│       ┌─────────────────────────────┐                                       │
│       │    Sharding Middleware      │                                       │
│       │  (Vitess, Citus, ProxySQL)  │                                       │
│       └─────────────────────────────┘                                       │
│             /        |        \                                              │
│            ↓         ↓         ↓                                             │
│       ┌────────┐ ┌────────┐ ┌────────┐                                      │
│       │ MySQL  │ │ MySQL  │ │ MySQL  │                                      │
│       │ Shard1 │ │ Shard2 │ │ Shard3 │                                      │
│       └────────┘ └────────┘ └────────┘                                      │
│                                                                              │
│  Tools:                                                                      │
│  ┌───────────────┬───────────────────────────────────────────────────────┐  │
│  │ Vitess        │ MySQL sharding (powers YouTube, Slack, GitHub)        │  │
│  │ Citus         │ PostgreSQL extension for horizontal scaling           │  │
│  │ ProxySQL      │ MySQL query routing and load balancing                │  │
│  │ PgBouncer     │ PostgreSQL connection pooling (not sharding)          │  │
│  └───────────────┴───────────────────────────────────────────────────────┘  │
│                                                                              │
│  ✅ Pros: App doesn't know about shards, some cross-shard support          │
│  ❌ Cons: Added complexity, potential single point of failure              │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  OPTION 3: NEWSQL (Native Distributed SQL)                                  │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  SQL databases DESIGNED for distribution from the start:                    │
│                                                                              │
│  ┌───────────────┬───────────────────────────────────────────────────────┐  │
│  │ CockroachDB   │ PostgreSQL-compatible, automatic sharding, ACID       │  │
│  │ Google Spanner│ Globally distributed, TrueTime, used by Google        │  │
│  │ YugabyteDB    │ PostgreSQL & Cassandra compatible                     │  │
│  │ TiDB          │ MySQL-compatible, from PingCAP                        │  │
│  │ PlanetScale   │ Serverless MySQL (built on Vitess)                    │  │
│  └───────────────┴───────────────────────────────────────────────────────┘  │
│                                                                              │
│  ✅ Pros: Full SQL, automatic sharding, distributed ACID transactions      │
│  ❌ Cons: Higher latency than single-node, operational complexity          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### SQL vs NoSQL Sharding Comparison

| Aspect | Traditional SQL + Sharding | NoSQL (Cassandra) | NewSQL (CockroachDB) |
|--------|---------------------------|-------------------|---------------------|
| **Sharding** | Add-on (manual/middleware) | Native (built-in) | Native (built-in) |
| **Cross-shard JOINs** | Hard (scatter-gather) | Not supported | Supported |
| **Cross-shard TXNs** | Complex (2PC needed) | Limited | Full ACID |
| **Query flexibility** | Full SQL | Limited (CQL) | Full SQL |
| **Operational complexity** | High (you manage) | Medium | Medium |
| **When to use** | Existing SQL apps | Write-heavy, simple queries | Need SQL + scale |

---

## 3. CAP Theorem and PACELC

### CAP Theorem

```
┌─────────────────────────────────────────────────────────────────────┐
│                       CAP THEOREM                                    │
│    "In a distributed system, during a network partition,            │
│     you can only guarantee either Consistency or Availability"       │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│                        ┌───────────────┐                            │
│                        │ CONSISTENCY   │                            │
│                        │ (All nodes    │                            │
│                        │  see same     │                            │
│                        │  data)        │                            │
│                        └───────────────┘                            │
│                             /\                                       │
│                            /  \                                      │
│                           /    \                                     │
│                          /      \                                    │
│     ┌───────────────┐   /        \   ┌───────────────┐              │
│     │ AVAILABILITY  │◄─┘          └─►│ PARTITION     │              │
│     │ (Every request│                │ TOLERANCE     │              │
│     │  gets a       │                │ (System works │              │
│     │  response)    │                │  despite      │              │
│     │               │                │  network      │              │
│     └───────────────┘                │  splits)      │              │
│                                      └───────────────┘              │
│                                                                      │
│  YOU MUST HAVE P:                                                   │
│  Network partitions WILL happen. The real choice is C vs A.         │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### During a Partition: CP vs AP

```
SCENARIO: Network partition between DC-East and DC-West

┌─────────────┐         PARTITION         ┌─────────────┐
│   DC-East   │    ═══════╳═══════════    │   DC-West   │
│   Primary   │                           │   Replica   │
└─────────────┘                           └─────────────┘


CP CHOICE (Consistency over Availability):
┌─────────────────────────────────────────────────────────────────────┐
│  • DC-West becomes read-only or unavailable                         │
│  • Writes only accepted by DC-East (primary)                        │
│  • Users in DC-West see errors or stale data                        │
│  • When partition heals, data is consistent                         │
│                                                                      │
│  EXAMPLES: Traditional RDBMS, ZooKeeper, etcd                       │
│  USE WHEN: Financial transactions, inventory, anything where        │
│            wrong data is worse than no data                         │
└─────────────────────────────────────────────────────────────────────┘


AP CHOICE (Availability over Consistency):
┌─────────────────────────────────────────────────────────────────────┐
│  • Both DCs continue accepting reads AND writes                     │
│  • Users in both locations can work normally                        │
│  • Data diverges during partition                                   │
│  • When partition heals, CONFLICT RESOLUTION needed                 │
│                                                                      │
│  EXAMPLES: Cassandra, DynamoDB, CouchDB                             │
│  USE WHEN: Shopping carts, social media, anything where             │
│            availability is more important than perfect consistency  │
└─────────────────────────────────────────────────────────────────────┘
```

### PACELC: The Complete Picture

```
CAP only talks about partition scenarios. 
PACELC asks: What about normal operation?

┌─────────────────────────────────────────────────────────────────────┐
│                         PACELC                                       │
│                                                                      │
│     IF                           ELSE                                │
│   Partition                  (normal operation)                      │
│      │                             │                                 │
│      ▼                             ▼                                 │
│   ┌─────┐                      ┌─────┐                              │
│   │ A   │  or                  │ L   │  or                          │
│   │     │                      │     │                              │
│   │ C   │                      │ C   │                              │
│   └─────┘                      └─────┘                              │
│                                                                      │
│   P: Partition                  E: Else (normal)                    │
│   A: Availability               L: Latency                          │
│   C: Consistency                C: Consistency                      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

PACELC CLASSIFICATIONS:

PA/EL: High availability, low latency, eventual consistency
       Examples: DynamoDB, Cassandra
       "Be fast and available, consistency can wait"

PC/EC: Strong consistency always, sacrifice latency
       Examples: RDBMS with sync replication
       "Consistency is king, we'll wait for it"

PA/EC: Available during partition, but consistent normally
       Examples: MongoDB (default config)
       "Be consistent when we can, available when we must"

PC/EL: Consistent during partition, but fast normally
       Examples: PAXOS-based systems with local reads
       "Fast reads, but partition = unavailability"
```

### Database CAP/PACELC Classifications

| Database | CAP | PACELC | Trade-off Notes |
|----------|-----|--------|-----------------|
| **PostgreSQL** (single) | CA* | PC/EC | *No partition tolerance in single node |
| **PostgreSQL** (sync rep) | CP | PC/EC | Replica failure blocks writes |
| **MySQL** (async rep) | AP | PA/EL | Can lose committed writes |
| **Cassandra** | AP | PA/EL | Tunable consistency per query |
| **MongoDB** | CP | PC/EC | Default; can tune for AP |
| **DynamoDB** | AP | PA/EL | Eventual consistency default |
| **CockroachDB** | CP | PC/EC | Serializable everywhere |
| **Redis** (cluster) | AP | PA/EL | Async replication |

---

## 4. Consensus and Coordination

### Why Consensus Matters

```
SCENARIO: Primary database fails, need to elect new primary

Without consensus:
┌─────────────────────────────────────────────────────────────────────┐
│  Replica A: "I should be primary!"                                  │
│  Replica B: "No, I should be primary!"                              │
│  Replica C: "I have the most recent data!"                          │
│                                                                      │
│  RESULT: Split brain 🧠💥🧠                                         │
│  Multiple nodes think they're primary                               │
│  Clients write to different "primaries"                             │
│  Data divergence and corruption!                                    │
└─────────────────────────────────────────────────────────────────────┘

With consensus (Raft/Paxos):
┌─────────────────────────────────────────────────────────────────────┐
│  Nodes vote, majority agrees on ONE leader                          │
│  All writes go through single agreed leader                         │
│  No split brain possible                                            │
└─────────────────────────────────────────────────────────────────────┘
```

### Raft Consensus (Simplified)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    RAFT CONSENSUS                                    │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  THREE ROLES:                                                        │
│  • Leader: Handles all writes, sends heartbeats                      │
│  • Follower: Replicates from leader, votes in elections              │
│  • Candidate: Requesting votes to become leader                      │
│                                                                      │
│  LEADER ELECTION:                                                    │
│  1. Leader stops sending heartbeats (crashed or partitioned)         │
│  2. Followers timeout, become candidates                             │
│  3. Candidates request votes from all nodes                          │
│  4. Candidate with majority votes becomes new leader                 │
│  5. New leader starts sending heartbeats                             │
│                                                                      │
│  LOG REPLICATION:                                                    │
│  1. Client sends write to leader                                     │
│  2. Leader appends to its log, sends to followers                    │
│  3. Followers append to their logs, send ACK                         │
│  4. Once majority ACKs, leader commits                               │
│  5. Leader notifies followers to commit                              │
│  6. Client gets success response                                     │
│                                                                      │
│  SAFETY GUARANTEES:                                                  │
│  • At most one leader per term                                       │
│  • Leader's log is always "most complete"                            │
│  • Committed entries are never lost                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Consensus Use Cases

```
1. LEADER ELECTION
   • Database primary failover
   • Kafka partition leader
   • Distributed lock services

2. CONFIGURATION MANAGEMENT  
   • Cluster membership (who's in/out)
   • Schema changes
   • Feature flags

3. DISTRIBUTED LOCKS
   • Exactly-once processing
   • Resource allocation
   • Fencing tokens

4. ATOMIC BROADCAST
   • Total ordering of messages
   • Replicated state machines
   • Distributed transactions (2PC/3PC)
```

---

## 5. Conflict Resolution & Anti-Entropy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    📍 CONNECTION TO LEVEL 2 (MVCC)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  In Level 2, we covered MVCC for SINGLE-NODE concurrency:                   │
│  • Transaction IDs (xmin/xmax) to track row versions                        │
│  • Visibility rules for snapshot isolation                                  │
│  • Readers never block writers on ONE server                                │
│                                                                              │
│  NOW WE EXTEND TO DISTRIBUTED:                                               │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Single-Node MVCC              Distributed Challenge                        │
│  ────────────────              ────────────────────                         │
│  Local transaction ID    →     Need GLOBAL timestamp ordering               │
│  One copy of data        →     Multiple copies, may diverge                 │
│  Visibility = "committed?"→    Visibility = "committed on which nodes?"     │
│  No conflicts (one writer)→    Conflicts when multiple nodes write          │
│                                                                              │
│  DISTRIBUTED MVCC SOLUTIONS:                                                │
│  • Hybrid Logical Clocks (HLC) - CockroachDB, YugabyteDB                   │
│  • TrueTime (GPS + atomic clocks) - Google Spanner                         │
│  • Vector Clocks - Riak, Dynamo                                             │
│  • Conflict Resolution - LWW, CRDTs, application logic                     │
│                                                                              │
│  👉 This section covers what happens when versions CONFLICT across nodes.   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

> When multiple nodes can accept writes, conflicts are inevitable. How do we detect and resolve them?

---

### The Conflict Problem

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WRITE CONFLICT SCENARIO                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Multi-leader or leaderless system (e.g., Cassandra, DynamoDB)              │
│                                                                              │
│  TIME    NODE A (US)              NODE B (EU)                                │
│  ─────────────────────────────────────────────────────────────────────────── │
│  T1      User sets name="Alice"                                              │
│  T2                               User sets name="Alicia"                    │
│  T3      (network partition - nodes can't sync)                              │
│  T4      (partition heals - sync happens)                                    │
│                                                                              │
│  QUESTION: What is the user's name now? "Alice" or "Alicia"?                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Conflict Resolution Strategies

#### 1. Last-Write-Wins (LWW)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LAST-WRITE-WINS (LWW)                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  RULE: The write with the highest timestamp wins.                           │
│                                                                              │
│  Node A: name="Alice",  timestamp=1000                                       │
│  Node B: name="Alicia", timestamp=1002  ← HIGHER, THIS WINS!                │
│                                                                              │
│  RESULT: name="Alicia"                                                       │
│                                                                              │
│  ✅ PROS:                                                                    │
│  • Simple to implement                                                       │
│  • No conflict resolution logic needed                                       │
│  • Deterministic (all nodes reach same result)                               │
│                                                                              │
│  ❌ CONS:                                                                    │
│  • DATA LOSS! The "Alice" write is silently discarded                       │
│  • Clock synchronization issues (what if Node A's clock is ahead?)          │
│  • Concurrent writes = random winner (depends on clock)                     │
│                                                                              │
│  USED BY: Cassandra (default), DynamoDB                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 2. Vector Clocks (Detect Conflicts, Don't Auto-Resolve)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    VECTOR CLOCKS                                             │
│         "Track causality, detect concurrent writes"                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  CONCEPT: Each node maintains a counter. Vector = [NodeA:count, NodeB:count]│
│                                                                              │
│  EXAMPLE:                                                                    │
│                                                                              │
│  STEP 1: Initial write on Node A                                            │
│          name="Alice", vector=[A:1, B:0]                                     │
│                                                                              │
│  STEP 2: Sync to Node B                                                      │
│          Node B receives: name="Alice", vector=[A:1, B:0]                    │
│                                                                              │
│  STEP 3: Node B updates (knows about A:1)                                   │
│          name="Alice B.", vector=[A:1, B:1]                                  │
│                                                                              │
│  STEP 4: Meanwhile, Node A also updates (doesn't know B:1)                  │
│          name="Alice A.", vector=[A:2, B:0]                                  │
│                                                                              │
│  STEP 5: Sync - CONFLICT DETECTED!                                          │
│          Version 1: [A:1, B:1] - has B:1 but only A:1                       │
│          Version 2: [A:2, B:0] - has A:2 but no B                           │
│          Neither "happens before" the other → CONCURRENT!                   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ COMPARISON RULES:                                                       ││
│  │                                                                         ││
│  │ [A:1, B:1] vs [A:2, B:0]                                                ││
│  │                                                                         ││
│  │ Is [A:1,B:1] ≤ [A:2,B:0]? NO (B:1 > B:0)                                ││
│  │ Is [A:2,B:0] ≤ [A:1,B:1]? NO (A:2 > A:1)                                ││
│  │                                                                         ││
│  │ Neither dominates → CONCURRENT WRITES → CONFLICT!                       ││
│  │                                                                         ││
│  │ If [A:1,B:0] vs [A:2,B:1]:                                              ││
│  │ Is [A:1,B:0] ≤ [A:2,B:1]? YES (1≤2, 0≤1) → [A:2,B:1] is NEWER          ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ON CONFLICT: Return BOTH versions to client, let them resolve!            │
│               (Amazon's shopping cart: merge items from both)               │
│                                                                              │
│  USED BY: Riak, Amazon Dynamo (original paper)                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### 3. CRDTs (Conflict-Free Replicated Data Types)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CRDTs                                                     │
│         "Design data structures that auto-merge without conflicts"          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  IDEA: Use data structures where ALL merge orders give SAME result          │
│                                                                              │
│  EXAMPLE: G-Counter (Grow-only Counter)                                     │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Each node keeps its own counter:                                           │
│                                                                              │
│  Node A: {A: 5, B: 0, C: 0}  → A incremented 5 times                        │
│  Node B: {A: 0, B: 3, C: 0}  → B incremented 3 times                        │
│  Node C: {A: 0, B: 0, C: 2}  → C incremented 2 times                        │
│                                                                              │
│  MERGE: Take MAX of each component                                          │
│  Result: {A: 5, B: 3, C: 2}  → Total = 5 + 3 + 2 = 10                       │
│                                                                              │
│  No matter what order nodes sync, result is always 10!                      │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│  EXAMPLE: LWW-Register (Last-Writer-Wins Register)                          │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Each value has timestamp:                                                  │
│  Node A: {value: "Alice", ts: 1000}                                         │
│  Node B: {value: "Bob", ts: 1005}                                           │
│                                                                              │
│  MERGE: Keep value with highest timestamp                                   │
│  Result: {value: "Bob", ts: 1005}                                           │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│  EXAMPLE: OR-Set (Observed-Remove Set)                                      │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Add wins over concurrent remove (for shopping carts):                      │
│  Node A: ADD "item1"                                                        │
│  Node B: REMOVE "item1" (concurrent)                                        │
│                                                                              │
│  MERGE: Item stays! (Add wins, better UX for e-commerce)                    │
│                                                                              │
│  USED BY: Redis CRDT, Riak, Automerge, Yjs (collaborative editing)         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Merkle Trees (Anti-Entropy / Consistency Verification)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    MERKLE TREES                                              │
│         "Efficiently detect which data is out of sync"                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM: Node A has 1 billion keys, Node B has 1 billion keys.             │
│           How do we find which keys are different WITHOUT comparing all?    │
│                                                                              │
│  SOLUTION: Hash tree (Merkle tree)                                          │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         ROOT HASH                                        ││
│  │                        H(H1 + H2)                                        ││
│  │                      = "abc123..."                                       ││
│  │                       /         \                                        ││
│  │                      /           \                                       ││
│  │               ┌─────┐             ┌─────┐                                ││
│  │               │ H1  │             │ H2  │                                ││
│  │               │H(A+B)             │H(C+D)                                ││
│  │               └─────┘             └─────┘                                ││
│  │               /     \             /     \                                ││
│  │              /       \           /       \                               ││
│  │          ┌─────┐ ┌─────┐   ┌─────┐ ┌─────┐                              ││
│  │          │ H(A)│ │ H(B)│   │ H(C)│ │ H(D)│                              ││
│  │          └─────┘ └─────┘   └─────┘ └─────┘                              ││
│  │             │       │         │       │                                  ││
│  │          ┌─────┐ ┌─────┐   ┌─────┐ ┌─────┐                              ││
│  │          │Key A│ │Key B│   │Key C│ │Key D│                              ││
│  │          │=100 │ │=200 │   │=300 │ │=400 │                              ││
│  │          └─────┘ └─────┘   └─────┘ └─────┘                              ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  SYNC PROCESS:                                                               │
│                                                                              │
│  1. Node A sends root hash to Node B                                        │
│  2. Node B compares: Same? → In sync! Different? → Drill down               │
│  3. Compare H1 vs H1': Same? → Left subtree in sync                         │
│                        Different? → Check H(A), H(B)                         │
│  4. Find exactly which keys differ: O(log N) comparisons!                   │
│                                                                              │
│  EXAMPLE:                                                                    │
│  ───────────────────────────────────────────────────────────────────────────│
│  Node A: 1 billion keys                                                      │
│  Node B: 1 billion keys, but Key C is different                             │
│                                                                              │
│  Without Merkle: Compare 1 billion keys = 1 billion operations              │
│  With Merkle:    Compare ~30 hashes (log₂ 1 billion ≈ 30)                   │
│                                                                              │
│  USED BY: Cassandra (anti-entropy repair), Bitcoin, Git, IPFS               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Read Repair & Anti-Entropy

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    KEEPING REPLICAS IN SYNC                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. READ REPAIR (On-demand, during reads)                                   │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Client reads key "user:100" from 3 replicas (quorum read):                │
│                                                                              │
│  Replica A: {name: "Alice", version: 5}                                     │
│  Replica B: {name: "Alice", version: 5}                                     │
│  Replica C: {name: "Al", version: 3}     ← STALE!                           │
│                                                                              │
│  Coordinator notices version mismatch:                                       │
│  → Return version 5 to client                                               │
│  → Asynchronously UPDATE Replica C with version 5                           │
│                                                                              │
│  2. ANTI-ENTROPY (Background, periodic)                                     │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  Background process compares Merkle trees between replicas:                 │
│                                                                              │
│  Every 10 minutes:                                                          │
│  1. Node A and Node B exchange Merkle root hashes                           │
│  2. If different, drill down to find divergent keys                         │
│  3. Exchange and reconcile those specific keys                              │
│                                                                              │
│  WHY BOTH?                                                                   │
│  • Read repair: Fixes hot (frequently read) data quickly                    │
│  • Anti-entropy: Fixes cold (rarely read) data eventually                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Conflict Resolution Summary

| Strategy | Data Loss? | Complexity | Best For |
|----------|------------|------------|----------|
| **Last-Write-Wins** | Yes (silent) | Low | Simple cases, logs |
| **Vector Clocks** | No (client resolves) | Medium | Shopping carts, docs |
| **CRDTs** | No (auto-merge) | High | Counters, sets, collab editing |
| **Application Logic** | No (custom) | High | Domain-specific rules |

---

## 6. Interview Checklist

### Questions You Should Be Able to Answer

#### Replication
- [ ] "What's the difference between sync and async replication?"
- [ ] "How do you handle replication lag for reads?"
- [ ] "What is the CAP theorem? Give an example of a CP and AP system."
- [ ] "How does quorum-based replication work?"

#### Sharding
- [ ] "How would you choose a shard key for a social media app?"
- [ ] "What is a hotspot and how do you prevent it?"
- [ ] "How do you handle cross-shard joins?"
- [ ] "What is consistent hashing and when would you use it?"

#### CAP/PACELC
- [ ] "During a partition, should this system prioritize consistency or availability?"
- [ ] "What trade-offs does eventual consistency create?"
- [ ] "How does PACELC extend CAP?"

### Decision Framework

```
CHOOSING REPLICATION STRATEGY:

Is data loss acceptable?
├── NO → Synchronous replication (or quorum with W = all)
└── YES → How much lag is acceptable?
          ├── Seconds → Async with monitoring
          └── Minutes → Async, eventual consistency

CHOOSING SHARD KEY:

What's your primary access pattern?
├── User-centric → Shard by user_id
├── Time-series → Shard by time bucket + hash
├── Geographic → Shard by region
└── Random → Hash-based sharding

Need range queries?
├── YES → Consider range-based sharding (accept hotspot risk)
└── NO → Hash-based is safer
```

### Common Pitfalls

| Mistake | Why It's Wrong | Correct Understanding |
|---------|----------------|----------------------|
| "CAP means pick 2 of 3" | P is mandatory | Choose C or A during partitions |
| "Sharding is always better" | Adds massive complexity | Only shard when you must |
| "Use celebrity_id as shard key" | Creates hotspots | Shard by content_id or compound key |
| "Sync replication = no data loss" | Can still lose in-flight txns | Need 2PC for guaranteed durability |

---

## Next Steps

Continue to **[Level 4: Real-Time Updates](04_REALTIME_UPDATES.md)** to learn about CDC, WebSockets, and fan-out patterns.

