# Quick Reference Card 📋

> **30-minute review before your interview**

---

## Storage: B-Tree vs LSM-Tree

| Aspect | B-Tree | LSM-Tree |
|--------|--------|----------|
| **Write** | Random I/O (slow) | Sequential I/O (fast) |
| **Read** | Fast, consistent | Variable (check levels) |
| **Use For** | OLTP, read-heavy | Time-series, write-heavy |
| **Examples** | PostgreSQL, MySQL | Cassandra, RocksDB |

**Interview Line:** *"B-Trees optimize for reads with in-place updates; LSM-Trees optimize for writes with append-only logs and background compaction."*

---

## Indexing Cheatsheet

```
CLUSTERED: Table data IS the index (only 1 per table)
NON-CLUSTERED: Separate structure pointing to data

COMPOSITE INDEX: (a, b, c)
  ✅ WHERE a = ?
  ✅ WHERE a = ? AND b = ?
  ❌ WHERE b = ?  (skips leftmost!)
  
COVERING INDEX: Includes all columns needed
  → Index-only scan, no heap lookup
  
SELECTIVITY: High = good for index, Low = full scan wins
  email: High ✅ | gender: Low ❌
```

---

## MVCC & Isolation Quick Reference

**MVCC:** Multiple versions of rows, readers see consistent snapshot, readers never block writers.

| Level | Dirty Read | Non-Rep Read | Phantom | Write Skew |
|-------|:----------:|:------------:|:-------:|:----------:|
| Read Uncommitted | ❌ | ❌ | ❌ | ❌ |
| Read Committed | ✅ | ❌ | ❌ | ❌ |
| Repeatable Read | ✅ | ✅ | ⚠️* | ❌ |
| Serializable | ✅ | ✅ | ✅ | ✅ |

*PostgreSQL RR prevents phantoms; MySQL uses gap locks

---

## CAP / PACELC

```
CAP: During PARTITION, choose Consistency OR Availability
     (Partition tolerance is required)

PACELC: 
  IF Partition → C or A
  ELSE (normal) → Latency or Consistency
```

| Database | CAP | PACELC | Notes |
|----------|-----|--------|-------|
| PostgreSQL | CP | PC/EC | Strong consistency |
| Cassandra | AP | PA/EL | Tunable per query |
| Redis | AP | PA/EL | Memory + async replication |
| MongoDB | CP | PC/EC | Default config |

---

## Replication Quick Reference

```
SYNC:  Client waits for replica ACK
       ✅ No data loss | ❌ Higher latency

ASYNC: Client doesn't wait
       ✅ Fast | ❌ Possible data loss on failover

QUORUM: W + R > N for strong consistency
        Example: N=3, W=2, R=2
```

---

## Sharding Essentials

```
HASH: hash(key) % num_shards
      ✅ Even distribution | ❌ No range queries

RANGE: key 1-1000 → Shard A, 1001-2000 → Shard B
       ✅ Range queries | ❌ Hotspot risk

GOOD SHARD KEY:
• High cardinality (many values)
• Even distribution (no celebrities)
• Matches query pattern (filter by this key)
```

---

## Real-Time Architecture

```
DB → CDC (Debezium) → Kafka → Worker → Redis Pub/Sub → WebSocket → Client
     ^                         ^
     Hop 1: DB changes         Hop 2: Push to client
```

| Tech | Use Case |
|------|----------|
| **CDC** | Capture ALL DB changes (even direct SQL) |
| **WebSocket** | Bi-directional (chat, games) |
| **SSE** | Server → Client only (feeds, tickers) |
| **Fan-out on Write** | Pre-compute timelines for normal users |
| **Fan-out on Read** | Query at read time for celebrities |

---

## Database Selection Cheat

| Need | Choose | Why |
|------|--------|-----|
| ACID, complex queries | PostgreSQL | Transactions, JOINs |
| Massive writes, time-series | Cassandra | LSM, horizontal scale |
| Sub-ms latency cache | Redis | In-memory, data structures |
| Event streaming, decoupling | Kafka | Durable log, replay |

---

## Common System Design Patterns

```
USER DATA → PostgreSQL (ACID)
CACHE → Redis (TTL, invalidate via CDC)
SEARCH → Elasticsearch (sync via CDC)
EVENTS → Kafka (decouple services)
TIME-SERIES → Cassandra (write-heavy)
REAL-TIME → Redis Pub/Sub + WebSocket
```

---

## Senior Gotchas - Quick Answers

| Question | Key Points |
|----------|------------|
| WebSocket server restart? | Reconnect with backoff, state in Redis, request catch-up |
| Out-of-order messages? | Partition by key, sequence numbers, buffer window |
| Primary DB failure? | Detect → Elect → Promote → Fence old primary |
| Connection pool exhausted? | Check pg_stat_activity, find slow queries, use pooler |
| Zero-downtime migrations? | Expand-contract, backward compatible, online DDL |
| Stale cache? | CDC invalidation, delete-on-write, short TTL |

---

## Interview Power Phrases

| Instead of... | Say... |
|---------------|--------|
| "It's fast" | "It optimizes for sequential I/O with LSM-trees" |
| "It scales" | "We shard by user_id for data locality" |
| "It's consistent" | "We use synchronous replication with W=2" |
| "It's real-time" | "We use CDC to fan-out via Redis Pub/Sub" |
| "We cache it" | "Write-through cache with CDC invalidation" |

---

## The 5-Step Database Answer Structure

```
1. DATA MODEL
   "The entities are... with relationships..."

2. CONSISTENCY REQUIREMENTS
   "This needs strong/eventual consistency because..."

3. SCALE ESTIMATES
   "We expect X reads/sec, Y writes/sec, Z storage"

4. TECHNOLOGY CHOICE
   "We'll use PostgreSQL for X, Redis for Y because..."

5. OPTIMIZATION
   "We'll add indexes on..., cache with TTL of..., shard by..."
```

---

*Good luck with your interview! 🚀*

