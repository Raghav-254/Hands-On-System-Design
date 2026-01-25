# Level 5: Architectural Mapping (The Scenario Layer)

> Map the right database technology to the right use case. This is where theory meets practice.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🗺️  HOW THIS LEVEL CONNECTS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LEVEL 5: ARCHITECTURAL MAPPING ◄── YOU ARE HERE                           │
│  ════════════════════════════════════════════════════════════════════════════│
│  Scope: Choosing the RIGHT tool for the job                                 │
│  Focus: Database selection, trade-offs, real-world scenarios               │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                   ALL PREVIOUS LEVELS COME TOGETHER                     ││
│  │                                                                         ││
│  │  PostgreSQL: Why choose it?                                             ││
│  │  ├── Level 1: Uses B-Tree (read-optimized)                              ││
│  │  ├── Level 2: Strong ACID, full isolation levels                        ││
│  │  ├── Level 3: Streaming replication (but limited sharding)              ││
│  │  └── Level 4: Logical replication for CDC                               ││
│  │                                                                         ││
│  │  Cassandra: Why choose it?                                              ││
│  │  ├── Level 1: Uses LSM-Tree (write-optimized)                           ││
│  │  ├── Level 2: Tunable consistency (not full ACID)                       ││
│  │  ├── Level 3: Built for distribution, peer-to-peer                      ││
│  │  └── Level 4: CDC via Debezium connector                                ││
│  │                                                                         ││
│  │  Redis: Why choose it?                                                  ││
│  │  ├── Level 1: In-memory (no disk I/O for reads!)                        ││
│  │  ├── Level 2: No transactions (simple key-value)                        ││
│  │  ├── Level 3: Cluster mode, async replication (AP system)               ││
│  │  └── Level 4: Pub/Sub for real-time fan-out                             ││
│  │                                                                         ││
│  │  Kafka: Why choose it?                                                  ││
│  │  ├── Level 1: Append-only log (sequential writes)                       ││
│  │  ├── Level 2: Ordering within partition                                 ││
│  │  ├── Level 3: Distributed, replicated, fault-tolerant                   ││
│  │  └── Level 4: THE backbone for CDC and event streaming                  ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  THIS IS THE "SYSTEM DESIGN INTERVIEW" LEVEL:                               │
│  • "What database would you use for X?" - Answer with trade-offs           │
│  • "Why not use MongoDB?" - Explain CAP/PACELC implications                │
│  • "How would you scale this?" - Combine sharding + caching + queuing      │
│                                                                              │
│  APPLIES TO:                                                                │
│  ✅ Every system design question!                                           │
│  ✅ This is where you demonstrate senior-level thinking                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents
1. [PostgreSQL/MySQL](#1-postgresqlmysql)
2. [Cassandra](#2-cassandra)
3. [Redis](#3-redis)
4. [Kafka](#4-kafka)
5. [OLTP vs OLAP](#5-oltp-vs-olap)
6. [Blob/Object Storage](#6-blobobject-storage)
7. [Decision Matrix](#7-decision-matrix)

---

## 1. PostgreSQL/MySQL

### Core Identity

| Aspect | PostgreSQL | MySQL |
|--------|------------|-------|
| **Architecture** | Process-per-connection | Thread-per-connection |
| **MVCC** | Heap-based (needs VACUUM) | Undo log-based |
| **Replication** | Streaming (WAL) | Binary log |
| **JSON Support** | Native JSONB | JSON type |
| **Best For** | Complex queries, data integrity | High read throughput |

### CAP/PACELC Classification

```
SINGLE NODE: CA (no partition tolerance)
WITH SYNC REPLICATION: CP / PC/EC
  - During partition: Writes blocked (Consistency over Availability)
  - Normal operation: Consistent, higher latency (wait for replica ACK)

WITH ASYNC REPLICATION: AP / PA/EL  
  - During partition: Writes continue on primary
  - Normal operation: Fast, eventual consistency
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Financial transactions (ACID guarantees)
├── E-commerce orders (complex relations, consistency)
├── User accounts & authentication
├── Content management (rich queries)
└── Any system where consistency > availability

⚠️ LIMITATIONS:
├── Single-node write throughput ceiling (~10K-50K TPS)
├── Scaling reads: Add replicas
├── Scaling writes: Vertical only (or application-level sharding)
└── Not ideal for: Time-series, IoT, massive write loads
```

### Interview Talking Points

```
"We use PostgreSQL for our user and order data because:
1. ACID transactions ensure money never disappears
2. Foreign keys maintain referential integrity
3. Complex JOIN queries for reporting
4. JSONB for flexible attributes without schema changes

For scaling, we use:
- Read replicas for query distribution
- Connection pooling (PgBouncer) for efficiency
- Application-level sharding by tenant_id if needed"
```

---

## 2. Cassandra

### Core Identity

| Aspect | Details |
|--------|---------|
| **Data Model** | Wide-column (partition key + clustering columns) |
| **Storage** | LSM-Tree (write-optimized) |
| **Consistency** | Tunable (ONE to ALL) |
| **Replication** | Peer-to-peer, no single leader |
| **Query Language** | CQL (SQL-like, but limited) |

### CAP/PACELC Classification

```
CAP: AP (Available during Partition)
PACELC: PA/EL (Prioritize Availability and Low Latency)

Default consistency: LOCAL_ONE (fast but weak)
Can tune per-query: QUORUM, ALL (stronger but slower)

Trade-off knobs:
- Replication Factor: 3 (typical)
- Write Consistency: QUORUM = 2/3 must ACK
- Read Consistency: QUORUM = 2/3 must respond
- For strong consistency: R + W > N
```

### Data Model Design

```
CASSANDRA DESIGN PRINCIPLE: Model for your queries, not your entities

❌ WRONG (relational thinking):
  users(id, name, email)
  orders(id, user_id, product, time)
  
  SELECT * FROM orders WHERE user_id = 123 ORDER BY time DESC
  → Requires secondary index, SLOW

✅ RIGHT (query-driven):
  orders_by_user(user_id, time, order_id, product)
  PRIMARY KEY ((user_id), time)
  
  → user_id is partition key (data locality)
  → time is clustering column (sorted within partition)
  → Query is a single partition scan, FAST
```

### Primary Key = Partition Key + Clustering Key

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  📖 DETAILED COVERAGE: See Level 2 - Database Logic                        │
│     Section: "NoSQL Indexing: Partition Key, Clustering Key & Secondary    │
│              Indexes"                                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  QUICK REFERENCE:                                                            │
│                                                                              │
│  PRIMARY KEY ((partition_key), clustering_key1, clustering_key2, ...)       │
│               └──────┬──────┘  └──────────────────┬──────────────────┘      │
│                      │                            │                          │
│            PARTITION KEY                  CLUSTERING COLUMNS                 │
│         (WHERE data lives)              (HOW data is sorted)                │
│                                                                              │
│  ┌─────────────────────────┬─────────────────────────────────────────────┐  │
│  │     PARTITION KEY       │         CLUSTERING KEY                     │  │
│  ├─────────────────────────┼─────────────────────────────────────────────┤  │
│  │ Determines WHICH NODE   │ Determines SORT ORDER within partition     │  │
│  │ MUST be in WHERE (=)    │ Optional, enables range queries (<, >)     │  │
│  └─────────────────────────┴─────────────────────────────────────────────┘  │
│                                                                              │
│  EXAMPLE:                                                                    │
│  CREATE TABLE messages (                                                     │
│      chat_id UUID,                                                           │
│      sent_at TIMESTAMP,                                                      │
│      content TEXT,                                                           │
│      PRIMARY KEY ((chat_id), sent_at)  ← chat_id: partition, sent_at: sort  │
│  );                                                                          │
│                                                                              │
│  ✅ FAST: WHERE chat_id = 'abc' AND sent_at > '2024-01-15'                  │
│  ❌ SLOW: WHERE sent_at > '2024-01-15' (no partition key = cluster scan!)   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### DynamoDB Terminology

```
┌────────────────────┬────────────────────┬────────────────────┐
│     Cassandra      │      DynamoDB      │      Meaning       │
├────────────────────┼────────────────────┼────────────────────┤
│   Partition Key    │   Partition Key    │ Where data lives   │
│   Clustering Key   │    Sort Key        │ Order within       │
│   Primary Key      │   Composite Key    │ PK + SK together   │
│   Secondary Index  │   LSI / GSI        │ Query on non-keys  │
└────────────────────┴────────────────────┴────────────────────┘

👉 For full coverage of LSI vs GSI trade-offs, see Level 2 - Database Logic.
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Time-series data (IoT sensors, metrics)
├── Event logging (append-only, massive scale)
├── Messaging (inbox per user)
├── Recommendations (pre-computed per user)
└── Any write-heavy, read-by-key workload

⚠️ LIMITATIONS:
├── No JOINs (denormalize everything)
├── No ad-hoc queries (design tables per query)
├── Deletes are expensive (tombstones)
├── Secondary indexes are limited
└── Not for: Complex queries, small datasets, strong consistency
```

### Interview Talking Points

```
"We use Cassandra for our event logging because:
1. 100K+ writes/sec across the cluster
2. Time-series data is append-only (LSM-Tree sweet spot)
3. Partition by (device_id, day) for data locality
4. Cluster by timestamp for efficient range queries
5. Tunable consistency: ONE for writes, QUORUM for reads

We accept eventual consistency because:
- Logs don't need strong consistency
- We can tolerate seconds of lag
- Availability is more important than perfect ordering"
```

---

## 3. Redis

### Core Identity

| Aspect | Details |
|--------|---------|
| **Storage** | In-memory (with optional persistence) |
| **Data Structures** | Strings, Lists, Sets, Hashes, Sorted Sets, Streams |
| **Latency** | Sub-millisecond |
| **Persistence** | RDB (snapshots) / AOF (append-only log) |
| **Clustering** | Redis Cluster (hash slots) |

### CAP/PACELC Classification

```
CAP: AP (in cluster mode, prioritizes availability)
PACELC: PA/EL (fast and available)

Replication: Asynchronous
- Primary failure can lose recent writes
- For durability: Use WAIT command or accept loss

Cluster mode:
- 16384 hash slots distributed across nodes
- Client routes to correct node
- Failover via Sentinel or Cluster consensus
```

### Key Patterns

```
1. CACHE-ASIDE
   ┌─────────────────────────────────────────────┐
   │ read(key):                                  │
   │   value = redis.get(key)                    │
   │   if value is None:                         │
   │       value = db.query(key)                 │
   │       redis.setex(key, TTL, value)          │
   │   return value                              │
   └─────────────────────────────────────────────┘

2. WRITE-THROUGH
   ┌─────────────────────────────────────────────┐
   │ write(key, value):                          │
   │   db.insert(key, value)                     │
   │   redis.set(key, value)                     │
   └─────────────────────────────────────────────┘

3. RATE LIMITING (Sliding Window)
   ┌─────────────────────────────────────────────┐
   │ ZADD rate:{ip} {timestamp} {request_id}     │
   │ ZREMRANGEBYSCORE rate:{ip} 0 {timestamp-60} │
   │ ZCARD rate:{ip} → count in last 60 seconds  │
   └─────────────────────────────────────────────┘

4. DISTRIBUTED LOCK (Redlock)
   ┌─────────────────────────────────────────────┐
   │ SET lock:{resource} {token} NX PX 30000     │
   │ # NX = only if not exists                   │
   │ # PX = expire in 30 seconds                 │
   │ # token = unique identifier for unlock      │
   └─────────────────────────────────────────────┘

5. LEADERBOARD (Sorted Set)
   ┌─────────────────────────────────────────────┐
   │ ZADD leaderboard {score} {user_id}          │
   │ ZREVRANGE leaderboard 0 9 WITHSCORES        │
   │ → Top 10 users by score, O(log N) updates   │
   └─────────────────────────────────────────────┘
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Caching (session, query results, computed data)
├── Rate limiting
├── Leaderboards / rankings
├── Real-time analytics counters
├── Pub/Sub messaging
├── Distributed locks
└── Queue (with Redis Streams)

⚠️ LIMITATIONS:
├── Data must fit in memory (expensive at scale)
├── Persistence options have trade-offs
├── Not a primary data store
├── Cluster adds complexity
└── Not for: Large datasets, durable storage, complex queries
```

### Interview Talking Points

```
"We use Redis as our caching layer because:
1. Sub-millisecond latency for hot data
2. Reduces database load by 90%+
3. Native data structures (sorted sets for leaderboards)
4. TTL-based expiration for cache freshness

Cache invalidation strategy:
- CDC events trigger cache deletes
- TTL as a safety net
- Cache-aside for reads

For distributed locks:
- Redlock algorithm across 5 Redis instances
- Fencing tokens for safety"
```

---

## 4. Kafka

### Core Identity

| Aspect | Details |
|--------|---------|
| **Model** | Distributed commit log |
| **Storage** | Append-only log on disk |
| **Ordering** | Per-partition ordering guaranteed |
| **Retention** | Time-based or size-based |
| **Consumers** | Pull-based with consumer groups |

### Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                         KAFKA CLUSTER                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  TOPIC: orders (3 partitions, replication factor 2)                 │
│                                                                      │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │ Partition 0 │  │ Partition 1 │  │ Partition 2 │                 │
│  │ Leader: B1  │  │ Leader: B2  │  │ Leader: B3  │                 │
│  │ Replica: B2 │  │ Replica: B3 │  │ Replica: B1 │                 │
│  └─────────────┘  └─────────────┘  └─────────────┘                 │
│        │                │                │                          │
│        ▼                ▼                ▼                          │
│  [msg1,msg4,...]  [msg2,msg5,...]  [msg3,msg6,...]                  │
│                                                                      │
│  PARTITIONING:                                                       │
│  • Default: Round-robin                                             │
│  • With key: hash(key) % num_partitions                             │
│  • Messages with same key → same partition → ordering guaranteed    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Consumer Groups

```
CONSUMER GROUP: order-processors

┌─────────────────────────────────────────────────────────────────────┐
│                                                                      │
│  Partition 0 ──────► Consumer A                                     │
│  Partition 1 ──────► Consumer B                                     │
│  Partition 2 ──────► Consumer C                                     │
│                                                                      │
│  • Each partition assigned to ONE consumer in a group               │
│  • Add consumers (up to partition count) for parallelism            │
│  • Consumer failure → partition reassigned to another               │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘

MULTIPLE GROUPS (Fan-out):

Topic: orders
├── Consumer Group: analytics-pipeline
│   └── Reads all messages for analytics
├── Consumer Group: notification-service  
│   └── Reads all messages for emails
└── Consumer Group: inventory-service
    └── Reads all messages to update stock
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Event sourcing (immutable event log)
├── Stream processing (real-time pipelines)
├── Microservices communication (decoupling)
├── Log aggregation
├── CDC destination (from Debezium)
└── Message replay (re-process historical events)

⚠️ LIMITATIONS:
├── Not a database (no queries, only sequential read)
├── Not for request-response (use HTTP)
├── Ordering only within partition
├── Operational complexity (Zookeeper/KRaft, brokers)
└── Latency higher than direct calls
```

### Interview Talking Points

```
"We use Kafka as the backbone of our event-driven architecture:

1. Decoupling: Services publish events, don't know consumers
2. Durability: Events stored for 7 days, replay if needed
3. Scaling: Add partitions for throughput, consumers for parallelism
4. Ordering: Partition by user_id so user events processed in order

Key patterns:
- Outbox pattern: Write to DB + outbox, Debezium → Kafka
- Saga orchestration: Kafka connects saga steps
- CQRS: Commands via HTTP, events via Kafka to read model"
```

---

## 5. OLTP vs OLAP

### The Two Worlds of Data Processing

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OLTP vs OLAP                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  OLTP (Online Transaction Processing)                                       │
│  ─────────────────────────────────────────────────────────────────────────── │
│  "The application database"                                                  │
│                                                                              │
│  • INSERT this order                                                         │
│  • UPDATE user's email                                                       │
│  • SELECT user WHERE id = 123                                               │
│                                                                              │
│  Characteristics:                                                            │
│  • Short queries, few rows affected                                         │
│  • High concurrency (1000s of users)                                        │
│  • Low latency required (ms)                                                │
│  • Row-oriented storage                                                      │
│  • Normalized schema (3NF)                                                   │
│                                                                              │
│  Examples: PostgreSQL, MySQL, Oracle                                        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  OLAP (Online Analytical Processing)                                        │
│  ─────────────────────────────────────────────────────────────────────────── │
│  "The analytics database"                                                    │
│                                                                              │
│  • What was total revenue last quarter by region?                           │
│  • Which products have declining sales trend?                               │
│  • Customer cohort analysis over 2 years                                    │
│                                                                              │
│  Characteristics:                                                            │
│  • Long queries, millions of rows scanned                                   │
│  • Low concurrency (few analysts)                                           │
│  • High latency acceptable (seconds to minutes)                             │
│  • Column-oriented storage                                                   │
│  • Denormalized schema (Star/Snowflake)                                     │
│                                                                              │
│  Examples: Snowflake, BigQuery, Redshift, ClickHouse                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Side-by-Side Comparison

| Aspect | OLTP | OLAP |
|--------|------|------|
| **Purpose** | Run the business | Analyze the business |
| **Users** | Customers, apps | Analysts, data scientists |
| **Queries** | Simple, predefined | Complex, ad-hoc |
| **Data Size** | GBs to TBs | TBs to PBs |
| **Freshness** | Real-time | Hourly/daily batch |
| **Schema** | Normalized (3NF) | Denormalized (Star) |
| **Storage** | Row-oriented | Column-oriented |
| **Concurrency** | 1000s | 10s |
| **Latency** | Milliseconds | Seconds to minutes |

### Row vs Column Storage

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ROW vs COLUMN STORAGE                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TABLE: sales (id, date, product, region, amount)                           │
│                                                                              │
│  ROW-ORIENTED (OLTP - PostgreSQL, MySQL):                                   │
│  ─────────────────────────────────────────────────────────────────────────── │
│  Stored as: [row1_all_columns][row2_all_columns][row3_all_columns]...       │
│                                                                              │
│  Disk:                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ 1|2024-01-15|Widget|US|100 │ 2|2024-01-15|Gadget|EU|200 │ ...      │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ✅ GOOD FOR: Fetch entire row (SELECT * WHERE id=1)                        │
│  ❌ BAD FOR: Aggregate one column (SUM(amount) for 1B rows)                 │
│              Must read ALL columns to get one column!                       │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  COLUMN-ORIENTED (OLAP - ClickHouse, Redshift, Parquet):                    │
│  ─────────────────────────────────────────────────────────────────────────── │
│  Stored as: [all_ids][all_dates][all_products][all_regions][all_amounts]    │
│                                                                              │
│  Disk:                                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐    │
│  │ IDs:     1, 2, 3, 4, 5, ...                                         │    │
│  │ Dates:   2024-01-15, 2024-01-15, 2024-01-16, ...                    │    │
│  │ Products: Widget, Gadget, Widget, ...                               │    │
│  │ Regions: US, EU, US, ...                                            │    │
│  │ Amounts: 100, 200, 150, ...  ← Only read this for SUM!              │    │
│  └─────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ✅ GOOD FOR: Aggregate queries (read only columns needed)                  │
│  ✅ GOOD FOR: Compression (similar values together)                         │
│  ❌ BAD FOR: Fetch single row (must read from many files)                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Data Warehouse Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TYPICAL DATA WAREHOUSE FLOW                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SOURCES              ETL/ELT           WAREHOUSE          CONSUMPTION       │
│                                                                              │
│  ┌──────────┐        ┌──────────┐      ┌──────────────┐   ┌──────────┐     │
│  │ PostgreSQL│──┐    │          │      │              │   │ Dashboards│     │
│  │  (OLTP)   │  │    │  Spark   │      │  Snowflake   │   │ (Tableau) │     │
│  └──────────┘  │    │  Airflow │      │  Redshift    │──►│           │     │
│                │───►│  dbt     │─────►│  BigQuery    │   └──────────┘     │
│  ┌──────────┐  │    │          │      │              │                     │
│  │   Kafka   │──┤    └──────────┘      │  ClickHouse  │   ┌──────────┐     │
│  │  (Events) │  │                      │              │   │ ML Models│     │
│  └──────────┘  │                       └──────────────┘──►│           │     │
│                │                                          └──────────┘     │
│  ┌──────────┐  │                                                           │
│  │   S3     │──┘                                                           │
│  │  (Logs)  │                                                              │
│  └──────────┘                                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### HDFS & Big Data Storage

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HDFS (Hadoop Distributed File System)                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT: Distributed file system for storing massive datasets                 │
│  WHEN: Petabyte-scale storage, batch processing                             │
│                                                                              │
│  ARCHITECTURE:                                                               │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                       NAMENODE (Master)                                 ││
│  │           Stores metadata: which blocks are where                       ││
│  │           Single point of failure (use HA NameNode!)                    ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                    /              |              \                          │
│                   /               |               \                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │
│  │   DATANODE 1    │  │   DATANODE 2    │  │   DATANODE 3    │             │
│  │ Block A (copy 1)│  │ Block A (copy 2)│  │ Block A (copy 3)│             │
│  │ Block B (copy 1)│  │ Block C (copy 1)│  │ Block B (copy 2)│             │
│  └─────────────────┘  └─────────────────┘  └─────────────────┘             │
│                                                                              │
│  KEY CONCEPTS:                                                               │
│  • Files split into BLOCKS (default 128MB)                                  │
│  • Each block replicated 3x across DataNodes                                │
│  • Write-once, read-many (immutable files)                                  │
│  • "Move compute to data" (run jobs where data lives)                       │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  FILE FORMATS ON HDFS:                                                       │
│  ┌──────────────┬────────────────────────────────────────────────────────┐  │
│  │   Format     │   Description                                         │  │
│  ├──────────────┼────────────────────────────────────────────────────────┤  │
│  │   Parquet    │   Columnar, best for analytics, high compression      │  │
│  │   ORC        │   Columnar, optimized for Hive, good compression      │  │
│  │   Avro       │   Row-based, good for streaming, schema evolution     │  │
│  │   CSV/JSON   │   Human-readable, poor performance, avoid at scale    │  │
│  └──────────────┴────────────────────────────────────────────────────────┘  │
│                                                                              │
│  MODERN ALTERNATIVES:                                                        │
│  • S3 + query engines (Spark, Presto, Athena) replacing HDFS               │
│  • Delta Lake, Iceberg, Hudi (ACID on object storage)                       │
│  • Databricks, Snowflake (managed solutions)                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### OLAP Databases Comparison

| Database | Type | Best For |
|----------|------|----------|
| **ClickHouse** | Column-store | Real-time analytics, time-series |
| **Snowflake** | Cloud DW | General analytics, variable workloads |
| **BigQuery** | Serverless | Ad-hoc queries, Google ecosystem |
| **Redshift** | Cloud DW | AWS ecosystem, Postgres-compatible |
| **Druid** | Real-time OLAP | Sub-second queries on streaming data |
| **Presto/Trino** | Query engine | Federated queries across sources |
| **Spark SQL** | Batch processing | ETL, ML pipelines |

---

## 6. Blob/Object Storage

### What is Object Storage?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OBJECT STORAGE vs FILE STORAGE                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  FILE STORAGE (NFS, EFS):                                                   │
│  ─────────────────────────────────────────────────────────────────────────── │
│  /home/                                                                      │
│  ├── user1/                                                                  │
│  │   ├── documents/                                                         │
│  │   │   └── report.pdf                                                     │
│  │   └── photos/                                                            │
│  │       └── vacation.jpg                                                   │
│  └── user2/                                                                  │
│                                                                              │
│  • Hierarchical (directories and files)                                     │
│  • Supports file locking, permissions                                        │
│  • Limited scalability                                                       │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  OBJECT STORAGE (S3, GCS, Azure Blob):                                      │
│  ─────────────────────────────────────────────────────────────────────────── │
│  Bucket: my-app-data                                                         │
│  Objects:                                                                    │
│    • Key: "users/123/profile.jpg"  → Binary data + metadata                 │
│    • Key: "users/123/resume.pdf"   → Binary data + metadata                 │
│    • Key: "logs/2024/01/15/app.log"→ Binary data + metadata                 │
│                                                                              │
│  • Flat namespace (key → value)                                             │
│  • "/" in key is just a character (no real directories!)                    │
│  • Immutable objects (versioning optional)                                  │
│  • Virtually unlimited scalability                                          │
│  • Eventual consistency (historically, now often strong)                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### When to Use Object Storage

```
✅ PERFECT FOR:
├── User-generated content (images, videos, documents)
├── Static assets (JS, CSS, images for web apps)
├── Backups and archives
├── Data lake storage (Parquet files for analytics)
├── Log storage
└── ML training data and models

❌ NOT FOR:
├── Transactional data (use a database)
├── Frequently updated small files (high latency)
├── Data requiring file locking
└── Low-latency access patterns (consider caching)
```

### Object Storage Services Comparison

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    OBJECT STORAGE OPTIONS                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  AWS S3 (Simple Storage Service)                                            │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • The "standard" (S3 API is de-facto industry standard)                    │
│  • Storage classes: Standard, Infrequent Access, Glacier (archival)         │
│  • Strong consistency (as of Dec 2020)                                      │
│  • Integrates with entire AWS ecosystem                                     │
│  • Pricing: ~$0.023/GB/month (Standard)                                     │
│                                                                              │
│  Google Cloud Storage (GCS)                                                  │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • S3-compatible API available                                              │
│  • Storage classes: Standard, Nearline, Coldline, Archive                   │
│  • Strong consistency                                                        │
│  • Best for: Google Cloud / BigQuery users                                  │
│                                                                              │
│  Azure Blob Storage                                                          │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • Hot, Cool, Archive tiers                                                 │
│  • Best for: Azure ecosystem, Microsoft shops                               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  LinkedIn Ambry                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • LinkedIn's open-source blob storage                                      │
│  • Designed for immutable media (images, videos)                            │
│  • Low latency, high throughput                                             │
│  • On-premise, not a cloud service                                          │
│  • Use case: Social media platforms with massive media                      │
│                                                                              │
│  MinIO                                                                       │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • S3-compatible, self-hosted object storage                                │
│  • Open source, can run on Kubernetes                                       │
│  • Use case: On-premise, hybrid cloud, development                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### S3 Access Patterns

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    COMMON S3 PATTERNS                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. DIRECT UPLOAD (Pre-signed URLs)                                         │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Client ──► Your API ──► Generate Pre-signed URL                            │
│    │                            │                                            │
│    │         ┌──────────────────┘                                            │
│    │         │                                                               │
│    │         ▼                                                               │
│    └──────► S3 (direct upload, bypasses your server)                        │
│                                                                              │
│  WHY: Don't proxy large files through your servers                          │
│                                                                              │
│  2. CDN IN FRONT (CloudFront + S3)                                          │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Client ──► CloudFront (CDN) ──► S3 (origin)                                │
│              │                                                               │
│              └── Caches at edge locations worldwide                          │
│                                                                              │
│  WHY: Reduce latency, reduce S3 costs (fewer requests)                      │
│                                                                              │
│  3. LIFECYCLE POLICIES                                                       │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Day 0:   Object in S3 Standard ($0.023/GB)                                 │
│  Day 30:  Auto-transition to Infrequent Access ($0.0125/GB)                 │
│  Day 90:  Auto-transition to Glacier ($0.004/GB)                            │
│  Day 365: Auto-delete                                                        │
│                                                                              │
│  WHY: Automatic cost optimization for aging data                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Interview Talking Points

```
"For user-uploaded images, we:
1. Generate a pre-signed S3 URL (client uploads directly)
2. Store metadata (user_id, filename, size) in PostgreSQL
3. Serve via CloudFront CDN for low latency
4. Use lifecycle policies: Standard → IA after 30 days → Glacier after 1 year

For our data lake:
1. Store raw events in S3 as Parquet files (columnar, compressed)
2. Partition by date: s3://data-lake/events/year=2024/month=01/day=15/
3. Query with Athena/Spark (no data movement)
4. Use Delta Lake for ACID transactions on object storage"
```

---

## 7. Decision Matrix

### Quick Reference Table

| Requirement | PostgreSQL | Cassandra | Redis | Kafka |
|-------------|------------|-----------|-------|-------|
| ACID Transactions | ✅ | ❌ | ❌ | ❌ |
| Complex Queries | ✅ | ❌ | ❌ | ❌ |
| High Write Throughput | ⚠️ | ✅ | ✅ | ✅ |
| Low Latency Reads | ⚠️ | ⚠️ | ✅ | ❌ |
| Horizontal Scale | ⚠️ | ✅ | ⚠️ | ✅ |
| Durability | ✅ | ✅ | ⚠️ | ✅ |
| Global Distribution | ⚠️ | ✅ | ⚠️ | ⚠️ |
| Message Replay | ❌ | ❌ | ❌ | ✅ |

### Scenario-Based Selection

```
SCENARIO 1: E-Commerce Platform
├── User accounts: PostgreSQL (ACID, complex queries)
├── Product catalog: PostgreSQL + Elasticsearch (search)
├── Shopping cart: Redis (fast, ephemeral)
├── Order processing: PostgreSQL (transactions)
├── Order events: Kafka (decouple inventory, shipping)
└── Session storage: Redis (fast, TTL)

SCENARIO 2: Social Media Feed
├── User profiles: PostgreSQL (relations, consistency)
├── Posts: PostgreSQL (source of truth)
├── Timeline cache: Redis (pre-computed feeds)
├── Post events: Kafka (fan-out workers)
├── Activity stream: Cassandra (time-series, write-heavy)
└── Real-time updates: Redis Pub/Sub → WebSocket

SCENARIO 3: IoT Sensor Platform
├── Device registry: PostgreSQL (relations)
├── Sensor readings: Cassandra (massive writes, time-series)
├── Real-time dashboard: Redis (aggregations)
├── Event pipeline: Kafka (sensor → processing → storage)
└── Alerts: Kafka → Alert service

SCENARIO 4: Financial Trading
├── Accounts/Balances: PostgreSQL (ACID!)
├── Trade execution: PostgreSQL (serializable transactions)
├── Market data: Kafka (streaming prices)
├── Order book cache: Redis (low latency)
├── Audit log: Cassandra (append-only)
└── Real-time prices: Kafka → WebSocket
```

---

## Interview Checklist

### Questions You Should Be Able to Answer

- [ ] "Why PostgreSQL and not Cassandra for user accounts?"
- [ ] "When would you choose Cassandra over PostgreSQL?"
- [ ] "Why use Redis when PostgreSQL can also cache?"
- [ ] "What's the difference between Redis and Kafka Pub/Sub?"
- [ ] "How would you design a system using all four?"

### Common Mistakes

| Mistake | Why It's Wrong |
|---------|----------------|
| "Kafka as a database" | Kafka is a log, not for queries |
| "Redis as primary storage" | Memory-only, use as cache layer |
| "Cassandra for transactions" | No ACID, use PostgreSQL |
| "PostgreSQL for 1M writes/sec" | Single-node limit, use Cassandra |

---

## Next Steps

Continue to **[Level 6: Senior Gotchas](06_SENIOR_GOTCHAS.md)** for edge case interview questions.

