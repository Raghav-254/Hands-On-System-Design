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
│  │                                                                         ││
│  │  Time-Series (InfluxDB/Prometheus): Why choose it?                      ││
│  │  ├── Level 1: Columnar, time-partitioned storage                        ││
│  │  ├── Level 2: Optimized for append-only time-stamped data               ││
│  │  ├── Level 3: Horizontal scaling, retention policies                    ││
│  │  └── Level 4: Metrics → alerting → dashboards pipeline                  ││
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
1. [Database Types Overview](#1-database-types-overview)
2. [PostgreSQL/MySQL (Relational)](#2-postgresqlmysql-relational)
3. [Cassandra (Wide-Column)](#3-cassandra-wide-column)
4. [Redis (Key-Value & Caching)](#4-redis-key-value--caching)
5. [Kafka (Message Streaming)](#5-kafka-message-streaming)
6. [Time-Series Databases](#6-time-series-databases)
7. [OLTP vs OLAP](#7-oltp-vs-olap)
8. [Blob/Object Storage](#8-blobobject-storage)
9. [Decision Matrix](#9-decision-matrix)

---

## 1. Database Types Overview

> Quick reference for database types. Detailed deep dives are in dedicated sections below.

### The Database Landscape

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DATABASE TYPES BY DATA MODEL                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │                    SQL (Relational)                                │   │
│  │                    ─────────────────                               │   │
│  │                           │                                         │   │
│  │         ┌─────────────────┼─────────────────┐                      │   │
│  │         │                 │                 │                      │   │
│  │         ▼                 ▼                 ▼                      │   │
│  │    PostgreSQL          MySQL           Oracle                      │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │                    NoSQL (Non-Relational)                          │   │
│  │                    ──────────────────────                          │   │
│  │                           │                                         │   │
│  │    ┌──────────┬───────────┼───────────┬────────────┐               │   │
│  │    │          │           │           │            │               │   │
│  │    ▼          ▼           ▼           ▼            ▼               │   │
│  │ Key-Value  Document  Wide-Column   Graph     Time-Series          │   │
│  │                                                                     │   │
│  │  Redis     MongoDB    Cassandra    Neo4j      InfluxDB            │   │
│  │  DynamoDB  Couchbase  HBase        Neptune    TimescaleDB         │   │
│  │  Memcached            Bigtable                Prometheus          │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │                    Specialized                                     │   │
│  │                    ───────────                                     │   │
│  │                           │                                         │   │
│  │         ┌─────────────────┼─────────────────┐                      │   │
│  │         │                 │                 │                      │   │
│  │         ▼                 ▼                 ▼                      │   │
│  │     Search           Message Queue       NewSQL                   │   │
│  │   Elasticsearch        Kafka          CockroachDB                 │   │
│  │   Solr                 RabbitMQ       Spanner                     │   │
│  │   Algolia              Kinesis        YugabyteDB                  │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Database Types Comparison

| Type | Examples | Data Model | Best For | Not For |
|------|----------|------------|----------|---------|
| **Relational (SQL)** | PostgreSQL, MySQL, Oracle | Tables with rows & columns, relationships via JOINs | Complex queries, ACID transactions, data integrity, JOINs | Unstructured data, horizontal scaling, schema changes |
| **Key-Value** | Redis, DynamoDB, Memcached | Simple key → value pairs | Caching, sessions, simple lookups, counters | Complex queries, relationships, range scans |
| **Document** | MongoDB, Couchbase, Firestore | JSON-like documents, nested data | Flexible schema, content management, catalogs | Heavy JOINs, strict schema enforcement |
| **Wide-Column** | Cassandra, HBase, Bigtable | Rows with dynamic columns, column families | Time-series, write-heavy, IoT, logs, analytics | Ad-hoc queries, JOINs, strong consistency |
| **Graph** | Neo4j, Amazon Neptune, ArangoDB | Nodes and edges (relationships) | Social networks, recommendations, fraud detection | Simple CRUD, tabular data, analytics |
| **Time-Series** | InfluxDB, TimescaleDB, Prometheus | Timestamped data points | Metrics, monitoring, IoT, financial data | General purpose, relationships |
| **Search** | Elasticsearch, Solr, Algolia | Inverted index for full-text search | Full-text search, log analytics, autocomplete | Primary data store, transactions |
| **NewSQL** | CockroachDB, Spanner, TiDB | Relational + distributed | SQL + horizontal scale, global distribution | Simple apps (overkill), cost-sensitive |

### When to Use What: Decision Flowchart

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHOOSING A DATABASE TYPE                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  START: What's your PRIMARY access pattern?                                │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Need complex JOINs and ACID transactions?                                 │
│  ├── YES → Relational (PostgreSQL, MySQL)                                  │
│  │         └── Need global scale? → NewSQL (CockroachDB, Spanner)          │
│  │                                                                          │
│  └── NO → What's your data structure?                                      │
│           │                                                                 │
│           ├── Simple key → value lookups?                                  │
│           │   └── Key-Value (Redis for cache, DynamoDB for persistence)   │
│           │                                                                 │
│           ├── Nested/hierarchical documents?                               │
│           │   └── Document (MongoDB, Couchbase)                            │
│           │                                                                 │
│           ├── Time-stamped events/metrics?                                 │
│           │   └── Wide-Column (Cassandra) or Time-Series (InfluxDB)       │
│           │                                                                 │
│           ├── Complex relationships to traverse?                           │
│           │   └── Graph (Neo4j, Neptune)                                   │
│           │                                                                 │
│           └── Full-text search needed?                                     │
│               └── Search (Elasticsearch) + another DB as primary          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

> 👉 **Deep dives for Key-Value (Redis), Wide-Column (Cassandra), and Time-Series are in dedicated sections below.**
> 
> Document (MongoDB) and Graph (Neo4j) databases are less common in system design interviews but useful for specific use cases like content management and social networks respectively.

### Common Multi-Database Architectures

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    POLYGLOT PERSISTENCE                                      │
│         "Use the right database for each use case"                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  EXAMPLE: E-Commerce Platform                                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │                         Application                                │   │
│  │                             │                                       │   │
│  │     ┌───────────┬───────────┼───────────┬───────────┐              │   │
│  │     │           │           │           │           │              │   │
│  │     ▼           ▼           ▼           ▼           ▼              │   │
│  │                                                                     │   │
│  │  PostgreSQL   Redis     Elasticsearch  Cassandra   S3             │   │
│  │  ──────────   ─────     ─────────────  ─────────   ──             │   │
│  │  Users        Sessions  Product        Event       Product        │   │
│  │  Orders       Cart      Search         Logs        Images         │   │
│  │  Payments     Cache                    Analytics                  │   │
│  │  (ACID!)      (Fast!)   (Search!)     (Scale!)    (Files!)       │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  WHY MULTIPLE DATABASES?                                                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • PostgreSQL: ACID for money-related operations                           │
│  • Redis: Sub-millisecond cache, session storage                          │
│  • Elasticsearch: Full-text search, autocomplete                          │
│  • Cassandra: High-volume event logging, no JOINs needed                  │
│  • S3: Cheap storage for large files                                      │
│                                                                              │
│  KEPT IN SYNC VIA:                                                          │
│  • CDC (Debezium) for PostgreSQL → Kafka → Elasticsearch                  │
│  • Application-level writes to multiple stores                            │
│  • Event-driven architecture                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. PostgreSQL/MySQL (Relational)

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
├── Read-heavy workloads (B-Tree = fast point lookups & range scans)
│   → B-Tree: O(log n) reads, data is sorted, sequential I/O for ranges
│   → Index-organized: clustered index keeps related data together
└── Any system where consistency > availability

⚠️ LIMITATIONS:
├── Single-node write throughput ceiling (~10K-50K TPS)
│   → B-Tree: Writes require in-place updates, random I/O
│   → Every write may trigger page splits, rebalancing
├── Scaling reads: Add replicas
├── Scaling writes: Vertical only (or application-level sharding)
└── Not ideal for: Time-series, IoT, massive write loads (use LSM-Tree DBs)
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
  • Connection pooling = reuse DB connections instead of opening/closing per request
  • PgBouncer = popular connection pooler (sits between app and DB)
  • Essential for: microservices, serverless, high-concurrency apps
- Application-level sharding by tenant_id if needed"
```

---

## 3. Cassandra (Wide-Column)

### Core Identity

| Aspect | Details |
|--------|---------|
| **Data Model** | Wide-column (partition key + clustering columns) |
| **Storage** | LSM-Tree (write-optimized) |
| **Consistency** | Tunable (ONE to ALL) |
| **Replication** | Peer-to-peer, no single leader |
| **Query Language** | CQL (SQL-like, but limited) |

### Why "Wide-Column"?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WIDE-COLUMN EXPLAINED                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  RELATIONAL (Fixed Schema):                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Every row MUST have the same columns:                                      │
│                                                                              │
│  | user_id | name  | email           | phone       |                       │
│  |---------|-------|-----------------|-------------|                       │
│  | 1       | Alice | alice@mail.com  | 555-1234    |                       │
│  | 2       | Bob   | bob@mail.com    | 555-5678    |                       │
│  | 3       | Carol | carol@mail.com  | NULL        | ← Must have column!   │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  WIDE-COLUMN (Dynamic Columns):                                             │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Each row can have DIFFERENT columns, and MANY of them (thousands!):       │
│                                                                              │
│  Row Key: "user:1"                                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ name:Alice │ email:alice@mail.com │ phone:555-1234 │ age:30 │ ...  │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Row Key: "user:2"                                                          │
│  ┌──────────────────────────────────────────────────────────┐              │
│  │ name:Bob │ email:bob@mail.com │ twitter:@bob │           │ ← Different! │
│  └──────────────────────────────────────────────────────────┘              │
│                                                                              │
│  Row Key: "sensor:001:2024-01-15"                                          │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ 00:00:temp:72 │ 00:00:humid:45 │ 00:01:temp:73 │ 00:01:humid:46 │...│   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│  ↑ This row could have THOUSANDS of columns (one per timestamp)!           │
│                                                                              │
│  WHY "WIDE"?                                                                │
│  • Rows can grow "wide" with many columns                                  │
│  • Columns are dynamic (add new ones anytime, no ALTER TABLE)              │
│  • Think of it as: row_key → { column_name: value, column_name: value }    │
│  • Essentially a 2D key-value store                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

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

### DynamoDB Comparison

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DYNAMODB: KEY-VALUE vs WIDE-COLUMN                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DynamoDB supports TWO modes:                                               │
│                                                                              │
│  1. PURE KEY-VALUE (simple):                                                │
│     ─────────────────────────────────────────────────────────────────────   │
│     Only partition key, no sort key. Like Redis.                           │
│                                                                              │
│     Key: "user:123" → Value: {name: "Alice", email: "alice@..."}           │
│     Key: "session:abc" → Value: {userId: 123, expires: "..."}              │
│                                                                              │
│     ✅ Simple lookups: GetItem("user:123")                                  │
│     ❌ No range queries (no sort key)                                       │
│                                                                              │
│  2. WIDE-COLUMN (with sort key):                                            │
│     ─────────────────────────────────────────────────────────────────────   │
│     Partition key + sort key. Like Cassandra.                              │
│                                                                              │
│     Partition: "user:123"                                                   │
│     ├── Sort: "order#2024-01-15" → {total: 99.99, items: [...]}            │
│     ├── Sort: "order#2024-01-20" → {total: 149.50, status: "shipped"}      │
│     └── Sort: "profile"          → {name: "Alice", tier: "gold"}           │
│                                                                              │
│     ✅ Range queries on sort key                                            │
│     ✅ Multiple item types in same partition (single-table design)         │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  DynamoDB is often called "key-value" but with sort key it's wide-column.  │
│  Similar concepts to Cassandra, different terminology:                      │
│                                                                              │
│  ┌────────────────────┬────────────────────┬────────────────────┐          │
│  │     Cassandra      │      DynamoDB      │      Meaning       │          │
│  ├────────────────────┼────────────────────┼────────────────────┤          │
│  │   Partition Key    │   Partition Key    │ Where data lives   │          │
│  │   Clustering Key   │    Sort Key        │ Order within       │          │
│  │   Primary Key      │   Composite Key    │ PK + SK together   │          │
│  │   Secondary Index  │   LSI / GSI        │ Query on non-keys  │          │
│  └────────────────────┴────────────────────┴────────────────────┘          │
│                                                                              │
│  EXAMPLE: Orders Table                                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Table: orders                                                              │
│  Partition Key: customer_id                                                 │
│  Sort Key: order_date                                                       │
│                                                                              │
│  ┌─────────────────┬─────────────────┬──────────────────────────────────┐  │
│  │ customer_id(PK) │ order_date(SK)  │ Attributes (flexible schema)     │  │
│  ├─────────────────┼─────────────────┼──────────────────────────────────┤  │
│  │ user_123        │ 2024-01-15      │ {total: 99.99, items: [...]}     │  │
│  │ user_123        │ 2024-01-20      │ {total: 149.50, status: "shipped"}│  │
│  │ user_456        │ 2024-01-18      │ {total: 25.00, items: [...]}     │  │
│  └─────────────────┴─────────────────┴──────────────────────────────────┘  │
│                                                                              │
│  QUERIES:                                                                   │
│  ✅ FAST: Get all orders for user_123                                      │
│           → Single partition, sorted by date                               │
│  ✅ FAST: Get user_123's orders between Jan 10-20                          │
│           → Partition + sort key range query                               │
│  ❌ SLOW: Get all orders with total > $100                                 │
│           → Requires scan or GSI on 'total'                                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

👉 For full coverage of LSI vs GSI trade-offs, see Level 2 - Database Logic.
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Time-series data (IoT sensors, metrics)
├── Event logging (append-only, massive scale)
├── Messaging (inbox per user)
├── Recommendations (pre-computed per user)
├── Write-heavy workloads (LSM-Tree = blazing fast writes)
│   → LSM-Tree: Writes go to memtable (RAM), sequential disk flush
│   → No random I/O on writes, no page splits
│   → Can handle 100K+ writes/sec per node
└── Any write-heavy, read-by-key workload

⚠️ LIMITATIONS:
├── No JOINs (denormalize everything)
├── No ad-hoc queries (design tables per query)
├── Deletes are expensive (tombstones)
├── Secondary indexes are limited
├── Reads can be slower (LSM-Tree may check multiple SSTables)
│   → Point reads: check memtable + multiple SSTable levels
│   → Mitigated by bloom filters, but still slower than B-Tree
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

## 4. Redis (Key-Value & Caching)

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

### Scaling Redis

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HOW TO SCALE REDIS                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. VERTICAL SCALING (Scale Up)                                             │
│     ─────────────────────────────────────────────────────────────────────   │
│     • Add more RAM (Redis is memory-bound)                                 │
│     • Faster CPU helps with high ops/sec                                   │
│     • Limit: Single machine capacity                                       │
│                                                                              │
│  2. READ REPLICAS (Scale Reads)                                             │
│     ─────────────────────────────────────────────────────────────────────   │
│                                                                              │
│     ┌─────────────┐      ┌─────────────┐                                   │
│     │   Primary   │─────►│  Replica 1  │ ← Reads                           │
│     │  (Writes)   │─────►│  Replica 2  │ ← Reads                           │
│     │             │─────►│  Replica 3  │ ← Reads                           │
│     └─────────────┘      └─────────────┘                                   │
│                                                                              │
│     • Primary handles all writes                                           │
│     • Replicas handle read traffic                                         │
│     • Async replication (eventual consistency)                             │
│                                                                              │
│  3. REDIS CLUSTER (Scale Writes + Data)                                     │
│     ─────────────────────────────────────────────────────────────────────   │
│                                                                              │
│     ┌──────────────────────────────────────────────────────────────────┐   │
│     │                    16384 Hash Slots                               │   │
│     │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐              │   │
│     │  │  Node A     │  │  Node B     │  │  Node C     │              │   │
│     │  │ Slots 0-5460│  │Slots 5461-  │  │Slots 10923- │              │   │
│     │  │             │  │   10922     │  │   16383     │              │   │
│     │  └─────────────┘  └─────────────┘  └─────────────┘              │   │
│     └──────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│     • Data automatically sharded by key: slot = CRC16(key) % 16384         │
│     • Each node handles a subset of keys (horizontal scaling)              │
│     • Add more nodes → more capacity                                       │
│     • Client-side routing or proxy (Redis Cluster protocol)                │
│                                                                              │
│  4. REDIS SENTINEL (High Availability, not scaling)                        │
│     ─────────────────────────────────────────────────────────────────────   │
│     • Monitors primary, auto-promotes replica on failure                   │
│     • Not for scaling, just for failover                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Caching Patterns

| Pattern | One-Liner Definition |
|---------|----------------------|
| **Cache-Aside** | App checks cache first; on miss, fetches from DB and populates cache |
| **Write-Through** | App writes to DB and cache simultaneously (cache always up-to-date) |
| **Write-Behind** | App writes to cache only; cache asynchronously writes to DB (faster, riskier) |
| **Read-Through** | Cache itself fetches from DB on miss (app only talks to cache) |

### Key Patterns

```
1. CACHE-ASIDE (Lazy Loading)
   → App checks cache first; on miss, fetches from DB and populates cache
   ┌─────────────────────────────────────────────┐
   │ read(key):                                  │
   │   value = redis.get(key)                    │
   │   if value is None:                         │
   │       value = db.query(key)                 │
   │       redis.setex(key, TTL, value)          │
   │   return value                              │
   └─────────────────────────────────────────────┘
   ✅ Only caches what's needed
   ❌ First request always misses (cold cache)

2. WRITE-THROUGH
   → App writes to DB and cache simultaneously
   ┌─────────────────────────────────────────────┐
   │ write(key, value):                          │
   │   db.insert(key, value)                     │
   │   redis.set(key, value)                     │
   └─────────────────────────────────────────────┘
   ✅ Cache always up-to-date
   ❌ Write latency increases (two writes)
   ❌ May cache data that's never read

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

## 5. Kafka (Message Streaming)

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
│                                                                      │
│  LEADER vs REPLICA:                                                  │
│  • Leader: The broker that handles ALL reads/writes for a partition │
│  • Replica: Passive copy for fault tolerance (syncs from leader)    │
│  • If leader fails → a replica is promoted to new leader            │
│  • Producers/consumers only talk to the leader (by default)         │
│  • Replication factor 2 = 1 leader + 1 replica (survives 1 failure)                 │
│        │                │                │                          │
│        ▼                ▼                ▼                          │
│  [msg1,msg4,...]  [msg2,msg5,...]  [msg3,msg6,...]                  │
│                                                                      │
│  HOW PRODUCER DECIDES WHICH PARTITION TO SEND TO:                    │
│  • No key provided: Round-robin (distribute evenly across partitions)│
│    Message 1 → Partition 0                                           │
│    Message 2 → Partition 1                                           │
│    Message 3 → Partition 2                                           │
│    Message 4 → Partition 0 (cycles back)                             │
│    → Good for load balancing, but no ordering guarantee              │
│                                                                      │
│  PARTITIONING:                                                       │
│  • Default (no key): Round-robin (load balanced, no ordering)        │
│  • With key: hash(key) % partitions (same key → same partition)      │
│                                                                      │
│  ORDERING GUARANTEE:                                                 │
│  • Within a partition: GUARANTEED (append-only log, like WAL)        │
│    → Messages appended to end, never inserted in middle              │
│    → Consumer reads in exact order producer wrote                    │
│  • Across partitions: NO guarantee                                   │
│    → That's why you use a key when order matters!                    │
│                                                                      │
│  SUMMARY:                                                            │
│  • Default (no key): Round-robin                                             │
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

## 6. Time-Series Databases

> Optimized for timestamped data points - essential for monitoring, IoT, and analytics.

### Core Identity

| Aspect | Details |
|--------|---------|
| **Data Model** | Metric name + tags + timestamp + value |
| **Storage** | Columnar, time-partitioned, compressed |
| **Queries** | Time-range based aggregations |
| **Retention** | Automatic downsampling and expiration |
| **Ingestion** | High-throughput writes, append-only |

> **💡 Columnar Storage: Same as OLAP?**
> 
> **Similar concept** - both store data by columns (not rows) for better compression and aggregation performance.
> 
> | Aspect | OLAP Columnar (Snowflake, Redshift) | Time-Series (InfluxDB, Prometheus) |
> |--------|-------------------------------------|-------------------------------------|
> | **Optimized for** | Ad-hoc analytics across many dimensions | Time-based queries on metrics |
> | **Partitioning** | By various keys | By time (automatic) |
> | **Special features** | Complex JOINs, SQL | Downsampling, retention, rate(), derivative() |
> | **Compression** | General columnar | Time-specific (delta, gorilla encoding) |
> | **Ingestion** | Batch-oriented | High-frequency streaming |
> 
> **ClickHouse** blurs this line - it's an OLAP columnar DB that's also excellent for time-series!

### CAP/PACELC Classification

```
CAP: AP (typically, prioritize availability for metrics)
PACELC: PA/EL (fast writes, eventual consistency acceptable)

Most time-series DBs prioritize:
- High write throughput (millions of points/sec)
- Fast time-range queries
- Automatic data lifecycle management
```

### Data Model

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TIME-SERIES DATA MODEL                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  METRIC + TAGS + TIMESTAMP + VALUE                                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  cpu_usage{host="server-01", region="us-east"} @ 2024-01-15T10:00:00 = 45.2│
│  cpu_usage{host="server-01", region="us-east"} @ 2024-01-15T10:00:01 = 47.8│
│  cpu_usage{host="server-02", region="eu-west"} @ 2024-01-15T10:00:00 = 32.1│
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  COMPONENTS:                                                                │
│  ┌───────────────┬───────────────────────────────────────────────────────┐ │
│  │ Metric Name   │ What you're measuring (cpu_usage, temperature, etc.) │ │
│  ├───────────────┼───────────────────────────────────────────────────────┤ │
│  │ Tags/Labels   │ Dimensions to filter by (host, region, service)      │ │
│  ├───────────────┼───────────────────────────────────────────────────────┤ │
│  │ Timestamp     │ When the measurement occurred                        │ │
│  ├───────────────┼───────────────────────────────────────────────────────┤ │
│  │ Value         │ The measurement (usually numeric)                    │ │
│  └───────────────┴───────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Key Features

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    TIME-SERIES SPECIFIC FEATURES                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. AUTOMATIC DOWNSAMPLING                                                  │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Raw data (1-second intervals):                                             │
│  10:00:00 → 45.2                                                            │
│  10:00:01 → 47.8                                                            │
│  10:00:02 → 43.1                                                            │
│  ...                                                                         │
│                                                                              │
│  After 7 days → Downsample to 1-minute averages:                           │
│  10:00:00 → 45.4 (avg of 60 points)                                        │
│  10:01:00 → 46.2                                                            │
│                                                                              │
│  After 30 days → Downsample to 1-hour averages:                            │
│  10:00:00 → 45.8 (avg of 60 minutes)                                       │
│                                                                              │
│  BENEFIT: Keep years of data without exploding storage                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  2. RETENTION POLICIES                                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • Raw data: Keep 7 days, then delete                                      │
│  • 1-minute rollups: Keep 30 days                                          │
│  • 1-hour rollups: Keep 1 year                                             │
│  • Daily rollups: Keep forever                                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  3. TIME-BASED QUERIES                                                      │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  "Last 5 minutes":  WHERE time > now() - 5m                                │
│  "Today vs yesterday": Compare same hour, different day                    │
│  "90th percentile over 1 hour windows"                                     │
│  "Rate of change (derivative)"                                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Ideal Use Cases

```
✅ PERFECT FOR:
├── Application metrics (latency, error rates, throughput)
├── Infrastructure monitoring (CPU, memory, disk, network)
├── IoT sensor data (temperature, humidity, location)
├── Financial market data (stock prices, trades)
├── User analytics (DAU, session length, events over time)
└── Log metrics (request rates, error counts)

⚠️ LIMITATIONS:
├── Not for general-purpose queries (use a relational DB)
├── Limited JOIN support
├── High cardinality can be expensive
├── Not ideal for: User data, transactions, relationships
```

### Why Are Time-Range Queries So Fast (Hot Question)?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HOW TIME-SERIES DBs ACHIEVE FAST QUERIES                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. TIME-BASED PARTITIONING (The Biggest Win!)                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Data is automatically partitioned by time (hourly, daily chunks):          │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Query: "Show CPU usage for last 24 hours"                          │   │
│  │                                                                      │   │
│  │  Traditional DB:  Scan entire table → Filter by timestamp → SLOW    │   │
│  │                                                                      │   │
│  │  Time-Series DB:  Go directly to last 24 partitions → FAST          │   │
│  │                                                                      │   │
│  │  [2024-01-13] [2024-01-14] [2024-01-15] ← Only read these!          │   │
│  │       ↓             ↓             ↓                                  │   │
│  │   (skip)        (skip)        (read)                                 │   │
│  │                                                                      │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  2. DATA IS NATURALLY SORTED BY TIME                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • Writes are append-only (always current timestamp)                        │
│  • No random inserts in the middle                                          │
│  • No need for B-tree index on timestamp - data IS the index!               │
│                                                                              │
│  3. COLUMNAR STORAGE + TIME-SPECIFIC COMPRESSION                            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌────────────────┬────────────────┬────────────────┐                       │
│  │  Technique     │  How It Works  │  Compression   │                       │
│  ├────────────────┼────────────────┼────────────────┤                       │
│  │ Delta encoding │ Store diff     │ Timestamp:     │                       │
│  │                │ between values │ 1705312800000  │                       │
│  │                │                │ +1000 (+1 sec) │                       │
│  │                │                │ +1000          │                       │
│  │                │                │ (saves 90%!)   │                       │
│  ├────────────────┼────────────────┼────────────────┤                       │
│  │ Gorilla/XOR    │ For floats that│ 45.2 → 47.8    │                       │
│  │ encoding       │ change slowly  │ XOR diff tiny  │                       │
│  ├────────────────┼────────────────┼────────────────┤                       │
│  │ Run-length     │ Repeated values│ [0,0,0,0,0]    │                       │
│  │                │ stored once    │ → (0, count:5) │                       │
│  └────────────────┴────────────────┴────────────────┘                       │
│                                                                              │
│  4. PRE-AGGREGATED ROLLUPS                                                  │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Raw data:      Every second (huge!)                                │   │
│  │  5-min rollup:  avg, min, max per 5 minutes                         │   │
│  │  1-hour rollup: avg, min, max per hour                              │   │
│  │  1-day rollup:  avg, min, max per day                               │   │
│  │                                                                      │   │
│  │  Query "last 6 months"?                                             │   │
│  │  → Use 1-day rollup (180 rows) instead of raw (15M rows!)           │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  💡 Do you need to configure rollups?                                       │
│  → YES, most TSDBs require one-time configuration (materialized views,    │
│    continuous aggregates, or recording rules). Some offer automatic       │
│    downsampling with retention policies.                                  │
│                                                                              │
│  ⚠️  IMPORTANT: Retention vs Rollups - Two SEPARATE Concepts!              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Q: "If I stored data in seconds, can I access it after 1 month?"          │
│  A: It depends on YOUR retention policy configuration!                      │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  RETENTION POLICY = How long to KEEP raw data before DELETING       │   │
│  │  ROLLUPS/DOWNSAMPLING = Creating ADDITIONAL aggregated versions     │   │
│  │                                                                      │   │
│  │  These are INDEPENDENT! Raw data is NOT "converted" to rollups.    │   │
│  │  Raw data is either KEPT or DELETED based on retention.            │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  TYPICAL CONFIGURATION (you decide!):                                       │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  Data Type          │  Retention Period  │  Storage Cost            │   │
│  ├─────────────────────┼────────────────────┼──────────────────────────┤   │
│  │  Raw (1-sec)        │  7-30 days         │  HUGE (delete after)     │   │
│  │  5-min rollups      │  6 months          │  Medium                  │   │
│  │  1-hour rollups     │  2 years           │  Small                   │   │
│  │  1-day rollups      │  Forever           │  Tiny                    │   │
│  └─────────────────────┴────────────────────┴──────────────────────────┘   │
│                                                                              │
│  EXAMPLE SCENARIO:                                                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • You store CPU metrics every second                                       │
│  • Retention policy: raw data kept for 7 days                               │
│  • Rollup policy: 5-min avg kept for 1 year                                 │
│                                                                              │
│  Day 1:  Query "last hour" → ✅ Second-level granularity available         │
│  Day 8:  Query "Day 1" → ❌ Raw data DELETED, only 5-min rollup exists     │
│  Day 30: Query "Day 1" → ❌ Only 5-min rollup (no second-level!)           │
│                                                                              │
│  💡 WANT TO KEEP SECOND-LEVEL FOREVER?                                      │
│  • Set retention = infinite (but storage cost will be MASSIVE!)            │
│  • Most companies don't need second-level data for historical analysis    │
│  • For debugging recent issues: 7-30 days of raw is usually enough        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  💡 WHERE IS ROLLUP DATA STORED?                                            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  → Rollups are stored in SEPARATE tables/measurements/metrics              │
│  → Raw data and rollup data are NOT mixed together                         │
│  → Example: raw = cpu_usage, rollup = cpu_usage_5min, cpu_usage_hourly    │
│                                                                              │
│  QUERYING ROLLUPS:                                                          │
│  • EXPLICIT: You choose which to query (raw for recent, rollup for old)   │
│  • AUTOMATIC: Some TSDBs auto-select resolution based on time range       │
│                                                                              │
│                                                                              │
│  5. SEQUENTIAL I/O (Not Random!)                                            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  How time partitioning + columnar storage work TOGETHER:                    │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  STEP 1: Partition by TIME (horizontal split)                       │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │                                                                      │   │
│  │  [Partition: Jan 15] [Partition: Jan 16] [Partition: Jan 17]        │   │
│  │         ↓                   ↓                    ↓                   │   │
│  │    All metrics          All metrics          All metrics            │   │
│  │    for this day         for this day         for this day           │   │
│  │                                                                      │   │
│  │  Query "last 24 hours"? → Only read Jan 17 partition!               │   │
│  │                                                                      │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │  STEP 2: Within each partition, COLUMNAR storage (vertical split)  │   │
│  │  ─────────────────────────────────────────────────────────────────  │   │
│  │                                                                      │   │
│  │  Inside [Partition: Jan 17]:                                        │   │
│  │                                                                      │   │
│  │  ┌─────────────┬─────────────┬─────────────┬─────────────┐          │   │
│  │  │ timestamps  │ cpu_usage   │ memory_mb   │ disk_io     │          │   │
│  │  ├─────────────┼─────────────┼─────────────┼─────────────┤          │   │
│  │  │ 10:00:00    │ 45.2        │ 8192        │ 1024        │          │   │
│  │  │ 10:00:01    │ 47.8        │ 8195        │ 1028        │          │   │
│  │  │ 10:00:02    │ 46.1        │ 8190        │ 1030        │          │   │
│  │  │ ...         │ ...         │ ...         │ ...         │          │   │
│  │  └─────────────┴─────────────┴─────────────┴─────────────┘          │   │
│  │                                                                      │   │
│  │  Stored as COLUMNS on disk (not rows!):                             │   │
│  │  [timestamps: 10:00:00, 10:00:01, 10:00:02, ...]  ← Sequential!     │   │
│  │  [cpu_usage:  45.2, 47.8, 46.1, ...]              ← Sequential!     │   │
│  │  [memory_mb:  8192, 8195, 8190, ...]              ← Sequential!     │   │
│  │                                                                      │   │
│  │  Query "AVG(cpu_usage)"? → Read ONLY cpu_usage column sequentially  │   │
│  │  (Don't need to read memory_mb or disk_io at all!)                  │   │
│  └──────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  RESULT: Time partitioning (skip irrelevant days) +                        │
│          Columnar (read only needed columns) = BLAZING FAST                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Time-Series DBs vs Cassandra for Metrics

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WHY TIME-SERIES DBs OVER CASSANDRA?                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Cassandra CAN store time-series data (and many companies do!).            │
│  But purpose-built TSDBs have advantages:                                  │
│                                                                              │
│  ┌────────────────────┬─────────────────────┬─────────────────────────────┐│
│  │ Feature            │ Cassandra           │ Time-Series DB (InfluxDB)   ││
│  ├────────────────────┼─────────────────────┼─────────────────────────────┤│
│  │ Auto Downsampling  │ ❌ Build yourself    │ ✅ Built-in (1s→1m→1h)      ││
│  │ Retention Policies │ ❌ TTL creates       │ ✅ Auto-delete old data     ││
│  │                    │    tombstones!       │    (no tombstones)          ││
│  │ Compression        │ ⚠️ Generic           │ ✅ Specialized (delta,      ││
│  │                    │                     │    gorilla encoding)        ││
│  │ Query Language     │ CQL (SELECT...)     │ PromQL/Flux (rate(),       ││
│  │                    │                     │    derivative(), avg())     ││
│  │ Aggregations       │ ❌ Client-side       │ ✅ Native (percentiles,     ││
│  │                    │                     │    histograms, moving avg)  ││
│  │ Alerting           │ ❌ Separate system   │ ✅ Built-in (Alertmanager)  ││
│  │ Visualization      │ ❌ Separate system   │ ✅ Grafana integration      ││
│  └────────────────────┴─────────────────────┴─────────────────────────────┘│
│                                                                              │
│  THE TOMBSTONE PROBLEM:                                                     │
│  ─────────────────────────────────────────────────────────────────────────  │
│  • Cassandra uses "tombstones" for deletes (marks as deleted, doesn't      │
│    actually remove until compaction)                                       │
│  • Metrics with TTL = constant deletes = tombstone buildup                 │
│  • Too many tombstones = slow reads, compaction storms                     │
│  • Time-series DBs handle retention natively (drop entire time blocks)     │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  WHEN TO USE CASSANDRA FOR TIME-SERIES:                                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│  ✅ Already running Cassandra for other data                               │
│  ✅ Need raw event storage (not just numeric metrics)                      │
│  ✅ Very high cardinality (millions of unique tag combinations)            │
│  ✅ Need more query flexibility than TSDBs offer                           │
│  ✅ Multi-datacenter replication is critical                               │
│                                                                              │
│  WHEN TO USE TIME-SERIES DB:                                               │
│  ─────────────────────────────────────────────────────────────────────────  │
│  ✅ Application/infrastructure monitoring                                  │
│  ✅ Need downsampling and retention out-of-the-box                         │
│  ✅ Want PromQL/Flux for time-based queries                                │
│  ✅ Integrated alerting and dashboards                                     │
│  ✅ Better compression = lower storage costs                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Interview Talking Points

```
"For our monitoring system, we use Prometheus + Grafana because:
1. Pull-based model works well with Kubernetes service discovery
2. PromQL is powerful for alerting rules
3. Built-in alertmanager for notifications
4. Grafana provides rich visualization

For IoT sensor data, we chose TimescaleDB because:
1. SQL queries for complex analytics
2. Native PostgreSQL - existing team expertise
3. Continuous aggregates for real-time rollups
4. Compression reduces storage 90%+

Key considerations:
- Retention policies to manage storage costs
- Downsampling for historical data
- High-cardinality tags can explode storage"
```

---

## 7. OLTP vs OLAP

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

### Normalized vs Denormalized Schema (Why the difference?)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NORMALIZED SCHEMA (3NF) - OLTP                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  GOAL: Eliminate data redundancy, ensure data integrity                    │
│                                                                              │
│  Data is SPLIT into multiple related tables:                               │
│                                                                              │
│  ┌─────────────┐    ┌─────────────────┐    ┌─────────────┐                 │
│  │   USERS     │    │     ORDERS      │    │  PRODUCTS   │                 │
│  ├─────────────┤    ├─────────────────┤    ├─────────────┤                 │
│  │ user_id (PK)│◄───│ user_id (FK)    │    │ product_id  │                 │
│  │ name        │    │ order_id (PK)   │    │ name        │                 │
│  │ email       │    │ product_id (FK) │───►│ price       │                 │
│  │ address     │    │ quantity        │    │ category    │                 │
│  └─────────────┘    │ order_date      │    └─────────────┘                 │
│                     └─────────────────┘                                     │
│                                                                              │
│  ✅ BENEFITS:                                                                │
│  • NO duplicate data (user email stored ONCE)                              │
│  • Update in ONE place (change email → update 1 row)                       │
│  • Data consistency guaranteed                                              │
│  • Smaller storage footprint                                               │
│                                                                              │
│  ❌ DRAWBACKS:                                                               │
│  • Complex queries require JOINs (slower for analytics)                    │
│  • "Get order with user and product details" = 3-table JOIN                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                    DENORMALIZED SCHEMA (Star) - OLAP                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  GOAL: Optimize for read performance, minimize JOINs                       │
│                                                                              │
│  Data is FLATTENED - redundant data is stored to avoid JOINs:              │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      ORDERS_FACT (Denormalized)                     │   │
│  ├─────────────────────────────────────────────────────────────────────┤   │
│  │ order_id | user_name | user_email | product_name | product_price | │   │
│  │          | product_category | quantity | order_date | region      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  Same data, but ALL in one table (duplicated!):                            │
│  • order_1: "John", "john@email.com", "iPhone", $999, "Electronics"...    │
│  • order_2: "John", "john@email.com", "AirPods", $199, "Electronics"...   │
│  • order_3: "John", "john@email.com", "MacBook", $1999, "Electronics"...  │
│  (John's name and email repeated 3 times!)                                  │
│                                                                              │
│  ✅ BENEFITS:                                                                │
│  • FAST reads (no JOINs needed!)                                           │
│  • Simple queries: SELECT SUM(product_price) WHERE region = 'US'           │
│  • Optimized for aggregations (scan single table)                          │
│                                                                              │
│  ❌ DRAWBACKS:                                                               │
│  • Data redundancy (more storage)                                          │
│  • Updates are HARD (John changes email → update ALL his orders!)          │
│  • Not suitable for OLTP (transactional) workloads                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

WHY THIS TRADE-OFF MAKES SENSE:
┌────────────────────────────────────────────────────────────────────────────┐
│  OLTP (Normalized):                                                        │
│  • Many small writes (INSERT order, UPDATE email)                          │
│  • Data changes frequently → need single source of truth                   │
│  • JOINs are fine for small result sets (1 user, 1 order)                 │
│                                                                            │
│  OLAP (Denormalized):                                                      │
│  • Mostly reads, rare writes (batch ETL loads)                             │
│  • Data is historical (doesn't change after loaded)                        │
│  • Scanning millions of rows → JOINs would be SLOW                         │
│  • Redundancy is acceptable for read performance                           │
└────────────────────────────────────────────────────────────────────────────┘
```

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

### Data Warehouse Architecture: The Complete Story

Let me tell this as a story to connect all the dots.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHAPTER 1: THE PROBLEM                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  You have a PostgreSQL database running your e-commerce app.               │
│                                                                              │
│  One day, your CEO asks: "What was our revenue by region last quarter?"    │
│                                                                              │
│  You write this query on your OLTP database:                               │
│                                                                              │
│    SELECT region, SUM(amount)                                               │
│    FROM orders                                                              │
│    WHERE created_at > '2024-01-01'                                         │
│    GROUP BY region;                                                         │
│                                                                              │
│  PROBLEMS:                                                                   │
│  ❌ Query takes 30 minutes (scans millions of rows)                         │
│  ❌ Your production app slows down for users                                │
│  ❌ Analysts keep running these queries, app keeps crashing                 │
│                                                                              │
│  SOLUTION: Create a SEPARATE database for analytics!                       │
│                                                                              │
│  ┌─────────────────┐                    ┌─────────────────┐                 │
│  │  OLTP Database  │     Copy data      │  OLAP Database  │                 │
│  │  (PostgreSQL)   │  ───────────────►  │  (Warehouse)    │                 │
│  │  For your app   │      nightly       │  For analysts   │                 │
│  └─────────────────┘                    └─────────────────┘                 │
│                                                                              │
│  Now analysts query the warehouse, app stays fast! ✅                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHAPTER 2: TRADITIONAL WAY (2010s)                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STEP 1: EXTRACT data from source databases                                │
│  STEP 2: Store raw data in HDFS                                            │
│  STEP 3: QUERY directly with Hive/Spark SQL (or transform + load)          │
│                                                                              │
│  ┌──────────┐       ┌──────────┐       ┌─────────────────────────────────┐ │
│  │PostgreSQL│──────►│   HDFS   │──────►│  Hive / Spark SQL               │ │
│  │  MySQL   │       │(storage) │       │  (query HDFS directly!)         │ │
│  │  Kafka   │       │          │       │                                 │ │
│  └──────────┘       └──────────┘       │  OR                             │ │
│     EXTRACT           STORE            │                                 │ │
│                                        │  Transform → Load → Warehouse  │ │
│                                        └─────────────────────────────────┘ │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  WHAT IS HDFS?                                                              │
│  • Hadoop Distributed File System                                           │
│  • Just a way to store files across many machines                          │
│  • Like a giant hard drive spread across 100 servers                       │
│  • Stores files as Parquet, ORC, Avro (efficient formats)                  │
│  • You CANNOT query HDFS directly - need Spark/Hive on top!                │
│                                                                              │
│  PROBLEMS WITH TRADITIONAL:                                                 │
│  ❌ HDFS clusters are expensive (pay for servers 24/7)                      │
│  ❌ Complex to manage (need Hadoop admins)                                  │
│  ❌ Slow iteration (batch processing, not real-time)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHAPTER 3: MODERN WAY (2020s)                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Replace HDFS with S3 (cloud blob storage), use managed warehouses.        │
│                                                                              │
│  ┌──────────┐       ┌──────────┐       ┌──────────┐       ┌──────────┐     │
│  │PostgreSQL│──────►│    S3    │──────►│   dbt    │──────►│Snowflake │     │
│  │  MySQL   │       │ (cheap!) │       │(transform│       │ BigQuery │     │
│  │  Kafka   │       │          │       │ in SQL)  │       │ Redshift │     │
│  └──────────┘       └──────────┘       └──────────┘       └──────────┘     │
│     EXTRACT           STORE            TRANSFORM            QUERY          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  WHAT IS S3?                                                                │
│  • Simple Storage Service (AWS) - just blob/file storage                   │
│  • Like Dropbox/Google Drive but for massive data                          │
│  • Pay only for what you store (no servers to manage!)                     │
│  • Azure Blob, Google Cloud Storage = same thing on other clouds           │
│                                                                              │
│  WHAT IS SNOWFLAKE/BIGQUERY/REDSHIFT?                                       │
│  • Cloud data warehouses (fully managed)                                    │
│  • You just write SQL, they handle everything                              │
│  • Columnar storage (fast for analytics)                                   │
│  • Pay per query or per compute time                                       │
│                                                                              │
│  WHY THIS IS BETTER:                                                        │
│  ✅ No servers to manage                                                    │
│  ✅ Pay only for what you use                                               │
│  ✅ Scale instantly                                                         │
│  ✅ Real-time streaming possible                                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  💡 SO SNOWFLAKE REPLACES HDFS + SPARK?                                     │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐   │
│  │  TRADITIONAL (2010s)          │  MODERN (2020s)                     │   │
│  ├───────────────────────────────┼─────────────────────────────────────┤   │
│  │  HDFS (storage)               │  Snowflake storage (S3 internally) │   │
│  │  + Hive/Spark SQL (compute)   │  + Snowflake compute (virtual WH)  │   │
│  │  + Manual management          │  + Fully managed                   │   │
│  │  = You manage everything      │  = Just write SQL!                 │   │
│  └───────────────────────────────┴─────────────────────────────────────┘   │
│                                                                              │
│  YES, Snowflake bundles storage + compute + management into one service.  │
│                                                                              │
│  WHEN IS SPARK STILL USED?                                                  │
│  • Complex ETL (joins across 100s of tables, ML feature engineering)       │
│  • ML pipelines (training models on big data)                              │
│  • When you need more control than SQL provides                            │
│  • But for most analytics: Snowflake + dbt (SQL) is enough!               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHAPTER 4: ALL TERMS SIMPLIFIED                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  Term              │  What It Actually Is                              ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  S3/Blob Storage   │  Cloud hard drive. Just stores files.            ││
│  │                    │  Cannot query directly. Super cheap.              ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  HDFS              │  Old-school S3. Same idea but you manage servers.││
│  │                    │  Being replaced by S3 in most companies.          ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  Data Warehouse    │  Database optimized for analytics (Snowflake).   ││
│  │                    │  Data is loaded, organized, fast to query.       ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  Snowflake/BigQuery│  Cloud data warehouse. Fully managed.            ││
│  │  Redshift          │  Write SQL, they handle compute/storage.         ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  Spark             │  Engine to process big data. Runs on HDFS/S3.    ││
│  │                    │  Used for ETL (transform data).                   ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  dbt               │  Tool to write SQL transformations.               ││
│  │                    │  Modern alternative to Spark for simple ETL.      ││
│  ├────────────────────┼───────────────────────────────────────────────────┤│
│  │  Parquet/ORC       │  File formats optimized for analytics.            ││
│  │                    │  Columnar, compressed. Store data in S3/HDFS.    ││
│  └────────────────────┴───────────────────────────────────────────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CHAPTER 5: PUTTING IT ALL TOGETHER                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  MODERN DATA ARCHITECTURE:                                                  │
│                                                                              │
│   SOURCES                STORAGE              TRANSFORM         SERVE       │
│  ┌──────────┐                                                               │
│  │PostgreSQL│──┐         ┌──────────┐        ┌──────────┐     ┌─────────┐  │
│  └──────────┘  │         │          │        │          │     │Dashboards│  │
│                │         │   S3     │        │  dbt or  │     │ Tableau  │  │
│  ┌──────────┐  ├────────►│  (raw    │───────►│  Spark   │────►│ Looker   │  │
│  │  Kafka   │──┤         │  files)  │        │          │     └─────────┘  │
│  └──────────┘  │         │          │        └──────────┘           │       │
│                │         └──────────┘              │                │       │
│  ┌──────────┐  │                                   ▼                │       │
│  │  APIs    │──┘                           ┌──────────────┐         │       │
│  └──────────┘                              │  Snowflake   │◄────────┘       │
│                                            │  (warehouse) │                  │
│                                            │  Query here! │                  │
│                                            └──────────────┘                  │
│                                                   │                          │
│                                                   ▼                          │
│                                            ┌──────────────┐                  │
│                                            │  ML Models   │                  │
│                                            │  Reports     │                  │
│                                            └──────────────┘                  │
│                                                                              │
│  TL;DR: Sources → S3 (store) → Transform → Warehouse (query) → Dashboards │
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

## 8. Blob/Object Storage

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

## 9. Decision Matrix

### Quick Reference Table

| Requirement | PostgreSQL | Cassandra | Redis | Kafka | Time-Series (InfluxDB) |
|-------------|------------|-----------|-------|-------|------------------------|
| ACID Transactions | ✅ | ❌ | ❌ | ❌ | ❌ |
| Complex Queries | ✅ | ❌ | ❌ | ❌ | ⚠️ (time-based only) |
| High Write Throughput | ⚠️ | ✅ | ✅ | ✅ | ✅ |
| Low Latency Reads | ⚠️ | ⚠️ | ✅ | ❌ | ✅ (recent data) |
| Horizontal Scale | ⚠️ | ✅ | ⚠️ | ✅ | ✅ |
| Durability | ✅ | ✅ | ⚠️ | ✅ | ✅ |
| Global Distribution | ⚠️ | ✅ | ⚠️ | ⚠️ | ⚠️ |
| Message Replay | ❌ | ❌ | ❌ | ✅ | ❌ |
| Time-Range Queries | ⚠️ | ⚠️ | ❌ | ❌ | ✅ |
| Auto Downsampling | ❌ | ❌ | ❌ | ❌ | ✅ |

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

SCENARIO 5: Monitoring & Observability Platform
├── User/Team config: PostgreSQL (relations, ACID)
├── Metrics storage: Prometheus or InfluxDB (time-series)
├── Logs storage: Elasticsearch (full-text search)
├── Traces: Jaeger with Cassandra backend
├── Dashboards: Grafana → queries Prometheus/InfluxDB
├── Alerts: Prometheus Alertmanager
└── Long-term storage: S3 (downsampled metrics as Parquet)
```

---

## Interview Checklist

### Questions You Should Be Able to Answer

- [ ] "Why PostgreSQL and not Cassandra for user accounts?"
- [ ] "When would you choose Cassandra over PostgreSQL?"
- [ ] "Why use Redis when PostgreSQL can also cache?"
- [ ] "What's the difference between Redis and Kafka Pub/Sub?"
- [ ] "How would you design a system using all four?"
- [ ] "When would you use a time-series DB vs Cassandra for metrics?"
- [ ] "What's the difference between InfluxDB, Prometheus, and TimescaleDB?"

### Common Mistakes

| Mistake | Why It's Wrong |
|---------|----------------|
| "Kafka as a database" | Kafka is a log, not for queries |
| "Redis as primary storage" | Memory-only, use as cache layer |
| "Cassandra for transactions" | No ACID, use PostgreSQL |
| "PostgreSQL for 1M writes/sec" | Single-node limit, use Cassandra |
| "Cassandra for time-series metrics" | Works, but time-series DBs have downsampling, retention built-in |
| "PostgreSQL for high-cardinality metrics" | Not optimized, use InfluxDB/TimescaleDB |

---

## Next Steps

Continue to **[Level 6: Senior Gotchas](06_SENIOR_GOTCHAS.md)** for edge case interview questions.

