# Level 2: Database Logic (The Performance Layer)

> This level covers how databases optimize query execution and handle concurrent access—the most frequently tested topics in system design interviews.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🗺️  HOW THIS LEVEL CONNECTS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LEVEL 2: DATABASE LOGIC ◄── YOU ARE HERE                                  │
│  ════════════════════════════════════════════════════════════════════════════│
│  Scope: SINGLE-NODE, query optimization & concurrency                       │
│  Focus: How databases execute queries efficiently and handle transactions  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         BUILDS ON LEVEL 1                               ││
│  │                                                                         ││
│  │  Level 1 Foundation        Level 2 (This)                               ││
│  │  ────────────────────      ──────────────                               ││
│  │  B-Tree structure     ───► Indexes ARE B-Trees on disk                  ││
│  │  Pages                ───► Each index node = one page                   ││
│  │  Buffer Pool          ───► Index pages cached in buffer pool            ││
│  │  WAL                  ───► Transactions written to WAL first            ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         EXTENDS TO LEVEL 3                              ││
│  │                                                                         ││
│  │  Level 2 (This)            Level 3 (Distributed)                        ││
│  │  ──────────────            ────────────────────                         ││
│  │  Indexes (single node) ──► Each replica has its own indexes             ││
│  │  MVCC (local txn IDs)  ──► Distributed MVCC (global timestamps)         ││
│  │  Isolation levels      ──► Distributed transactions (2PC, Saga)         ││
│  │  Write skew            ──► Same problem, harder across nodes!           ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  WHY THIS LEVEL MATTERS MOST FOR INTERVIEWS:                                │
│  • "Add an index" - but which type? Composite? Covering?                   │
│  • "What isolation level?" - know the trade-offs                           │
│  • "How do readers not block writers?" - MVCC is the answer               │
│  • These are the #1 most asked database questions!                         │
│                                                                              │
│  APPLIES TO:                                                                │
│  ✅ SQL: PostgreSQL, MySQL, SQL Server (B-Tree indexes, MVCC)               │
│  ✅ NoSQL: Partition keys, clustering keys, secondary indexes (covered!)   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents
1. [Indexing Deep Dive](#1-indexing-deep-dive) (SQL Focus)
   - Clustered vs Non-Clustered
   - Composite, Covering, Selectivity
2. [NoSQL Indexing](#nosql-indexing-partition-key-clustering-key--secondary-indexes)
   - Partition Key vs Clustering Key
   - Secondary Indexes (Local & Global)
3. [MVCC (Multi-Version Concurrency Control)](#2-mvcc-multi-version-concurrency-control)
4. [Isolation Levels](#3-isolation-levels)
5. [Interview Checklist](#4-interview-checklist)

---

## 1. Indexing Deep Dive

### Why Indexes Matter

```
WITHOUT INDEX:
┌─────────────────────────────────────────────────────────────────────┐
│ SELECT * FROM users WHERE email = 'john@example.com'                │
│                                                                      │
│ Execution: FULL TABLE SCAN                                          │
│ - Read every single row (1 million rows = 1 million reads)          │
│ - Time: O(N)                                                        │
│ - I/O: Potentially gigabytes of data                                │
└─────────────────────────────────────────────────────────────────────┘

WITH INDEX:
┌─────────────────────────────────────────────────────────────────────┐
│ SELECT * FROM users WHERE email = 'john@example.com'                │
│                                                                      │
│ Execution: INDEX SEEK                                               │
│ - Navigate B-tree index: 3-4 node lookups                           │
│ - Time: O(log N)                                                    │
│ - I/O: A few kilobytes                                              │
└─────────────────────────────────────────────────────────────────────┘
```

---

### Clustered vs Non-Clustered Indexes

This is a **critical distinction** that interviewers love to probe.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    APPLICABILITY                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  ✅ SQL Databases: MySQL, PostgreSQL, SQL Server, Oracle                    │
│  ⚠️  NoSQL: Concept differs - Cassandra uses "partition key" for clustering │
│            DynamoDB uses "sort key" for ordering within partitions          │
│                                                                              │
│  This section focuses on SQL/Relational database indexing.                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### One-Liner Definitions

| Index Type | Definition | Why We Need It |
|------------|------------|----------------|
| **Clustered Index** | The table data itself, physically sorted by the index key | Fast range queries on primary key; data locality |
| **Non-Clustered Index** | A separate lookup structure pointing to the actual rows | Fast lookups on non-primary columns (email, username) |

---

#### Clustered Index (Primary Index)

> **Definition:** An index where the leaf nodes contain the actual row data, physically sorted by the key.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      CLUSTERED INDEX (Detailed View)                         │
│                "The table IS the index, sorted by the key"                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TABLE: users (id PRIMARY KEY, name, email, created_at)                     │
│  CLUSTERED ON: id                                                            │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  LEVEL 0: ROOT NODE (Page 100)                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ Page 100 (ROOT) - Type: INTERNAL                                        ││
│  │                                                                         ││
│  │  ┌─────────────────────────────────────────────────────────────────┐   ││
│  │  │  Key: 50        │  Key: 100       │                             │   ││
│  │  │  ↓ Go to        │  ↓ Go to        │  ↓ Go to                    │   ││
│  │  │  Page 101       │  Page 102       │  Page 103                   │   ││
│  │  │  (ids < 50)     │  (50 ≤ ids <100)│  (ids ≥ 100)                │   ││
│  │  └─────────────────────────────────────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                    /                │                \                       │
│                   /                 │                 \                      │
│  ═══════════════════════════════════════════════════════════════════════════│
│  LEVEL 1: INTERNAL NODES                                                    │
│  ═══════════════════════════════════════════════════════════════════════════│
│  ┌─────────────────────┐ ┌─────────────────────┐ ┌─────────────────────┐   │
│  │ Page 101            │ │ Page 102            │ │ Page 103            │   │
│  │ Type: INTERNAL      │ │ Type: INTERNAL      │ │ Type: INTERNAL      │   │
│  │                     │ │                     │ │                     │   │
│  │ Key:25    Key:35    │ │ Key:60    Key:80    │ │ Key:120   Key:150   │   │
│  │ ↓Page201  ↓Page202  │ │ ↓Page203  ↓Page204  │ │ ↓Page205  ↓Page206  │   │
│  │           ↓Page207  │ │           ↓Page208  │ │           ↓Page209  │   │
│  └─────────────────────┘ └─────────────────────┘ └─────────────────────┘   │
│          /        \              /        \              /        \         │
│         ↓          ↓            ↓          ↓            ↓          ↓        │
│  ═══════════════════════════════════════════════════════════════════════════│
│  LEVEL 2: LEAF NODES (Contain ACTUAL ROW DATA!)                             │
│  ═══════════════════════════════════════════════════════════════════════════│
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ Page 201 - Type: LEAF (ids 10-24)                                       ││
│  │ ┌─────────────────────────────────────────────────────────────────────┐ ││
│  │ │ id:10 │ name:"Alice" │ email:"alice@x.com"  │ created:"2024-01-10" │ ││
│  │ │ id:15 │ name:"Bob"   │ email:"bob@x.com"    │ created:"2024-01-11" │ ││
│  │ │ id:20 │ name:"Carol" │ email:"carol@x.com"  │ created:"2024-01-12" │ ││
│  │ │ id:22 │ name:"David" │ email:"david@x.com"  │ created:"2024-01-13" │ ││
│  │ └─────────────────────────────────────────────────────────────────────┘ ││
│  │ Next leaf: → Page 202                                                   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ Page 202 - Type: LEAF (ids 25-34)                                       ││
│  │ ┌─────────────────────────────────────────────────────────────────────┐ ││
│  │ │ id:25 │ name:"Eve"   │ email:"eve@x.com"    │ created:"2024-01-14" │ ││
│  │ │ id:28 │ name:"Frank" │ email:"frank@x.com"  │ created:"2024-01-15" │ ││
│  │ │ id:30 │ name:"Grace" │ email:"grace@x.com"  │ created:"2024-01-16" │ ││
│  │ └─────────────────────────────────────────────────────────────────────┘ ││
│  │ Prev leaf: ← Page 201 | Next leaf: → Page 207                           ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ... more leaf pages ...                                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

FINDING id=28:
Step 1: Read Page 100 (root) → 28 < 50 → go to Page 101
Step 2: Read Page 101 → 25 ≤ 28 < 35 → go to Page 202
Step 3: Read Page 202 (leaf) → scan for id=28 → FOUND!
Total: 3 page reads

RANGE QUERY (id BETWEEN 20 AND 30):
Step 1-2: Same as above, find starting leaf (Page 201)
Step 3: Read Page 201 → get id:20, 22
Step 4: Follow "next leaf" → Page 202 → get id:25, 28, 30
Total: 4 page reads (sequential I/O, very fast!)
```

**KEY POINTS:**
```
• Only ONE clustered index per table (data can only be physically sorted one way)
• Usually the PRIMARY KEY
• Range queries are VERY fast (leaf pages are linked, sequential read)
• MySQL InnoDB: Always clustered by PRIMARY KEY (or hidden row ID if no PK)
• PostgreSQL: Technically "heap" storage (not clustered), but CLUSTER command can reorder once
```

---

#### Non-Clustered Index (Secondary Index)

> **Definition:** A separate B-tree structure where leaf nodes contain the indexed column(s) plus a pointer back to the actual row.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   NON-CLUSTERED INDEX (Detailed View)                        │
│              "Separate lookup structure pointing to actual data"             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TABLE: users (id PRIMARY KEY, name, email, created_at)                     │
│  SECONDARY INDEX ON: email                                                   │
│                                                                              │
│  WHY: To quickly find users by email (WHERE email = 'bob@x.com')            │
│       Without this index, we'd have to scan the ENTIRE table!               │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  SECONDARY INDEX B-TREE (Separate structure from the table!)                │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ Page 500 (ROOT) - Index on "email"                                      ││
│  │                                                                         ││
│  │  ┌─────────────────────────────────────────────────────────────────┐   ││
│  │  │  Key: "f@..."   │  Key: "m@..."   │                             │   ││
│  │  │  ↓ Page 501     │  ↓ Page 502     │  ↓ Page 503                 │   ││
│  │  │  (email < "f@") │  ("f@" to "m@") │  (email ≥ "m@")             │   ││
│  │  └─────────────────────────────────────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                    /                │                \                       │
│                   ↓                 ↓                 ↓                      │
│  ═══════════════════════════════════════════════════════════════════════════│
│  LEAF NODES: Contain (email → Primary Key pointer)                          │
│  ═══════════════════════════════════════════════════════════════════════════│
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ Page 501 - Type: LEAF                                                   ││
│  │ ┌───────────────────────────────────────────────────────────────────┐  ││
│  │ │  email              │  Points to (Primary Key)                    │  ││
│  │ ├───────────────────────────────────────────────────────────────────┤  ││
│  │ │  "alice@x.com"      │  → PK: 10                                   │  ││
│  │ │  "bob@x.com"        │  → PK: 15                                   │  ││
│  │ │  "carol@x.com"      │  → PK: 20                                   │  ││
│  │ │  "david@x.com"      │  → PK: 22                                   │  ││
│  │ │  "eve@x.com"        │  → PK: 25                                   │  ││
│  │ └───────────────────────────────────────────────────────────────────┘  ││
│  │                                                                         ││
│  │ NOTE: Sorted by EMAIL, not by id!                                       ││
│  │       Leaf has email + primary key ONLY (not full row data!)            ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│  QUERY: SELECT * FROM users WHERE email = 'bob@x.com'                       │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  Step 1: Search secondary index for "bob@x.com"                             │
│          Root (Page 500) → "bob" < "f@" → Page 501                          │
│          Leaf (Page 501) → Found! email="bob@x.com" → PK:15                 │
│                                                                              │
│  Step 2: BOOKMARK LOOKUP (Key Lookup)                                       │
│          Now we only have PK:15, but query wants SELECT *                   │
│          Must go to CLUSTERED INDEX with PK:15 to get full row              │
│                                                                              │
│          Clustered Index: PK:15 → Page 201 → Full row!                      │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                                                                         ││
│  │   Secondary Index               Clustered Index (Actual Data)           ││
│  │   ┌──────────────┐              ┌──────────────────────────────────┐   ││
│  │   │ "bob@x.com"  │              │ id:15 │ name:"Bob" │ email:...  │   ││
│  │   │    ↓         │              │       │            │            │   ││
│  │   │  PK: 15  ────│─────────────►│  ← FULL ROW DATA HERE          │   ││
│  │   └──────────────┘              └──────────────────────────────────┘   ││
│  │                                                                         ││
│  │   This extra lookup = "Bookmark Lookup" or "Key Lookup"                 ││
│  │   Adds extra I/O!                                                       ││
│  │                                                                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

TOTAL I/O FOR "SELECT * WHERE email = 'bob@x.com'":
  Index pages read:    2 (root + leaf of secondary index)
  Bookmark lookup:     3 (navigate clustered index to get full row)
  Total:               5 page reads

COMPARE TO COVERING INDEX:
  SELECT email FROM users WHERE email = 'bob@x.com'
  → Only need secondary index! No bookmark lookup needed.
  → email is already in the index leaf node.
  Total:               2 page reads (much faster!)
```

**KEY POINTS:**
```
• Many non-clustered indexes per table (as many as you need)
• Leaf nodes store: indexed columns + pointer to clustered index (PK)
• "Bookmark lookup" / "Key lookup": Extra I/O to fetch columns not in index
• Index-only scan: If all columns in query are in index, no lookup needed!
• Trade-off: Each index slows down INSERT/UPDATE (must maintain multiple structures)
```

---

#### Head-to-Head Comparison

| Aspect | Clustered Index | Non-Clustered Index |
|--------|-----------------|---------------------|
| **Definition** | Table data sorted by key | Separate lookup structure |
| **Count per table** | Only 1 | Many |
| **Leaf content** | Full row data | Indexed columns + PK pointer |
| **Range scan speed** | Very fast (sequential I/O) | Slower (random I/O for lookups) |
| **Point lookup** | Fast (direct to data) | Slower (extra hop to clustered) |
| **Insert overhead** | Higher (maintain physical order) | Lower (separate structure) |
| **Space** | Is the table itself | Additional storage |
| **Best for** | Primary key, range queries | Lookup columns (email, username) |

---

#### SQL vs NoSQL Comparison

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    INDEX CONCEPTS: SQL vs NoSQL                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SQL (MySQL, PostgreSQL):                                                   │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • Clustered Index: Data sorted by PRIMARY KEY (one per table)              │
│  • Non-Clustered: Secondary indexes with PK pointers                        │
│  • B-Tree: Both types use B-Tree structure                                  │
│                                                                              │
│  NoSQL (Cassandra, DynamoDB):                                               │
│  ─────────────────────────────────────────────────────────────────────────── │
│  • NO traditional "clustered index" concept                                 │
│  • Partition Key: Determines which node stores the data (hash-based)        │
│  • Clustering Key (Sort Key): Sorts data WITHIN a partition                 │
│  • Together: PRIMARY KEY = (Partition Key) + Clustering Key                 │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ SQL Clustered Index ≈ NoSQL Clustering Key (sorting within partition)  ││
│  │ SQL Non-Clustered   ≈ NoSQL Secondary Index / Global Secondary Index   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  KEY DIFFERENCE (Default Design Philosophy):                                │
│  • SQL: One big sorted structure (B-Tree) - single-node by default         │
│  • NoSQL: Data partitioned first, then sorted within each partition         │
│                                                                              │
│  ⚠️  NOTE: This is about DEFAULT DESIGN, not capability!                    │
│  SQL CAN be sharded via: Application logic, Vitess, Citus, or NewSQL       │
│  (CockroachDB, Spanner). See Level 3: Distributed Systems for details.     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Composite Indexes (Multi-Column)

> **Problem Solved:** Queries filter on multiple columns (e.g., `WHERE country = 'US' AND city = 'NYC'`), but separate indexes can't efficiently handle combined conditions.

```
INDEX: CREATE INDEX idx_user_country_city ON users(country, city)

┌─────────────────────────────────────────────────────────────────────┐
│                    COMPOSITE INDEX STRUCTURE                         │
│                     (Sorted by country, THEN city)                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │ (Australia, Melbourne) → ptr                                 │    │
│  │ (Australia, Sydney)    → ptr                                 │    │
│  │ (Canada, Montreal)     → ptr                                 │    │
│  │ (Canada, Toronto)      → ptr                                 │    │
│  │ (USA, Austin)          → ptr                                 │    │
│  │ (USA, Boston)          → ptr                                 │    │
│  │ (USA, Chicago)         → ptr                                 │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

#### The "Leftmost Prefix" Rule

```
Index: (country, city, zipcode)

✅ USES INDEX:
• WHERE country = 'USA'
• WHERE country = 'USA' AND city = 'Boston'
• WHERE country = 'USA' AND city = 'Boston' AND zipcode = '02101'

❌ CANNOT USE INDEX:
• WHERE city = 'Boston'                    ← Skips country!
• WHERE zipcode = '02101'                  ← Skips country AND city!
• WHERE country = 'USA' AND zipcode = '02101' ← Skips city!

ANALOGY: Phone book sorted by (Last Name, First Name)
- Can find all "Smiths" quickly
- Can find "John Smith" quickly  
- CANNOT find all "Johns" quickly (would scan entire book)
```

#### Column Order Matters!

```
SCENARIO: User queries
• 80%: Filter by country only
• 15%: Filter by country AND city
• 5%:  Filter by city only

GOOD INDEX ORDER: (country, city)
• 95% of queries use the index efficiently

BAD INDEX ORDER: (city, country)
• Only 5% of queries use the index efficiently

RULE: Put most frequently filtered columns FIRST
      Put high-cardinality columns FIRST (if tie in frequency)
```

---

### Index Selectivity

> **Problem Solved:** Not all indexes are useful! An index on a low-cardinality column (e.g., `gender`) returns too many rows, making a full table scan faster. Selectivity helps decide if an index is worth creating.

**Selectivity** = (Number of distinct values) / (Total number of rows)

```
HIGH SELECTIVITY (Good for indexing):
• email: 1,000,000 distinct / 1,000,000 rows = 1.0 (perfect!)
• user_id: Very high selectivity
• phone_number: Very high selectivity

LOW SELECTIVITY (Often poor for indexing):
• gender: 3 distinct / 1,000,000 rows = 0.000003 (terrible!)
• is_active: 2 distinct values
• country: ~200 distinct / 1,000,000 rows = 0.0002

WHY LOW SELECTIVITY IS BAD:
- Index on is_active=true matches 500,000 rows
- 500,000 bookmark lookups = worse than table scan!
- Optimizer will ignore the index
```

#### The 10-15% Rule of Thumb

```
If a query returns more than ~10-15% of the table:
→ Full table scan is often FASTER than using the index

WHY?
• Sequential I/O (table scan) vs Random I/O (index lookups)
• Index access: read index page + read data page
• Table scan: just read data pages sequentially
```

---

### Covering Indexes (Index-Only Scans)

> **Problem Solved:** Even with an index, fetching columns not in the index requires an extra disk lookup (bookmark lookup). Covering indexes eliminate this by including all needed columns in the index itself.

```
PROBLEM:
SELECT email, created_at FROM users WHERE email = 'x@y.com'

Index on (email) requires:
1. Index lookup → find row pointer
2. Heap/clustered lookup → fetch created_at  ← EXTRA I/O!

SOLUTION: Covering Index
CREATE INDEX idx_email_cover ON users(email) INCLUDE (created_at)

Now the index contains all needed columns!
• No bookmark lookup required
• "Index-only scan" - never touches the table
```

#### PostgreSQL INCLUDE Syntax

```sql
-- Traditional covering index (all columns in B-tree)
CREATE INDEX idx_covering ON orders(customer_id, order_date, total);

-- INCLUDE syntax (non-key columns stored but not sorted)
CREATE INDEX idx_include ON orders(customer_id) INCLUDE (order_date, total);

DIFFERENCE:
• Traditional: Can use for ORDER BY on all columns
• INCLUDE: Only customer_id used for ordering, others just "along for the ride"
• INCLUDE: Smaller index, faster to maintain
```

---

### Index Anti-Patterns

| Anti-Pattern | Problem | Solution |
|--------------|---------|----------|
| **Index every column** | Slows down writes, wastes space | Index based on query patterns |
| **Function on indexed column** | `WHERE UPPER(email) = 'X'` won't use index | Use functional index or fix data |
| **Type mismatch** | `WHERE user_id = '123'` (string vs int) | Ensure type consistency |
| **LIKE with leading wildcard** | `WHERE name LIKE '%john%'` can't use index | Use full-text search |
| **OR conditions** | `WHERE a = 1 OR b = 2` may not use index | Use UNION or restructure |
| **Too many indexes** | Each INSERT updates all indexes | Audit and remove unused |

---

### NoSQL Indexing: Partition Key, Clustering Key & Secondary Indexes

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    APPLICABILITY                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ✅ NoSQL Databases: Cassandra, ScyllaDB, DynamoDB, CosmosDB                │
│  ⚠️  SQL: Similar concepts exist (partitioning) but terminology differs     │
│                                                                              │
│  These concepts are CRITICAL for NoSQL interviews!                          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Primary Key = Partition Key + Clustering Key (CRITICAL!)

> **Why This Matters:** In NoSQL, how you define your primary key determines WHERE data lives (which node) and HOW it's sorted. Get this wrong and your queries become expensive cluster-wide scans!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    NoSQL PRIMARY KEY ANATOMY                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  In Cassandra/ScyllaDB/DynamoDB, the PRIMARY KEY has TWO parts:             │
│                                                                              │
│  PRIMARY KEY ((partition_key), clustering_key1, clustering_key2, ...)       │
│               └──────┬──────┘  └──────────────────┬──────────────────┘      │
│                      │                            │                          │
│            PARTITION KEY                  CLUSTERING COLUMNS                 │
│         (WHERE data lives)              (HOW data is sorted)                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**One-Liner Definitions:**

| Concept | Definition | Query Impact |
|---------|------------|--------------|
| **Partition Key** | Determines WHICH NODE stores the data (hash → node assignment) | MUST be in WHERE clause (= or IN only) |
| **Clustering Key** | Determines SORT ORDER within a partition | Optional in WHERE, enables range queries |
| **Primary Key** | Partition Key + Clustering Key(s) together | Uniquely identifies a row |

#### Examples

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  EXAMPLE 1: Simple Primary Key                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│  CREATE TABLE users (                                                        │
│      user_id UUID PRIMARY KEY,  ← Only partition key, no clustering         │
│      name TEXT,                                                              │
│      email TEXT                                                              │
│  );                                                                          │
│                                                                              │
│  PRIMARY KEY (user_id)  =  Partition Key: user_id                           │
│                            Clustering Key: (none)                           │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  EXAMPLE 2: Composite Primary Key (Most Common!)                            │
│  ───────────────────────────────────────────────────────────────────────────│
│  CREATE TABLE messages (                                                     │
│      chat_id UUID,                                                           │
│      sent_at TIMESTAMP,                                                      │
│      message_id UUID,                                                        │
│      content TEXT,                                                           │
│      PRIMARY KEY ((chat_id), sent_at, message_id)                           │
│  );                     ▲            ▲                                       │
│                         │            │                                       │
│              Partition Key     Clustering Columns                           │
│                                                                              │
│  • All messages for chat_id=123 → SAME partition (same node)                │
│  • Within partition: sorted by sent_at, then message_id                     │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  EXAMPLE 3: Composite Partition Key (Avoid Hotspots!)                       │
│  ───────────────────────────────────────────────────────────────────────────│
│  CREATE TABLE sensor_data (                                                  │
│      device_id UUID,                                                         │
│      day DATE,                                                               │
│      hour INT,                                                               │
│      reading DOUBLE,                                                         │
│      PRIMARY KEY ((device_id, day), hour)                                   │
│  );                       ▲              ▲                                   │
│                           │              │                                   │
│            Composite Partition Key    Clustering Column                      │
│             (device + day)                                                   │
│                                                                              │
│  • Data for device_id=X, day=2024-01-15 → ONE partition                     │
│  • Prevents "partition too large" for devices with years of data           │
│  • Query for one day = one partition scan                                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Physical Storage Visualization

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    HOW DATA IS PHYSICALLY STORED                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TABLE: messages                                                             │
│  PRIMARY KEY ((chat_id), sent_at)                                           │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  PARTITION 1: chat_id = "abc-123"                                       ││
│  │  (All stored together on same node, sorted by sent_at)                  ││
│  │                                                                         ││
│  │  ┌─────────────────────────────────────────────────────────────────┐   ││
│  │  │ sent_at: 2024-01-15 10:00 │ message: "Hello"                    │   ││
│  │  │ sent_at: 2024-01-15 10:01 │ message: "Hi there!"                │   ││
│  │  │ sent_at: 2024-01-15 10:05 │ message: "How are you?"             │   ││
│  │  │ sent_at: 2024-01-15 10:06 │ message: "Good, thanks!"            │   ││
│  │  └─────────────────────────────────────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  PARTITION 2: chat_id = "xyz-789"                                       ││
│  │  (Different partition, possibly different node)                         ││
│  │                                                                         ││
│  │  ┌─────────────────────────────────────────────────────────────────┐   ││
│  │  │ sent_at: 2024-01-15 09:00 │ message: "Meeting at 3?"            │   ││
│  │  │ sent_at: 2024-01-15 09:30 │ message: "Sure!"                    │   ││
│  │  └─────────────────────────────────────────────────────────────────┘   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  QUERY IMPLICATIONS:                                                         │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  ✅ FAST: WHERE chat_id = 'abc-123'                                         │
│           → Single partition lookup                                         │
│                                                                              │
│  ✅ FAST: WHERE chat_id = 'abc-123' AND sent_at > '2024-01-15 10:00'        │
│           → Single partition, range scan on clustering column               │
│                                                                              │
│  ❌ SLOW: WHERE sent_at > '2024-01-15 10:00'                                │
│           → Must scan ALL partitions! (full cluster scan)                   │
│                                                                              │
│  ❌ SLOW: WHERE content = 'Hello'                                           │
│           → Not part of primary key, needs secondary index                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Partition Key vs Clustering Key Summary

```
┌─────────────────────────┬─────────────────────────────────────────────┐
│     PARTITION KEY       │         CLUSTERING KEY                     │
├─────────────────────────┼─────────────────────────────────────────────┤
│ Determines WHICH NODE   │ Determines SORT ORDER within partition     │
│ data lives on           │                                            │
├─────────────────────────┼─────────────────────────────────────────────┤
│ Hash of partition key   │ Stored sorted (ASC or DESC)                │
│ → node assignment       │                                            │
├─────────────────────────┼─────────────────────────────────────────────┤
│ MUST be in WHERE clause │ Optional in WHERE (enables range queries)  │
├─────────────────────────┼─────────────────────────────────────────────┤
│ Cannot do range queries │ CAN do range queries (<, >, BETWEEN)       │
│ (only = or IN)          │                                            │
├─────────────────────────┼─────────────────────────────────────────────┤
│ Avoid hotspots!         │ Choose based on query patterns             │
│ (even distribution)     │                                            │
└─────────────────────────┴─────────────────────────────────────────────┘

DESIGN RULES:
1. Every query MUST specify the full partition key
2. Clustering columns enable efficient range scans
3. One table per query pattern (denormalization is expected!)
4. Keep partition size < 100MB (avoid "wide partition" problems)
```

---

#### Secondary Indexes (Local & Global)

> **Problem Solved:** What if you need to query by a column that's NOT in your primary key? Secondary indexes let you query on non-key columns, but with trade-offs.

##### Why Not Just Use Clustering Key?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│          CLUSTERING KEY vs SECONDARY INDEX: When to Use What                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  KEY INSIGHT:                                                                │
│  • Clustering Key: You MUST know the partition key to query                 │
│  • Secondary Index: You can query WITHOUT knowing the partition key          │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  EXAMPLE: TABLE messages                                                     │
│           PRIMARY KEY ((chat_id), sent_at)                                  │
│           Columns: chat_id, sent_at, sender_id, content                     │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  QUERY 1: "Recent messages in chat ABC"                                      │
│  WHERE chat_id = 'ABC' AND sent_at > '2024-01-15'                           │
│  ✅ Clustering key works! You know the partition (chat_id).                 │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  QUERY 2: "ALL messages by user123 across ALL chats"                         │
│  WHERE sender_id = 'user123'                                                │
│  ❌ Clustering key WON'T work!                                              │
│     - sender_id is NOT in the primary key                                   │
│     - You don't know WHICH chat_id to look in                               │
│     - Would require scanning EVERY partition!                               │
│  ✅ Need a secondary index on sender_id                                     │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  BOTTOM LINE:                                                                │
│  ┌─────────────────────────┬────────────────────────────────────────────┐   │
│  │     CLUSTERING KEY      │         SECONDARY INDEX                   │   │
│  ├─────────────────────────┼────────────────────────────────────────────┤   │
│  │ "Within partition X,    │ "Across ALL partitions, find rows where   │   │
│  │  give me rows by Y"     │  column Z = ?"                            │   │
│  │ (MUST know partition!)  │ (Don't need to know partition!)           │   │
│  └─────────────────────────┴────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              SECONDARY INDEX vs GLOBAL SECONDARY INDEX (GSI)                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SECONDARY INDEX (Local / Native)                                           │
│  ─────────────────────────────────                                          │
│  • Index data stored ON THE SAME NODE as the base data                      │
│  • Each node indexes only its own partition data                            │
│  • Query must SCATTER to all nodes, then GATHER results                     │
│                                                                              │
│  ┌─────────┐  ┌─────────┐  ┌─────────┐                                      │
│  │ Node 1  │  │ Node 2  │  │ Node 3  │                                      │
│  │ ─────── │  │ ─────── │  │ ─────── │                                      │
│  │ Data A  │  │ Data B  │  │ Data C  │                                      │
│  │ Index A │  │ Index B │  │ Index C │  ◄── Each node has LOCAL index      │
│  └─────────┘  └─────────┘  └─────────┘                                      │
│       │            │            │                                           │
│       └────────────┼────────────┘                                           │
│                    ▼                                                        │
│           Query: WHERE status = 'active'                                    │
│           → Must ask ALL 3 nodes! (scatter-gather)                          │
│                                                                              │
│  ───────────────────────────────────────────────────────────────────────────│
│                                                                              │
│  GLOBAL SECONDARY INDEX (GSI) - DynamoDB concept                            │
│  ────────────────────────────────────────────────────────────────────────── │
│  • Completely separate table with its own partition key                     │
│  • Asynchronously replicated from base table                                │
│  • Query goes to specific partition (no scatter-gather!)                    │
│                                                                              │
│  Base Table: PRIMARY KEY (user_id)                                          │
│  ┌───────────────────────────────────────┐                                  │
│  │ user_id │ email           │ status    │                                  │
│  │ u1      │ a@test.com      │ active    │                                  │
│  │ u2      │ b@test.com      │ inactive  │                                  │
│  │ u3      │ c@test.com      │ active    │                                  │
│  └───────────────────────────────────────┘                                  │
│           │                                                                  │
│           │ Async replication                                               │
│           ▼                                                                  │
│  GSI: PRIMARY KEY (email)                                                   │
│  ┌───────────────────────────────────────┐                                  │
│  │ email           │ user_id │ (projected)│                                 │
│  │ a@test.com      │ u1      │            │                                 │
│  │ b@test.com      │ u2      │            │                                 │
│  │ c@test.com      │ u3      │            │                                 │
│  └───────────────────────────────────────┘                                  │
│                                                                              │
│  Query: WHERE email = 'a@test.com'                                          │
│  → Goes directly to correct partition in GSI!                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Local Secondary Index (LSI) - DynamoDB

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LOCAL SECONDARY INDEX (LSI)                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  • SAME partition key as base table, DIFFERENT sort key                     │
│  • Stored on same partition as base data (no extra write latency)           │
│  • Strongly consistent reads possible                                       │
│  • MUST be created at table creation time (cannot add later!)               │
│                                                                              │
│  EXAMPLE:                                                                    │
│  Base Table: PRIMARY KEY (user_id, order_date)                              │
│  LSI: PRIMARY KEY (user_id, order_total)  ← same partition key              │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  PARTITION: user_id = "u1"                                             │ │
│  │                                                                        │ │
│  │  Base Table (sorted by order_date):                                    │ │
│  │  ┌────────────────────────────────────────────┐                        │ │
│  │  │ order_date: 2024-01-01 │ order_total: $50  │                        │ │
│  │  │ order_date: 2024-01-15 │ order_total: $200 │                        │ │
│  │  │ order_date: 2024-02-01 │ order_total: $25  │                        │ │
│  │  └────────────────────────────────────────────┘                        │ │
│  │                                                                        │ │
│  │  LSI (sorted by order_total):                                          │ │
│  │  ┌────────────────────────────────────────────┐                        │ │
│  │  │ order_total: $25  │ order_date: 2024-02-01 │                        │ │
│  │  │ order_total: $50  │ order_date: 2024-01-01 │                        │ │
│  │  │ order_total: $200 │ order_date: 2024-01-15 │                        │ │
│  │  └────────────────────────────────────────────┘                        │ │
│  │                                                                        │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  USE CASE: "Get user's orders sorted by total" (same user, different sort) │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Comparison Table

```
┌──────────────────┬─────────────────────┬─────────────────────────────────────┐
│                  │  SECONDARY INDEX    │  GLOBAL SECONDARY INDEX (GSI)       │
│                  │  (Cassandra/Local)  │  (DynamoDB)                         │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Partition Key    │ Same as base table  │ Can be DIFFERENT from base table    │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Storage          │ On same node as     │ Separate "table" with own           │
│                  │ base data           │ partitioning                        │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Query Pattern    │ Scatter-gather      │ Single partition lookup             │
│                  │ (hit all nodes!)    │                                     │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Consistency      │ Strongly consistent │ Eventually consistent               │
│                  │                     │ (async replication lag)             │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Write Cost       │ Low (local update)  │ Higher (replicate to GSI)           │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ Read Cost        │ High for large      │ Low (direct partition access)       │
│                  │ clusters            │                                     │
├──────────────────┼─────────────────────┼─────────────────────────────────────┤
│ When to Use      │ Small clusters,     │ High-cardinality queries,           │
│                  │ low-cardinality     │ different access patterns           │
│                  │ columns             │                                     │
└──────────────────┴─────────────────────┴─────────────────────────────────────┘
```

#### DynamoDB Terminology Mapping

```
┌────────────────────┬────────────────────┬────────────────────┐
│     Cassandra      │      DynamoDB      │      Meaning       │
├────────────────────┼────────────────────┼────────────────────┤
│   Partition Key    │   Partition Key    │ Where data lives   │
│   Clustering Key   │    Sort Key        │ Order within       │
│   Primary Key      │   Composite Key    │ PK + SK together   │
│   Secondary Index  │   LSI / GSI        │ Query on non-keys  │
└────────────────────┴────────────────────┴────────────────────┘
```

#### Anti-Patterns for NoSQL Indexes

```
❌ WRONG: Creating secondary index on high-cardinality column
   → Scatter-gather across entire cluster for every query!

❌ WRONG: Not including partition key in queries
   → Full cluster scan, defeats the purpose of partitioning

❌ WRONG: Using GSI for data that needs strong consistency
   → GSI is eventually consistent, may return stale data

❌ WRONG: Creating too many GSIs
   → Each GSI doubles your write costs (write to base + GSI)

✅ RIGHT: Model your tables around your query patterns
   → One table per query, denormalize data, embrace duplication
```

---

## 2. MVCC (Multi-Version Concurrency Control)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    📍 SCOPE: SINGLE-NODE FOCUS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  In this section, we cover MVCC on a SINGLE database server.                │
│  This is the foundation you need to understand first.                       │
│                                                                              │
│  WHAT WE COVER HERE (Level 2):                                              │
│  • How one PostgreSQL/MySQL instance handles concurrent transactions        │
│  • Transaction IDs, visibility rules, isolation levels                      │
│  • Single-node: xmin/xmax, undo logs, snapshot isolation                    │
│                                                                              │
│  WHAT COMES LATER (Level 3 - Distributed Systems):                          │
│  • How to achieve consistent snapshots ACROSS multiple nodes                │
│  • Global timestamp ordering (Hybrid Logical Clocks, TrueTime)              │
│  • Distributed transactions (2PC, Saga pattern)                             │
│  • Conflict resolution (Vector Clocks, CRDTs) when nodes diverge            │
│                                                                              │
│  CONNECTION:                                                                 │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │  Single-Node MVCC ──────► Distributed MVCC                              ││
│  │  (This section)           (Level 3)                                     ││
│  │                                                                         ││
│  │  Same concept, but distributed adds:                                    ││
│  │  • Global timestamps instead of local transaction IDs                   ││
│  │  • Cross-node coordination (which node has latest version?)             ││
│  │  • Conflict resolution when network partitions occur                    ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  👉 Master single-node first, then distributed concepts build naturally.    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### The Concurrency Problem

```
PROBLEM: Read-Write Conflicts

TIME    Writer                    Reader
─────────────────────────────────────────────────
T1      BEGIN                     
T2      UPDATE users              
        SET balance = 100         
        WHERE id = 1              
T3                                BEGIN
T4                                SELECT balance 
                                  FROM users 
                                  WHERE id = 1
                                  
QUESTION: What does the reader see?
• Old value (balance = 50)?
• New value (balance = 100)?
• Is the reader blocked?

WITHOUT MVCC (Lock-based):
• Reader is BLOCKED until writer commits/rolls back
• This destroys read throughput

WITH MVCC:
• Reader sees OLD value (50) - consistent snapshot
• Reader is NEVER blocked by writers
• Writers are NEVER blocked by readers
```

### How MVCC Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                      MVCC ARCHITECTURE                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Every row has hidden system columns:                                │
│                                                                      │
│  PostgreSQL:                                                         │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  xmin   │  xmax   │  user_id  │  balance  │  email           │   │
│  │  (100)  │  (102)  │    1      │    50     │  a@b.com         │   │
│  └──────────────────────────────────────────────────────────────┘   │
│     ▲          ▲                                                     │
│     │          └── Transaction that deleted/updated this version     │
│     └───────────── Transaction that created this version             │
│                                                                      │
│  MySQL InnoDB:                                                       │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │  DB_TRX_ID  │  DB_ROLL_PTR  │  user_id  │  balance  │ email  │   │
│  └──────────────────────────────────────────────────────────────┘   │
│       ▲               ▲                                              │
│       │               └── Pointer to undo log (previous versions)    │
│       └────────────────── Transaction ID that modified this row      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### MVCC Read Path

```
SCENARIO: Transaction 105 wants to read user_id = 1

Current row versions in table:
┌─────────────────────────────────────────────────────┐
│  Version 1: xmin=100, xmax=102, balance=50          │ ← Old version
│  Version 2: xmin=102, xmax=∞,   balance=75          │ ← Current
└─────────────────────────────────────────────────────┘

Transaction states:
• TXN 100: Committed at timestamp T1
• TXN 102: Committed at timestamp T3
• TXN 105: Started at timestamp T2 (between T1 and T3)

VISIBILITY CHECK for TXN 105:
┌─────────────────────────────────────────────────────────────────────┐
│ Is Version 2 visible to TXN 105?                                    │
│                                                                      │
│ 1. Was xmin (102) committed before TXN 105 started?                 │
│    → NO, TXN 102 committed at T3, but TXN 105 started at T2         │
│    → Version 2 is INVISIBLE to TXN 105                              │
│                                                                      │
│ Is Version 1 visible to TXN 105?                                    │
│                                                                      │
│ 1. Was xmin (100) committed before TXN 105 started?                 │
│    → YES, TXN 100 committed at T1                                   │
│ 2. Is xmax (102) still active or committed after TXN 105 started?   │
│    → TXN 102 wasn't committed when 105 started                      │
│    → Version 1 is VISIBLE to TXN 105                                │
│                                                                      │
│ RESULT: TXN 105 sees balance = 50 (the old version!)                │
└─────────────────────────────────────────────────────────────────────┘
```

### PostgreSQL vs MySQL MVCC

| Aspect | PostgreSQL | MySQL InnoDB |
|--------|------------|--------------|
| **Old versions stored** | In main table (heap) | In undo log (separate) |
| **Version chain** | Multiple tuples in heap | Rollback pointer chain |
| **Cleanup** | VACUUM process | Purge thread |
| **Index handling** | Index points to all versions | Index points to latest |
| **Bloat risk** | Higher (dead tuples) | Lower |
| **VACUUM needed** | Yes, critical | No |

### The VACUUM Problem (PostgreSQL)

```
PostgreSQL MVCC creates "dead tuples":

TIME    Operation               Heap State
─────────────────────────────────────────────────────────────
T1      INSERT id=1             [id:1, xmin:100, xmax:∞]
T2      UPDATE id=1             [id:1, xmin:100, xmax:101] ← Dead
                                [id:1, xmin:101, xmax:∞]  ← Live
T3      UPDATE id=1             [id:1, xmin:100, xmax:101] ← Dead
                                [id:1, xmin:101, xmax:102] ← Dead  
                                [id:1, xmin:102, xmax:∞]  ← Live

PROBLEM: Dead tuples waste space and slow down scans!

SOLUTION: VACUUM
• Marks dead tuple space as reusable
• VACUUM FULL: Rewrites table (locks table!)
• autovacuum: Background process (tune it!)

INTERVIEW TIP: "PostgreSQL requires VACUUM tuning for write-heavy
workloads to prevent table bloat."
```

### MVCC Benefits

```
1. READERS NEVER BLOCK WRITERS
   • SELECT doesn't acquire locks that block INSERT/UPDATE
   • Perfect for read-heavy OLTP workloads

2. WRITERS NEVER BLOCK READERS  
   • UPDATE doesn't block SELECT
   • No read latency spikes during writes

3. CONSISTENT SNAPSHOTS
   • Reader sees database as of transaction start
   • No "torn reads" or inconsistent data

4. NO LOCK WAITS FOR READS
   • Reads always succeed immediately
   • Huge throughput improvement vs lock-based
```

---

## 3. Isolation Levels

### The Isolation Spectrum

```
WEAKER                                                      STRONGER
(Faster)                                                    (Slower)
   │                                                            │
   ▼                                                            ▼
┌──────────┐  ┌────────────────┐  ┌─────────────────┐  ┌───────────────┐
│READ      │  │READ            │  │REPEATABLE       │  │SERIALIZABLE   │
│UNCOMMITTED│  │COMMITTED       │  │READ             │  │               │
└──────────┘  └────────────────┘  └─────────────────┘  └───────────────┘
     │               │                    │                    │
     │               │                    │                    │
  Dirty           Dirty               Dirty                Dirty
  Reads           Reads               Reads                Reads
  Possible        PREVENTED           PREVENTED            PREVENTED
                                                           
  Non-Rep         Non-Rep             Non-Rep              Non-Rep
  Reads           Reads               Reads                Reads
  Possible        Possible            PREVENTED            PREVENTED
                                                           
  Phantom         Phantom             Phantom              Phantom
  Reads           Reads               Reads                Reads
  Possible        Possible            Possible*            PREVENTED
                                                           
                                      * Depends on DB
```

### The Anomalies Explained

#### Dirty Read

```
DIRTY READ: Reading uncommitted data from another transaction

TXN A                           TXN B
──────────────────────────────────────────
BEGIN
UPDATE accounts 
SET balance = 100 
WHERE id = 1
(balance was 50)
                                BEGIN
                                SELECT balance FROM accounts
                                WHERE id = 1
                                → Returns 100 (uncommitted!)
ROLLBACK
(balance is back to 50)
                                -- TXN B made decisions based on 
                                -- data that never existed!

PREVENTED BY: Read Committed and above
```

#### Non-Repeatable Read

```
NON-REPEATABLE READ: Same query returns different results in same transaction

TXN A                           TXN B
──────────────────────────────────────────
BEGIN
SELECT balance FROM accounts
WHERE id = 1
→ Returns 50
                                BEGIN
                                UPDATE accounts 
                                SET balance = 100 
                                WHERE id = 1
                                COMMIT
SELECT balance FROM accounts
WHERE id = 1
→ Returns 100 (different!)

-- Same query, same transaction, different result!

PREVENTED BY: Repeatable Read and above
```

#### Phantom Read

```
PHANTOM READ: New rows appear that match a previous query's criteria

TXN A                           TXN B
──────────────────────────────────────────
BEGIN
SELECT COUNT(*) FROM orders
WHERE status = 'pending'
→ Returns 5
                                BEGIN
                                INSERT INTO orders 
                                (status) VALUES ('pending')
                                COMMIT
SELECT COUNT(*) FROM orders
WHERE status = 'pending'
→ Returns 6 (phantom row appeared!)

PREVENTED BY: Serializable
(PostgreSQL's Repeatable Read also prevents this)
```

#### Write Skew (The Tricky One!)

```
WRITE SKEW: Two transactions read same data, make decisions, 
            write different rows, result violates constraint

SCENARIO: On-call system, at least 1 doctor must be on-call

CONSTRAINT: COUNT(*) WHERE on_call = true >= 1

Current state: Alice and Bob are both on-call

TXN A (Alice wants off)         TXN B (Bob wants off)
──────────────────────────────────────────────────────
BEGIN                           BEGIN
SELECT COUNT(*) FROM doctors
WHERE on_call = true
→ Returns 2 (safe to leave!)
                                SELECT COUNT(*) FROM doctors
                                WHERE on_call = true
                                → Returns 2 (safe to leave!)
UPDATE doctors 
SET on_call = false 
WHERE name = 'Alice'
                                UPDATE doctors 
                                SET on_call = false 
                                WHERE name = 'Bob'
COMMIT                          COMMIT

RESULT: BOTH doctors are off-call! Constraint violated!

WHY IT HAPPENED:
• Each transaction read the same data
• Each made a decision based on that data
• Each wrote to DIFFERENT rows
• No conflict detected because no row was modified by both

PREVENTED BY: Serializable (via conflict detection or locking)
WORKAROUND: SELECT ... FOR UPDATE (explicit locking)
```

### Isolation Level Implementations

| Database | Default Level | Repeatable Read Behavior |
|----------|---------------|-------------------------|
| PostgreSQL | Read Committed | Prevents phantoms (Snapshot Isolation) |
| MySQL InnoDB | Repeatable Read | Gap locking prevents phantoms |
| Oracle | Read Committed | Only has Read Committed and Serializable |
| SQL Server | Read Committed | Has full spectrum |

### PostgreSQL Snapshot Isolation

```
PostgreSQL's "Repeatable Read" is actually Snapshot Isolation:

1. At transaction start, take a "snapshot" of all committed transactions
2. Throughout the transaction, only see data from that snapshot
3. If two transactions modify the same row → first committer wins,
   second gets "could not serialize" error

BENEFIT: Prevents phantoms (unlike standard SQL Repeatable Read)
DOWNSIDE: Write skew is still possible (need Serializable for that)
```

### MySQL Gap Locking

```
MySQL's approach to preventing phantoms at Repeatable Read:

GAP LOCK: Lock the "gap" between index entries

Index entries: [10, 20, 30, 40, 50]

Query: SELECT * FROM t WHERE id > 25 AND id < 45 FOR UPDATE

Locks acquired:
• Record lock on 30
• Record lock on 40  
• Gap lock on (20, 30) - prevents inserts between 20 and 30
• Gap lock on (30, 40) - prevents inserts between 30 and 40
• Gap lock on (40, 50) - prevents inserts between 40 and 50

DOWNSIDE: Can cause deadlocks in high-concurrency scenarios
```

### Choosing the Right Isolation Level

```
READ COMMITTED (Default for many):
├── Best for: General OLTP workloads
├── Allows: Non-repeatable reads (usually acceptable)
├── Trade-off: Fastest, fewest locks/conflicts
└── Use when: Individual query consistency is enough

REPEATABLE READ:
├── Best for: Reports, multi-query transactions
├── Allows: Write skew (be aware!)
├── Trade-off: More memory (maintain snapshot)
└── Use when: Transaction must see consistent data throughout

SERIALIZABLE:
├── Best for: Financial transactions, constraint enforcement
├── Allows: Nothing (fully isolated)
├── Trade-off: Slowest, most conflicts/retries
└── Use when: Correctness is paramount

INTERVIEW TIP: "We use Read Committed for most operations but 
Serializable for financial transfers to prevent write skew."
```

---

## 4. Interview Checklist

### Questions You Should Be Able to Answer

#### Indexing
- [ ] "What's the difference between clustered and non-clustered indexes?"
- [ ] "Why does column order matter in composite indexes?"
- [ ] "When would an index hurt performance?"
- [ ] "What is index selectivity and why does it matter?"
- [ ] "How do covering indexes avoid heap lookups?"

#### MVCC
- [ ] "How do readers and writers avoid blocking each other?"
- [ ] "What's the difference between PostgreSQL and MySQL MVCC?"
- [ ] "Why does PostgreSQL need VACUUM?"
- [ ] "How does a transaction know which row versions to see?"

#### Isolation Levels
- [ ] "What's the difference between dirty read and non-repeatable read?"
- [ ] "What is write skew and how do you prevent it?"
- [ ] "Why might Serializable cause more transaction retries?"
- [ ] "What isolation level would you use for a banking application?"

### Quick Reference Table

| Anomaly | Read Uncommitted | Read Committed | Repeatable Read | Serializable |
|---------|------------------|----------------|-----------------|--------------|
| Dirty Read | ❌ Possible | ✅ Prevented | ✅ Prevented | ✅ Prevented |
| Non-Repeatable Read | ❌ Possible | ❌ Possible | ✅ Prevented | ✅ Prevented |
| Phantom Read | ❌ Possible | ❌ Possible | ⚠️ Varies | ✅ Prevented |
| Write Skew | ❌ Possible | ❌ Possible | ❌ Possible | ✅ Prevented |

### Common Pitfalls

| Mistake | Why It's Wrong | Correct Understanding |
|---------|----------------|----------------------|
| "More indexes = faster" | Slows down writes | Index based on query patterns |
| "MVCC means no locks" | Writes still lock | Writers don't block readers |
| "Repeatable Read prevents all issues" | Write skew still possible | Need Serializable for full isolation |
| "Serializable is always safest" | Can cause performance issues | Right isolation for the use case |

---

## Next Steps

Continue to **[Level 3: Distributed Systems](03_DISTRIBUTED_SYSTEMS.md)** to learn about replication, sharding, and CAP theorem.

