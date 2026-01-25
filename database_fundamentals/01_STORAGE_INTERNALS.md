# Level 1: Storage Internals (The Disk Layer)

> Understanding how databases physically store and retrieve data is the foundation of all optimization decisions.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    🗺️  HOW THIS LEVEL CONNECTS                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LEVEL 1: STORAGE INTERNALS ◄── YOU ARE HERE                               │
│  ════════════════════════════════════════════════════════════════════════════│
│  Scope: SINGLE-NODE, physical disk operations                               │
│  Focus: How data is stored and retrieved at the hardware level             │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │                         CONCEPT MAP                                     ││
│  │                                                                         ││
│  │  Level 1 (This)          Level 2               Level 3                 ││
│  │  Storage Internals       Database Logic        Distributed Systems     ││
│  │  ─────────────────       ──────────────        ────────────────────    ││
│  │                                                                         ││
│  │  B-Tree/LSM-Tree  ───►   Indexes use these  ───►  Each node has these  ││
│  │  (data structures)       (for fast lookups)      (replicated)          ││
│  │                                                                         ││
│  │  Pages & Buffer Pool ─►  MVCC stores versions ─►  Sync across nodes    ││
│  │  (memory management)     (in these pages)        (which page is latest?)││
│  │                                                                         ││
│  │  WAL (Write-Ahead Log)─► Crash recovery ──────►  Replication uses WAL! ││
│  │  (durability)            (replay log)            (stream to replicas)  ││
│  │                                                                         ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  WHY START HERE?                                                            │
│  • You can't optimize queries without understanding disk I/O               │
│  • Indexing decisions depend on B-Tree vs LSM-Tree trade-offs              │
│  • WAL is the foundation for both durability AND replication               │
│  • "Why is this slow?" often traces back to storage layer                  │
│                                                                              │
│  APPLIES TO:                                                                │
│  ✅ SQL: PostgreSQL, MySQL (B-Tree, WAL, buffer pool)                       │
│  ✅ NoSQL: Cassandra, RocksDB (LSM-Tree, memtable, SSTables)               │
│  ✅ Both: These are universal storage concepts                              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## Table of Contents
1. [B-Trees vs LSM-Trees](#1-b-trees-vs-lsm-trees)
2. [Page/Block Storage](#2-pageblock-storage)
3. [The Buffer Pool](#3-the-buffer-pool)
4. [Write-Ahead Logging (WAL)](#4-write-ahead-logging-wal)
5. [Deep Dive: Physical Storage Visualized](#5-deep-dive-physical-storage-visualized)
6. [Interview Checklist](#6-interview-checklist)

---

## 1. B-Trees vs LSM-Trees

### The Core Question
**"How should we organize data on disk to optimize read vs write performance?"**

These are the two fundamental data structures that power all modern databases.

---

### B-Trees (Balanced Trees)

**Used by:** PostgreSQL, MySQL/InnoDB, Oracle, SQL Server

#### How It Works

```
                        ┌─────────────────────┐
                        │    Root Node        │
                        │   [30] [60] [90]    │
                        └─────────────────────┘
                       /          |            \
           ┌──────────┐    ┌──────────┐    ┌──────────┐
           │ [10][20] │    │ [40][50] │    │ [70][80] │
           └──────────┘    └──────────┘    └──────────┘
              /    \          /    \          /    \
           ┌────┐ ┌────┐  ┌────┐ ┌────┐  ┌────┐ ┌────┐
           │ 5  │ │ 15 │  │ 35 │ │ 45 │  │ 65 │ │ 75 │
           │ 8  │ │ 18 │  │ 38 │ │ 48 │  │ 68 │ │ 78 │
           └────┘ └────┘  └────┘ └────┘  └────┘ └────┘
              ▲
              └── Leaf nodes contain actual data (or pointers)
```

#### Key Properties

| Property | Value | Implication |
|----------|-------|-------------|
| **Structure** | Self-balancing tree | O(log N) for all operations |
| **Node Size** | Typically 4KB-16KB (matches disk page) | One disk I/O per level |
| **Fan-out** | ~100-500 children per node | 3-4 levels for billions of rows |
| **Updates** | In-place modification | Random I/O on writes |
| **Ordering** | Sorted by key | Range queries are efficient |

#### Read Path (Finding a Record)

```
1. Start at root node (usually in memory)
2. Binary search within node to find correct child pointer
3. Follow pointer to next level
4. Repeat until reaching leaf node
5. Return data from leaf

Example: Find record with key=45
- Root: 45 > 30, 45 < 60 → go middle
- Level 2: 45 > 40, 45 < 50 → go middle  
- Leaf: Found key=45, return data

Total: ~3 disk I/Os (often just 1-2 if internal nodes cached)
```

#### Write Path (Inserting a Record)

```
1. Find correct leaf node (same as read)
2. Insert key in sorted order
3. If leaf is full → SPLIT:
   - Create new leaf node
   - Move half the keys to new node
   - Add pointer in parent
   - If parent full → split propagates up

This is why writes are expensive: potential cascade of random I/O
```

#### Why B-Trees Favor Reads

1. **Predictable I/O**: Always O(log N) disk reads
2. **Point queries are fast**: Direct path to data
3. **Range queries are fast**: Leaf nodes are linked
4. **Data is always sorted**: No post-processing needed

#### Why B-Trees Struggle with Writes

1. **Random I/O**: Each write touches a random disk location
2. **Write Amplification**: One logical write may cause multiple physical writes (splits)
3. **Locking overhead**: Need to lock pages during modification

---

### LSM-Trees (Log-Structured Merge Trees)

**Used by:** Cassandra, RocksDB, LevelDB, ScyllaDB, HBase

#### How It Works

```
┌─────────────────────────────────────────────────────────────────────┐
│                            MEMORY                                    │
│  ┌─────────────────────────────────────────────────────────────┐    │
│  │                    MemTable (Sorted)                         │    │
│  │    Writes go here first → Sorted in-memory structure         │    │
│  │    (Usually a Red-Black Tree or Skip List)                   │    │
│  └─────────────────────────────────────────────────────────────┘    │
│                              │                                       │
│                              │ When full, flush to disk              │
│                              ▼                                       │
└─────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────┐
│                             DISK                                     │
│                                                                      │
│  Level 0: ┌────────┐ ┌────────┐ ┌────────┐  (Recent SSTables)       │
│           │SSTable1│ │SSTable2│ │SSTable3│                          │
│           └────────┘ └────────┘ └────────┘                          │
│                              │                                       │
│                              │ Compaction (merge + dedupe)           │
│                              ▼                                       │
│  Level 1: ┌────────────────────────────────┐  (Larger, fewer files) │
│           │         Merged SSTable          │                        │
│           └────────────────────────────────┘                        │
│                              │                                       │
│                              ▼                                       │
│  Level 2: ┌────────────────────────────────────────────┐            │
│           │            Even Larger SSTable              │            │
│           └────────────────────────────────────────────┘            │
└─────────────────────────────────────────────────────────────────────┘
```

#### Key Terminology

| Term | Definition |
|------|------------|
| **MemTable** | In-memory sorted buffer for recent writes |
| **SSTable** | Sorted String Table - immutable, sorted file on disk |
| **Compaction** | Background process that merges SSTables |
| **Bloom Filter** | Probabilistic structure to skip SSTables that don't have a key |
| **Tombstone** | Marker indicating a deleted key |

#### Write Path

```
1. Write to MemTable (memory) - FAST!
2. Also write to WAL (for durability)
3. When MemTable is full:
   - Flush to disk as new SSTable (sequential write)
   - Clear MemTable
4. Background compaction merges SSTables

Key insight: ALL writes are SEQUENTIAL (no random I/O)
```

#### Read Path

```
1. Check MemTable first (most recent data)
2. Check Bloom filters for each SSTable level
3. For each SSTable that might have the key:
   - Binary search within SSTable
   - Return if found
4. Check next level if not found

Worst case: Must check ALL levels → Reads can be slow
```

#### Why LSM-Trees Favor Writes

1. **Sequential I/O only**: Flushing MemTable is one sequential write
2. **No in-place updates**: Never modify existing files
3. **Write batching**: Many writes consolidated in one flush
4. **Compaction is background**: Doesn't block writes

#### Why LSM-Trees Struggle with Reads

1. **Multiple levels to check**: May need to read from many SSTables
2. **Space amplification**: Same key may exist in multiple levels
3. **Read amplification**: One logical read = multiple physical reads

---

### Head-to-Head Comparison

| Aspect | B-Tree | LSM-Tree |
|--------|--------|----------|
| **Write Pattern** | Random I/O | Sequential I/O |
| **Read Pattern** | Predictable | Variable (depends on levels) |
| **Write Latency** | Higher (10-100ms) | Lower (1-10ms) |
| **Read Latency** | Lower, consistent | Higher, variable |
| **Space Efficiency** | Better (no duplication) | Worse (temporary duplication) |
| **Write Amplification** | Lower | Higher (due to compaction) |
| **Read Amplification** | Lower | Higher (check multiple levels) |
| **Concurrency** | Locking required | Lock-free writes possible |
| **Best For** | OLTP, Read-heavy | Write-heavy, Time-series |

---

### When to Use Which

```
USE B-TREE WHEN:
├── Read:Write ratio > 10:1
├── Need point queries with consistent latency
├── Require strong ACID transactions
├── Space is a concern (less write amplification)
└── Examples: User profiles, Financial transactions, Inventory

USE LSM-TREE WHEN:
├── Write:Read ratio > 1:1
├── Ingesting high-volume data
├── Can tolerate variable read latency
├── Need horizontal scalability
└── Examples: Time-series, Logs, IoT sensors, Analytics
```

---

## 2. Page/Block Storage

### The Fundamental Unit

Databases don't read individual bytes from disk—they read **pages** (also called blocks).

```
┌─────────────────────────────────────────────────────────────────────┐
│                         DISK LAYOUT                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Page 0    Page 1    Page 2    Page 3    Page 4    Page 5           │
│ ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐  ┌──────┐         │
│ │      │  │      │  │      │  │      │  │      │  │      │         │
│ │ 8KB  │  │ 8KB  │  │ 8KB  │  │ 8KB  │  │ 8KB  │  │ 8KB  │         │
│ │      │  │      │  │      │  │      │  │      │  │      │         │
│ └──────┘  └──────┘  └──────┘  └──────┘  └──────┘  └──────┘         │
│                                                                      │
│ Even if you need 1 byte, the DB reads the entire 8KB page           │
└─────────────────────────────────────────────────────────────────────┘
```

#### Page Sizes

| Database | Default Page Size | Configurable? |
|----------|-------------------|---------------|
| PostgreSQL | 8 KB | Compile-time only |
| MySQL/InnoDB | 16 KB | Yes (innodb_page_size) |
| Oracle | 8 KB | Yes |
| SQL Server | 8 KB | No |
| SQLite | 4 KB | Yes |

### Page Structure (PostgreSQL Example)

```
┌─────────────────────────────────────────────────────────────────────┐
│                         PAGE STRUCTURE (8KB)                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ PAGE HEADER (24 bytes)                                          │ │
│  │ - LSN (Log Sequence Number)                                     │ │
│  │ - Checksum                                                      │ │
│  │ - Flags                                                         │ │
│  │ - Free space pointers                                           │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ ITEM POINTERS (Line Pointers)                                   │ │
│  │ [ptr1][ptr2][ptr3][ptr4]...                                     │ │
│  │ Each pointer: offset + length to actual tuple                   │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
│                              │ Grows downward                        │
│                              ▼                                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │                        FREE SPACE                               │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              ▲                                       │
│                              │ Grows upward                          │
│                              │                                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ TUPLES (Actual Row Data)                                        │ │
│  │ [Header][Col1][Col2][Col3]   ← Tuple 4                          │ │
│  │ [Header][Col1][Col2][Col3]   ← Tuple 3                          │ │
│  │ [Header][Col1][Col2][Col3]   ← Tuple 2                          │ │
│  │ [Header][Col1][Col2][Col3]   ← Tuple 1                          │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ SPECIAL SPACE (for B-tree: sibling pointers, etc.)              │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Why This Matters for System Design

1. **Row Size Affects Rows Per Page**
   ```
   8KB page / 100 bytes per row = ~80 rows per page
   8KB page / 1KB per row = ~8 rows per page
   
   Implication: Smaller rows = fewer I/O for range scans
   ```

2. **Page Splits Cause Fragmentation**
   ```
   When a page is full and you insert:
   - Page must split
   - Data gets spread across non-contiguous pages
   - Range scans become random I/O
   
   Solution: VACUUM (Postgres) or OPTIMIZE TABLE (MySQL)
   ```

3. **TOAST (The Oversized Attribute Storage Technique)**
   ```
   What if a column is larger than a page?
   - PostgreSQL: TOAST table stores large values separately
   - Access to TOASTed values = extra I/O
   
   Interview tip: "Large text columns should be stored separately 
   or in object storage to avoid bloating the main table."
   ```

---

## 3. The Buffer Pool

### The Core Problem

```
Disk I/O: ~10ms (HDD) or ~0.1ms (SSD)
Memory access: ~100ns

That's 1,000x to 100,000x difference!
```

### Solution: The Buffer Pool

The buffer pool is a region of memory that caches frequently accessed pages.

```
┌─────────────────────────────────────────────────────────────────────┐
│                         APPLICATION                                  │
│                              │                                       │
│                              │ SQL Query                             │
│                              ▼                                       │
├─────────────────────────────────────────────────────────────────────┤
│                       DATABASE ENGINE                                │
│                              │                                       │
│                              │ Need page 42                          │
│                              ▼                                       │
├─────────────────────────────────────────────────────────────────────┤
│                        BUFFER POOL                                   │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │                     Page Cache (RAM)                          │   │
│  │  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐    │   │
│  │  │P:42│ │P:17│ │P:99│ │P:3 │ │P:88│ │P:12│ │P:56│ │P:71│    │   │
│  │  │ ★  │ │    │ │    │ │    │ │    │ │    │ │    │ │    │    │   │
│  │  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘    │   │
│  │     ▲                                                         │   │
│  │     └── Page 42 is in cache! Return immediately (100ns)       │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                              │                                       │
│                              │ Cache MISS? Fetch from disk           │
│                              ▼                                       │
├─────────────────────────────────────────────────────────────────────┤
│                          DISK                                        │
│  ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐           │
│  │ P1 │ │ P2 │ │ P3 │ │... │ │P42 │ │... │ │P99 │ │P100│           │
│  └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘ └────┘           │
└─────────────────────────────────────────────────────────────────────┘
```

### Buffer Pool Components

#### 1. Page Table (Hash Map)
```
Maps: Page ID → Buffer Pool Slot

page_table = {
    42: slot_0,   # Page 42 is in buffer slot 0
    17: slot_1,   # Page 17 is in buffer slot 1
    99: slot_2,   # ...and so on
}
```

#### 2. Frame Descriptors (Metadata per slot)
```
For each buffer pool slot:
- page_id: Which page is stored here
- pin_count: How many queries are using this page
- dirty_bit: Has this page been modified?
- last_accessed: For LRU eviction
```

#### 3. Eviction Policy

When buffer pool is full and we need a new page:

| Policy | How It Works | Pros | Cons |
|--------|--------------|------|------|
| **LRU** | Evict least recently used | Simple, effective | Scan queries pollute cache |
| **Clock** | Circular buffer with use bits | More efficient than LRU | Still susceptible to scans |
| **LRU-K** | Track K most recent accesses | Better for mixed workloads | More memory overhead |
| **2Q** | Separate queues for new/old pages | Scan-resistant | More complex |

#### PostgreSQL's Approach: Clock Sweep

```
┌────────────────────────────────────────────────────────────────────┐
│                        CLOCK ALGORITHM                              │
├────────────────────────────────────────────────────────────────────┤
│                                                                     │
│      ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐ ┌────┐                    │
│      │ P1 │ │ P2 │ │ P3 │ │ P4 │ │ P5 │ │ P6 │                    │
│      │ u=1│ │ u=0│ │ u=1│ │ u=0│ │ u=1│ │ u=0│                    │
│      └────┘ └────┘ └────┘ └────┘ └────┘ └────┘                    │
│         ▲                                                           │
│         │                                                           │
│       Clock                                                         │
│       Hand                                                          │
│                                                                     │
│  Algorithm:                                                         │
│  1. If usage_count > 0: decrement and move on                       │
│  2. If usage_count == 0: EVICT this page                            │
│  3. Each access increments usage_count (capped at 5)                │
│                                                                     │
│  Benefit: Recently-accessed pages get multiple "lives"              │
└────────────────────────────────────────────────────────────────────┘
```

### Dirty Pages and Checkpointing

```
WRITE PATH:
1. Modify page in buffer pool
2. Mark page as DIRTY
3. Page stays in memory (not immediately written to disk!)
4. Background writer OR checkpoint flushes dirty pages

WHY DELAY WRITES?
- Batching: Multiple writes to same page = one disk write
- Performance: Disk writes are slow
- Durability: WAL ensures we don't lose data (see next section)
```

### Buffer Pool Sizing

| Database | Configuration | Rule of Thumb |
|----------|---------------|---------------|
| PostgreSQL | `shared_buffers` | 25% of RAM |
| MySQL | `innodb_buffer_pool_size` | 70-80% of RAM |

```
Interview Insight:
"If your buffer pool hit rate drops below 99%, you're likely 
I/O bound. Either add RAM or reduce working set size."

How to check (PostgreSQL):
SELECT 
  sum(heap_blks_hit) / (sum(heap_blks_hit) + sum(heap_blks_read)) as hit_ratio
FROM pg_statio_user_tables;
```

---

## 4. Write-Ahead Logging (WAL)

### The Durability Problem

```
SCENARIO:
1. User transfers $100: Account A → Account B
2. We update Account A in buffer pool (dirty page)
3. We update Account B in buffer pool (dirty page)
4. CRASH before dirty pages written to disk!

RESULT: Money disappeared? Data corruption!
```

### The Solution: Write-Ahead Logging

**Rule: Before ANY change is applied to data pages, it MUST be logged to the WAL first.**

```
┌─────────────────────────────────────────────────────────────────────┐
│                      WAL ARCHITECTURE                                │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Transaction: UPDATE accounts SET balance = balance - 100            │
│               WHERE id = 'A';                                        │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ STEP 1: Write to WAL (Sequential, Fast)                       │   │
│  │                                                               │   │
│  │   WAL Segment File:                                           │   │
│  │   ┌─────────────────────────────────────────────────────┐    │   │
│  │   │ LSN:1001 │ TXN:42 │ UPDATE │ Table:accounts │ ...   │    │   │
│  │   └─────────────────────────────────────────────────────┘    │   │
│  │   ┌─────────────────────────────────────────────────────┐    │   │
│  │   │ LSN:1002 │ TXN:42 │ UPDATE │ Table:accounts │ ...   │    │   │
│  │   └─────────────────────────────────────────────────────┘    │   │
│  │   ┌─────────────────────────────────────────────────────┐    │   │
│  │   │ LSN:1003 │ TXN:42 │ COMMIT │                        │    │   │
│  │   └─────────────────────────────────────────────────────┘    │   │
│  │                                                               │   │
│  │   fsync() → Guaranteed on disk!                               │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ STEP 2: Apply to Buffer Pool (Memory, Fast)                   │   │
│  │                                                               │   │
│  │   Buffer Pool:                                                │   │
│  │   ┌────────────┐                                              │   │
│  │   │ accounts   │ ← Modified in memory                         │   │
│  │   │ A: $900    │   (dirty page)                               │   │
│  │   │ B: $1100   │                                              │   │
│  │   └────────────┘                                              │   │
│  │                                                               │   │
│  │   NOT fsync'd yet - that's okay, WAL has us covered!          │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │ STEP 3: Later - Checkpoint                                    │   │
│  │                                                               │   │
│  │   Background process writes dirty pages to data files         │   │
│  │   Records checkpoint position in WAL                          │   │
│  │   Old WAL segments can be recycled                            │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### WAL Record Structure

```
┌─────────────────────────────────────────────────────────────────────┐
│                       WAL RECORD FORMAT                              │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ Header                                                          │ │
│  │ - LSN (Log Sequence Number): Unique, monotonic ID               │ │
│  │ - Transaction ID                                                │ │
│  │ - Timestamp                                                     │ │
│  │ - Record Type (INSERT, UPDATE, DELETE, COMMIT, etc.)            │ │
│  │ - Length                                                        │ │
│  │ - Previous LSN (for linked list traversal)                      │ │
│  └────────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ Payload                                                         │ │
│  │ - Table/Index identifier                                        │ │
│  │ - Page number                                                   │ │
│  │ - Offset within page                                            │ │
│  │ - Before image (for UNDO) - optional                            │ │
│  │ - After image (for REDO)                                        │ │
│  └────────────────────────────────────────────────────────────────┘ │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ CRC Checksum                                                    │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Crash Recovery Process

```
ON STARTUP AFTER CRASH:

1. ANALYSIS PHASE
   - Scan WAL from last checkpoint
   - Build list of transactions that were in-progress
   - Build list of dirty pages

2. REDO PHASE (Roll Forward)
   - Replay ALL changes from WAL since checkpoint
   - Apply to data pages (even if already applied)
   - This is idempotent (safe to repeat)

3. UNDO PHASE (Roll Back)
   - For transactions that didn't commit
   - Undo their changes using before-images
   - Write compensation log records

RESULT: Database in consistent state as of last committed transaction
```

### WAL Modes and Trade-offs

| Mode | Description | Durability | Performance |
|------|-------------|------------|-------------|
| **fsync** | WAL fsync'd on every commit | Strongest | Slowest |
| **fdatasync** | Data fsync'd, not metadata | Strong | Faster |
| **fsync_writethrough** | Bypass OS cache | Strongest | Slowest |
| **off** | No fsync (DANGEROUS!) | None | Fastest |

### WAL for Replication

```
WAL isn't just for crash recovery—it's also how replication works!

┌──────────────┐     WAL Stream      ┌──────────────┐
│              │ ─────────────────►  │              │
│   PRIMARY    │                     │   REPLICA    │
│              │ ◄─────────────────  │              │
└──────────────┘   Ack (optional)    └──────────────┘

Postgres: "Streaming Replication" uses WAL
MySQL: "Binary Log" (binlog) - similar concept
```

### Checkpointing

```
PURPOSE: Limit crash recovery time

Without checkpoints:
- Recovery must replay ALL WAL ever written
- Could take hours for old databases!

With checkpoints:
- Periodically write all dirty pages to disk
- Record checkpoint LSN
- Recovery only needs to replay WAL after checkpoint

TRADE-OFF:
- Frequent checkpoints = Faster recovery, More I/O during operation
- Infrequent checkpoints = Slower recovery, Less I/O during operation

PostgreSQL: checkpoint_timeout (default 5 min)
MySQL: innodb_log_file_size affects checkpoint frequency
```

---

## 5. Deep Dive: Physical Storage Visualized

> This section answers the "but how does it ACTUALLY work?" questions.

---

### Q1: What Does an SSTable Actually Store? (With Example)

An SSTable (Sorted String Table) is an **immutable file on disk** containing sorted key-value pairs.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SSTABLE FILE STRUCTURE (Simplified)                       │
│                         File: data-001.sst (~256MB)                          │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ DATA BLOCKS (Sorted Key-Value Pairs)                                   │ │
│  │                                                                        │ │
│  │  Block 1 (4KB):                                                        │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Key: "user:1001" │ Value: {"name":"Alice","email":"a@x.com"}    │  │ │
│  │  │ Key: "user:1002" │ Value: {"name":"Bob","email":"b@x.com"}      │  │ │
│  │  │ Key: "user:1003" │ Value: {"name":"Carol","email":"c@x.com"}    │  │ │
│  │  │ Key: "user:1004" │ Value: {"name":"David","email":"d@x.com"}    │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                        │ │
│  │  Block 2 (4KB):                                                        │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │ Key: "user:1005" │ Value: {"name":"Eve","email":"e@x.com"}      │  │ │
│  │  │ Key: "user:1006" │ Value: {"name":"Frank","email":"f@x.com"}    │  │ │
│  │  │ ...                                                             │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  │                                                                        │ │
│  │  ... more data blocks ...                                              │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ INDEX BLOCK (Maps key ranges to block offsets)                         │ │
│  │                                                                        │ │
│  │  "user:1001" → Block 1 at offset 0                                     │ │
│  │  "user:1005" → Block 2 at offset 4096                                  │ │
│  │  "user:1009" → Block 3 at offset 8192                                  │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ BLOOM FILTER (Probabilistic "is key in this file?" check)              │ │
│  │                                                                        │ │
│  │  Bit array: [0,1,1,0,1,0,0,1,1,0,1,0,1,1,0,0...]                       │ │
│  │  "user:9999" → hash → bits not set → DEFINITELY NOT HERE (skip file!) │ │
│  │  "user:1002" → hash → bits set → MAYBE HERE (check the file)          │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ FOOTER (Metadata: offsets to index, bloom filter, compression info)    │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**Concrete Example - Cassandra SSTable:**

```
TABLE: users (user_id PRIMARY KEY, name, email, created_at)

INSERT user_id=1001, name='Alice', email='a@x.com', created_at='2024-01-15'
INSERT user_id=1002, name='Bob', email='b@x.com', created_at='2024-01-16'
UPDATE user_id=1001 SET email='alice@new.com'
DELETE user_id=1002

MEMTABLE (in memory, sorted):
┌─────────────────────────────────────────────────────────────────────────┐
│ user:1001 → {name:'Alice', email:'alice@new.com', ts:1003}  (latest)    │
│ user:1002 → TOMBSTONE {deleted_at: ts:1004}                 (deleted)   │
└─────────────────────────────────────────────────────────────────────────┘
                │
                │ FLUSH (when MemTable is full)
                ▼
SSTABLE FILE ON DISK:
┌─────────────────────────────────────────────────────────────────────────┐
│ Same data, but now immutable on disk. Never modified, only compacted.  │
└─────────────────────────────────────────────────────────────────────────┘
```

**Key Points:**
- SSTable = Sorted data blocks + Index + Bloom filter + Footer
- Each entry has a timestamp (for conflict resolution)
- Deletes are stored as "tombstones" (marker that says "deleted")
- Files are IMMUTABLE - never modified after creation
- **Within each SSTable:** Data blocks are always sequential (written in one flush). **Across SSTables:** No sequence relationship—Level 0 files can have overlapping key ranges; after compaction, Level 1+ files have non-overlapping ranges.

---

### Q2: Is B-Tree Physical Storage or Just Logical?

**B-Tree is PHYSICAL storage, not just logical!** Each node in the B-Tree is a **physical disk page**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│             B-TREE NODES ARE ACTUAL DISK PAGES (Physical!)                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  DISK FILE: table_data.ibd (InnoDB) or base/16384 (PostgreSQL)              │
│                                                                              │
│  ┌────────────────────────────────────────────────────────────────────────┐ │
│  │ Page 0 (8KB) - ROOT NODE                                               │ │
│  │ ┌────────────────────────────────────────────────────────────────────┐│ │
│  │ │ Page Header (LSN, checksum, type=BTREE_INTERNAL)                   ││ │
│  │ ├────────────────────────────────────────────────────────────────────┤│ │
│  │ │ Keys:     [    50    |    100    |    150    ]                     ││ │
│  │ │ Pointers: [→Page 1   |→Page 2    |→Page 3    |→Page 4]             ││ │
│  │ └────────────────────────────────────────────────────────────────────┘│ │
│  └────────────────────────────────────────────────────────────────────────┘ │
│                           /              |              \                    │
│                          /               |               \                   │
│  ┌──────────────────────┐  ┌──────────────────────┐  ┌──────────────────────┐
│  │ Page 1 (8KB) - LEAF  │  │ Page 2 (8KB) - LEAF  │  │ Page 3 (8KB) - LEAF  │
│  │ ┌──────────────────┐ │  │ ┌──────────────────┐ │  │ ┌──────────────────┐ │
│  │ │ id:10 → row data │ │  │ │ id:51 → row data │ │  │ │ id:101→ row data │ │
│  │ │ id:20 → row data │ │  │ │ id:60 → row data │ │  │ │ id:110→ row data │ │
│  │ │ id:30 → row data │ │  │ │ id:70 → row data │ │  │ │ id:120→ row data │ │
│  │ │ id:40 → row data │ │  │ │ id:80 → row data │ │  │ │ id:130→ row data │ │
│  │ │ id:45 → row data │ │  │ │ id:95 → row data │ │  │ │ id:145→ row data │ │
│  │ │ →Page 2 (sibling)│ │  │ │ →Page 3 (sibling)│ │  │ │ →Page 4 (sibling)│ │
│  │ └──────────────────┘ │  │ └──────────────────┘ │  │ └──────────────────┘ │
│  └──────────────────────┘  └──────────────────────┘  └──────────────────────┘
│                                                                              │
│  PHYSICAL DISK LAYOUT:                                                       │
│  ┌────────┬────────┬────────┬────────┬────────┬────────┐                    │
│  │ Page 0 │ Page 1 │ Page 2 │ Page 3 │ Page 4 │ Page 5 │ ...                │
│  │  ROOT  │  LEAF  │  LEAF  │  LEAF  │  LEAF  │  FREE  │                    │
│  │  8KB   │  8KB   │  8KB   │  8KB   │  8KB   │  8KB   │                    │
│  └────────┴────────┴────────┴────────┴────────┴────────┘                    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

**The "Logical vs Physical" Confusion:**
- The B-Tree STRUCTURE is logical (conceptual tree with parent/child)
- The STORAGE is physical (each node = a real 8KB/16KB page on disk)
- The tree is laid out across disk pages, connected by page numbers (pointers)

**Important:** Pages do NOT have to be physically sequential! After many inserts/splits/deletes, Page 0 might point to Page 5, which points to Page 12. The tree structure is maintained by pointers (page numbers) inside each node, not by physical adjacency. This fragmentation is why `VACUUM FULL` / `OPTIMIZE TABLE` are needed to restore sequential layout for better range scan performance.

---

### Q3: What Is a Dirty Page?

A **dirty page** is a page in the buffer pool that has been **modified in memory but NOT yet written to disk**.

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DIRTY PAGE LIFECYCLE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  STEP 1: Page loaded from disk into buffer pool                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ DISK                          BUFFER POOL (Memory)                      ││
│  │ ┌────────┐                    ┌────────┐                                ││
│  │ │ Page 5 │ ──── READ ─────►  │ Page 5 │  dirty_bit = FALSE (clean)     ││
│  │ │ id:100 │                    │ id:100 │                                ││
│  │ │ bal:500│                    │ bal:500│                                ││
│  │ └────────┘                    └────────┘                                ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  STEP 2: User runs UPDATE accounts SET balance=600 WHERE id=100              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ DISK                          BUFFER POOL (Memory)                      ││
│  │ ┌────────┐                    ┌────────┐                                ││
│  │ │ Page 5 │  (still old!)      │ Page 5 │  dirty_bit = TRUE  🔴 DIRTY!  ││
│  │ │ id:100 │                    │ id:100 │                                ││
│  │ │ bal:500│   ≠                │ bal:600│  ← Modified in memory!         ││
│  │ └────────┘                    └────────┘                                ││
│  │                                                                         ││
│  │ NOTE: Disk still has old value! Only memory is updated.                 ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  STEP 3: Background writer (or checkpoint) flushes dirty page                │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ DISK                          BUFFER POOL (Memory)                      ││
│  │ ┌────────┐                    ┌────────┐                                ││
│  │ │ Page 5 │ ◄── WRITE ────────│ Page 5 │  dirty_bit = FALSE (clean)     ││
│  │ │ id:100 │                    │ id:100 │                                ││
│  │ │ bal:600│   =                │ bal:600│  ← Now disk matches memory     ││
│  │ └────────┘                    └────────┘                                ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

WHY DELAY WRITES?
1. BATCHING: 10 updates to same page = 1 disk write (not 10)
2. PERFORMANCE: Disk writes are slow; memory writes are fast
3. SAFETY: WAL guarantees durability even if dirty page not yet flushed
```

---

### Q5: B-Tree and LSM-Tree Visualization With Pages

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         B-TREE: PAGES ON DISK                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  LOGICAL VIEW (Tree):              PHYSICAL VIEW (Disk Pages):               │
│                                                                              │
│        [Root]                      ┌──────────────────────────────────────┐ │
│        /    \                      │ DISK FILE (e.g., tablespace.dat)     │ │
│    [Node]  [Node]                  │                                      │ │
│    /   \    /   \                  │ Page 0: File header                  │ │
│  [Leaf][Leaf][Leaf][Leaf]          │ Page 1: Root node [keys: 50, 100]    │ │
│                                    │ Page 2: Internal node [keys: 25]     │ │
│                                    │ Page 3: Internal node [keys: 75]     │ │
│                                    │ Page 4: Leaf [rows 1-40]             │ │
│                                    │ Page 5: Leaf [rows 41-80]            │ │
│                                    │ Page 6: Leaf [rows 81-120]           │ │
│                                    │ Page 7: Leaf [rows 121-160]          │ │
│                                    │ Page 8: (free)                       │ │
│                                    │ ...                                  │ │
│                                    └──────────────────────────────────────┘ │
│                                                                              │
│  FINDING id=75:                                                              │
│  1. Read Page 1 (root)     → "75 > 50, go right child (Page 3)"             │
│  2. Read Page 3 (internal) → "75 >= 75, go to Page 6"                        │
│  3. Read Page 6 (leaf)     → Binary search within page, find id=75          │
│                                                                              │
│  TOTAL: 3 page reads = 3 disk I/Os (but root often cached!)                 │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│                        LSM-TREE: PAGES ON DISK                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  MEMORY:                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ MemTable (Red-Black Tree / Skip List)                                 │  │
│  │ Sorted in memory: key1 < key2 < key3 < key4 < ...                     │  │
│  │ Size: ~64MB (configurable)                                            │  │
│  │ When full → Flush to disk as new SSTable                              │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                         │                                    │
│                                         │ FLUSH (sequential write)           │
│                                         ▼                                    │
│  DISK:                                                                       │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ Level 0: Recent SSTables (each is a separate file)                    │  │
│  │                                                                       │  │
│  │  ┌──────────────────┐  Each SSTable is MANY 4KB pages:                │  │
│  │  │ sst-001.db       │  ┌────┬────┬────┬────┬────┬────┬────┐          │  │
│  │  │ (256MB file)     │  │Pg 0│Pg 1│Pg 2│Pg 3│... │Idx │Blm │          │  │
│  │  └──────────────────┘  │Data│Data│Data│Data│    │    │    │          │  │
│  │                        └────┴────┴────┴────┴────┴────┴────┘          │  │
│  │  ┌──────────────────┐                                                 │  │
│  │  │ sst-002.db       │                                                 │  │
│  │  └──────────────────┘                                                 │  │
│  │                                                                       │  │
│  │  ┌──────────────────┐                                                 │  │
│  │  │ sst-003.db       │                                                 │  │
│  │  └──────────────────┘                                                 │  │
│  │                          │                                            │  │
│  │                          │ COMPACTION (merge + deduplicate)           │  │
│  │                          ▼                                            │  │
│  │ Level 1: Larger, merged SSTables                                      │  │
│  │  ┌────────────────────────────────────┐                               │  │
│  │  │ sst-L1-001.db (merged from above)  │                               │  │
│  │  └────────────────────────────────────┘                               │  │
│  │                          │                                            │  │
│  │                          ▼                                            │  │
│  │ Level 2: Even larger                                                  │  │
│  │  ┌────────────────────────────────────────────────────────────────┐   │  │
│  │  │ sst-L2-001.db                                                  │   │  │
│  │  └────────────────────────────────────────────────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                              │
│  FINDING key="user:5000":                                                    │
│  1. Check MemTable (memory)        → Not found                              │
│  2. Check Bloom filter of sst-003  → "Maybe here"                           │
│  3. Read index block of sst-003    → "Data block at offset X"               │
│  4. Read data block at offset X    → Found! (or not, check next level)      │
│                                                                              │
│  WORST CASE: Check all levels = many disk reads (why LSM reads are slower)  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

### Q6: Buffer Pool Write Path - Where Do Writes Go First?

This is the **most important question** for understanding database durability!

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    THE WRITE PATH (Step by Step)                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  User: UPDATE accounts SET balance = 600 WHERE id = 100                      │
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 1: Write to WAL (Write-Ahead Log) FIRST!                           ││
│  │                                                                          ││
│  │         ┌──────────────────────────────────────────────────────────┐    ││
│  │         │ WAL FILE (append-only, sequential writes)                │    ││
│  │         │                                                          │    ││
│  │         │ ...previous entries...                                   │    ││
│  │         │ LSN:1001 | UPDATE accounts SET balance=600 WHERE id=100 │◄───┤│
│  │         │                                                          │    ││
│  │         └──────────────────────────────────────────────────────────┘    ││
│  │                                                                          ││
│  │         fsync() → GUARANTEED ON DISK! (This is what makes it durable)   ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 2: Apply change to Buffer Pool (Memory)                             ││
│  │                                                                          ││
│  │         ┌────────────────────────────────────────┐                      ││
│  │         │ BUFFER POOL                            │                      ││
│  │         │                                        │                      ││
│  │         │ Page 5: id=100, balance=600 🔴 DIRTY   │◄─── Modified here    ││
│  │         │                                        │                      ││
│  │         └────────────────────────────────────────┘                      ││
│  │                                                                          ││
│  │         NO fsync() yet! Data page is only in memory.                     ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 3: Acknowledge to User                                              ││
│  │                                                                          ││
│  │         "UPDATE 1" → User sees success!                                  ││
│  │                                                                          ││
│  │         WHY IS THIS SAFE?                                                ││
│  │         • WAL is on disk (Step 1)                                        ││
│  │         • If crash now, replay WAL to recover!                           ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
│  ┌─────────────────────────────────────────────────────────────────────────┐│
│  │ STEP 4: Background - Dirty page written to disk (later)                  ││
│  │                                                                          ││
│  │         ┌────────────────┐                  ┌────────────────┐           ││
│  │         │ BUFFER POOL    │                  │ DATA FILE      │           ││
│  │         │ Page 5 (dirty) │ ──── FLUSH ───►  │ Page 5         │           ││
│  │         │ balance=600    │    (background)  │ balance=600    │           ││
│  │         └────────────────┘                  └────────────────┘           ││
│  │                                                                          ││
│  │         Checkpoint process writes dirty pages periodically               ││
│  │         After this, old WAL entries can be discarded                     ││
│  └─────────────────────────────────────────────────────────────────────────┘│
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why Buffer Pool? (The Benefits)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    BUFFER POOL BENEFITS                                      │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  BENEFIT 1: AVOID REDUNDANT DISK READS                                       │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Without Buffer Pool:                                                        │
│    Query 1: SELECT * FROM users WHERE id=100  → Read from disk (10ms)       │
│    Query 2: SELECT * FROM users WHERE id=100  → Read from disk (10ms)       │
│    Query 3: SELECT * FROM users WHERE id=100  → Read from disk (10ms)       │
│    Total: 30ms                                                               │
│                                                                              │
│  With Buffer Pool:                                                           │
│    Query 1: SELECT * FROM users WHERE id=100  → Read from disk (10ms)       │
│    Query 2: SELECT * FROM users WHERE id=100  → Read from MEMORY (0.1ms)    │
│    Query 3: SELECT * FROM users WHERE id=100  → Read from MEMORY (0.1ms)    │
│    Total: 10.2ms (3x faster!)                                                │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│  BENEFIT 2: BATCH MULTIPLE WRITES TO SAME PAGE                               │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Without Buffer Pool:                                                        │
│    UPDATE id=100 → Write to disk                                             │
│    UPDATE id=101 → Write to disk (same page!)                                │
│    UPDATE id=102 → Write to disk (same page!)                                │
│    Total: 3 disk writes                                                      │
│                                                                              │
│  With Buffer Pool:                                                           │
│    UPDATE id=100 → Modify in memory (dirty page)                             │
│    UPDATE id=101 → Modify in memory (same page, already dirty)              │
│    UPDATE id=102 → Modify in memory (same page, already dirty)              │
│    ... later, flush once ...                                                 │
│    Total: 1 disk write! (3x less I/O)                                        │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│  BENEFIT 3: SEQUENTIAL WAL WRITES ARE FAST                                   │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  Data pages are scattered across disk (random I/O = slow)                    │
│  WAL is append-only (sequential I/O = fast!)                                 │
│                                                                              │
│  RANDOM I/O:   Seek to page A, write | Seek to page B, write | ...          │
│                ▼          ▼             ▼          ▼                         │
│                ~10ms      ~10ms         ~10ms      ~10ms                     │
│                                                                              │
│  SEQUENTIAL:   Append | Append | Append | Append                             │
│                ▼        ▼        ▼        ▼                                  │
│                ~0.1ms   ~0.1ms   ~0.1ms   ~0.1ms                             │
│                                                                              │
│  ─────────────────────────────────────────────────────────────────────────── │
│                                                                              │
│  SUMMARY:                                                                    │
│  • User gets fast acknowledgment (WAL is sequential, fast)                  │
│  • Data page writes are batched and done in background                      │
│  • Crash recovery replays WAL to rebuild any lost dirty pages              │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### The Key Insight

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                              │
│   Q: "Do we write to buffer pool and ack, or write to disk first?"          │
│                                                                              │
│   A: BOTH, but in the right order:                                           │
│                                                                              │
│      1. Write to WAL on disk (for durability)        ◄── This is the key!   │
│      2. Write to buffer pool in memory (for speed)                          │
│      3. Acknowledge to user                                                  │
│      4. Later: Flush dirty pages to data files                              │
│                                                                              │
│   The WAL write is what makes it durable.                                    │
│   The buffer pool write is what makes it fast.                              │
│   Together, you get BOTH durability AND performance.                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Interview Checklist
- [ ] "Why does Cassandra use LSM-Trees while PostgreSQL uses B-Trees?"
- [ ] "What is write amplification and how does each structure handle it?"
- [ ] "How do Bloom filters help LSM-Tree read performance?"
- [ ] "What is compaction and when does it happen?"

#### Page Storage
- [ ] "What happens when you insert a row larger than a page?"
- [ ] "Why do we care about page splits?"
- [ ] "How does page size affect query performance?"

#### Buffer Pool
- [ ] "How do you know if your buffer pool is undersized?"
- [ ] "What is a dirty page and when does it get written to disk?"
- [ ] "Why might a full table scan hurt other queries?"

#### WAL
- [ ] "What is the Write-Ahead Logging rule?"
- [ ] "How does crash recovery work?"
- [ ] "What's the relationship between WAL and replication?"
- [ ] "How do checkpoints affect recovery time?"

### Key Formulas

```
Buffer Pool Hit Ratio = cache_hits / (cache_hits + cache_misses)
Target: > 99%

Write Amplification = bytes_written_to_disk / bytes_written_by_app
B-Tree: 10-30x, LSM-Tree: 10-100x (depends on compaction)

Read Amplification = disk_reads / logical_reads
B-Tree: 1x (usually), LSM-Tree: 1-10x (depends on levels)
```

### Common Pitfalls

| Mistake | Why It's Wrong | Correct Answer |
|---------|----------------|----------------|
| "B-Trees are always faster" | Depends on workload | B-Trees favor reads, LSM favors writes |
| "Just increase buffer pool" | Can cause OOM or swap | Size should be based on working set |
| "WAL is for backup" | That's one use, but not primary | Primary purpose is durability |
| "Checkpoints are for performance" | They actually hurt performance | They limit recovery time |

---

## Next Steps

Continue to **[Level 2: Database Logic](02_DATABASE_LOGIC.md)** to learn about indexing, MVCC, and isolation levels.

