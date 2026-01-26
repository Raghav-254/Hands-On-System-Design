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
│  │  Isolation levels     ───► Distributed transactions (2PC, Saga)         ││
│  │  Write conflicts      ───► LWW, Vector Clocks, CRDTs                    ││
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
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  ⚠️  SQL vs NoSQL: WHICH TOPICS APPLY WHERE?                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Not all distributed concepts apply equally to SQL and NoSQL!              │
│                                                                              │
│  ┌───────────────────────┬────────────┬────────────┬──────────────────────┐│
│  │ Topic                 │ SQL        │ NewSQL     │ NoSQL (Leaderless)   ││
│  │                       │(PostgreSQL)│(Cockroach) │(Cassandra, Dynamo)   ││
│  ├───────────────────────┼────────────┼────────────┼──────────────────────┤│
│  │ Replication           │ ✅ Yes     │ ✅ Yes     │ ✅ Yes               ││
│  │ Sharding              │ ⚠️ Add-on  │ ✅ Native  │ ✅ Native            ││
│  │ CAP/PACELC            │ ✅ Applies │ ✅ Applies │ ✅ Applies           ││
│  │ Consensus (Raft)      │ ✅ For HA  │ ✅ Core    │ ❌ No leader         ││
│  │ Conflict Resolution   │ ❌ N/A     │ ✅ Internal│ ✅ Primary use       ││
│  │ Hinted Handoff        │ ❌ N/A     │ ❌ Uses Raft│ ✅ Primary use       ││
│  │ Read Repair           │ ❌ N/A     │ ⚠️ Some    │ ✅ Primary use       ││
│  │ Anti-Entropy          │ ❌ N/A     │ ⚠️ Some    │ ✅ Primary use       ││
│  └───────────────────────┴────────────┴────────────┴──────────────────────┘│
│                                                                              │
│  WHY THE DIFFERENCE?                                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • SQL (single-leader): ONE node accepts writes → no write conflicts!     │
│    Uses consensus only for FAILOVER (elect new leader when primary dies). │
│                                                                              │
│  • NewSQL: Uses consensus for EVERY WRITE to ensure strong consistency.   │
│    Handles conflicts internally, you don't see them.                      │
│                                                                              │
│  • NoSQL (leaderless): ANY node can accept writes → conflicts happen!     │
│    Needs LWW/Vector Clocks/CRDTs + repair mechanisms for consistency.     │
│                                                                              │
│  👉 Sections 1-4 apply broadly. Section 5 is primarily for leaderless.    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents

1. [Replication Strategies](#1-replication-strategies)
   - [Why Replicate?](#why-replicate)
   - [Synchronous vs Asynchronous Replication](#synchronous-vs-asynchronous-replication)
   - [Replication Topologies](#replication-topologies)
   - [Handling Replication Lag](#handling-replication-lag)

2. [Sharding (Horizontal Partitioning)](#2-sharding-horizontal-partitioning)
   - [Sharding Strategies](#sharding-strategies) (Range, Hash, Consistent Hashing)
   - [Choosing a Shard Key](#choosing-a-shard-key)
   - [The Hotspot Problem](#the-hotspot-problem)
   - [Cross-Shard Operations](#cross-shard-operations)
   - [SQL Sharding: Add-On vs Native](#sql-sharding-add-on-vs-native)

3. [CAP Theorem and PACELC](#3-cap-theorem-and-pacelc)
   - [CAP Theorem](#cap-theorem)
   - [CP vs AP During Partition](#during-a-partition-cp-vs-ap)
   - [PACELC: The Complete Picture](#pacelc-the-complete-picture)
   - [Database Classifications](#database-cappacelc-classifications)

4. [Consensus and Coordination](#4-consensus-and-coordination)
   - [Why Consensus Matters](#why-consensus-matters)
   - [Raft Consensus](#raft-consensus-simplified)
   - [Consensus Use Cases](#consensus-use-cases)
   - [Distributed Locks vs Database Locks](#distributed-locks-vs-database-locks)
   - [Fencing Tokens](#fencing-tokens-preventing-zombie-leaders)
   - [Distributed Transactions: 2PC vs Saga](#distributed-transactions-2pc-vs-saga)

5. [Conflict Resolution & Anti-Entropy](#5-conflict-resolution--anti-entropy)
   - **Part 1: Write Conflicts**
     - [The Conflict Problem](#the-conflict-problem)
     - [Last-Write-Wins (LWW)](#1-last-write-wins-lww)
     - [Vector Clocks](#2-vector-clocks-detect-conflicts-dont-auto-resolve)
     - [CRDTs](#3-crdts-conflict-free-replicated-data-types)
   - **Part 2: Keeping Replicas in Sync**
     - [Layer 1: Hinted Handoff](#layer-1-hinted-handoff-preventing-divergence)
     - [Layer 2: Read Repair](#layer-2-read-repair-fixing-divergence-on-demand)
     - [Layer 3: Anti-Entropy with Merkle Trees](#layer-3-anti-entropy-with-merkle-trees-background-repair)
   - [Section 5 Summary](#section-5-summary)

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
│  ✅ Even distribution of KEYS across shards                          │
│  ✅ Works with any key type                                          │
│  ❌ Range queries require scatter-gather                             │
│  ❌ Adding shards requires rehashing (unless consistent hashing)     │
│  ⚠️  Does NOT solve "hot key" problem (celebrity still on 1 shard!) │
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
│  ⚠️  IMPORTANT: Consistent hashing solves RESHARDING, not HOT KEYS! │
│  • Celebrity's data still lands on ONE shard                        │
│  • See "The Hotspot Problem" section below for solutions            │
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
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE PROBLEM: JOINs ACROSS SHARDS                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  You have TWO TABLES that need to be joined:                                │
│                                                                              │
│  TABLE: users                    TABLE: orders                              │
│  ┌──────────────────────┐        ┌─────────────────────────────────┐       │
│  │ id │ name   │ email  │        │ order_id │ user_id │ total │ ...│       │
│  │ 1  │ Alice  │ a@...  │        │ 101      │ 1       │ $50   │    │       │
│  │ 2  │ Bob    │ b@...  │        │ 102      │ 2       │ $30   │    │       │
│  │ 3  │ Carol  │ c@...  │        │ 103      │ 1       │ $75   │    │       │
│  └──────────────────────┘        │ 104      │ 3       │ $20   │    │       │
│                                  └─────────────────────────────────┘       │
│                                                                              │
│  QUERY: "Get all pending orders with user details"                          │
│  SELECT * FROM orders o JOIN users u ON o.user_id = u.id                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  SCENARIO A: BOTH tables sharded by user_id (CO-LOCATED) ✅                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Shard 0 (user_id % 2 = 0)         Shard 1 (user_id % 2 = 1)               │
│  ┌───────────────────────────┐     ┌───────────────────────────┐           │
│  │ users:                    │     │ users:                    │           │
│  │   id=2 (Bob)              │     │   id=1 (Alice)            │           │
│  │                           │     │   id=3 (Carol)            │           │
│  │ orders:                   │     │ orders:                   │           │
│  │   order_id=102, user_id=2 │     │   order_id=101, user_id=1 │           │
│  │                           │     │   order_id=103, user_id=1 │           │
│  └───────────────────────────┘     │   order_id=104, user_id=3 │           │
│                                    └───────────────────────────┘           │
│                                                                              │
│  ✅ JOIN happens WITHIN each shard (no network hop!)                        │
│  ✅ Each shard has the user AND their orders together                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│  SCENARIO B: Tables sharded by DIFFERENT keys ❌                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  users sharded by user_id:         orders sharded by order_id:             │
│                                                                              │
│  Shard 0         Shard 1           Shard 0         Shard 1                 │
│  ┌─────────┐     ┌─────────┐       ┌─────────┐     ┌─────────┐             │
│  │ user 2  │     │ user 1  │       │ order   │     │ order   │             │
│  │ (Bob)   │     │ (Alice) │       │ 102     │     │ 101     │             │
│  │         │     │ user 3  │       │ 104     │     │ 103     │             │
│  │         │     │ (Carol) │       │         │     │         │             │
│  └─────────┘     └─────────┘       └─────────┘     └─────────┘             │
│                                                                              │
│  To JOIN order 101 with user 1:                                            │
│                                                                              │
│    Orders Shard 1          Users Shard 1                                   │
│    ┌───────────┐           ┌───────────┐                                   │
│    │ order 101 │ ────────► │ user 1    │   ← NETWORK HOP!                 │
│    │ user_id=1 │  lookup   │ (Alice)   │                                   │
│    └───────────┘           └───────────┘                                   │
│                                                                              │
│  ❌ EVERY order needs a cross-shard lookup to get user info!               │
│  ❌ 1000 orders = 1000 network round trips (or batch, still slow)          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

SOLUTIONS:

1. CO-LOCATE RELATED DATA
   • Shard BOTH tables by user_id (Scenario A above)
   • Trade-off: "Find all orders" becomes scatter-gather

2. DENORMALIZATION
   • Store user_name directly in orders table
   • Trade-off: Data duplication, update complexity

3. APPLICATION-LEVEL JOINS
   • Fetch orders first, then batch fetch users by user_ids
   • Trade-off: More round trips, application complexity

4. AVOID CROSS-SHARD TRANSACTIONS
   • Use Saga pattern for distributed transactions
   • Accept eventual consistency where possible
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
│  LOG REPLICATION (uses QUORUM internally!):                          │
│  1. Client sends write to leader                                     │
│  2. Leader appends to its log, sends to followers                    │
│  3. Followers append to their logs, send ACK                         │
│  4. Once majority ACKs, leader commits  ← This is W = majority!     │
│  5. Leader notifies followers to commit                              │
│  6. Client gets success response                                     │
│                                                                      │
│  NOTE: Raft uses quorum (majority = N/2 + 1) for commits.           │
│  Difference from Dynamo-style quorum: Raft has a LEADER,            │
│  Dynamo is leaderless (any node can accept writes).                 │
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
   • Fencing tokens (see below)

4. ATOMIC BROADCAST
   • Total ordering of messages
   • Replicated state machines
   • Distributed transactions (2PC/3PC)
```

### Distributed Locks vs Database Locks

```
┌─────────────────────────────────────────────────────────────────────────────┐
│        DATABASE LOCKS (Level 2)  vs  DISTRIBUTED LOCKS (Level 3)            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DATABASE LOCKS (What we covered in Level 2):                               │
│  ─────────────────────────────────────────────                              │
│  • Managed BY the database (PostgreSQL, MySQL)                              │
│  • Scope: ONE database, transactions within that database                   │
│  • Use case: Prevent lost updates, ensure isolation                        │
│  • Examples: SELECT...FOR UPDATE, S-Lock, X-Lock, 2PL                      │
│  • Automatic: Database handles lock acquisition/release                    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Service A                                                          │   │
│  │     │                                                               │   │
│  │     └────► PostgreSQL ─── SELECT...FOR UPDATE ───► row locked      │   │
│  │             (manages locks internally)                              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  DISTRIBUTED LOCKS (This level):                                            │
│  ─────────────────────────────────                                          │
│  • Managed by EXTERNAL lock service (Redis, ZooKeeper, etcd)               │
│  • Scope: MULTIPLE services/servers that DON'T share a database            │
│  • Use case: Coordinate actions when there's NO shared database            │
│  • Examples: Redis SETNX, Redlock, ZooKeeper locks                         │
│  • Manual: YOU must handle expiry, renewal, fencing tokens                 │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  CONCRETE EXAMPLE: Cron Job on Multiple Servers                            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SCENARIO: "Send daily email digest at 9 AM"                               │
│  You have 5 app servers, each with a cron job scheduled for 9 AM.         │
│                                                                              │
│  ⚠️  WHY LOAD BALANCER CAN'T HELP:                                          │
│  • Cron jobs are INTERNAL scheduled tasks, not external requests           │
│  • Each server's cron daemon triggers independently at 9 AM                │
│  • There's no incoming HTTP request for load balancer to route!            │
│                                                                              │
│  WITHOUT coordination: All 5 servers wake up at 9 AM → 5 emails sent!     │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  At 9:00 AM, ALL servers wake up independently:                       │ │
│  │                                                                       │ │
│  │  Server 1 ───┐                                                        │ │
│  │  Server 2 ───┤     ┌──────────────────┐                               │ │
│  │  Server 3 ───┼────►│  Redis           │   Only ONE gets the lock!    │ │
│  │  Server 4 ───┤     │  "daily_email"   │   That one sends the email.  │ │
│  │  Server 5 ───┘     └──────────────────┘   Others see lock, skip.     │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  CODE:                                                                      │
│  # Each server runs this at 9 AM via cron                                  │
│  if redis.setnx("daily_email_lock", server_id, expiry=60):                 │
│      send_daily_email()                                                    │
│      redis.delete("daily_email_lock")                                      │
│  else:                                                                      │
│      # Another server already has the lock, skip                           │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  SIMPLE LOCKS vs CONSENSUS-BASED LOCKS:                                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SIMPLE LOCK (Redis SETNX):                                                │
│  • Single Redis server manages the lock                                    │
│  • Fast and simple                                                         │
│  • ❌ Single point of failure (Redis dies = lock lost)                     │
│  • ❌ NOT using consensus                                                  │
│  • Good for: Non-critical tasks (email digest, cache refresh)             │
│                                                                              │
│  CONSENSUS-BASED LOCK (ZooKeeper, etcd):                                   │
│  • Lock state replicated across multiple nodes using Raft/Paxos           │
│  • ✅ Survives node failures (majority must agree)                         │
│  • ✅ Uses consensus internally                                            │
│  • Slower, more complex                                                    │
│  • Good for: Critical tasks (payment processing, leader election)         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Redis Lock:         ZooKeeper Lock:                               │   │
│  │  ┌────────┐           ┌────────┐                                   │   │
│  │  │ Redis  │           │ ZK Node│◄──┐                               │   │
│  │  │(single)│           │   A    │   │ Raft/Paxos                   │   │
│  │  └────────┘           └────────┘   │ replication                  │   │
│  │      │                    ▲        │                               │   │
│  │      │                    │        │                               │   │
│  │  If Redis dies,       ┌──┴────┐ ┌─┴──────┐                        │   │
│  │  lock is lost!        │ZK Node│ │ZK Node │                        │   │
│  │                       │   B   │ │   C    │                        │   │
│  │                       └───────┘ └────────┘                        │   │
│  │                       If A dies, B or C takes over!              │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  WHY CAN'T WE USE DATABASE LOCK HERE?                                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • There's no "row" to lock - we're coordinating a TASK, not data         │
│  • The job might call external APIs, not just database                    │
│  • We need a lock BEFORE we decide what to do                             │
│  • Database lock = "lock this data" | Distributed lock = "lock this task" │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  WHEN TO USE WHICH:                                                         │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  DATABASE LOCKS:                                                            │
│  • "Lock this ROW while I update it"                                      │
│  • Inventory decrement, bank transfer, booking seat                       │
│  • You're protecting DATA in a database                                   │
│                                                                              │
│  DISTRIBUTED LOCKS:                                                         │
│  • "Only ONE server should run this TASK"                                 │
│  • Cron jobs, scheduled tasks, batch processing                           │
│  • Leader election (only one server is "active")                          │
│  • You're coordinating WORK across servers                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Fencing Tokens (Preventing Zombie Leaders)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE PROBLEM: ZOMBIE LEADER                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Leader election for a database cluster                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  You have 3 servers: A, B, C. Only ONE can be leader (accept writes).      │
│  ZooKeeper manages leader election.                                        │
│                                                                              │
│  T1: Server A is elected leader                                            │
│  T2: Server A accepts writes, sends to storage                             │
│  T3: Server A hits GC pause / network partition (stuck!)                   │
│  T4: ZooKeeper: "A is unresponsive, elect new leader"                      │
│  T5: Server B becomes new leader                                           │
│  T6: Server B accepts writes, sends to storage                             │
│  T7: Server A wakes up, STILL THINKS IT'S THE LEADER! 😱                   │
│  T8: Server A accepts a write → CONFLICTS with B's writes!                 │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Timeline:                                                          │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │                                                                     │   │
│  │  Server A: [LEADER]──writes──►[GC PAUSE 💤]──wakes up──►[writes!]  │   │
│  │                                      │                    ↓         │   │
│  │  ZooKeeper:               [A dead?]──┴──[elect B]        CONFLICT!  │   │
│  │                                             │             ↑         │   │
│  │  Server B:                           [LEADER]───writes───┘         │   │
│  │                                                                     │   │
│  │  RESULT: Two servers both think they're leader!                    │   │
│  │          Data corruption, split-brain!                             │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE SOLUTION: FENCING TOKENS                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  IDEA: Every leader election gets a monotonically increasing token (epoch).│
│        Storage rejects writes from old leaders with outdated tokens.       │
│                                                                              │
│  HOW IT WORKS:                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  T1: Server A elected → gets epoch #33                                     │
│  T2: Server A writes with epoch #33 → Storage accepts, stores epoch=33    │
│  T5: Server B elected → gets epoch #34                                     │
│  T6: Server B writes with epoch #34 → Storage updates epoch=34            │
│  T7: Server A wakes up, writes with epoch #33                              │
│  T8: Storage: "33 < 34? REJECTED! You're not the leader anymore!"         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Server A        ZooKeeper           Storage           Server B    │   │
│  │     │                │                   │                  │       │   │
│  │     │◄─leader #33────│                   │                  │       │   │
│  │     │───write+#33───►│──────────────────►│ epoch=33         │       │   │
│  │     │                │                   │                  │       │   │
│  │     │  💤 PAUSE      │                   │                  │       │   │
│  │     │                │                   │                  │       │   │
│  │     │                │──leader #34──────►│──────────────────│       │   │
│  │     │                │                   │◄───write+#34─────│       │   │
│  │     │                │                   │ epoch=34         │       │   │
│  │     │                │                   │                  │       │   │
│  │     │───write+#33───►│──────────────────►│                  │       │   │
│  │     │                │                   │ 33 < 34          │       │   │
│  │     │◄───REJECTED!───│───────────────────│ NOT LEADER!      │       │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  KEY POINTS:                                                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  1. The fencing token is a monotonically increasing number (epoch/term)    │
│  2. STORAGE must check the token and reject old ones                       │
│  3. ZooKeeper's zxid, Raft's term, Kafka's epoch are all fencing tokens   │
│  4. Without fencing tokens, you get split-brain (two leaders)              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Distributed Transactions: 2PC vs Saga

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DISTRIBUTED TRANSACTIONS                                  │
│         "How to update multiple services/databases atomically?"             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM: Order service needs to:                                           │
│  1. Deduct inventory (Inventory DB)                                         │
│  2. Charge payment (Payment Service)                                        │
│  3. Create order (Order DB)                                                 │
│                                                                              │
│  If step 2 fails, step 1 must be rolled back! How?                         │
│                                                                              │
│  TWO APPROACHES:                                                            │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  1. TWO-PHASE COMMIT (2PC) - Strong consistency, blocking                  │
│  2. SAGA PATTERN - Eventual consistency, non-blocking                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    TWO-PHASE COMMIT (2PC)                                    │
│         "All-or-nothing across multiple nodes"                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PHASE 1: PREPARE (Voting)                                                  │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Coordinator ──► "Can you commit?" ──► Participant A                       │
│              ──► "Can you commit?" ──► Participant B                       │
│                                                                              │
│  Participants:                                                              │
│  • Lock resources                                                           │
│  • Write to local WAL                                                       │
│  • Reply YES (prepared) or NO (abort)                                      │
│                                                                              │
│  PHASE 2: COMMIT (Decision)                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  If ALL said YES:                                                           │
│     Coordinator ──► "COMMIT" ──► All participants commit                   │
│                                                                              │
│  If ANY said NO:                                                            │
│     Coordinator ──► "ABORT" ──► All participants rollback                  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Coordinator        Inventory DB       Payment         Order DB    │   │
│  │      │                  │                │                │         │   │
│  │      │── PREPARE ──────►│                │                │         │   │
│  │      │── PREPARE ───────────────────────►│                │         │   │
│  │      │── PREPARE ────────────────────────────────────────►│         │   │
│  │      │                  │                │                │         │   │
│  │      │◄── YES ──────────│                │                │         │   │
│  │      │◄── YES ───────────────────────────│                │         │   │
│  │      │◄── YES ────────────────────────────────────────────│         │   │
│  │      │                  │                │                │         │   │
│  │      │── COMMIT ───────►│                │                │         │   │
│  │      │── COMMIT ────────────────────────►│                │         │   │
│  │      │── COMMIT ─────────────────────────────────────────►│         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ✅ PROS: Strong consistency (ACID across nodes)                           │
│  ❌ CONS:                                                                   │
│     • BLOCKING: Locks held during entire protocol                          │
│     • Coordinator is SPOF (if crashes during phase 2, participants stuck)  │
│     • High latency (2 network round-trips minimum)                         │
│     • Doesn't scale well (all participants must be available)              │
│                                                                              │
│  USED BY: Traditional databases, XA transactions, some NewSQL              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    SAGA PATTERN                                              │
│         "Sequence of local transactions with compensating actions"          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  IDEA: Break distributed transaction into local transactions.              │
│  If one fails, execute COMPENSATING TRANSACTIONS to undo previous steps.   │
│                                                                              │
│  EXAMPLE: Order Saga                                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  HAPPY PATH:                                                        │   │
│  │                                                                     │   │
│  │  T1: Reserve Inventory ──► T2: Charge Payment ──► T3: Create Order │   │
│  │                                                                     │   │
│  │  FAILURE (Payment fails):                                          │   │
│  │                                                                     │   │
│  │  T1: Reserve Inventory ──► T2: Charge Payment ✗                    │   │
│  │           │                       │                                 │   │
│  │           │◄──── C1: Release ◄────┘                                │   │
│  │                  Inventory                                          │   │
│  │                                                                     │   │
│  │  Each step has a COMPENSATING action:                              │   │
│  │  T1: Reserve Inventory  →  C1: Release Inventory                   │   │
│  │  T2: Charge Payment     →  C2: Refund Payment                      │   │
│  │  T3: Create Order       →  C3: Cancel Order                        │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  TWO COORDINATION STYLES:                                                   │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  CHOREOGRAPHY: Services emit events, others react                          │
│  • Order → "InventoryReserved" → Payment → "PaymentCharged" → ...         │
│  • No central coordinator, but complex to track                            │
│                                                                              │
│  ORCHESTRATION: Central saga orchestrator directs each step                │
│  • Orchestrator calls each service in sequence                             │
│  • Easier to understand, single point to monitor                           │
│                                                                              │
│  ✅ PROS:                                                                   │
│     • Non-blocking (no long-held locks)                                    │
│     • Better availability (services can be temporarily down)               │
│     • Scales better than 2PC                                               │
│                                                                              │
│  ❌ CONS:                                                                   │
│     • Eventual consistency (not ACID)                                      │
│     • Complex to implement (compensating logic)                            │
│     • Harder to debug                                                      │
│                                                                              │
│  USED BY: Microservices, e-commerce, booking systems                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    2PC vs SAGA: WHEN TO USE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────┬─────────────────────┬────────────────────────────────┐│
│  │ Aspect          │ 2PC                 │ Saga                           ││
│  ├─────────────────┼─────────────────────┼────────────────────────────────┤│
│  │ Consistency     │ Strong (ACID)       │ Eventual                       ││
│  │ Availability    │ Lower (blocking)    │ Higher (non-blocking)          ││
│  │ Latency         │ Higher (2 RTTs)     │ Lower (async possible)         ││
│  │ Complexity      │ Protocol is complex │ Compensation logic is complex  ││
│  │ Scale           │ Limited             │ Better                         ││
│  │ Isolation       │ Yes (locks)         │ No (dirty reads possible)      ││
│  └─────────────────┴─────────────────────┴────────────────────────────────┘│
│                                                                              │
│  USE 2PC WHEN:                                                              │
│  • Strong consistency is mandatory (financial transactions)                │
│  • Few participants, low latency requirements                              │
│  • Within a single database cluster (not across microservices)            │
│                                                                              │
│  USE SAGA WHEN:                                                             │
│  • High availability is priority                                           │
│  • Microservices architecture                                              │
│  • Can tolerate eventual consistency                                       │
│  • Long-running transactions (minutes/hours)                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
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

---

### The Two Big Problems in Distributed Data

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TWO CHALLENGES, DIFFERENT SOLUTIONS                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  When you have MULTIPLE NODES that can accept writes, you face:             │
│                                                                              │
│  ╔═══════════════════════════════════════════════════════════════════════╗  │
│  ║  PROBLEM 1: WRITE CONFLICTS                                           ║  │
│  ║  ─────────────────────────────────────────────────────────────────────║  │
│  ║  "Two users update the SAME key on DIFFERENT nodes at the same time" ║  │
│  ║                                                                       ║  │
│  ║  Node A: name = "Alice"    Node B: name = "Alicia"                   ║  │
│  ║                    ↘             ↙                                    ║  │
│  ║                      WHICH WINS?                                      ║  │
│  ║                                                                       ║  │
│  ║  SOLUTIONS: LWW, Vector Clocks, CRDTs (see below)                    ║  │
│  ╚═══════════════════════════════════════════════════════════════════════╝  │
│                                                                              │
│  ╔═══════════════════════════════════════════════════════════════════════╗  │
│  ║  PROBLEM 2: REPLICA DIVERGENCE                                        ║  │
│  ║  ─────────────────────────────────────────────────────────────────────║  │
│  ║  "Replicas get out of sync due to failures, delays, or partitions"   ║  │
│  ║                                                                       ║  │
│  ║  Node A: version 5 ✓       Node B: version 5 ✓       Node C: version 3 ←STALE!║
│  ║                                                                       ║  │
│  ║  SOLUTIONS: Three layers of repair (see below)                       ║  │
│  ╚═══════════════════════════════════════════════════════════════════════╝  │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  THIS SECTION'S STORYLINE:                                                  │
│                                                                              │
│  1. WRITE CONFLICTS ────────────────────────────────────────────────────── │
│     │                                                                       │
│     ├── Last-Write-Wins (simple, but loses data)                           │
│     ├── Vector Clocks (detect conflicts, let client resolve)              │
│     └── CRDTs (auto-merge, no conflicts by design)                        │
│                                                                              │
│  2. REPLICA DIVERGENCE ─────────────────────────────────────────────────── │
│     │                                                                       │
│     ├── Hinted Handoff (PREVENT divergence during temp failures)          │
│     │   └── "Node is down? Store hint, deliver when it's back"            │
│     │                                                                       │
│     ├── Read Repair (FIX divergence on-demand during reads)               │
│     │   └── "Reading and found stale replica? Update it!"                 │
│     │                                                                       │
│     └── Anti-Entropy + Merkle Trees (FIX divergence in background)        │
│         └── "Periodically compare and sync all replicas"                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Part 1: Write Conflicts

> When multiple nodes accept writes, which value wins?

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
│  T1      User sets name="Alice"   (accepts locally, ACKs client)            │
│  T2                               User sets name="Alicia" (accepts locally) │
│  T3      ←──── async background replication ────→                           │
│          (Leaders continuously stream changes to each other)                │
│                                                                              │
│  QUESTION: What is the user's name now? "Alice" or "Alicia"?                │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  WHEN DOES SYNC HAPPEN? (Multi-Leader)                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  YES, both leaders sync! But ASYNCHRONOUSLY:                                │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Leader A (US)                    Leader B (EU)                    │   │
│  │     │                                │                              │   │
│  │     │◄─── Background replication ───►│                              │   │
│  │     │     (bidirectional, async)     │                              │   │
│  │     │                                │                              │   │
│  │  HOW:                                                               │   │
│  │  • Each leader has a "change stream" or "replication log"          │   │
│  │  • Continuously sends new writes to other leaders                  │   │
│  │  • When conflicting writes arrive → CONFLICT RESOLUTION kicks in   │   │
│  │                                                                     │   │
│  │  TIMING:                                                            │   │
│  │  • Usually milliseconds to seconds (depends on network)            │   │
│  │  • During partition: changes queue up, sync when healed            │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ⚠️  KEY INSIGHT: Conflicts are DETECTED during sync, not during write!    │
│  Each leader happily accepts its write. Only when they exchange data       │
│  do they realize "oops, we both wrote to the same key!"                    │
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

## Part 2: Keeping Replicas in Sync (Handling Divergence)

> Conflicts are about "which value wins?" But what about replicas that simply missed an update due to failures or network issues? We need mechanisms to detect and repair divergence.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THREE LAYERS OF REPLICA REPAIR                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Think of these as defense layers - each handles different scenarios:      │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                                                                         ││
│  │  LAYER 1: HINTED HANDOFF                                               ││
│  │  ─────────────────────────────────────────────────────────────────────  ││
│  │  WHEN: Node is temporarily DOWN during a write                         ││
│  │  HOW:  Store "hint" on another node, deliver when it recovers          ││
│  │  GOAL: PREVENT divergence before it happens                            ││
│  │                                                                         ││
│  │  Timeline: Write arrives → Node down → Hint stored → Node up → Synced  ││
│  │                                                                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                          ↓                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                                                                         ││
│  │  LAYER 2: READ REPAIR                                                  ││
│  │  ─────────────────────────────────────────────────────────────────────  ││
│  │  WHEN: Client reads data and we notice a stale replica                 ││
│  │  HOW:  Compare versions from multiple replicas, update stale ones      ││
│  │  GOAL: FIX divergence on-demand (for HOT data that gets read often)   ││
│  │                                                                         ││
│  │  Timeline: Read request → Check replicas → Stale found → Update async  ││
│  │                                                                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                          ↓                                                  │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                                                                         ││
│  │  LAYER 3: ANTI-ENTROPY (with Merkle Trees)                             ││
│  │  ─────────────────────────────────────────────────────────────────────  ││
│  │  WHEN: Background process runs periodically (every 10 mins)            ││
│  │  HOW:  Compare Merkle tree hashes to find divergent keys efficiently   ││
│  │  GOAL: FIX divergence for COLD data that's rarely read                 ││
│  │                                                                         ││
│  │  Timeline: Every N mins → Compare hashes → Find diff → Sync keys       ││
│  │                                                                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  WHY ALL THREE?                                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • Hinted Handoff: Fast, but hints can expire or be lost                   │
│  • Read Repair: Fast for hot data, but cold data never gets fixed          │
│  • Anti-Entropy: Catches everything, but adds background load              │
│                                                                              │
│  Together: Eventually consistent with good performance trade-offs!         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Layer 1: Hinted Handoff (Preventing Divergence)

> **First line of defense**: When a node is temporarily down, don't let it miss data.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HINTED HANDOFF                                            │
│         "Write now, sync later when node recovers"                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM:                                                                   │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  You want to write to Node A, but Node A is temporarily down.              │
│                                                                              │
│  OPTIONS:                                                                   │
│  1. Fail the write → Bad for availability!                                 │
│  2. Write only to available nodes → Node A misses data forever?            │
│  3. Hinted Handoff → Write succeeds, A gets data when it recovers ✓        │
│                                                                              │
│  HOW IT WORKS:                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SCENARIO: Write to replicas A, B, C. Node A is down.                      │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  Client                                                               │ │
│  │     │                                                                 │ │
│  │     ├──write──► Node A (DOWN! ❌)                                     │ │
│  │     │                                                                 │ │
│  │     ├──write──► Node B ✓  (stores normally)                          │ │
│  │     │                                                                 │ │
│  │     └──write──► Node C ✓  (stores normally)                          │ │
│  │                    │                                                  │ │
│  │                    └──► ALSO stores "hint" for Node A:               │ │
│  │                         "When A is back, give it this data"          │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  LATER, WHEN NODE A RECOVERS:                                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  Node A comes back online                                             │ │
│  │     │                                                                 │ │
│  │     │◄──────── Node C sends "hinted" data ────────┐                  │ │
│  │     │                                              │                  │ │
│  │     ▼                                              │                  │ │
│  │  Node A now has the data!                    Hint delivered,         │ │
│  │  Replicas A, B, C are in sync.               hint deleted.           │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  KEY POINTS:                                                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  1. HINT STORAGE: The hint is stored on another node (C in example)        │
│     - Contains: "for Node A" + the actual data                             │
│     - Temporary: deleted after successful handoff                          │
│                                                                              │
│  2. SLOPPY QUORUM: Can write to "substitute" node if original is down     │
│     - W=2 needed → A down → write to B, C instead                         │
│     - Maintains write availability during failures                         │
│                                                                              │
│  3. LIMITATIONS (Why we need more layers!):                                │
│     - Hints have TTL (e.g., 3 hours in Cassandra)                          │
│     - If A is down too long, hint expires → Layer 2 or 3 must fix it      │
│     - Hints stored in memory/disk → node crash = hints lost               │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  ⚠️  WHICH SYSTEMS USE THIS?                                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  LEADERLESS (Dynamo-style): ✅ YES - Primary use case!                      │
│  • Cassandra, Riak, DynamoDB, Voldemort                                    │
│  • Any node can accept writes, so sloppy quorum makes sense               │
│  • If preferred replica is down, write to substitute + hint               │
│                                                                              │
│  MULTI-LEADER: ✅ Sometimes (within each leader's replica set)             │
│  • Each leader may have followers; hinted handoff can apply there         │
│                                                                              │
│  SINGLE-LEADER: ❌ NOT applicable                                          │
│  • Only ONE node (leader) accepts writes                                   │
│  • If leader fails → FAILOVER (elect new leader via consensus)            │
│  • No "substitute node" concept - writes blocked until new leader         │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  Single-Leader Failure:        Leaderless Failure:                 │   │
│  │  ─────────────────────         ───────────────────                 │   │
│  │                                                                     │   │
│  │  Leader down?                  Node A down?                        │   │
│  │     ↓                             ↓                                │   │
│  │  WAIT for failover!           Write to B, C instead!              │   │
│  │  (Raft elects new leader)     (Store hint for A)                  │   │
│  │     ↓                             ↓                                │   │
│  │  Writes blocked briefly       Writes continue! ✓                  │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  USED BY: Cassandra, Riak, DynamoDB, Voldemort (all leaderless)            │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  SLOPPY QUORUM VISUALIZATION:                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  STRICT QUORUM (W=2 of A, B, C):                                           │
│  A down → Can't achieve quorum → WRITE FAILS!                              │
│                                                                              │
│  SLOPPY QUORUM (W=2, any 2 of A, B, C, D, E...):                          │
│  A down → Write to B + D (D holds hint for A) → WRITE SUCCEEDS!           │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Ring with 5 nodes, data should go to A, B, C:                     │   │
│  │  (A is down, so we use D as substitute)                            │   │
│  │                                                                     │   │
│  │              ┌───┐                                                  │   │
│  │         ┌────│ A │◄─── DOWN!                                       │   │
│  │         │    └───┘                                                  │   │
│  │       ┌───┐       ┌───┐                                            │   │
│  │       │ E │       │ B │ ◄─── Gets write (regular replica, NO hint)│   │
│  │       └───┘       └───┘                                            │   │
│  │         │    ┌───┐   │                                              │   │
│  │         └────│ D │◄──┴── Gets write + HINT for A                   │   │
│  │              └───┘       (D is SUBSTITUTE for A)                   │   │
│  │                                                                     │   │
│  │  WHO STORES HINTS?                                                 │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │  • B: Regular replica → stores data ONLY (no hint)                │   │
│  │  • D: Substitute for A → stores data + HINT for A                 │   │
│  │                                                                     │   │
│  │  WHY? D knows "I'm not supposed to have this data permanently.    │   │
│  │  When A comes back, I need to hand it off." B doesn't need a      │   │
│  │  hint because B is a legitimate replica for this key.             │   │
│  │                                                                     │   │
│  │  Result: W=2 achieved (B + D), data not lost!                      │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Layer 2: Read Repair (Fixing Divergence On-Demand)

> **Second line of defense**: When a client reads, check if replicas are in sync. If not, fix them.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    READ REPAIR                                               │
│         "Notice stale data during reads, fix it immediately"                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Client reads key "user:100" from 3 replicas (quorum read)       │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │                                                                       │ │
│  │  Client                           Coordinator                         │ │
│  │     │                                │                                │ │
│  │     │─── READ "user:100" ───────────►│                                │ │
│  │     │                                │                                │ │
│  │     │                   ┌────────────┼────────────┐                   │ │
│  │     │                   │            │            │                   │ │
│  │     │                   ▼            ▼            ▼                   │ │
│  │     │              Replica A    Replica B    Replica C               │ │
│  │     │              version=5    version=5    version=3 ← STALE!      │ │
│  │     │              name=Alice   name=Alice   name=Al                 │ │
│  │     │                   │            │            │                   │ │
│  │     │                   └────────────┼────────────┘                   │ │
│  │     │                                │                                │ │
│  │     │                     Coordinator notices:                        │ │
│  │     │                     "C has old version!"                        │ │
│  │     │                                │                                │ │
│  │     │◄──── Return version 5 ─────────│                                │ │
│  │     │      (Alice)                   │                                │ │
│  │     │                                │                                │ │
│  │     │                  (async) ──────┴──────► Update Replica C       │ │
│  │     │                                         with version 5         │ │
│  │                                                                       │ │
│  └───────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  KEY POINTS:                                                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  1. HAPPENS DURING READS: No extra background process needed               │
│  2. ASYNC UPDATE: Client doesn't wait for repair to complete               │
│  3. GREAT FOR HOT DATA: Frequently read keys get fixed quickly             │
│  4. LIMITATION: Cold data (rarely read) stays stale forever!               │
│     → That's why we need Layer 3 (Anti-Entropy)                            │
│                                                                              │
│  USED BY: Cassandra, Riak, DynamoDB                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Layer 3: Anti-Entropy with Merkle Trees (Background Repair)

> **Third line of defense**: Periodically compare ALL data across replicas and fix any divergence. Catches cold data that read repair misses.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE CHALLENGE                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Node A has 1 billion keys, Node B has 1 billion keys.                      │
│  Some keys might be out of sync. How do we find them efficiently?          │
│                                                                              │
│  NAIVE APPROACH: Compare all 1 billion keys = 1 billion network calls! ❌   │
│                                                                              │
│  SMART APPROACH: Use Merkle Trees for O(log N) comparisons! ✓              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    MERKLE TREES                                              │
│         "Hash trees that efficiently detect which data is out of sync"      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Each node builds a hash tree of its data:                                 │
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
│  ─────────────────────────────────────────────────────────────────────────  │
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

┌─────────────────────────────────────────────────────────────────────────────┐
│                    ANTI-ENTROPY PROCESS                                      │
│         "Periodic background job that uses Merkle trees to sync"            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Every 10 minutes (configurable):                                           │
│                                                                              │
│  1. Node A and Node B exchange Merkle root hashes                           │
│  2. If different, drill down to find divergent keys                         │
│  3. Exchange and reconcile those specific keys                              │
│  4. Both nodes now in sync!                                                 │
│                                                                              │
│  WHY THIS IS LAYER 3 (last resort):                                         │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • Runs periodically (not real-time like Layer 1 & 2)                       │
│  • Adds background load (computing hashes, network traffic)                 │
│  • But catches EVERYTHING that Layer 1 & 2 missed!                          │
│                                                                              │
│  PERFECT FOR:                                                               │
│  • Cold data that's rarely read (read repair never triggered)              │
│  • Data missed when hints expired (node down too long)                      │
│  • Recovering from corruption or bugs                                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Section 5 Summary

**Part 1: Write Conflict Resolution** (Which value wins?)

| Strategy | Data Loss? | Complexity | Best For |
|----------|------------|------------|----------|
| **Last-Write-Wins** | Yes (silent) | Low | Simple cases, logs |
| **Vector Clocks** | No (client resolves) | Medium | Shopping carts, docs |
| **CRDTs** | No (auto-merge) | High | Counters, sets, collab editing |
| **Application Logic** | No (custom) | High | Domain-specific rules |

**Part 2: Replica Sync Mechanisms** (Keeping replicas consistent)

| Layer | Mechanism | When It Runs | Best For |
|-------|-----------|--------------|----------|
| **1** | Hinted Handoff | During writes (node down) | Temp failures (minutes/hours) |
| **2** | Read Repair | During reads | Hot data (frequently accessed) |
| **3** | Anti-Entropy | Background (periodic) | Cold data, long outages |

```
DECISION FLOW: How does divergence get fixed?

Node goes down during write?
├── YES → Hinted Handoff (Layer 1): Store hint, deliver on recovery
│         ↓
│         Hint expired? (node down too long)
│         ├── NO → Node recovers, gets hinted data ✓
│         └── YES → Fall through to Layer 2 or 3
│
└── NO → Data written, but some replicas might be stale
         ↓
         Is this key frequently read?
         ├── YES → Read Repair (Layer 2): Fixed during next read ✓
         └── NO → Anti-Entropy (Layer 3): Fixed during background sync ✓
```

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

