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

**1. [Indexing Deep Dive](#1-indexing-deep-dive)** (SQL Focus)
   - [Clustered vs Non-Clustered Indexes](#clustered-vs-non-clustered-indexes)
   - [Composite Indexes](#composite-indexes-multi-column)
   - [Index Selectivity](#index-selectivity)
   - [Covering Indexes](#covering-indexes-index-only-scans)

**2. [NoSQL Indexing](#nosql-indexing-partition-key-clustering-key--secondary-indexes)**
   - Partition Key vs Clustering Key
   - Secondary Indexes (Local & Global)

**3. [Transactions & Concurrency Control](#2-transactions--concurrency-control)**
   - [2.1 What is a Transaction?](#21-what-is-a-transaction) (ACID)
   - [2.2 Concurrency Anomalies](#22-what-can-go-wrong-concurrency-anomalies)
   - [2.3 Locking](#23-the-naive-solution-locking)
   - [2.4 MVCC](#24-the-clever-solution-mvcc-multi-version-concurrency-control)
   - [2.5 Isolation Levels](#25-isolation-levels-choosing-your-protection)
   - [2.6 How It All Ties Together](#26--how-it-all-ties-together) ← Connect the concepts!
   - [2.7 Pessimistic vs Optimistic Locking](#27-pessimistic-vs-optimistic-locking)
   - [2.8 Famous Concurrency Problems](#28-famous-concurrency-problems--solutions-interview-gold) ← Interview Gold!
   - [2.9 SQL vs NoSQL: Transaction Support](#29-sql-vs-nosql-transaction--concurrency-support)
   - [2.10 Level 2 → Level 3 Connection](#210-level-2--level-3-connection)

**4. [Interview Checklist](#3-interview-checklist)**
   - [Quick Reference: Concurrency Anomalies](#quick-reference-concurrency-anomalies)
   - [Quick Reference: Pessimistic vs Optimistic](#quick-reference-pessimistic-vs-optimistic)
   - [Common Pitfalls](#common-pitfalls)

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

## 2. Transactions & Concurrency Control

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    📖 THE STORY WE'RE TELLING                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  This section follows a natural progression:                                │
│                                                                              │
│  1. WHAT IS A TRANSACTION? → The atomic unit of work                        │
│  2. WHAT CAN GO WRONG? → Concurrency anomalies (reads AND writes)           │
│  3. NAIVE SOLUTION → Locking everything (simple but slow)                   │
│  4. CLEVER SOLUTION → MVCC (readers don't block writers)                    │
│  5. TUNING KNOBS → Isolation levels (how much protection?)                  │
│  6. CHOOSING STRATEGY → Pessimistic vs Optimistic locking                   │
│                                                                              │
│  📍 SCOPE: Single-node concurrency                                          │
│  📍 EXTENDS TO: Level 3 covers distributed transactions (2PC, Sagas)        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.1 What is a Transaction?

> **One-liner:** A transaction is an atomic unit of work where ALL operations succeed together or FAIL together—there's no partial state.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TRANSACTION: THE ACID GUARANTEE                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Transfer $100 from Alice to Bob                                  │
│                                                                              │
│  BEGIN TRANSACTION;                                                         │
│      UPDATE accounts SET balance = balance - 100 WHERE user = 'Alice';     │
│      UPDATE accounts SET balance = balance + 100 WHERE user = 'Bob';       │
│  COMMIT;                                                                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  WITHOUT TRANSACTION (What could go wrong):                                 │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  Step 1: Deduct $100 from Alice    ✓ (Alice: $900)                     │ │
│  │  Step 2: 💥 CRASH / POWER FAILURE                                      │ │
│  │  Step 3: Add $100 to Bob           ✗ (Never executed!)                 │ │
│  │                                                                        │ │
│  │  RESULT: $100 vanished into thin air! Alice lost money, Bob got nothing│ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  WITH TRANSACTION:                                                          │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │  Step 1: Deduct $100 from Alice    (logged to WAL, not yet committed)  │ │
│  │  Step 2: 💥 CRASH                                                      │ │
│  │  Step 3: On restart → ROLLBACK (Alice gets $100 back)                  │ │
│  │                                                                        │ │
│  │  RESULT: Either BOTH happen or NEITHER happens. Money is safe!         │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### ACID Properties (Quick Reference)

| Property | Meaning | Ensures |
|----------|---------|---------|
| **Atomicity** | All or nothing | No partial transactions |
| **Consistency** | Valid state to valid state | Constraints always hold |
| **Isolation** | Transactions don't interfere | Concurrent = sequential result |
| **Durability** | Committed = permanent | Survives crashes |

---

#### What Happens When a Transaction Commits?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│          COMMIT: BUFFER POOL vs DISK                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  QUESTION: "When I commit, does data go to disk or buffer pool?"            │
│                                                                              │
│  ANSWER: Both, but differently!                                             │
│                                                                              │
│  ON COMMIT:                                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  1. WAL (Write-Ahead Log) → FLUSHED TO DISK immediately ✓                  │
│     This guarantees durability. If crash, WAL can replay.                  │
│                                                                              │
│  2. Data pages → STAY IN BUFFER POOL (as "dirty pages")                    │
│     Written to disk LATER by background process.                           │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │                                                                    │    │
│  │   COMMIT                                                           │    │
│  │     │                                                              │    │
│  │     ├──► WAL ──► DISK (immediate, synchronous)                    │    │
│  │     │           Guarantees durability!                             │    │
│  │     │                                                              │    │
│  │     └──► Data Page ──► Buffer Pool ──► DISK (later, async)        │    │
│  │                        (dirty page)    (checkpoint/bgwriter)       │    │
│  │                                                                    │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  WHY? Performance!                                                          │
│  • Disk writes are slow                                                    │
│  • WAL is sequential (fast), data pages are random (slow)                  │
│  • As long as WAL is on disk, data can be recovered                        │
│                                                                              │
│  (See Level 1: Storage Internals for more on WAL and Buffer Pool)          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 What Can Go Wrong? (Concurrency Anomalies)

When multiple transactions run concurrently, bad things can happen. Let's categorize them:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        CONCURRENCY ANOMALIES                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  READ ANOMALIES (Problems when READING data):                               │
│  ────────────────────────────────────────────                               │
│  • Dirty Read       → Read uncommitted data (might be rolled back!)        │
│  • Non-Repeatable   → Same query, different result (row was modified)      │
│  • Phantom Read     → New rows appear matching your query criteria         │
│                                                                              │
│  WRITE ANOMALIES (Problems when WRITING data):                              │
│  ─────────────────────────────────────────────                              │
│  • Dirty Write      → Overwrite another transaction's uncommitted write    │
│  • Lost Update      → Two writes, one silently overwrites the other        │
│  • Write Skew       → Two transactions make conflicting decisions          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### READ ANOMALY 1: Dirty Read

> **Problem:** Reading uncommitted data that might be rolled back.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              DIRTY READ                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TXN A (Writer)                       TXN B (Reader)                        │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                                                      │
│  UPDATE accounts                                                            │
│  SET balance = 100                                                          │
│  WHERE id = 1                                                               │
│  (balance was 50, now 100 in-flight)                                        │
│                                        BEGIN                                │
│                                        SELECT balance FROM accounts         │
│                                        WHERE id = 1                         │
│                                        → Returns 100 ← UNCOMMITTED DATA!   │
│  ROLLBACK ← Changed my mind!                                                │
│  (balance is back to 50)                                                    │
│                                        -- TXN B made decisions based on    │
│                                        -- data that NEVER EXISTED!          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  REAL-WORLD IMPACT:                                                         │
│  • TXN B might approve a loan based on a $100 balance that was rolled back │
│  • Inventory system thinks item exists, but insert was rolled back         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  PREVENTION: Read Committed isolation or higher (→ See Section 2.5)        │
│              MVCC ensures you only see committed data (→ See Section 2.4)  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### READ ANOMALY 2: Non-Repeatable Read

> **Problem:** Same query returns different results within the same transaction.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          NON-REPEATABLE READ                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TXN A (Reader)                       TXN B (Writer)                        │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                                                      │
│  SELECT balance FROM accounts                                               │
│  WHERE id = 1                                                               │
│  → Returns 50                                                               │
│                                        BEGIN                                │
│                                        UPDATE accounts                      │
│                                        SET balance = 100                    │
│                                        WHERE id = 1                         │
│                                        COMMIT ✓                             │
│  -- Later in SAME transaction:                                              │
│  SELECT balance FROM accounts                                               │
│  WHERE id = 1                                                               │
│  → Returns 100 ← DIFFERENT!                                                 │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  WHY IT'S BAD:                                                              │
│  • Report shows inconsistent totals (read balance twice, got different)    │
│  • Business logic assumed balance = 50, but later sees 100                 │
│  • "Within my transaction, the world should appear frozen"                 │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  PREVENTION: Repeatable Read isolation or higher (→ See Section 2.5)       │
│              MVCC snapshot keeps your view frozen (→ See Section 2.4)      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### READ ANOMALY 3: Phantom Read

> **Problem:** New rows appear that match a previous query's criteria, causing decisions made earlier in the transaction to become invalid.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            PHANTOM READ                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Flight booking - max 10 seats per flight                         │
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                                                      │
│  SELECT COUNT(*) FROM bookings                                              │
│  WHERE flight_id = 'ABC123'                                                 │
│  → Returns 9 (one seat left!)                                               │
│                                                                              │
│  -- Decision: OK, I can book one more...                                    │
│                                                                              │
│                                        BEGIN                                │
│                                        SELECT COUNT(*) FROM bookings        │
│                                        WHERE flight_id = 'ABC123'           │
│                                        → Returns 9 (one seat left!)         │
│                                                                              │
│                                        INSERT INTO bookings                 │
│                                        (flight_id, passenger)               │
│                                        VALUES ('ABC123', 'Bob')             │
│                                        COMMIT ✓ (now 10 seats)              │
│                                                                              │
│  -- Still think there's 1 seat left (based on old count)...                │
│  INSERT INTO bookings                                                       │
│  (flight_id, passenger)                                                     │
│  VALUES ('ABC123', 'Alice')                                                 │
│  COMMIT ✓                                                                   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  RESULT: Flight has 11 bookings! OVERBOOKING!                               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  WHY IT'S A PROBLEM:                                                        │
│  • TXN A made a DECISION based on the count (9 seats → 1 left)             │
│  • A new row was INSERTED that matches the same WHERE clause               │
│  • TXN A's decision is now INVALID, but it doesn't know!                   │
│  • Result: Business constraint violated                                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  WHEN PHANTOMS ARE OK vs PROBLEMATIC:                                       │
│                                                                              │
│  ✅ OK: Displaying a list (user refreshes → sees new data, fine!)          │
│  ❌ BAD: Making decisions based on counts or aggregates                    │
│  ❌ BAD: Reports that must be internally consistent                        │
│  ❌ BAD: Constraint checking (max items, unique usernames, etc.)           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  DIFFERENCE FROM NON-REPEATABLE READ:                                       │
│  • Non-Repeatable: EXISTING row was MODIFIED                               │
│  • Phantom: NEW row was INSERTED that matches your WHERE clause            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  PREVENTION:                                                                │
│  • Serializable isolation (→ See Section 2.5)                              │
│  • PostgreSQL Repeatable Read also prevents (uses snapshot isolation)      │
│  • MySQL uses gap locking at Repeatable Read (→ See Section 2.5)           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### WRITE ANOMALY 0: Dirty Write (Most Fundamental!)

> **Problem:** One transaction overwrites data that another uncommitted transaction has already written.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DIRTY WRITE                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Two transactions updating the same row concurrently              │
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                                                      │
│  UPDATE accounts                                                            │
│  SET balance = 100                                                          │
│  WHERE id = 1                                                               │
│  (uncommitted!)                                                             │
│                                        BEGIN                                │
│                                        UPDATE accounts                      │
│                                        SET balance = 200                    │
│                                        WHERE id = 1                         │
│                                        (overwrites uncommitted value!)      │
│                                        COMMIT ✓                             │
│  ROLLBACK ← TXN A aborts!                                                   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  QUESTION: What is the balance now?                                         │
│                                                                              │
│  • TXN B committed balance = 200                                            │
│  • But TXN A rolled back... should balance go back to original?             │
│  • TXN B's commit was based on overwriting TXN A's uncommitted write!       │
│  • DATABASE IS NOW IN INCONSISTENT STATE!                                   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  REAL-WORLD EXAMPLE: Ordering System                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Two tables: orders, invoices (linked by order_id)                         │
│                                                                              │
│  TXN A: Insert order 123, insert invoice for order 123                     │
│  TXN B: Insert order 456, insert invoice for order 456                     │
│                                                                              │
│  WITH DIRTY WRITES:                                                         │
│  TXN A: Insert order 123                                                   │
│  TXN B: Overwrites with order 456 (dirty write!)                           │
│  TXN A: Insert invoice for "order 123"                                     │
│  TXN B: Insert invoice for "order 456"                                     │
│                                                                              │
│  RESULT: Invoice points to wrong order! Data corruption!                   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  HOW DATABASES PREVENT THIS:                                                │
│                                                                              │
│  ALL databases prevent dirty writes by default—even Read Uncommitted!      │
│  • Writers acquire exclusive (X) locks before modifying                    │
│  • Another writer must WAIT until first writer commits/rollbacks           │
│  • This is so fundamental, it's not even listed as an isolation concern    │
│                                                                              │
│  PREVENTED BY: All isolation levels (via write locks)                       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### WRITE ANOMALY 1: Lost Update (CRITICAL!)

> **Problem:** Two transactions read the same value, both modify it, one overwrites the other's change.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           LOST UPDATE                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Two users increment a counter (counter = 10)                     │
│                                                                              │
│  TXN A                                TXN B                                 │
│  ────────────────────────────────────────────────────────3───────────────    │
│  BEGIN                                BEGIN                                 │
│  SELECT counter FROM t                                                      │
│  → Returns 10                                                               │
│                                        SELECT counter FROM t                │
│                                        → Returns 10                         │
│  -- Application: newVal = 10 + 1                                            │
│  UPDATE t SET counter = 11                                                  │
│                                        -- Application: newVal = 10 + 1      │
│                                        UPDATE t SET counter = 11           │
│  COMMIT ✓                              COMMIT ✓                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  EXPECTED: counter = 12 (incremented twice)                                 │
│  ACTUAL:   counter = 11 ← TXN A's update was LOST!                          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  REAL-WORLD EXAMPLES:                                                       │
│  • Two users edit same document, one's changes disappear                   │
│  • Inventory decremented twice, but only one decrement recorded            │
│  • Two payments processed, one payment lost                                │
│  • Like count: 1000 users click "like", only 800 recorded                  │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  PREVENTION:                                                                │
│  • Atomic operations: UPDATE t SET counter = counter + 1 (→ See below)     │
│  • Pessimistic locking: SELECT ... FOR UPDATE (→ See Section 2.6)          │
│  • Optimistic locking: Version column + retry (→ See Section 2.6)          │
│  • Serializable isolation for complex cases (→ See Section 2.5)            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### The Root Cause: Read-Modify-Write is NOT Atomic

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   READ-MODIFY-WRITE PATTERN                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  THE PATTERN THAT CAUSES LOST UPDATES:                                      │
│  ─────────────────────────────────────                                      │
│                                                                              │
│     ┌─────────┐      ┌─────────┐      ┌─────────┐                           │
│     │  READ   │ ───► │ MODIFY  │ ───► │  WRITE  │                           │
│     │ counter │      │ +1 in   │      │ counter │                           │
│     │ from DB │      │ app code│      │ to DB   │                           │
│     └─────────┘      └─────────┘      └─────────┘                           │
│          │                                  │                               │
│          └──────── GAP WHERE OTHERS ────────┘                               │
│                    CAN INTERFERE                                            │
│                                                                              │
│  THE PROBLEM:                                                               │
│  • Read (SELECT counter) → value goes to application                       │
│  • Modify (newVal = counter + 1) → happens in application memory           │
│  • Write (UPDATE counter = newVal) → value goes back to database           │
│                                                                              │
│  Between READ and WRITE, another transaction can sneak in!                  │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTIONS:                                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 1: Atomic Database Operation (BEST when possible)                 │
│  ───────────────────────────────────────────────────────────                │
│  Instead of:                                                                │
│    val = SELECT counter FROM t;                                             │
│    UPDATE t SET counter = val + 1;                                          │
│                                                                              │
│  Do this:                                                                   │
│    UPDATE t SET counter = counter + 1;   ← Database handles atomically!    │
│                                                                              │
│  Works for: Increment, decrement, append, simple arithmetic                │
│  Doesn't work for: Complex business logic between read and write           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 2: Pessimistic Locking (SELECT ... FOR UPDATE)                    │
│  ────────────────────────────────────────────────────────                   │
│  SELECT counter FROM t WHERE id = 1 FOR UPDATE;  ← Lock the row            │
│  -- do complex business logic --                                            │
│  UPDATE t SET counter = newVal WHERE id = 1;                                │
│  COMMIT;                                         ← Release lock             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 3: Optimistic Locking (Version check)                             │
│  ───────────────────────────────────────────────                            │
│  SELECT counter, version FROM t WHERE id = 1;                               │
│  -- do complex business logic --                                            │
│  UPDATE t SET counter = newVal, version = version + 1                       │
│  WHERE id = 1 AND version = oldVersion;  ← Fails if version changed!       │
│  -- If 0 rows affected, retry from beginning                               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 4: Compare-And-Swap (CAS) - Common in NoSQL                       │
│  ─────────────────────────────────────────────────────                      │
│  Same as optimistic locking, but often built into the database:            │
│  • Redis: WATCH + MULTI/EXEC                                               │
│  • DynamoDB: ConditionExpression                                           │
│  • Cassandra: IF column = expected_value (Lightweight Transactions)        │
│                                                                              │
│  DynamoDB Example:                                                          │
│    UpdateItem(                                                              │
│      Key: {id: 1},                                                          │
│      UpdateExpression: "SET counter = counter + 1",                         │
│      ConditionExpression: "counter = :expected",                            │
│      ExpressionAttributeValues: {":expected": 10}                           │
│    )                                                                         │
│    → Fails if counter ≠ 10                                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### WRITE ANOMALY 2: Write Skew

> **Problem:** Two transactions make decisions based on overlapping reads, write to different rows, together violate a constraint.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            WRITE SKEW                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Hospital on-call system                                          │
│  CONSTRAINT: At least 1 doctor must be on-call at all times                │
│  CURRENT STATE: Alice and Bob are both on-call                              │
│                                                                              │
│  TXN A (Alice wants off)              TXN B (Bob wants off)                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                BEGIN                                 │
│  SELECT COUNT(*) FROM doctors                                               │
│  WHERE on_call = true                                                       │
│  → Returns 2 (safe to leave!)                                               │
│                                        SELECT COUNT(*) FROM doctors         │
│                                        WHERE on_call = true                 │
│                                        → Returns 2 (safe to leave!)         │
│  UPDATE doctors                                                             │
│  SET on_call = false                                                        │
│  WHERE name = 'Alice'                                                       │
│                                        UPDATE doctors                       │
│                                        SET on_call = false                  │
│                                        WHERE name = 'Bob'                   │
│  COMMIT ✓                              COMMIT ✓                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  RESULT: BOTH doctors are off-call! CONSTRAINT VIOLATED!                    │
│                                                                              │
│  WHY IT'S TRICKY:                                                           │
│  • Each transaction was individually correct                               │
│  • They read the SAME data, wrote DIFFERENT rows                           │
│  • No row was modified by both → no conflict detected!                     │
│  • Database can't see the application-level constraint                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  OTHER EXAMPLES:                                                            │
│  • Double-booking: Two users book "last seat" simultaneously               │
│  • Budget: Two departments spend "remaining budget" at same time           │
│  • Username: Two users claim same username (check-then-insert)             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  PREVENTION:                                                                │
│  • Serializable isolation - ONLY this level prevents write skew!           │
│    (→ See Section 2.5)                                                     │
│  • Explicit locking: SELECT ... FOR UPDATE on the rows you're checking    │
│    (→ See Section 2.6)                                                     │
│  • Database constraints (UNIQUE, CHECK) when possible                      │
│                                                                              │
│  ⚠️  Repeatable Read does NOT prevent write skew!                           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Summary: All Concurrency Anomalies

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ANOMALY QUICK REFERENCE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ANOMALY              │ WHAT HAPPENS                │ CLASSIC EXAMPLE        │
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Dirty Write          │ Overwrite uncommitted data  │ Order/invoice mismatch │
│                       │                             │ after rollback         │
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Dirty Read           │ Read uncommitted data       │ See balance that       │
│                       │                             │ gets rolled back       │
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Non-Repeatable Read  │ Row changes between reads   │ Report shows           │
│                       │                             │ inconsistent totals    │
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Phantom Read         │ New rows appear in query    │ Count changes          │
│                       │                             │ mid-transaction        │
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Lost Update          │ One write overwrites        │ Counter incremented    │
│                       │ another's write             │ twice, +1 instead of +2│
│  ─────────────────────┼─────────────────────────────┼────────────────────── │
│  Write Skew           │ Decisions on same data,     │ Both doctors go        │
│                       │ write different rows        │ off-call               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  NOTE: Lost Update, Phantom, and Write Skew are all "check-then-act" races. │
│  The difference is WHAT changes: same row (Lost Update), new rows inserted  │
│  (Phantom), or different related rows (Write Skew).                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.3 The Naive Solution: Locking

> **Idea:** Before accessing data, acquire a lock. Other transactions must wait.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         LOCK-BASED CONCURRENCY                               │
│                    "Assume conflicts WILL happen, prevent them"             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LOCK TYPES:                                                                │
│  ───────────────────────────────────────────────────────────────────────    │
│                                                                              │
│  SHARED LOCK (S-Lock / Read Lock):                                          │
│  • Multiple transactions can hold S-lock on same row simultaneously        │
│  • Used for: SELECT (reading)                                              │
│  • Prevents: Others from writing while you're reading                      │
│                                                                              │
│  EXCLUSIVE LOCK (X-Lock / Write Lock):                                      │
│  • Only ONE transaction can hold X-lock on a row                           │
│  • Used for: UPDATE, DELETE, INSERT                                        │
│  • Prevents: Others from reading OR writing                                │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  LOCK COMPATIBILITY MATRIX:                                                 │
│  ┌─────────────────┬──────────────┬──────────────┐                          │
│  │ Requested \Held │   S-Lock     │   X-Lock     │                          │
│  ├─────────────────┼──────────────┼──────────────┤                          │
│  │   S-Lock        │   ✅ GRANT    │   ❌ WAIT     │                          │
│  │   X-Lock        │   ❌ WAIT     │   ❌ WAIT     │                          │
│  └─────────────────┴──────────────┴──────────────┘                          │
│                                                                              │
│  READ: Multiple readers OK                                                  │
│  WRITE: Writer is alone (no readers, no other writers)                      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### How Locking Prevents Lost Update

```
┌─────────────────────────────────────────────────────────────────────────────┐
│               LOCKING PREVENTS LOST UPDATE                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WITH LOCKS (SELECT ... FOR UPDATE):                                        │
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                BEGIN                                 │
│  SELECT counter FROM t                                                      │
│  FOR UPDATE  ← Acquire X-lock!                                              │
│  → Returns 10                                                               │
│  (holds X-lock on row)                                                      │
│                                        SELECT counter FROM t                │
│                                        FOR UPDATE                           │
│                                        → ⏳ BLOCKED! Waiting for X-lock...  │
│  UPDATE t SET counter = 11                                                  │
│  COMMIT ✓                                                                   │
│  (releases X-lock)                                                          │
│                                        → Lock acquired! Returns 11          │
│                                        UPDATE t SET counter = 12           │
│                                        COMMIT ✓                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  RESULT: counter = 12 ✅ (Both increments applied!)                         │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Lock Granularity

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        LOCK GRANULARITY                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  FINE-GRAINED ◄─────────────────────────────────────► COARSE-GRAINED        │
│                                                                              │
│  ┌──────────┐    ┌──────────┐    ┌──────────┐    ┌──────────┐              │
│  │   ROW    │    │   PAGE   │    │  TABLE   │    │ DATABASE │              │
│  │   LOCK   │    │   LOCK   │    │   LOCK   │    │   LOCK   │              │
│  └──────────┘    └──────────┘    └──────────┘    └──────────┘              │
│       ▲                                                  ▲                  │
│       │                                                  │                  │
│  High concurrency                                   Low concurrency         │
│  High overhead                                      Low overhead            │
│  (track many locks)                                 (one lock)              │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  MOST DATABASES USE: Row-level locking (best balance)                       │
│  EXCEPTION: Some operations escalate to table locks if too many row locks  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### The Problem with Pure Locking: Readers Block Writers!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WHY PURE LOCKING IS SLOW                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: E-commerce product page                                          │
│  • 1000 users viewing product (readers)                                     │
│  • 1 admin updating price (writer)                                          │
│                                                                              │
│  WITH PURE S-LOCK / X-LOCK:                                                 │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Reader 1: SELECT * FROM products    → Holds S-lock                        │
│  Reader 2: SELECT * FROM products    → Holds S-lock                        │
│  Reader 3: SELECT * FROM products    → Holds S-lock                        │
│  ...                                                                        │
│  Reader 1000: SELECT * FROM products → Holds S-lock                        │
│                                                                              │
│  Admin: UPDATE products SET price = 99                                      │
│         → ⏳ BLOCKED! Waiting for ALL 1000 S-locks to release!              │
│                                                                              │
│  New Reader: SELECT * FROM products                                         │
│              → ⏳ BLOCKED! Can't get S-lock while X-lock is waiting!        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  RESULT:                                                                    │
│  • Writes starve waiting for reads to finish                               │
│  • New reads queue behind pending writes                                   │
│  • Everything slows to a crawl                                             │
│  • Throughput collapses under concurrent load                              │
│                                                                              │
│  💡 THIS IS WHY WE NEED MVCC!                                               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Two-Phase Locking (2PL)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      TWO-PHASE LOCKING (2PL)                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM: If you release a lock too early, others can sneak in!            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  BAD EXAMPLE (Release lock early):                                          │
│  ───────────────────────────────────────────────────────────────────────    │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                                                      │
│  Lock(X), Read X = 100                                                      │
│  Unlock(X)  ← Released too early!                                           │
│                                        BEGIN                                │
│                                        Lock(X), Read X = 100                │
│                                        Lock(Y), Read Y = 50                 │
│                                        Write Y = 150                        │
│                                        Unlock(X), Unlock(Y), COMMIT         │
│  Lock(Y), Read Y = 150   ← Sees Y AFTER B changed it!                      │
│  Write X = 200                                                              │
│  Unlock(Y), COMMIT                                                          │
│                                                                              │
│  RESULT: Non-serializable!                                                  │
│  • TXN A saw X BEFORE B's changes (X = 100)                                │
│  • TXN A saw Y AFTER B's changes (Y = 150)                                 │
│  • This couldn't happen if they ran one-at-a-time!                         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION: 2PL RULE                                                         │
│  Once you release ANY lock, you can't acquire NEW locks                    │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │   Number of locks held                                              │   │
│  │         ▲                                                           │   │
│  │         │     ╱╲                                                    │   │
│  │         │    ╱  ╲                                                   │   │
│  │         │   ╱    ╲                                                  │   │
│  │         │  ╱      ╲                                                 │   │
│  │         │ ╱        ╲                                                │   │
│  │         │╱          ╲                                               │   │
│  │  ───────┴────────────┴──────────────────────────────────► Time     │   │
│  │         │ GROWING    │ SHRINKING                                    │   │
│  │         │  PHASE     │   PHASE                                      │   │
│  │         │ (acquire)  │  (release)                                   │   │
│  │         │ Can't      │  Can't                                       │   │
│  │         │ release    │  acquire                                     │   │
│  │                      │                                              │   │
│  │               LOCK POINT (all locks held here)                      │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  WHY THIS WORKS:                                                            │
│  • At lock point, you hold ALL locks you'll ever need                      │
│  • No one can sneak in between your operations                             │
│  • Transactions appear to run in some serial order = SERIALIZABLE          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  TRADE-OFFS:                                                                │
│  • Hold locks longer → more blocking (readers/writers wait)                │
│  • Can cause deadlocks (two transactions waiting for each other)           │
│  • 💡 This is why MVCC was invented! (→ See Section 2.4)                   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Deadlocks

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            DEADLOCK                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                BEGIN                                 │
│  LOCK row 1 ✓                                                               │
│                                        LOCK row 2 ✓                         │
│  LOCK row 2                                                                 │
│  → ⏳ Waiting for TXN B...                                                  │
│                                        LOCK row 1                           │
│                                        → ⏳ Waiting for TXN A...            │
│                                                                              │
│     ┌───────────────────────────────────────────────────────────────┐       │
│     │                                                               │       │
│     │   TXN A ──waits for──► row 2 ◄──held by── TXN B              │       │
│     │     ▲                                        │               │       │
│     │     │                                        │               │       │
│     │   holds                                    waits for         │       │
│     │     │                                        │               │       │
│     │     ▼                                        ▼               │       │
│     │   row 1 ◄──────────── waits for ──────────────               │       │
│     │                                                               │       │
│     │              CIRCULAR WAIT = DEADLOCK!                        │       │
│     │                                                               │       │
│     └───────────────────────────────────────────────────────────────┘       │
│                                                                              │
│  RESOLUTION:                                                                │
│  • Database detects cycle, aborts one transaction (victim)                 │
│  • Application should retry the aborted transaction                        │
│                                                                              │
│  PREVENTION:                                                                │
│  • Lock resources in consistent order                                      │
│  • Use timeouts                                                            │
│  • Keep transactions short                                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.4 The Clever Solution: MVCC (Multi-Version Concurrency Control)

> **Key Insight:** Instead of blocking readers, keep multiple versions of each row. Readers see a consistent snapshot; writers create new versions.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         MVCC: THE BIG IDEA                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PURE LOCKING:                          MVCC:                               │
│  ─────────────                          ─────                               │
│  "One copy of data,                     "Multiple versions of data,         │
│   block when conflicts"                  readers see old version"           │
│                                                                              │
│  ┌─────────────────────┐               ┌─────────────────────────────┐     │
│  │ Row: balance = 100  │               │ Version 1: balance = 100    │     │
│  │                     │               │ (created by TXN 50)         │     │
│  │ Reader: ⏳ WAIT      │               │                             │     │
│  │ Writer: ✍️ WRITING   │               │ Version 2: balance = 150    │     │
│  └─────────────────────┘               │ (created by TXN 60)         │     │
│                                        └─────────────────────────────┘     │
│                                                                              │
│                                        Reader (TXN 55): Sees Version 1 ✅   │
│                                        (started before TXN 60 committed)   │
│                                                                              │
│                                        Writer: Creates Version 2 ✅         │
│                                        (doesn't touch Version 1)           │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  RESULT: READERS NEVER BLOCK WRITERS, WRITERS NEVER BLOCK READERS!          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### How MVCC Works: Version Metadata

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      MVCC HIDDEN COLUMNS                                     │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Every row has HIDDEN system columns tracking its version:                  │
│                                                                              │
│  PostgreSQL:                                                                │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  xmin   │  xmax   │  id  │  name   │  balance  │                     │  │
│  │  (100)  │  (105)  │  1   │ "Alice" │   500     │                     │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│     ▲          ▲                                                            │
│     │          └── TXN that DELETED/UPDATED this row (made it invisible)    │
│     └───────────── TXN that CREATED this row (made it visible)              │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  MySQL InnoDB:                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │ DB_TRX_ID │ DB_ROLL_PTR │  id  │  name   │  balance  │               │  │
│  │   (100)   │  → undo log │  1   │ "Alice" │   500     │               │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│       ▲             ▲                                                       │
│       │             └── Pointer to UNDO LOG (chain of previous versions)    │
│       └──────────────── Last TXN that modified this row                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### MVCC Visibility Rules

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      MVCC VISIBILITY CHECK                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  QUESTION: Can Transaction 105 see this row version?                        │
│                                                                              │
│  Row Version: xmin=100, xmax=110, balance=500                               │
│                                                                              │
│  VISIBILITY ALGORITHM:                                                      │
│  ───────────────────────────────────────────────────────────────────────    │
│                                                                              │
│  1. Is xmin (100) committed?                                                │
│     └── YES? Continue. NO? Row is invisible.                                │
│                                                                              │
│  2. Did xmin (100) commit BEFORE my transaction (105) started?              │
│     └── YES? Row was created before I started. Continue.                   │
│     └── NO? Row didn't exist when I started. Invisible.                    │
│                                                                              │
│  3. Is xmax set? (Someone deleted/updated this version)                     │
│     └── NO (xmax = ∞)? Row is still live. VISIBLE!                         │
│     └── YES? Check if xmax transaction is visible to me...                 │
│                                                                              │
│  4. Did xmax (110) commit BEFORE my transaction (105) started?              │
│     └── YES? Row was deleted before I started. INVISIBLE.                  │
│     └── NO? Row deletion hasn't "happened" from my perspective. VISIBLE!   │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  EXAMPLE:                                                                   │
│  • TXN 100 created row at T1, committed at T2                              │
│  • TXN 105 started at T3                                                   │
│  • TXN 110 deleted row at T4, committed at T5                              │
│                                                                              │
│  Timeline: T1 ── T2 ── T3 ── T4 ── T5                                      │
│            create commit start delete commit                                │
│            (100)  (100) (105) (110)  (110)                                  │
│                                                                              │
│  At T4: TXN 105 sees the row (TXN 110 hasn't committed yet)                │
│  At T5: TXN 105 STILL sees the row (its snapshot is from T3!)              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### PostgreSQL vs MySQL MVCC

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   PostgreSQL vs MySQL MVCC                                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                   PostgreSQL                    MySQL InnoDB                │
│  ─────────────────────────────────────────────────────────────────────────  │
│  Old versions      In main table (heap)        In undo log (separate)      │
│  stored in:        alongside live rows         storage area                 │
│                                                                              │
│  Version chain:    Multiple row tuples         Pointer chain through        │
│                    in same table               undo log entries             │
│                                                                              │
│  Index behavior:   Points to ALL versions      Points to latest version    │
│                    (HOT optimization helps)    (undo log for older)        │
│                                                                              │
│  Cleanup:          VACUUM (background)         Purge thread (automatic)     │
│                    CRITICAL to run!            Generally hands-off          │
│                                                                              │
│  Bloat risk:       HIGH - dead tuples          LOW - undo log is separate  │
│                    accumulate in table                                      │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  POSTGRESQL GOTCHA: The VACUUM Problem                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ Without VACUUM:                                                     │   │
│  │ • Dead tuples accumulate in table                                   │   │
│  │ • Table size grows even without new data                           │   │
│  │ • Scans get slower (reading dead rows)                             │   │
│  │ • Eventually: transaction ID wraparound (database stops!)          │   │
│  │                                                                     │   │
│  │ SOLUTION:                                                           │   │
│  │ • autovacuum (default ON, tune aggressiveness)                     │   │
│  │ • Monitor pg_stat_user_tables for dead tuple ratio                 │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### MVCC Benefits Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      MVCC BENEFITS                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  1. READERS NEVER BLOCK WRITERS                                             │
│     ───────────────────────────                                             │
│     SELECT doesn't acquire locks that block UPDATE/INSERT                   │
│     → Perfect for read-heavy OLTP (most web applications!)                  │
│                                                                              │
│  2. WRITERS NEVER BLOCK READERS                                             │
│     ───────────────────────────                                             │
│     UPDATE doesn't block SELECT (readers see old version)                   │
│     → No read latency spikes during writes                                  │
│                                                                              │
│  3. CONSISTENT SNAPSHOTS                                                    │
│     ────────────────────                                                    │
│     Reader sees database as of transaction start                            │
│     → No "torn reads" or inconsistent data                                  │
│     → Reports calculate correctly even during updates                       │
│                                                                              │
│  4. HIGH THROUGHPUT                                                         │
│     ───────────────                                                         │
│     Reads never wait → massively parallel read workloads                    │
│     → 10x throughput vs pure locking for read-heavy apps                    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  TRADE-OFFS:                                                                │
│  • Storage overhead (multiple versions)                                     │
│  • Cleanup overhead (VACUUM in PostgreSQL)                                  │
│  • Writers still block writers (same row)                                   │
│  • Doesn't prevent all anomalies (need isolation levels)                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.5 Isolation Levels: Choosing Your Protection

> MVCC provides the mechanism. Isolation levels determine HOW MUCH protection you get.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ISOLATION LEVEL SPECTRUM                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WEAKER (Faster)                                    STRONGER (Slower)       │
│       │                                                   │                  │
│       ▼                                                   ▼                  │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │
│  │    READ      │ │    READ      │ │  REPEATABLE  │ │ SERIALIZABLE │        │
│  │ UNCOMMITTED  │ │  COMMITTED   │ │    READ      │ │              │        │
│  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘        │
│        │                │                │                │                  │
│        │                │                │                │                  │
│  ┌─────┴────────────────┴────────────────┴────────────────┴─────┐           │
│  │                                                              │           │
│  │  Dirty Read         ❌         ✅         ✅         ✅      │           │
│  │  Non-Repeatable     ❌         ❌         ✅         ✅      │           │
│  │  Phantom Read       ❌         ❌         ⚠️*        ✅      │           │
│  │  Lost Update        ❌         ❌         ✅**       ✅      │           │
│  │  Write Skew         ❌         ❌         ❌         ✅      │           │
│  │                                                              │           │
│  │  ❌ = Can happen    ✅ = Prevented                           │           │
│  │  * PostgreSQL prevents, MySQL allows                        │           │
│  │  ** Only with SELECT...FOR UPDATE                           │           │
│  │                                                              │           │
│  └──────────────────────────────────────────────────────────────┘           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Level 1: Read Uncommitted (Almost Never Used)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      READ UNCOMMITTED                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT IT DOES:                                                              │
│  • No protection at all                                                     │
│  • Can read uncommitted (dirty) data                                        │
│                                                                              │
│  HOW IT'S IMPLEMENTED:                                                      │
│  • No locks for reads, no snapshot isolation                                │
│  • Just read whatever is currently in the row                               │
│                                                                              │
│  PREVENTS:        Nothing                                                   │
│  ALLOWS:          Dirty Read, Non-Repeatable, Phantom, Lost Update, Write Skew│
│                                                                              │
│  USE CASES:       Almost none in production                                 │
│                   Maybe: Approximate counts where accuracy doesn't matter   │
│                                                                              │
│  INTERVIEW NOTE:  "I would never use Read Uncommitted in production.        │
│                    Even MySQL's default of Repeatable Read is safer."       │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Level 2: Read Committed (PostgreSQL Default)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      READ COMMITTED                                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT IT DOES:                                                              │
│  • Only see data that was COMMITTED before your QUERY started              │
│  • Each query gets a fresh snapshot                                         │
│                                                                              │
│  HOW IT'S IMPLEMENTED (MVCC):                                               │
│  ───────────────────────────────────────────────────────────────────────    │
│  • At start of each STATEMENT (not transaction), take snapshot             │
│  • Only see rows where xmin is committed AND xmin < query_start            │
│                                                                              │
│  WHY DIRTY READS ARE PREVENTED:                                             │
│  ───────────────────────────────────────────────────────────────────────    │
│  TXN B writes balance=100 but hasn't committed                              │
│  TXN A runs SELECT → checks "is TXN B committed?" → NO → skips that version │
│  TXN A sees the OLD committed version                                       │
│                                                                              │
│  WHY NON-REPEATABLE READS STILL HAPPEN:                                     │
│  ───────────────────────────────────────────────────────────────────────    │
│  Query 1 at T1 → sees rows committed before T1                              │
│  TXN B commits at T2                                                        │
│  Query 2 at T3 → sees rows committed before T3 (including TXN B!)           │
│  → Different snapshots = different results                                  │
│                                                                              │
│  PREVENTS:        Dirty Read                                                │
│  ALLOWS:          Non-Repeatable, Phantom, Lost Update, Write Skew          │
│                                                                              │
│  USE CASES:       Most OLTP applications                                    │
│                   When individual query consistency is sufficient           │
│                   When you don't need cross-query consistency               │
│                                                                              │
│  EXAMPLE:                                                                   │
│  • User views product page → sees committed price                          │
│  • Price changes between page loads → user sees new price                  │
│  • Acceptable for most applications!                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Level 3: Repeatable Read (MySQL Default)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      REPEATABLE READ                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT IT DOES:                                                              │
│  • Snapshot taken at TRANSACTION start (not query start)                   │
│  • Same query always returns same results within transaction               │
│                                                                              │
│  HOW IT'S IMPLEMENTED (MVCC Snapshot Isolation):                            │
│  ───────────────────────────────────────────────────────────────────────    │
│  • At BEGIN, record "active transaction list"                              │
│  • For entire transaction, only see rows committed BEFORE begin            │
│  • Ignore all commits that happen after transaction started                │
│                                                                              │
│  WHY NON-REPEATABLE READS ARE PREVENTED:                                    │
│  ───────────────────────────────────────────────────────────────────────    │
│  TXN A begins at T1 (snapshot taken)                                        │
│  TXN B updates row and commits at T2                                        │
│  TXN A queries at T3 → still uses T1 snapshot → sees old value!            │
│  TXN A queries at T4 → still uses T1 snapshot → same old value!            │
│                                                                              │
│  HOW LOST UPDATE IS PREVENTED (with SELECT...FOR UPDATE):                   │
│  ───────────────────────────────────────────────────────────────────────    │
│  TXN A: SELECT counter FOR UPDATE → gets lock, reads 10                    │
│  TXN B: SELECT counter FOR UPDATE → ⏳ BLOCKED                              │
│  TXN A: UPDATE counter = 11, COMMIT → releases lock                        │
│  TXN B: Lock acquired → reads 11 (current value!) → sets 12                │
│  ✅ Both increments applied!                                                │
│                                                                              │
│  PREVENTS:        Dirty Read, Non-Repeatable Read, Lost Update*            │
│  ALLOWS:          Phantom (in MySQL), Write Skew                            │
│  *With explicit locking (FOR UPDATE)                                        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  POSTGRESQL VS MYSQL DIFFERENCE:                                            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  PostgreSQL (True Snapshot Isolation):                                      │
│  • Prevents phantoms! (snapshot includes "no new rows" guarantee)          │
│  • First-committer-wins for conflicting writes                             │
│                                                                              │
│  MySQL (Gap Locking):                                                       │
│  • Prevents phantoms via gap locks (locks "between" index entries)         │
│  • But gap locks can cause deadlocks                                       │
│                                                                              │
│  USE CASES:                                                                 │
│  • Reports that need consistent snapshot                                   │
│  • Business logic with multiple queries                                    │
│  • When you query same data multiple times in transaction                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Level 4: Serializable (Strongest)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                      SERIALIZABLE                                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT IT DOES:                                                              │
│  • Transactions appear to run ONE AT A TIME, in some serial order          │
│  • No anomalies possible                                                    │
│                                                                              │
│  HOW IT'S IMPLEMENTED:                                                      │
│  ───────────────────────────────────────────────────────────────────────    │
│                                                                              │
│  OPTION 1: Strict 2PL (Traditional)                                         │
│  • Hold all locks until commit                                              │
│  • Readers block writers, writers block readers                            │
│  • SLOW but simple                                                          │
│                                                                              │
│  OPTION 2: SSI - Serializable Snapshot Isolation (PostgreSQL)               │
│  • Start with snapshot isolation (MVCC)                                    │
│  • Track read/write dependencies                                           │
│  • If cycle detected → abort one transaction                               │
│  • Better performance than 2PL!                                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  HOW SSI DETECTS CYCLES:                                                    │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SSI tracks two types of dependencies between concurrent transactions:      │
│                                                                              │
│  1. rw-dependency (read-write): A reads X, then B writes X                 │
│     "A read something that B later modified"                               │
│                                                                              │
│  2. wr-dependency (write-read): A writes X, then B reads X                 │
│     "B read something that A wrote" (normal visibility)                    │
│                                                                              │
│  THE DANGEROUS PATTERN (causes non-serializable execution):                │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │                                                                    │    │
│  │   TXN A ───rw───► TXN B                                           │    │
│  │     ▲               │                                              │    │
│  │     │               │                                              │    │
│  │     └──────rw───────┘                                              │    │
│  │                                                                    │    │
│  │   A read something B will write                                   │    │
│  │   B read something A will write                                   │    │
│  │   = CYCLE! One must abort.                                        │    │
│  │                                                                    │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  EXAMPLE (Doctors on-call - Write Skew Prevention):                        │
│  ───────────────────────────────────────────────────────────────────────    │
│  TXN A (Alice): Reads on_call count (sees Alice, Bob)                      │
│  TXN B (Bob):   Reads on_call count (sees Alice, Bob)                      │
│  TXN A: Writes Alice.on_call = false                                       │
│  TXN B: Writes Bob.on_call = false                                         │
│                                                                              │
│  Dependencies detected by SSI:                                              │
│  • A read doctors → B wrote to doctors (rw: A→B)                           │
│  • B read doctors → A wrote to doctors (rw: B→A)                           │
│  • CYCLE: A→B→A → One transaction aborts!                                  │
│  • Error: "could not serialize access due to read/write dependencies"      │
│                                                                              │
│  💡 This is exactly how SSI prevents WRITE SKEW!                            │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  WHY SSI IS BETTER THAN 2PL:                                               │
│  • No blocking! Transactions run optimistically                            │
│  • Only aborts when cycle actually detected                                │
│  • Most transactions have no cycles → no aborts                            │
│  • Trade-off: Must handle retry in application                             │
│                                                                              │
│  PREVENTS:        ALL anomalies (Dirty, Non-Rep, Phantom, Lost Update,     │
│                   Write Skew)                                               │
│                                                                              │
│  TRADE-OFFS:                                                                │
│  ───────────────────────────────────────────────────────────────────────    │
│  • Higher abort rate (must retry failed transactions)                      │
│  • More overhead (tracking dependencies or holding locks)                  │
│  • Lower throughput for write-heavy workloads                              │
│                                                                              │
│  USE CASES:                                                                 │
│  • Financial transactions (money transfer)                                 │
│  • Inventory reservation (prevent overbooking)                             │
│  • Any business constraint that must NEVER be violated                     │
│                                                                              │
│  INTERVIEW TIP: "For our payment service, we use Serializable isolation    │
│                  for the transfer operation. We accept higher abort rates   │
│                  because correctness is more important than throughput."    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Weak Isolation vs Strong Isolation

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    WEAK vs STRONG ISOLATION                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌───────────────────────────────┬───────────────────────────────────────┐  │
│  │       WEAK ISOLATION          │        STRONG ISOLATION              │  │
│  ├───────────────────────────────┼───────────────────────────────────────┤  │
│  │  • Read Uncommitted           │  • Serializable                       │  │
│  │  • Read Committed             │                                       │  │
│  │  • Repeatable Read            │                                       │  │
│  │  • Snapshot Isolation         │                                       │  │
│  ├───────────────────────────────┼───────────────────────────────────────┤  │
│  │  ✅ High performance           │  ✅ No anomalies                      │  │
│  │  ✅ High throughput            │  ✅ Full correctness                  │  │
│  │  ❌ Allows some anomalies      │  ❌ Lower throughput                  │  │
│  │  ❌ Subtle bugs possible       │  ❌ Higher abort rate                 │  │
│  └───────────────────────────────┴───────────────────────────────────────┘  │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  WHY DO MOST DATABASES DEFAULT TO WEAK ISOLATION?                          │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • PostgreSQL default: Read Committed (weak)                               │
│  • MySQL default: Repeatable Read (weak - allows write skew)               │
│  • Oracle default: Read Committed (weak)                                   │
│  • SQL Server default: Read Committed (weak)                               │
│                                                                              │
│  REASON: Performance! Most applications don't need Serializable.           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  THE DANGER OF WEAK ISOLATION:                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  • Works fine in development (low concurrency)                             │
│  • Works fine with light production load                                   │
│  • BREAKS under high concurrency! (race conditions appear)                 │
│  • Subtle bugs: inventory goes negative, money disappears, double-booking │
│                                                                              │
│  INTERVIEW TIP:                                                            │
│  "Most production bugs I've seen come from developers not understanding    │
│   that their database uses weak isolation by default. They assume         │
│   transactions are fully isolated, but write skew and lost updates        │
│   can still happen. For critical operations like payments, I explicitly   │
│   use Serializable or add pessimistic locks."                             │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Choosing the Right Isolation Level

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                  ISOLATION LEVEL DECISION TREE                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  START HERE: What does your operation need?                                 │
│                                                                              │
│                    ┌─────────────────────────────┐                          │
│                    │  Does your transaction do   │                          │
│                    │  multiple related queries?  │                          │
│                    └─────────────────────────────┘                          │
│                           │           │                                     │
│                          NO          YES                                    │
│                           │           │                                     │
│                           ▼           ▼                                     │
│                    ┌──────────┐  ┌─────────────────────────────┐            │
│                    │   READ   │  │  Do you check something,   │            │
│                    │COMMITTED │  │  then update based on it?  │            │
│                    └──────────┘  └─────────────────────────────┘            │
│                                        │           │                        │
│                                       NO          YES                       │
│                                        │           │                        │
│                                        ▼           ▼                        │
│                               ┌──────────────┐ ┌─────────────────┐          │
│                               │  REPEATABLE  │ │  SERIALIZABLE   │          │
│                               │     READ     │ │       or        │          │
│                               │              │ │ SELECT...FOR    │          │
│                               │              │ │ UPDATE + check  │          │
│                               └──────────────┘ └─────────────────┘          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  PRACTICAL RECOMMENDATIONS:                                                 │
│                                                                              │
│  READ COMMITTED (PostgreSQL default):                                       │
│  • General OLTP, user-facing APIs                                          │
│  • Single queries, eventual consistency OK                                 │
│  • Example: "Show me current inventory"                                    │
│                                                                              │
│  REPEATABLE READ (MySQL default):                                           │
│  • Reports, analytics within transaction                                   │
│  • Multi-query operations needing consistency                              │
│  • Example: "Generate monthly statement"                                   │
│                                                                              │
│  SERIALIZABLE:                                                              │
│  • Financial transfers                                                      │
│  • Booking systems (prevent double-booking)                                │
│  • Any check-then-act with business constraint                             │
│  • Example: "Transfer $100 from A to B"                                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.6 🔗 How It All Ties Together

> **Now that you understand locking, MVCC, and isolation levels, let's connect everything!**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE BIG PICTURE                                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHAT YOU WANT:      Run concurrent transactions without data corruption    │
│                                                                              │
│  WHAT CAN GO WRONG:  Anomalies (dirty read, lost update, write skew, etc.) │
│                      (Covered in Section 2.2)                               │
│                                                                              │
│  HOW TO PREVENT:     Two mechanisms (HOW it's done)                         │
│                      ├── Locking (block conflicts) → Section 2.3           │
│                      └── MVCC (multiple versions) → Section 2.4            │
│                                                                              │
│  HOW MUCH TO PREVENT: Isolation Levels (WHAT protection level)              │
│                      (Section 2.5)                                          │
│                      ├── Read Committed → prevents some anomalies           │
│                      ├── Repeatable Read → prevents more                    │
│                      └── Serializable → prevents all                        │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│           ISOLATION LEVELS                 IMPLEMENTED BY                   │
│           (The WHAT)                       (The HOW)                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Read Committed       ─────────────►   MVCC (only see committed versions)  │
│                                                                              │
│  Repeatable Read      ─────────────►   MVCC (snapshot at txn start)        │
│                                        + Gap locks for phantoms (MySQL)     │
│                                                                              │
│  Serializable         ─────────────►   MVCC + Dependency tracking (SSI)    │
│                            OR          Strict 2PL (lock everything)        │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  KEY INSIGHT:                                                               │
│  • Isolation Level = The DIAL you turn (how much protection)               │
│  • Locking & MVCC = The ENGINE inside (how protection is achieved)         │
│  • You choose the isolation level; database uses locking/MVCC internally   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Why Atomic Operations Don't Solve Everything

```
┌─────────────────────────────────────────────────────────────────────────────┐
│          ATOMIC OPERATIONS vs ISOLATION LEVELS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ATOMIC OPERATION: UPDATE counter = counter + 1                             │
│  ✅ Prevents: Lost Update (for simple increment)                            │
│  ❌ Cannot prevent: Write Skew, Phantom, complex business logic            │
│                                                                              │
│  WHY?                                                                       │
│  ───────────────────────────────────────────────────────────────────────    │
│  Atomic ops work when: single row, simple operation, no decisions needed   │
│                                                                              │
│  Write Skew example (doctors on-call):                                      │
│  1. SELECT COUNT(*) WHERE on_call = true → 2                               │
│  2. DECIDE: "OK, I can go off-call"                                        │
│  3. UPDATE doctors SET on_call = false WHERE name = 'Alice'                │
│                                                                              │
│  No single atomic operation can do CHECK + DECIDE + UPDATE!                │
│  You need: Serializable isolation OR explicit SELECT...FOR UPDATE          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  RULE OF THUMB:                                                             │
│  • Simple increment/decrement → Use atomic operation                       │
│  • Check-then-act logic → Use locking (FOR UPDATE) or Serializable         │
│  • Complex multi-row logic → Use Serializable isolation                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```


#### Database vs Application: Who Handles What?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│        WHAT DATABASE HANDLES vs WHAT YOU MUST DO                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│  SQL DATABASES (PostgreSQL, MySQL)                                          │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  DATABASE HANDLES AUTOMATICALLY:                                            │
│  ───────────────────────────────                                            │
│  ✅ Dirty writes (always blocked via X-locks)                              │
│  ✅ Dirty reads (at Read Committed+, via MVCC)                             │
│  ✅ Non-repeatable reads (at Repeatable Read+, via MVCC snapshot)          │
│  ✅ Phantoms (at Serializable, or RR in PostgreSQL)                        │
│  ✅ ACID transactions (atomicity, durability via WAL)                      │
│  ✅ Constraint enforcement (UNIQUE, FOREIGN KEY, CHECK)                    │
│                                                                              │
│  YOU MUST HANDLE (Application Level):                                       │
│  ────────────────────────────────────                                       │
│  ⚠️  Lost Update → Use atomic ops OR SELECT...FOR UPDATE OR optimistic lock│
│  ⚠️  Write Skew → Use Serializable OR SELECT...FOR UPDATE on read rows    │
│  ⚠️  Business constraints → DB constraints if possible, else app logic     │
│  ⚠️  Retry logic → For deadlocks and serialization failures               │
│                                                                              │
│  ════════════════════════════════════════════════════════════════════════   │
│  NoSQL DATABASES (DynamoDB, Cassandra, Redis)                               │
│  ════════════════════════════════════════════════════════════════════════   │
│                                                                              │
│  DATABASE HANDLES AUTOMATICALLY:                                            │
│  ───────────────────────────────                                            │
│  ✅ Single-item/single-partition atomicity                                 │
│  ✅ Basic durability (replication)                                         │
│                                                                              │
│  YOU MUST HANDLE (Application Level):                                       │
│  ────────────────────────────────────                                       │
│  ⚠️  Multi-item atomicity → TransactWriteItems (DynamoDB) or app logic     │
│  ⚠️  Lost Update → ConditionExpression / CAS / optimistic locking          │
│  ⚠️  Consistency → Choose consistency level per query                      │
│  ⚠️  ALL business constraints → Application enforced!                      │
│  ⚠️  Read-modify-write → Always use conditional updates                    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  KEY DIFFERENCE:                                                            │
│  • SQL: "Database protects you by default, opt-out if needed"              │
│  • NoSQL: "You protect yourself, database gives you tools"                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Developer Decision Tree

```
┌─────────────────────────────────────────────────────────────────────────────┐
│           HOW TO HANDLE CONCURRENCY: DECISION TREE                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  START: What operation are you doing?                                       │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                                                                     │   │
│  │  Q1: Is it a simple increment/decrement/append?                     │   │
│  │      │                                                              │   │
│  │      ├─ YES → Use ATOMIC OPERATION                                  │   │
│  │      │        SQL: UPDATE x SET count = count + 1                   │   │
│  │      │        DynamoDB: ADD count :val                              │   │
│  │      │        Redis: INCR key                                       │   │
│  │      │                                                              │   │
│  │      └─ NO → Continue to Q2                                         │   │
│  │                                                                     │   │
│  │  Q2: Do you READ then WRITE based on what you read?                 │   │
│  │      │                                                              │   │
│  │      ├─ YES → Is conflict rate HIGH or LOW?                         │   │
│  │      │        │                                                     │   │
│  │      │        ├─ HIGH (hot data) → PESSIMISTIC LOCKING (2.7)       │   │
│  │      │        │   SQL: SELECT ... FOR UPDATE                        │   │
│  │      │        │   Blocks others until you commit                   │   │
│  │      │        │                                                     │   │
│  │      │        └─ LOW (rare conflicts) → OPTIMISTIC LOCKING (2.7)   │   │
│  │      │            SQL: Version column + retry                       │   │
│  │      │            DynamoDB: ConditionExpression + retry             │   │
│  │      │                                                              │   │
│  │      └─ NO → Continue to Q3                                         │   │
│  │                                                                     │   │
│  │  Q3: Do you check MULTIPLE rows then make a decision?               │   │
│  │      (e.g., count doctors, then update one)                         │   │
│  │      │                                                              │   │
│  │      ├─ YES → WRITE SKEW risk!                                     │   │
│  │      │        SQL: Use SERIALIZABLE isolation (2.5)                 │   │
│  │      │        OR: SELECT ... FOR UPDATE on the rows you check      │   │
│  │      │        NoSQL: Very hard! Redesign data model if possible    │   │
│  │      │                                                              │   │
│  │      └─ NO → Default isolation is probably fine                     │   │
│  │              SQL: Read Committed                                    │   │
│  │              NoSQL: Eventual/Strong as needed                       │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Quick Reference: Anomaly → Solution

```
┌─────────────────────────────────────────────────────────────────────────────┐
│            ANOMALY → WHAT TO DO                                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ANOMALY          │ SQL SOLUTION              │ NoSQL SOLUTION              │
│  ─────────────────┼───────────────────────────┼─────────────────────────────│
│  Dirty Read       │ Default (Read Committed)  │ N/A (single-item atomic)    │
│                   │ Handled by MVCC           │                             │
│  ─────────────────┼───────────────────────────┼─────────────────────────────│
│  Non-Repeatable   │ Use Repeatable Read       │ Re-read with strong         │
│                   │ Handled by MVCC snapshot  │ consistency                 │
│  ─────────────────┼───────────────────────────┼─────────────────────────────│
│  Phantom          │ Use Serializable          │ Redesign data model         │
│                   │ (or RR in PostgreSQL)     │ (very hard in NoSQL)        │
│  ─────────────────┼───────────────────────────┼─────────────────────────────│
│  Lost Update      │ Atomic op OR              │ ConditionExpression         │
│                   │ SELECT...FOR UPDATE OR    │ (optimistic locking)        │
│                   │ Optimistic locking        │ Must handle in app!         │
│  ─────────────────┼───────────────────────────┼─────────────────────────────│
│  Write Skew       │ Serializable OR           │ Very difficult!             │
│                   │ Lock the rows you read    │ Consider SQL for this       │
│                   │ (SELECT...FOR UPDATE)     │ use case                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.7 Pessimistic vs Optimistic Locking

> Two fundamentally different philosophies for handling concurrent updates.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              PESSIMISTIC vs OPTIMISTIC LOCKING                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PESSIMISTIC LOCKING:                    OPTIMISTIC LOCKING:                │
│  ─────────────────────                   ──────────────────────             │
│  "Assume conflicts WILL happen"          "Assume conflicts are RARE"        │
│  "Lock first, then work"                 "Work first, check at commit"      │
│                                                                              │
│  ┌─────────────────────────────┐        ┌─────────────────────────────┐    │
│  │ 1. SELECT ... FOR UPDATE   │        │ 1. SELECT with version      │    │
│  │    (acquire lock)          │        │    (no lock)                │    │
│  │                            │        │                             │    │
│  │ 2. Do business logic       │        │ 2. Do business logic        │    │
│  │    (others wait)           │        │    (no blocking!)           │    │
│  │                            │        │                             │    │
│  │ 3. UPDATE                  │        │ 3. UPDATE WHERE version = X │    │
│  │                            │        │    (check version)          │    │
│  │ 4. COMMIT                  │        │                             │    │
│  │    (release lock)          │        │ 4. If 0 rows affected:      │    │
│  │                            │        │    → CONFLICT! Retry        │    │
│  └─────────────────────────────┘        └─────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Pessimistic Locking (SELECT ... FOR UPDATE)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   PESSIMISTIC LOCKING EXAMPLE                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO: Decrement inventory for order                                    │
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                BEGIN                                 │
│  SELECT stock FROM products                                                 │
│  WHERE id = 123                                                             │
│  FOR UPDATE;                                                                │
│  → Returns stock = 5                                                        │
│  → Holds X-lock on row                                                      │
│                                                                              │
│                                        SELECT stock FROM products           │
│                                        WHERE id = 123                       │
│                                        FOR UPDATE;                          │
│                                        → ⏳ BLOCKED! Waiting for lock...    │
│                                                                              │
│  -- Check: Is stock >= 1?                                                   │
│  UPDATE products                                                            │
│  SET stock = stock - 1                                                      │
│  WHERE id = 123;                                                            │
│  COMMIT;                                                                    │
│  → Releases lock                                                            │
│                                                                              │
│                                        → Lock acquired!                     │
│                                        → Returns stock = 4 (updated value!) │
│                                        UPDATE products                      │
│                                        SET stock = stock - 1                │
│                                        WHERE id = 123;                      │
│                                        COMMIT;                              │
│                                                                              │
│  RESULT: stock = 3 (both decrements applied correctly!)                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  VARIANTS:                                                                  │
│  • FOR UPDATE           → Exclusive lock (blocks other FOR UPDATEs)        │
│  • FOR SHARE            → Shared lock (allows other FOR SHAREs)            │
│  • FOR UPDATE NOWAIT    → Error immediately if lock unavailable            │
│  • FOR UPDATE SKIP LOCKED → Skip locked rows (useful for job queues!)      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Optimistic Locking (Version Column)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   OPTIMISTIC LOCKING EXAMPLE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  TABLE SCHEMA:                                                              │
│  products (id, name, stock, version)                                        │
│                                       ▲                                     │
│                                       └── Version column for conflict detection│
│                                                                              │
│  TXN A                                TXN B                                 │
│  ───────────────────────────────────────────────────────────────────────    │
│  BEGIN                                BEGIN                                 │
│  SELECT stock, version                                                      │
│  FROM products                                                              │
│  WHERE id = 123;                                                            │
│  → stock = 5, version = 7                                                   │
│  → NO LOCK ACQUIRED!                                                        │
│                                        SELECT stock, version                │
│                                        FROM products                        │
│                                        WHERE id = 123;                      │
│                                        → stock = 5, version = 7            │
│                                        → NO LOCK!                           │
│                                                                              │
│  -- Do business logic (maybe slow)                                          │
│                                        -- Do business logic (parallel!)     │
│                                                                              │
│  UPDATE products                                                            │
│  SET stock = 4, version = 8                                                 │
│  WHERE id = 123 AND version = 7;                                            │
│  → 1 row affected ✓                                                         │
│  COMMIT;                                                                    │
│                                                                              │
│                                        UPDATE products                      │
│                                        SET stock = 4, version = 8          │
│                                        WHERE id = 123 AND version = 7;     │
│                                        → 0 rows affected! ← VERSION CHANGED!│
│                                        → Application detects conflict       │
│                                        → RETRY from beginning               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  KEY INSIGHT:                                                               │
│  • No blocking during read or business logic                               │
│  • Conflict detected at UPDATE time via version mismatch                   │
│  • Application must handle retry logic                                     │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Comparison and When to Use Each

```
┌─────────────────────────────────────────────────────────────────────────────┐
│               PESSIMISTIC vs OPTIMISTIC: WHEN TO USE                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│                       PESSIMISTIC              OPTIMISTIC                   │
│  ────────────────────────────────────────────────────────────────────────── │
│  Conflict rate        High                     Low                          │
│  Lock duration        Entire operation         None (check at commit)       │
│  Throughput           Lower (blocking)         Higher (no blocking)         │
│  Deadlock risk        Yes                      No (no locks!)              │
│  Retry logic          Not needed               Required in application      │
│  Starvation           Possible (long waits)    No (but retries)            │
│                                                                              │
│  ────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  USE PESSIMISTIC WHEN:                                                      │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ • Conflicts are FREQUENT (hot rows)                                 │   │
│  │ • Cost of retry is HIGH (long business logic)                       │   │
│  │ • Operation MUST succeed on first try                               │   │
│  │ • Short transactions (lock held briefly)                            │   │
│  │                                                                     │   │
│  │ Examples:                                                           │   │
│  │ • Ticket booking (limited seats, many buyers)                       │   │
│  │ • Inventory decrement on flash sale                                 │   │
│  │ • Bank account balance update                                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  USE OPTIMISTIC WHEN:                                                       │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │ • Conflicts are RARE (users editing different documents)            │   │
│  │ • Read-heavy, occasional writes                                     │   │
│  │ • Long-running operations (don't want to hold locks)                │   │
│  │ • Distributed systems (hard to coordinate locks)                    │   │
│  │                                                                     │   │
│  │ Examples:                                                           │   │
│  │ • Wiki/document editing (usually different pages)                   │   │
│  │ • User profile updates                                              │   │
│  │ • Shopping cart modifications                                       │   │
│  │ • API updates where retries are acceptable                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  ────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  INTERVIEW ANSWER:                                                          │
│  "For our inventory system during flash sales, I'd use pessimistic         │
│   locking (SELECT FOR UPDATE) because conflict rate is very high—          │
│   thousands competing for limited stock. Optimistic would cause            │
│   endless retries. For user profile updates, I'd use optimistic            │
│   locking with a version column since conflicts are rare."                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```


```
┌─────────────────────────────────────────────────────────────────────────────┐
│        DATABASE-LEVEL vs APPLICATION-LEVEL: Who Does What?                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PESSIMISTIC LOCKING:                                                       │
│  ─────────────────────────────────────────────────────────────────────────  │
│  ✅ DATABASE-LEVEL mechanism                                                │
│  • SELECT ... FOR UPDATE is a database feature                             │
│  • Database manages the locks internally                                   │
│  • Application just issues the SQL command                                 │
│  • Lock released automatically on COMMIT/ROLLBACK                          │
│                                                                              │
│  OPTIMISTIC LOCKING:                                                        │
│  ─────────────────────────────────────────────────────────────────────────  │
│  ⚠️  APPLICATION-LEVEL pattern                                              │
│  • Database has NO built-in "optimistic lock" command                      │
│  • Application adds a version/timestamp column                             │
│  • Application reads version, includes in WHERE clause                     │
│  • Application handles retry logic on conflict                             │
│  • Database just executes normal SELECT/UPDATE - doesn't "know" it's OL    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│  ⚠️  WAIT! Doesn't MVCC already have versions? Why add another?            │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  MVCC VERSIONS (Database internal):                                         │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  • Hidden columns: xmin/xmax (PostgreSQL), DB_TRX_ID (MySQL)       │    │
│  │  • Purpose: READ CONSISTENCY - which version can I SEE?            │    │
│  │  • Managed by: Database automatically                              │    │
│  │  • Invisible to application                                        │    │
│  │  • Used for: Snapshot isolation, non-blocking reads               │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  OPTIMISTIC LOCK VERSION (Application column):                              │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  • Explicit column: version INT or updated_at TIMESTAMP            │    │
│  │  • Purpose: WRITE CONFLICT DETECTION - did someone else change it?│    │
│  │  • Managed by: Application (or ORM)                                │    │
│  │  • Visible to application                                          │    │
│  │  • Used for: Detecting concurrent modifications                   │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  THEY SOLVE DIFFERENT PROBLEMS:                                             │
│  • MVCC: "What version should I READ?" (isolation)                         │
│  • OL:   "Did someone WRITE since I read?" (conflict detection)            │
│                                                                              │
│  MVCC doesn't prevent lost updates! You can read version 1, someone        │
│  else writes version 2, you overwrite with your changes → LOST UPDATE.    │
│  Optimistic locking's version column catches this!                         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  ORM SUPPORT (makes optimistic locking easier):                            │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  Hibernate/JPA:    @Version annotation → auto-manages version column │  │
│  │  ActiveRecord:     lock_version column → auto-increment on save      │  │
│  │  Django:           Manual or django-concurrency package              │  │
│  │  Sequelize:        version: true in model options                    │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  NoSQL EQUIVALENT:                                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  DynamoDB:         ConditionExpression (built-in!)                   │  │
│  │  Cassandra:        IF column = value (Lightweight Transactions)      │  │
│  │  Redis:            WATCH + MULTI/EXEC                                │  │
│  │  MongoDB:          findOneAndUpdate with version check               │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Optimistic Locking Retry Pattern (Application Code)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                   OPTIMISTIC LOCKING: RETRY PATTERN                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PSEUDOCODE:                                                                │
│  ───────────────────────────────────────────────────────────────────────    │
│                                                                              │
│  function decrementStock(productId):                                        │
│      maxRetries = 3                                                         │
│      for attempt in 1..maxRetries:                                          │
│          // 1. Read current state                                           │
│          row = SELECT stock, version FROM products WHERE id = productId    │
│                                                                              │
│          // 2. Business logic                                               │
│          if row.stock < 1:                                                  │
│              throw "Out of stock"                                           │
│          newStock = row.stock - 1                                           │
│                                                                              │
│          // 3. Conditional update                                           │
│          rowsAffected = UPDATE products                                     │
│                         SET stock = newStock, version = row.version + 1     │
│                         WHERE id = productId AND version = row.version     │
│                                                                              │
│          // 4. Check if successful                                          │
│          if rowsAffected == 1:                                              │
│              return SUCCESS                                                 │
│          else:                                                              │
│              // Someone else modified the row, retry                        │
│              continue                                                       │
│                                                                              │
│      // All retries exhausted                                               │
│      throw "Concurrent modification, please retry"                          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  VARIATIONS:                                                                │
│  • Exponential backoff between retries                                     │
│  • Use timestamp instead of version (updated_at column)                    │
│  • Hash of row content as "version" (no extra column needed)               │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.8 Famous Concurrency Problems & Solutions (Interview Gold!)

> Real-world problems that frequently appear in system design interviews. Know these cold!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FAMOUS CONCURRENCY PROBLEMS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  These are the "classic" scenarios interviewers love to ask about.          │
│  Each demonstrates a different anomaly and requires a specific solution.   │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Problem 1: The Bank Transfer (Lost Update / Atomicity)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BANK TRANSFER                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO:                                                                  │
│  Transfer $100 from Alice to Bob                                            │
│                                                                              │
│  WHAT CAN GO WRONG:                                                         │
│  • Crash after deducting from Alice, before adding to Bob → $100 vanishes  │
│  • Two transfers at same time → Lost updates, incorrect balances           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION (SQL): Just use a transaction with atomic updates!               │
│  ───────────────────────────────────────────────────────────               │
│                                                                              │
│  BEGIN;                                                                     │
│  UPDATE accounts SET balance = balance - 100                                │
│         WHERE user = 'Alice' AND balance >= 100;  -- Atomic check!         │
│  -- If 0 rows affected → insufficient balance, ROLLBACK                    │
│  UPDATE accounts SET balance = balance + 100 WHERE user = 'Bob';            │
│  COMMIT;                                                                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  WHY THIS WORKS:                                                            │
│  • TRANSACTION: All or nothing (atomicity) - crash safe                    │
│  • balance >= 100 in WHERE: Atomic check, no read-then-write race          │
│  • Default isolation (Read Committed) is sufficient!                       │
│                                                                              │
│  No need for Serializable or FOR UPDATE for simple transfers!              │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION (NoSQL - DynamoDB):                                               │
│  ────────────────────────────                                               │
│  TransactWriteItems([                                                       │
│    { Update: {Key: {user: 'Alice'}, UpdateExpression: 'SET balance = balance - :amt',│
│               ConditionExpression: 'balance >= :amt'} },                   │
│    { Update: {Key: {user: 'Bob'}, UpdateExpression: 'SET balance = balance + :amt'} }│
│  ])                                                                         │
│  // Atomic across both items, but 2x cost!                                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Problem 2: The Flash Sale Inventory (Race Condition)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLASH SALE: LIMITED INVENTORY                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SCENARIO:                                                                  │
│  10 iPhones for sale, 1000 users clicking "Buy" simultaneously             │
│                                                                              │
│  WHAT CAN GO WRONG (Read-Modify-Write):                                    │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │  User A: SELECT stock → 10                                           │  │
│  │  User B: SELECT stock → 10                                           │  │
│  │  User A: UPDATE stock = 9 → success                                  │  │
│  │  User B: UPDATE stock = 9 → success (SAME VALUE!)                    │  │
│  │                                                                      │  │
│  │  Result: 2 items sold, but stock only decreased by 1!                │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  OVERSOLD: 15 purchases processed, only 10 items exist!                    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 1: Atomic Decrement (Best for simple cases)                       │
│  ──────────────────────────────────────────────────────                     │
│  UPDATE products SET stock = stock - 1                                      │
│  WHERE id = 123 AND stock > 0;                                              │
│                                                                              │
│  IF rows_affected = 0 → "Out of Stock"                                     │
│  IF rows_affected = 1 → "Success!"                                         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 2: Pessimistic Lock (When business logic is complex)              │
│  ─────────────────────────────────────────────────────────────              │
│  BEGIN;                                                                     │
│  SELECT stock FROM products WHERE id = 123 FOR UPDATE;                      │
│  -- Only one transaction can hold this lock!                               │
│  IF stock > 0:                                                              │
│      -- Complex business logic (calculate discounts, check user, etc.)     │
│      UPDATE products SET stock = stock - 1 WHERE id = 123;                  │
│      INSERT INTO orders (...);                                              │
│      COMMIT;                                                                 │
│  ELSE:                                                                      │
│      ROLLBACK;                                                              │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 3: Optimistic Lock (Lower contention scenarios)                   │
│  ────────────────────────────────────────────────────────                   │
│  SELECT stock, version FROM products WHERE id = 123;                        │
│  -- version = 5, stock = 10                                                │
│  UPDATE products SET stock = 9, version = 6                                 │
│  WHERE id = 123 AND version = 5;                                            │
│  -- If rows_affected = 0, someone else got there first → RETRY             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  REDIS SOLUTION (For distributed rate limiting + inventory):                │
│  ────────────────────────────────────────────────────────                   │
│  DECR inventory:product:123   -- Atomic decrement                          │
│  if result < 0:                                                             │
│      INCR inventory:product:123  -- Rollback                               │
│      return "Out of Stock"                                                  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Problem 3: The Flight Booking (Phantom Read)

> **Anomaly:** Phantom Read (→ See Section 2.2 for full explanation)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    FLIGHT BOOKING: SOLUTIONS                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SOLUTION 1: UNIQUE Constraint (Best - Database Enforced!)                  │
│  ─────────────────────────────────────────────────────────                  │
│  -- Create a "seats" table with seat numbers                               │
│  INSERT INTO seat_assignments (flight_id, seat_number, passenger)           │
│  VALUES (123, 'A1', 'Alice');                                               │
│                                                                              │
│  -- UNIQUE constraint on (flight_id, seat_number) prevents duplicates!     │
│  -- If duplicate: catch exception, try another seat                        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 2: Counter Table + Atomic Decrement                               │
│  ────────────────────────────────────────────                               │
│  UPDATE flight_availability SET seats_remaining = seats_remaining - 1      │
│  WHERE flight_id = 123 AND seats_remaining > 0;                             │
│                                                                              │
│  IF rows_affected = 1: INSERT INTO bookings (...);                         │
│  ELSE: "Sorry, no seats available"                                         │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 3: Serializable Isolation (Simple but expensive)                  │
│  ─────────────────────────────────────────────────────────                  │
│  SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;                              │
│  BEGIN;                                                                     │
│  SELECT COUNT(*) FROM bookings WHERE flight_id = 123;                       │
│  IF count < 150: INSERT INTO bookings (...);                               │
│  COMMIT;  -- Aborts if phantom detected, must RETRY                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Problem 4: The On-Call Doctors (Write Skew)

> **Anomaly:** Write Skew (→ See Section 2.2 for full explanation)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    ON-CALL SCHEDULING: SOLUTIONS                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  WHY COMMON SOLUTIONS DON'T WORK:                                           │
│  • Atomic operation? Can't do CHECK + DECIDE + UPDATE atomically           │
│  • Repeatable Read? Doesn't prevent write skew (different rows)           │
│  • Optimistic locking? No version conflict (updating different rows)      │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 1: SELECT ... FOR UPDATE (Lock what you read!)                   │
│  ───────────────────────────────────────────────────────                   │
│  BEGIN;                                                                     │
│  SELECT * FROM doctors WHERE on_call = true FOR UPDATE;                     │
│  -- Locks ALL on-call doctors' rows! Other transaction must wait.         │
│  IF count > 1:                                                              │
│      UPDATE doctors SET on_call = false WHERE name = 'Alice';               │
│  COMMIT;                                                                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 2: Serializable Isolation (PostgreSQL SSI)                        │
│  ───────────────────────────────────────────────────                        │
│  SET TRANSACTION ISOLATION LEVEL SERIALIZABLE;                              │
│  -- SSI detects read-write dependency cycle and aborts one transaction    │
│  -- See Section 2.5 for how SSI cycle detection works                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  SOLUTION 3: Database Constraint (Best if possible)                         │
│  ──────────────────────────────────────────────────                         │
│  -- Use a trigger or CHECK constraint to enforce minimum coverage          │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Problems 5 & 6: Distributed Systems Territory

```
┌─────────────────────────────────────────────────────────────────────────────┐
│        THESE BELONG IN LEVEL 3: DISTRIBUTED SYSTEMS                         │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  The following problems are NOT single-node concurrency issues:            │
│                                                                              │
│  • LIKE BUTTON (Hot Row / Sharding)                                        │
│    → Problem: Single row bottleneck at extreme scale                       │
│    → Solution: Redis, sharded counters, async writes                       │
│    → See Level 3 for: Distributed counters, caching patterns              │
│                                                                              │
│  • SHOPPING CART (Read-Your-Writes)                                        │
│    → Problem: Replication lag between primary and replicas                 │
│    → Solution: Read from primary, sticky sessions, version-aware reads     │
│    → See Level 3 for: Consistency models, replication strategies          │
│                                                                              │
│  These require distributed systems knowledge covered in Level 3!           │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Quick Reference: Problem → Solution (Single-Node)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              INTERVIEW CHEAT SHEET: FAMOUS PROBLEMS                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  PROBLEM              │ ANOMALY        │ GO-TO SOLUTION                     │
│  ─────────────────────┼────────────────┼────────────────────────────────── │
│  Bank Transfer        │ Lost Update    │ Atomic update in transaction       │
│  Flash Sale           │ Lost Update    │ Atomic decrement: stock = stock-1  │
│  Flight Booking       │ Phantom Read   │ UNIQUE constraint or Serializable  │
│  On-Call Doctors      │ Write Skew     │ FOR UPDATE or Serializable         │
│                                                                              │
│  (Like Button, Shopping Cart → See Level 3: Distributed Systems)           │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  INTERVIEW PATTERN:                                                         │
│  1. Identify the anomaly (lost update? write skew? phantom?)               │
│  2. Choose isolation level OR explicit locking                             │
│  3. Mention retry logic for aborted transactions                           │
│  4. Discuss scale: "At extreme scale, we'd use Redis/sharding"            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

#### Real-World: Alternatives to Serializable at Scale

```
┌─────────────────────────────────────────────────────────────────────────────┐
│        WHY SERIALIZABLE DOESN'T SCALE (And What To Do Instead)               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SERIALIZABLE PROBLEMS AT HIGH SCALE:                                       │
│  ─────────────────────────────────────                                      │
│  • Higher abort rate → more retries → higher latency                       │
│  • Dependency tracking overhead → CPU cost                                 │
│  • Contention on hot data → throughput collapse                            │
│  • Cross-shard serializable? → Distributed transactions (2PC) = slow!      │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  REAL-WORLD ALTERNATIVES:                                                   │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  1. AVOID THE PROBLEM BY DESIGN                                            │
│  ───────────────────────────────                                           │
│  • Denormalize data so transactions touch one row/partition                │
│  • Design so conflicts are impossible (user can only edit own data)       │
│  • Use event sourcing: append-only, no overwrites, no conflicts           │
│                                                                              │
│  Example: Instead of checking doctor count, assign doctors to SLOTS        │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  Old: Check count, then update → Write Skew possible               │    │
│  │  New: INSERT INTO on_call_slots (slot_id, doctor) VALUES (1, 'Alice')│   │
│  │       UNIQUE constraint on slot_id → database prevents conflict!    │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  2. DATABASE CONSTRAINTS INSTEAD OF ISOLATION                               │
│  ────────────────────────────────────────────                              │
│  • UNIQUE constraints → prevent duplicate bookings                         │
│  • CHECK constraints → prevent negative balance                            │
│  • FOREIGN KEY → prevent orphan records                                    │
│  • Triggers → complex business rules                                       │
│                                                                              │
│  Flight booking example:                                                    │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │  CREATE TABLE seat_assignments (                                   │    │
│  │    flight_id INT,                                                  │    │
│  │    seat_number VARCHAR(5),                                         │    │
│  │    passenger_id INT,                                               │    │
│  │    PRIMARY KEY (flight_id, seat_number)  -- Can't double-book!    │    │
│  │  );                                                                │    │
│  │                                                                    │    │
│  │  INSERT fails if seat already taken → no Serializable needed!     │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  3. EXPLICIT LOCKING (FOR UPDATE) AT LOWER ISOLATION                       │
│  ───────────────────────────────────────────────────                       │
│  • Use Read Committed (default) + SELECT FOR UPDATE on specific rows      │
│  • Lock only what you need, not entire table                              │
│  • More control, less overhead than Serializable                          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  4. MOVE HOT DATA TO REDIS/CACHE                                           │
│  ─────────────────────────────────                                         │
│  • Counters, rate limits, inventory counts → Redis atomic ops             │
│  • Async sync back to database                                            │
│  • Accept eventual consistency for non-critical data                      │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  5. DISTRIBUTED LOCKS (Cross-Service Coordination)                          │
│  ──────────────────────────────────────────────────                        │
│  • Redis SETNX / Redlock for distributed mutex                            │
│  • ZooKeeper for coordination                                             │
│  • Use when multiple services need to coordinate                          │
│  • Careful: Distributed locks have their own failure modes!               │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  6. COMPENSATING TRANSACTIONS (Saga Pattern)                                │
│  ───────────────────────────────────────────                               │
│  • Don't try to prevent bad state, UNDO it if it happens                  │
│  • Each step has a compensating action                                    │
│  • Common in microservices (covered in Level 3)                           │
│                                                                              │
│  ═══════════════════════════════════════════════════════════════════════════│
│                                                                              │
│  DECISION GUIDE:                                                            │
│  ┌────────────────────────────────────────────────────────────────────┐    │
│  │                                                                    │    │
│  │  Can you prevent with UNIQUE/CHECK constraint?                    │    │
│  │    YES → Use constraint (fastest, no locks)                       │    │
│  │    NO  ↓                                                          │    │
│  │                                                                    │    │
│  │  Can you redesign to avoid the conflict?                          │    │
│  │    YES → Redesign (best long-term)                                │    │
│  │    NO  ↓                                                          │    │
│  │                                                                    │    │
│  │  Is it a single hot row?                                          │    │
│  │    YES → Redis/cache layer                                        │    │
│  │    NO  ↓                                                          │    │
│  │                                                                    │    │
│  │  Is conflict rate high?                                           │    │
│  │    YES → FOR UPDATE (pessimistic)                                 │    │
│  │    NO  → Serializable or optimistic locking (retry on conflict)  │    │
│  │                                                                    │    │
│  └────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.9 SQL vs NoSQL: Transaction & Concurrency Support

> Now that you understand all the concepts (ACID, anomalies, locking, MVCC, isolation levels, pessimistic vs optimistic), let's see which databases support what!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│           TRANSACTION SUPPORT: SQL vs NoSQL                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  This is CRITICAL for system design interviews!                             │
│  Different databases offer different transaction guarantees.                │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Database Type | ACID Transactions | Multi-Row Atomic | Isolation Levels | MVCC |
|---------------|-------------------|------------------|------------------|------|
| **PostgreSQL** | ✅ Full | ✅ Yes | All 4 levels | ✅ Yes |
| **MySQL (InnoDB)** | ✅ Full | ✅ Yes | All 4 levels | ✅ Yes |
| **SQL Server** | ✅ Full | ✅ Yes | All 4 levels | ✅ Yes (optional) |
| **MongoDB** | ✅ Since 4.0 | ✅ Multi-doc txns | Snapshot only | ✅ Yes |
| **DynamoDB** | ⚠️ Limited | ✅ TransactWriteItems | None (eventual/strong) | ❌ No |
| **Cassandra** | ⚠️ Limited | ❌ Single partition | None (tunable consistency) | ❌ No |
| **Redis** | ⚠️ MULTI/EXEC | ✅ Same connection | Serializable only | ❌ No |
| **HBase** | ⚠️ Row-level | ❌ Single row | None | ✅ Yes |

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    DETAILED BREAKDOWN                                        │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  SQL DATABASES (MySQL, PostgreSQL, SQL Server)                              │
│  ─────────────────────────────────────────────                              │
│  ✅ Full ACID transactions                                                  │
│  ✅ Multi-table, multi-row transactions                                    │
│  ✅ All isolation levels available                                         │
│  ✅ Pessimistic locking (SELECT FOR UPDATE)                                │
│  ✅ MVCC for non-blocking reads                                            │
│  ✅ Constraints (FOREIGN KEY, UNIQUE, CHECK)                               │
│                                                                              │
│  USE WHEN: Financial systems, booking systems, any complex transactions    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  MongoDB (Document Store)                                                   │
│  ─────────────────────────                                                  │
│  ✅ Single-document operations are atomic (always)                         │
│  ✅ Multi-document transactions (since v4.0, 2018)                         │
│  ✅ Snapshot isolation                                                      │
│  ⚠️  Performance penalty for multi-doc transactions                        │
│  ⚠️  60-second transaction limit                                            │
│                                                                              │
│  USE WHEN: Flexible schema, document-per-entity design                     │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  DynamoDB (Key-Value / Wide Column)                                         │
│  ───────────────────────────────────                                        │
│  ✅ Single-item operations are atomic                                       │
│  ✅ TransactWriteItems: Up to 100 items across tables                      │
│  ✅ ConditionExpression for optimistic locking (CAS)                       │
│  ❌ No isolation levels (eventual or strong consistency choice)            │
│  ❌ No MVCC (single version per item)                                      │
│                                                                              │
│  CONCURRENCY PATTERN:                                                       │
│    UpdateItem with ConditionExpression = Optimistic Locking                │
│    TransactWriteItems = Multi-item atomic (expensive, 2x cost)             │
│                                                                              │
│  USE WHEN: Massive scale, simple access patterns, can tolerate eventual    │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Cassandra (Wide Column)                                                    │
│  ───────────────────────                                                    │
│  ✅ Single-partition operations are atomic                                  │
│  ⚠️  Lightweight Transactions (LWT) for CAS - slow, use sparingly!         │
│  ❌ No multi-partition transactions                                        │
│  ❌ No isolation levels                                                     │
│  ❌ Tunable consistency (ONE, QUORUM, ALL) ≠ isolation                     │
│                                                                              │
│  CONCURRENCY PATTERN:                                                       │
│    Last-Write-Wins (default) - may lose updates!                           │
│    LWT: IF column = expected_value (expensive, ~4x latency)                │
│                                                                              │
│  USE WHEN: Write-heavy, time-series, can tolerate LWW                      │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  Redis (In-Memory Key-Value)                                                │
│  ───────────────────────────                                                │
│  ✅ Single commands are atomic                                              │
│  ✅ MULTI/EXEC for command batching (all-or-nothing)                       │
│  ✅ WATCH for optimistic locking                                           │
│  ✅ Lua scripts run atomically                                             │
│  ❌ No multi-key transactions across slots (Cluster mode)                  │
│                                                                              │
│  CONCURRENCY PATTERN:                                                       │
│    WATCH key; MULTI; ...; EXEC (abort if key changed)                      │
│    INCRBY, LPUSH, etc. are atomic by themselves                            │
│                                                                              │
│  USE WHEN: Caching, counters, leaderboards, pub/sub                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

#### Interview Quick Reference: Which Database for What?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│              CHOOSING BASED ON TRANSACTION NEEDS                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  NEED FULL ACID TRANSACTIONS?                                               │
│  ────────────────────────────                                               │
│  YES → PostgreSQL, MySQL, MongoDB (4.0+)                                   │
│  NO  → DynamoDB, Cassandra, Redis                                          │
│                                                                              │
│  NEED MULTI-ROW/MULTI-DOC ATOMICITY?                                        │
│  ────────────────────────────────────                                       │
│  YES → PostgreSQL, MySQL, MongoDB, DynamoDB (TransactWriteItems)           │
│  NO  → Cassandra (single partition), Redis (single key), HBase             │
│                                                                              │
│  NEED SERIALIZABLE ISOLATION?                                               │
│  ────────────────────────────                                               │
│  YES → PostgreSQL (SSI), MySQL, SQL Server                                 │
│  NO  → Most NoSQL (eventual consistency model)                             │
│                                                                              │
│  NEED HIGH WRITE THROUGHPUT + SCALE?                                        │
│  ────────────────────────────────────                                       │
│  YES → Cassandra, DynamoDB (accept weaker guarantees)                      │
│  NO  → PostgreSQL, MySQL are fine                                          │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────  │
│                                                                              │
│  INTERVIEW PATTERN:                                                         │
│  "For the payment processing part of the system, I'd use PostgreSQL        │
│   because we need full ACID transactions with Serializable isolation.      │
│   For the activity feed, I'd use Cassandra because it's write-heavy        │
│   and eventual consistency is acceptable—we can tolerate a user            │
│   briefly seeing stale data."                                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### 2.10 Level 2 → Level 3 Connection

```
┌─────────────────────────────────────────────────────────────────────────────┐
│               FROM SINGLE-NODE TO DISTRIBUTED                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Everything in Level 2 assumed a SINGLE database server.                    │
│                                                                              │
│  LEVEL 3 (Distributed Systems) will cover:                                  │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │  SINGLE-NODE (Level 2)         DISTRIBUTED (Level 3)               │   │
│  │  ───────────────────────       ────────────────────                 │   │
│  │  Local transaction IDs    →    Global timestamps (HLC, TrueTime)   │   │
│  │  MVCC on one node         →    Distributed snapshots               │   │
│  │  Local locks              →    Distributed locks (Paxos/Raft)      │   │
│  │  ACID transactions        →    2PC, Saga pattern                   │   │
│  │  Single-node isolation    →    CAP theorem trade-offs              │   │
│  │  Write conflicts          →    Vector clocks, CRDTs                │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                              │
│  The concepts you learned here (MVCC, isolation levels, locking)           │
│  are the FOUNDATION. Distributed systems add coordination complexity!      │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Interview Checklist

### Questions You Should Be Able to Answer

#### Transactions & Concurrency
- [ ] "What is a transaction and what does ACID stand for?"
- [ ] "What is the Lost Update problem and how do you prevent it?"
- [ ] "What's the difference between pessimistic and optimistic locking?"
- [ ] "When would you use SELECT...FOR UPDATE?"

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

#### Famous Problems (Section 2.8)
- [ ] "How would you implement a bank transfer atomically?"
- [ ] "How do you prevent overselling during a flash sale?"
- [ ] "How would you prevent double-booking a flight seat?"
- [ ] "Explain the on-call doctors problem and how to solve it"
- [ ] "When would you use database constraints vs isolation levels?"

#### Indexing
- [ ] "What's the difference between clustered and non-clustered indexes?"
- [ ] "Why does column order matter in composite indexes?"
- [ ] "When would an index hurt performance?"
- [ ] "What is index selectivity and why does it matter?"
- [ ] "How do covering indexes avoid heap lookups?"

### Quick Reference: Concurrency Anomalies

| Anomaly | Read Uncommitted | Read Committed | Repeatable Read | Serializable |
|---------|------------------|----------------|-----------------|--------------|
| Dirty Read | ❌ Possible | ✅ Prevented | ✅ Prevented | ✅ Prevented |
| Non-Repeatable Read | ❌ Possible | ❌ Possible | ✅ Prevented | ✅ Prevented |
| Phantom Read | ❌ Possible | ❌ Possible | ⚠️ Varies | ✅ Prevented |
| Lost Update | ❌ Possible | ❌ Possible | ✅* Prevented | ✅ Prevented |
| Write Skew | ❌ Possible | ❌ Possible | ❌ Possible | ✅ Prevented |

*With explicit locking (SELECT...FOR UPDATE)

### Quick Reference: Pessimistic vs Optimistic

> For Famous Problems quick reference, see **Section 2.8**.

| Aspect | Pessimistic | Optimistic |
|--------|-------------|------------|
| Philosophy | "Conflicts WILL happen" | "Conflicts are RARE" |
| Mechanism | Lock before accessing | Check version at commit |
| Blocking | Yes (others wait) | No (retry on conflict) |
| Best for | High contention | Low contention |
| Example | Flash sale inventory | User profile updates |

### Common Pitfalls

| Mistake | Why It's Wrong | Correct Understanding |
|---------|----------------|----------------------|
| "More indexes = faster" | Slows down writes | Index based on query patterns |
| "MVCC means no locks" | Writes still lock | Writers don't block readers |
| "Repeatable Read prevents all issues" | Write skew still possible | Need Serializable for full isolation |
| "Serializable is always safest" | Can cause performance issues | Right isolation for the use case |
| "Optimistic is always better" | High conflict rate = endless retries | Choose based on contention level |
| "Just retry on conflict" | May cause livelock | Add backoff, limit retries |

---

## Next Steps

Continue to **[Level 3: Distributed Systems](03_DISTRIBUTED_SYSTEMS.md)** to learn about replication, sharding, and CAP theorem.

