# Database Fundamentals for System Design

> **A Master Guide Bridging Database Theory with System Design Application**

This guide is structured in 5 progressive levels, each building on the previous. Use this for deep preparation or jump to specific sections based on your weak areas.

---

## 📚 Guide Structure

| Level | File | Topics | When to Use |
|-------|------|--------|-------------|
| **1** | [Storage Internals](01_STORAGE_INTERNALS.md) | B-Trees, LSM-Trees, WAL, Buffer Pool | Understanding "why" databases work |
| **2** | [Database Logic](02_DATABASE_LOGIC.md) | Indexing (SQL & NoSQL), MVCC, Isolation Levels | Query optimization, concurrency |
| **3** | [Distributed Systems](03_DISTRIBUTED_SYSTEMS.md) | Replication, Sharding, CAP/PACELC | Scaling beyond single node |
| **4** | [Real-Time Updates](04_REALTIME_UPDATES.md) | CDC, WebSockets, SSE, Fan-out | Building reactive systems |
| **5** | [Architectural Mapping](05_ARCHITECTURAL_MAPPING.md) | MySQL, Postgres, Cassandra, Redis, Kafka | Choosing the right tool |
| **⚡** | [Senior Gotchas](06_SENIOR_GOTCHAS.md) | Edge cases, failure modes, deep questions | Staff+ level prep |
| **📋** | [Quick Reference Card](QUICK_REFERENCE_CARD.md) | 1-page summary | 30 mins before interview |

---

## 🔗 How All Levels Connect

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CONCEPT FLOW: SINGLE-NODE → DISTRIBUTED → REAL-TIME      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  LEVEL 1: STORAGE INTERNALS (Foundation)                                ││
│  │  ══════════════════════════════════════                                 ││
│  │  • B-Tree / LSM-Tree   • Pages / Buffer Pool   • WAL                   ││
│  │  Scope: SINGLE-NODE, how bytes hit the disk                            ││
│  └────────────────────────────────┬────────────────────────────────────────┘│
│                                   │                                          │
│                                   ▼ (builds on)                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  LEVEL 2: DATABASE LOGIC (Performance)                                  ││
│  │  ═════════════════════════════════════                                  ││
│  │  • SQL Indexes (B-Trees!)   • NoSQL Keys (Partition/Cluster)           ││
│  │  • MVCC   • Isolation Levels   • Secondary Indexes (LSI/GSI)           ││
│  │  Scope: SINGLE-NODE, query optimization & transactions                 ││
│  └────────────────────────────────┬────────────────────────────────────────┘│
│                                   │                                          │
│                                   ▼ (extends to multiple nodes)              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  LEVEL 3: DISTRIBUTED SYSTEMS (Scalability)                             ││
│  │  ══════════════════════════════════════════                             ││
│  │  • Replication (uses WAL!)   • Sharding   • CAP/PACELC                 ││
│  │  • Conflict Resolution (distributed MVCC)                               ││
│  │  Scope: MULTI-NODE, scaling beyond one machine                         ││
│  └────────────────────────────────┬────────────────────────────────────────┘│
│                                   │                                          │
│                                   ▼ (enables)                                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  LEVEL 4: REAL-TIME UPDATES (The "Now" Layer)                          ││
│  │  ════════════════════════════════════════════                           ││
│  │  • CDC (tails the WAL!)   • WebSockets/SSE   • Fan-out                 ││
│  │  Scope: Getting changes to users instantly                              ││
│  └────────────────────────────────┬────────────────────────────────────────┘│
│                                   │                                          │
│                                   ▼ (all together for)                       │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  LEVEL 5: ARCHITECTURAL MAPPING (Scenarios)                             ││
│  │  ══════════════════════════════════════════                             ││
│  │  • PostgreSQL vs Cassandra vs Redis vs Kafka                           ││
│  │  • "Which database for my use case?"                                   ││
│  │  Scope: Applying all knowledge to real system design                   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  KEY INSIGHT: Each level builds on the previous!                            │
│  • WAL (Level 1) → enables Replication (Level 3) → enables CDC (Level 4)   │
│  • MVCC (Level 2) → extends to Distributed MVCC (Level 3)                  │
│  • B-Trees (Level 1) → are what Indexes are (Level 2)                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 🎯 Quick Decision Framework

### "Which Database Should I Use?"

```
┌─────────────────────────────────────────────────────────────────────┐
│                    START: What's your priority?                      │
└─────────────────────────────────────────────────────────────────────┘
                                   │
          ┌────────────────────────┼────────────────────────┐
          ▼                        ▼                        ▼
   ┌─────────────┐          ┌─────────────┐          ┌─────────────┐
   │ CONSISTENCY │          │   SCALE     │          │  LATENCY    │
   │   (ACID)    │          │  (Volume)   │          │  (Speed)    │
   └─────────────┘          └─────────────┘          └─────────────┘
          │                        │                        │
          ▼                        ▼                        ▼
   ┌─────────────┐          ┌─────────────┐          ┌─────────────┐
   │  PostgreSQL │          │  Cassandra  │          │    Redis    │
   │    MySQL    │          │  ScyllaDB   │          │  Memcached  │
   └─────────────┘          └─────────────┘          └─────────────┘
          │                        │                        │
          │                        │                        │
          └────────────────────────┴────────────────────────┘
                                   │
                                   ▼
                         ┌─────────────────┐
                         │  Need to sync   │
                         │  between them?  │
                         │                 │
                         │     KAFKA       │
                         └─────────────────┘
```

---

## 🔥 The 5 Most Important Concepts

1. **B-Tree vs LSM-Tree** → Read-heavy vs Write-heavy workloads
2. **MVCC** → How databases achieve non-blocking reads
3. **CAP Theorem** → You can only pick 2 of 3 during partitions
4. **CDC + Fan-out** → Real-time updates at scale
5. **Index Selectivity** → Why some indexes are useless

---

## 📖 Recommended Reading Order

### For Beginners (< 3 YoE)
1. Start with Level 1 (Storage Internals) - understand the "why"
2. Move to Level 2 (Database Logic) - this is most interview-relevant
3. Read Level 5 (Architectural Mapping) - know when to use what
4. Skim Level 3 & 4 for awareness

### For Experienced Engineers (3-7 YoE)
1. Quick review of Level 1 & 2
2. Deep dive into Level 3 (Distributed Systems)
3. Master Level 4 (Real-Time Updates)
4. Study Level 5 with focus on trade-offs
5. **Critical**: Go through all Senior Gotchas

### For Staff+ Level
1. Focus on edge cases and failure modes (Level 6)
2. Be able to explain "why" for every decision
3. Know the operational aspects (monitoring, recovery)
4. Practice connecting concepts across levels

---

## 🗣️ Interview Phrases That Impress

| Instead of... | Say... |
|---------------|--------|
| "It's fast" | "It optimizes for sequential disk I/O with LSM-trees" |
| "It scales" | "We shard by user_id to ensure data locality and avoid cross-shard joins" |
| "It's consistent" | "We use synchronous replication with a quorum of 2/3 nodes" |
| "It's real-time" | "We use CDC to tail the WAL and fan-out via Redis Pub/Sub" |
| "We cache it" | "We use a write-through cache with TTL-based invalidation" |

---

## 🏗️ System Design Interview Structure

When asked "Design X", structure your database discussion as:

```
1. IDENTIFY THE DATA MODEL
   - What entities? What relationships?
   - Read:Write ratio?
   - Hot data vs Cold data?

2. CHOOSE STORAGE (Level 1 knowledge)
   - B-Tree (Postgres/MySQL) for read-heavy + ACID
   - LSM-Tree (Cassandra/RocksDB) for write-heavy + scale

3. OPTIMIZE ACCESS (Level 2 knowledge)
   - Which indexes? (Composite, covering)
   - What isolation level?
   - Any denormalization needed?

4. SCALE OUT (Level 3 knowledge)
   - Replication strategy?
   - Sharding key? (Avoid hotspots!)
   - Consistency requirements?

5. REAL-TIME REQUIREMENTS? (Level 4 knowledge)
   - Need CDC?
   - WebSocket or SSE?
   - Fan-out strategy?
```

---

## ⚠️ Common Mistakes to Avoid

| Mistake | Why It's Wrong | What to Do Instead |
|---------|----------------|---------------------|
| "Just add Redis in front" | Doesn't address cache invalidation | Explain the cache strategy (write-through, write-behind, TTL) |
| "We'll shard later" | Retrofitting sharding is painful | Discuss shard key selection early |
| "Use Kafka for everything" | Kafka adds latency and complexity | Use it for decoupling, not as primary data store |
| "NoSQL is faster" | Depends on access patterns | Explain the specific trade-off you're making |
| "We'll handle edge cases in the app" | Creates distributed systems bugs | Address at the database/protocol level |

---

## 📁 File Navigation

- **[01_STORAGE_INTERNALS.md](01_STORAGE_INTERNALS.md)** - The Disk Layer
- **[02_DATABASE_LOGIC.md](02_DATABASE_LOGIC.md)** - The Performance Layer  
- **[03_DISTRIBUTED_SYSTEMS.md](03_DISTRIBUTED_SYSTEMS.md)** - The Scalability Layer
- **[04_REALTIME_UPDATES.md](04_REALTIME_UPDATES.md)** - The "Now" Layer
- **[05_ARCHITECTURAL_MAPPING.md](05_ARCHITECTURAL_MAPPING.md)** - The Scenario Layer
- **[06_SENIOR_GOTCHAS.md](06_SENIOR_GOTCHAS.md)** - Edge Cases & Deep Questions
- **[QUICK_REFERENCE_CARD.md](QUICK_REFERENCE_CARD.md)** - Last-Minute Review

---

*Created for system design interview preparation. Each file is self-contained but builds on previous concepts.*

