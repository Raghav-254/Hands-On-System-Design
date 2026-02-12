# 🗄️ S3-like Object Storage - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 9

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **API Service** | Handle HTTP requests (PUT, GET, DELETE) | Stateless, behind load balancer |
| **Identity & Access Mgmt** | Auth & authorization | Validates who can access which bucket/object |
| **Metadata Service** | Store/query object metadata | Where is this object? What versions exist? |
| **Metadata DB** | Persist metadata | Sharded database (bucket, object name, UUID, versions) |
| **Data Routing Service** | Route data to correct nodes | Consults Placement Service for node selection |
| **Placement Service** | Manage cluster topology | Virtual cluster map, heartbeats, node health |
| **Data Nodes** | Store actual bytes | Primary + Secondaries, replication across DCs |

---

## The Story: Building an S3-like Object Storage

Object storage is fundamentally different from file systems or block storage.
There are no directories — just flat key-value pairs: a **key** (like `photos/cat.jpg`)
maps to a **blob** of bytes. Let me walk you through designing one at 100 PB scale.

The entire system boils down to two questions:
1. **Where are the bytes?** → Data Store (actual data on disk)
2. **What do I know about the object?** → Metadata Store (name, size, location)

Every design decision flows from keeping these two concerns cleanly separated.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Bucket creation** — top-level container for objects (globally unique name)
- **Object upload (PUT)** — store any blob of bytes under a key
- **Object download (GET)** — retrieve bytes by bucket + key
- **Object versioning** — keep multiple versions of the same key
- **Listing objects** — list objects in a bucket (like `aws s3 ls`)

### Non-Functional Requirements

- **100 PB** of storage capacity
- **6 nines durability** (99.9999%) — lose at most 1 object per million
- **4 nines availability** (99.99%) — ~52 minutes downtime per year
- **Storage efficiency** — minimize cost while maintaining durability

### Back-of-the-Envelope Estimation

```
┌──────────────────────────────────────────────────────────────┐
│  Storage capacity:       100 PB                              │
│  Durability:             99.9999% (6 nines)                  │
│  Availability:           99.99% (4 nines)                    │
│                                                              │
│  Object size distribution:                                   │
│    20% small  (<1MB,  median 0.5MB)                          │
│    60% medium (1-64MB, median 32MB)                          │
│    20% large  (>64MB, median 200MB)                          │
│                                                              │
│  Weighted average size:                                      │
│    0.2 × 0.5 + 0.6 × 32 + 0.2 × 200 = 59.3 MB             │
│                                                              │
│  At 40% storage utilization:                                 │
│    (100PB × 0.4) / 59.3 MB ≈ 0.68 billion objects           │
│                                                              │
│  Metadata per object: ~1 KB                                  │
│  Total metadata: 0.68B × 1KB = 0.68 TB                      │
│                                                              │
│  → Metadata is TINY (0.68 TB) vs actual data (100 PB)       │
│  → Bottleneck is DISK CAPACITY and DISK IOPS, not metadata  │
│  → SATA 7200 rpm disk: 100-150 random IOPS                  │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. API Design

Object storage APIs follow a simple REST pattern. The object is addressed
by `bucket_name` + `object_name` (the key).

### Bucket APIs

```
PUT /bucket-to-share
  → Creates a new bucket named "bucket-to-share"
  → Bucket names are globally unique

DELETE /bucket-to-share
  → Deletes the bucket (must be empty)
```

### Object APIs

```
PUT /{bucket_name}/{object_name}
  Body: raw bytes of the object
  Headers: Content-Type, Content-Length
  → Uploads (or overwrites) the object
  → Returns: { object_id (UUID), version_id, etag (checksum) }

GET /{bucket_name}/{object_name}
  → Downloads the object (latest version)
  → Returns: raw bytes + metadata headers

GET /{bucket_name}/{object_name}?versionId=3
  → Downloads a specific version

DELETE /{bucket_name}/{object_name}
  → Non-versioned: permanently deletes
  → Versioned: adds a "delete marker" (soft delete)

GET /{bucket_name}?prefix=photos/&maxKeys=1000
  → Lists objects in the bucket with optional prefix filter
  → Returns: list of { object_name, size, last_modified }
```

---

## 3. The Big Picture (High-Level Architecture)

The system has two cleanly separated halves: **Metadata Store** (what/where)
and **Data Store** (actual bytes). They're separated because metadata is tiny
(0.68 TB) and needs fast queries, while data is massive (100 PB) and needs
raw disk throughput.

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║              S3-LIKE OBJECT STORAGE - HIGH-LEVEL DESIGN                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║                         ┌──────────────┐                                     ║
║                         │    Client    │                                     ║
║                         └──────┬───────┘                                     ║
║                                │                                              ║
║                         ┌──────┴───────┐                                     ║
║                         │Load Balancer │                                     ║
║                         └──────┬───────┘                                     ║
║                                │                                              ║
║         ┌──────────────────────┼──────────────────────┐                      ║
║         │                      │                      │                      ║
║         ▼                      ▼                      ▼                      ║
║  ┌──────────────┐     ┌──────────────┐     ┌──────────────────┐             ║
║  │  Identity &  │◀───▶│  API Service │────▶│   Data Store     │             ║
║  │  Access Mgmt │     │  (stateless) │     │                  │             ║
║  └──────────────┘     └──────┬───────┘     │ ┌──────────────┐│             ║
║                              │              │ │Data Routing  ││             ║
║                              │              │ │Service       ││             ║
║                              ▼              │ └──────┬───────┘│             ║
║                       ┌──────────────┐     │        │         │             ║
║                       │  Metadata    │     │ ┌──────┴───────┐│             ║
║                       │  Store       │     │ │ Placement    ││             ║
║                       │              │     │ │ Service      ││             ║
║                       │ ┌──────────┐ │     │ └──────┬───────┘│             ║
║                       │ │Metadata  │ │     │        │         │             ║
║                       │ │Service   │ │     │ ┌──────┴───────┐│             ║
║                       │ └────┬─────┘ │     │ │ Data Nodes   ││             ║
║                       │      │       │     │ │(Primary +    ││             ║
║                       │ ┌────┴─────┐ │     │ │ Secondaries) ││             ║
║                       │ │Metadata  │ │     │ └──────────────┘│             ║
║                       │ │   DB     │ │     └──────────────────┘             ║
║                       │ └──────────┘ │                                      ║
║                       └──────────────┘                                      ║
║                                                                               ║
║  Metadata Store: SMALL (0.68 TB)        Data Store: HUGE (100 PB)           ║
║  "Where is this object?"                "Here are the actual bytes"          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. Deep Dive: Uploading an Object

This involves **two separate API calls** from the client. The bucket must
exist before uploading objects to it.

### Call 1: Create the Bucket (one-time setup)

> **Why PUT and not POST?** PUT is idempotent (safe to retry) and means "create
> at this exact URL" — the client chooses the bucket name, not the server.

```
User: PUT /bucket-to-share
      │
      ▼
① Load Balancer → API Service
      │
      ▼
② API Service → Identity & Access Management
   "Does this user have permission to create a bucket?"
      │
      ▼
③ API Service → Metadata Service
   Store: bucket_name="bucket-to-share", owner, creation_time, ACLs
      │
      ▼
④ Return success: bucket created
```

### Call 2: Upload an Object

```
User: PUT /bucket-to-share/script.txt  (with file bytes)
      │
      ▼
① Load Balancer → API Service
      │
      ▼
② API Service → Identity & Access Management
   "Does this user have permission to PUT objects in 'bucket-to-share'?"
   → YES: continue
   → NO: 403 Forbidden
      │
      ▼
③ API Service → Data Store (upload the actual bytes)
   │
   ├──▶ Data Routing Service
   │    "Where should I store this?"
   │         │
   │         ▼
   │    Placement Service
   │    "Use node-1 (DC-1) as Primary, node-2 (DC-2) and node-3 (DC-3) as Secondaries"
   │         │
   │         ▼
   │    Write to Primary Data Node → Replicate to Secondaries
   │    Returns: object_id (UUID) = "a1b2c3d4-..."
   │
      ▼
④ API Service → Metadata Service
   "Store the metadata for this object"
   Record: bucket_name="bucket-to-share", object_name="script.txt",
           object_id="a1b2c3d4-...", size=4096, checksum="sha256:abcd..."
      │
      ▼
⑤ Return success to user: { object_id, etag }
```

> **Why two separate calls?** Creating a bucket and uploading an object are
> different operations — like creating a folder vs putting a file in it.
> The bucket is created once; then you upload millions of objects to it.
> Each call goes through auth independently (standard REST practice).

> **Why data BEFORE metadata?** If we stored metadata first and then the data
> upload failed, we'd have a metadata entry pointing to non-existent bytes
> (a dangling pointer). By storing data first, the worst case is orphaned data
> with no metadata — which garbage collection can clean up later (Section 12).

---

## 5. Deep Dive: Downloading an Object

Now the reverse — fetching `script.txt` from `bucket-to-share`.

```
User: GET /bucket-to-share/script.txt
      │
      ▼
① Load Balancer → API Service
      │
      ▼
② API Service → Identity & Access Management
   "Does this user have read access to 'bucket-to-share'?"
      │
      ▼
③ API Service → Metadata Service
   "Where is 'bucket-to-share/script.txt'?"
   → Query Metadata DB
   → Returns: object_id = "a1b2c3d4-...", size = 4096, nodes = [node-1, node-2, node-3]
      │
      ▼
④ API Service → Data Store
   "Give me the bytes for object_id = a1b2c3d4-..."
   │
   ├──▶ Data Routing Service → tries Primary (node-1) first
   │    → If Primary is down → fallback to Secondary (node-2)
   │
      ▼
⑤ Return bytes to user + metadata headers (Content-Type, ETag, etc.)
```

> **Key insight:** The download path uses the `object_id` (UUID) from the
> Metadata DB to locate the actual bytes in the Data Store. The Metadata
> Store is the "index" and the Data Store is the "filing cabinet."

---

## 6. Deep Dive: Data Store Internals

Now let's zoom into the Data Store — the part that actually persists bytes to disk.
This is where durability and performance live.

### Components

```
┌──────────────────────────────────────────────────────────────────┐
│ Data Store                                                        │
│                                                                   │
│  ┌──────────────────┐                                            │
│  │ Data Routing     │ ← Receives data from API Service           │
│  │ Service          │ ← Consults Placement Service for routing   │
│  └────────┬─────────┘                                            │
│           │                                                       │
│  ┌────────┴─────────┐                                            │
│  │ Placement Service│ ← Knows the virtual cluster map            │
│  │                  │ ← Receives heartbeats from all data nodes  │
│  │                  │ ← Decides: which node is Primary, which    │
│  │                  │   are Secondaries for each new object      │
│  └────────┬─────────┘                                            │
│           │                                                       │
│     ┌─────┼──────────────────┐                                   │
│     ▼     ▼                  ▼                                   │
│  ┌──────┐ ┌──────┐   ┌──────┐                                   │
│  │Node 1│ │Node 2│   │Node 3│                                    │
│  │(DC-1)│ │(DC-2)│   │(DC-3)│                                    │
│  │Primary│ │Sec.  │   │Sec.  │                                   │
│  └──────┘ └──────┘   └──────┘                                   │
│                                                                   │
└──────────────────────────────────────────────────────────────────┘
```

### Virtual Cluster Map

The Placement Service maintains a hierarchical view of the cluster:

```
                      ┌─────────┐
        Root:         │ Default │
                      └────┬────┘
                     ┌─────┴──────┐
        Datacenter:  │            │
                  ┌──┴──┐    ┌───┴──┐
                  │DC-1 │    │DC-2  │
                  └──┬──┘    └──┬───┘
                 ┌───┴───┐   ┌──┴───┐
        Host:    │       │   │      │
              ┌──┴─┐  ┌─┴──┐ ┌┴──┐ ┌┴──┐
              │H-1 │  │H-2 │ │H-3│ │H-4│
              └─┬──┘  └─┬──┘ └─┬─┘ └─┬─┘
        Part:  P1,P2  P3,P4  P5,P6  P7,P8
```

> **Why this hierarchy?** When placing replicas, the Placement Service ensures
> they go to **different data centers** (or at least different hosts). This way,
> if an entire DC goes down, replicas in other DCs keep the data available.

### Data Persistence Flow

```
API Service
    │
    │ ① Write data
    ▼
Data Routing Service
    │
    │ ② "Where should this go?"
    ▼
Placement Service ◀──── Heartbeats from all Data Nodes
    │
    │ "Primary = node-1 (DC-1), Secondaries = node-2 (DC-2), node-3 (DC-3)"
    │
    │ ③ Send data to Primary
    ▼
Data Node Primary (node-1)
    │
    │ ④ Replicate to Secondaries
    ├──────────────────▶ Data Node Secondary (node-2)
    │                    ④ Replicate
    └──────────────────▶ Data Node Secondary (node-3)
    │
    │ ⑤ All replicas confirmed → return object_id (UUID) to API Service
    ▼
API Service receives object_id
```

---

## 7. Deep Dive: Data Organization on Disk

How do data nodes actually store objects on disk? Not as individual files — that would
be terrible for small objects.

### The Problem with One-File-Per-Object

```
If we store each object as a separate file on disk:
  • 0.68 billion objects = 0.68 billion files
  • Each file has inode overhead (~256 bytes)
  • Linux ext4 limit: ~4 billion inodes per filesystem
  • Tons of small files → filesystem metadata bloat
  • Random I/O for small files kills disk performance
```

### The Solution: Pack Small Objects into Large Files

```
Each data node has a local file system with:

  ┌──────────────┐ ┌──────────────┐ ┌──────────────────┐
  │ Read-only    │ │ Read-only    │ │ Read-write File   │
  │ File         │ │ File         │ │                   │
  │              │ │              │ │ ┌───────────────┐ │
  │ (sealed,     │ │ (sealed,     │ │ │ object 1      │ │ ← append
  │  immutable)  │ │  immutable)  │ │ ├───────────────┤ │
  │              │ │              │ │ │ object 2      │ │ ← append
  │              │ │              │ │ ├───────────────┤ │
  └──────────────┘ └──────────────┘ │ │ object 3      │ │ ← append
                                     │ ├───────────────┤ │
                                     │ │ object 4      │ │ ← NEW write
                                     │ ├───────────────┤ │
                                     │ │ (empty space)  │ │
                                     │ └───────────────┘ │
                                     └──────────────────┘

  How it works:
  ① New objects are APPENDED to the current read-write file
    (like a WAL — write-ahead log, sequential writes = fast)
  ② When the read-write file reaches a size limit (e.g., a few GB)
    → it's sealed and becomes read-only
    → a new read-write file is created
  ③ Metadata records: object_id → (file_name, offset, size)
    So to read object 2: seek to offset in the file, read 'size' bytes

  Why "read-write" vs "read-only"?
  • Read-write file: the ACTIVE file currently accepting new writes (appends).
    Only ONE read-write file exists at a time per data node.
  • Read-only files: SEALED files that are full and no longer accept writes.
    They only serve read requests. Immutable = safe to cache, replicate.

  Lifecycle:  New (read-write) → Full → Sealed (read-only) → Compacted eventually

  Why?
  • Sequential writes (append-only) are 100× faster than random writes
  • Fewer files = less filesystem metadata overhead
  • Small objects packed together = efficient disk utilization
```

---

## 8. Deep Dive: Durability

We promised 6 nines (99.9999%) durability. How do we ensure objects aren't lost?

### Option 1: Replication (Simple, More Storage)

```
Store 3 copies of every object across different data centers:

  Object "a1b2c3" is stored on:
    node-1 (DC-1) ← Primary
    node-2 (DC-2) ← Secondary
    node-3 (DC-3) ← Secondary

  If node-1 dies → still have 2 copies
  If DC-1 burns down → still have copies in DC-2 and DC-3

  Storage overhead: 3× (store 300 PB for 100 PB of data)
  
  Pros: Simple, fast reads (read from any replica)
  Cons: 3× storage cost — expensive at petabyte scale
```

### Option 2: Erasure Coding (Complex, Less Storage)

```
Instead of storing 3 full copies, split data into chunks and add parity:

  What are parity chunks?
    Parity chunks are mathematical "summaries" computed from the data chunks.
    They contain enough information to reconstruct any missing data chunk.

    Simple analogy (XOR parity):
      Data:   A = 5,  B = 3
      Parity: P = A + B = 8
      If A is lost → A = P - B = 8 - 3 = 5 (reconstructed!)

    Reed-Solomon is more complex math, but the same idea — parity lets
    you recover lost data without storing full copies.

  Example: 4+2 erasure coding (Reed-Solomon)
    Original object → split into 4 data chunks (d1, d2, d3, d4)
    Compute 2 parity chunks (p1, p2) from the data chunks
    Store all 6 chunks on 6 different nodes

    d1 → node-1 (DC-1)
    d2 → node-2 (DC-1)
    d3 → node-3 (DC-2)
    d4 → node-4 (DC-2)
    p1 → node-5 (DC-3)
    p2 → node-6 (DC-3)

  Can tolerate ANY 2 node failures:
    Lost d1, d3? → Reconstruct from d2, d4, p1, p2
    Lost d1, p1? → Reconstruct from d2, d3, d4, p2

  Storage overhead: 6/4 = 1.5× (vs 3× for replication)
  Saves 50% storage compared to 3-way replication!

  Pros: Storage efficient (1.5× vs 3×)
  Cons: Complex reconstruction, higher read latency (need multiple nodes),
        CPU overhead for encoding/decoding
```

### Comparison

| | Replication (3 copies) | Erasure Coding (4+2) |
|---|---|---|
| **Storage overhead** | 3× (300 PB for 100 PB) | 1.5× (150 PB for 100 PB) |
| **Fault tolerance** | Survives 2 node failures | Survives 2 node failures |
| **Read speed** | Fast (read from any copy) | Slower (may need to reconstruct) |
| **Write speed** | Fast (just copy bytes) | Slower (compute parity) |
| **Complexity** | Simple | Complex (Reed-Solomon math) |
| **Best for** | Hot data (frequently accessed) | Cold data (rarely accessed, large) |

> **In practice:** Use replication for hot/small objects (fast reads),
> erasure coding for cold/large objects (save storage costs).

---

## 9. Deep Dive: Correctness Verification

How do we know the bytes we read back are the same bytes that were written?
Disks can have silent data corruption (bit rot).

```
Where is the checksum stored?
  → In the METADATA DB (objects table), as a column alongside object_id, size, etc.
  → NOT in the data file itself. The data file just has raw bytes.

Who computes and compares?
  → The API Service / Data Node computes it. Metadata DB stores it.

Write path (during upload):
  ① API Service receives bytes from client
  ② API Service computes checksum: sha256(bytes) = "abcd1234..."
  ③ Data Node stores raw bytes in the read-write file
  ④ API Service stores checksum in Metadata DB:
     object_id → checksum = "sha256:abcd1234..."

Read path (during download):
  ① Data Node reads raw bytes from the data file
  ② Data Node recomputes checksum: sha256(bytes_read) = ???
  ③ Compare with checksum from Metadata DB
     → Match: data is good ✓ → return to client
     → Mismatch: data is CORRUPT!
       → Read from a different replica
       → Re-replicate the good copy to replace the corrupt one

Background:
  Periodic "scrubbing" — read all data and verify checksums.
  Detects bit rot before a second failure makes it unrecoverable.
```

---

## 10. Deep Dive: Metadata Data Model

The metadata is small but critical — it's the "index" that makes everything findable.

### Schema

```
┌──────────────────────────────────────────────────────────────────┐
│ buckets table                                                     │
├──────────────────┬───────────────────────────────────────────────┤
│ bucket_name (PK) │ "bucket-to-share"                             │
│ owner_id         │ "user-alice"                                  │
│ versioning       │ true/false                                    │
│ created_at       │ timestamp                                     │
│ acl              │ access control list                           │
└──────────────────┴───────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│ objects table                                                     │
├──────────────────┬───────────────────────────────────────────────┤
│ bucket_name (PK) │ "bucket-to-share"                             │
│ object_name (PK) │ "script.txt"                                  │
│ version_id  (PK) │ 3 (for versioned buckets)                    │
│ object_id        │ "a1b2c3d4-..." (UUID — points to Data Store) │
│ size             │ 4096 bytes                                    │
│ content_type     │ "text/plain"                                  │
│ checksum         │ "sha256:abcd1234..."                          │
│ is_delete_marker │ false                                         │
│ created_at       │ timestamp                                     │
├──────────────────┴───────────────────────────────────────────────┤
│ PK: (bucket_name, object_name, version_id)                       │
│                                                                   │
│ The object_id (UUID) is the bridge between Metadata and Data     │
│ Store. Metadata says "script.txt is UUID a1b2c3d4", and the     │
│ Data Store knows where UUID a1b2c3d4's bytes live on disk.       │
└──────────────────────────────────────────────────────────────────┘
```

### Data Store Internal Mapping

The Data Store maintains its own mapping from `object_id` to the physical
location on disk. This is stored locally on each Data Node.

```
┌──────────────────────────────────────────────────────────────────┐
│ Data Node local index (stored on each node)                      │
├───────────────┬──────────────────────────────────────────────────┤
│ object_id     │ "a1b2c3d4-..."                                   │
│ file_name     │ "data_file_0042.dat" (which packed file)         │
│ offset        │ 8192 (byte offset within the file)               │
│ size          │ 4096 (bytes to read from that offset)            │
├───────────────┴──────────────────────────────────────────────────┤
│                                                                   │
│ To read object "a1b2c3d4":                                       │
│   Open "data_file_0042.dat", seek to offset 8192, read 4096 bytes│
│                                                                   │
│ This index is small (object_id + file + offset + size ≈ 100 bytes│
│ per entry) and kept in memory or in a local embedded DB (RocksDB)│
│ on each Data Node for fast lookups.                              │
└──────────────────────────────────────────────────────────────────┘

So the full lookup chain is:

  Client: "Give me bucket-to-share/script.txt"
    │
    ▼
  Metadata DB: (bucket, object_name) → object_id = "a1b2c3d4"
    │
    ▼
  Data Node local index: object_id → (file_name, offset, size)
    │
    ▼
  Disk: read file at offset → return raw bytes
```

### Which Database for Metadata?

```
Metadata is 0.68 TB — small enough for a traditional relational DB.

Why MySQL/PostgreSQL (with sharding)?
  • Metadata operations need ACID transactions
    (e.g., versioning: atomically add new version + update "latest" pointer)
  • Access patterns are simple: point queries and range scans by URI
  • 0.68 TB fits comfortably in a sharded MySQL setup (e.g., Vitess)
  • Well-understood, battle-tested for this scale

Why NOT NoSQL (Cassandra/DynamoDB)?
  • We need strong consistency for metadata (two uploads to the same key
    must not create conflicting entries)
  • Listing objects in a bucket needs range scans — relational DBs
    handle this well with proper indexing
```

### Sharding the Metadata DB

```
The queries we need to support efficiently:

  Query 1: Find object by URI (used by GET, PUT, DELETE)
    SELECT * FROM objects
    WHERE bucket_name = 'my-bucket' AND object_name = 'photos/cat.jpg'
    → Most frequent query — every upload, download, delete uses this

  Query 2: List objects in a bucket (used by aws s3 ls)
    SELECT * FROM objects
    WHERE bucket_name = 'my-bucket' AND object_name LIKE 'photos/%'
    → Less frequent — browsing / listing operations

  Query 3: Get all versions of an object (used by version listing)
    SELECT * FROM objects
    WHERE bucket_name = 'my-bucket' AND object_name = 'cat.jpg'
    ORDER BY version_id DESC

The shard key must optimize for Query 1 (by far the most common).

At 0.68 TB, a single instance could handle the storage.
But for high availability and throughput, we shard.

Three sharding options:

  ❌ Option 1: Shard by bucket_name
     All objects in the same bucket → same shard.
     Problem: One bucket can have BILLIONS of objects → hotspot shard!
     A single popular bucket overwhelms one shard.

  ❌ Option 2: Shard by object_id (UUID)
     Evenly distributed — no hotspots.
     Problem: Most API operations use the URI, NOT the object_id.

     URI vs UUID — they are completely different:
       URI  = the human-readable path the client uses in the URL
              e.g., /my-bucket/photos/cat.jpg  (bucket_name + object_name)
       UUID = the internal ID the server generates after upload
              e.g., a1b2c3d4-e5f6-7890-...  (object_id, client never sees this)

     The client only knows the URI. For example:
       PUT /my-bucket/photos/cat.jpg  → we know bucket + object name
       GET /my-bucket/photos/cat.jpg  → we know bucket + object name
     With object_id sharding, we'd need to query ALL shards to find
     which shard has this object's metadata. That's a scatter-gather
     across every shard — terrible for latency.

  ✅ Option 3: Shard by hash(bucket_name, object_name)
     Shard key matches the access pattern!

     Why it works:
       Every API call includes bucket_name + object_name in the URL.
       hash("my-bucket", "photos/cat.jpg") → shard 7
       → Route directly to shard 7. No scatter-gather.

       PUT /my-bucket/photos/cat.jpg → hash → shard 7 → write metadata
       GET /my-bucket/photos/cat.jpg → hash → shard 7 → read metadata

     Why no hotspot?
       Objects from the same bucket are SPREAD across shards
       (hash distributes them). Unlike Option 1, no single shard
       gets all objects from a popular bucket.

     Example: 6 objects in "my-photos" bucket, 3 shards

       Upload — each object hashed to a shard:
         hash("my-photos", "beach.jpg")  → shard 1
         hash("my-photos", "sunset.jpg") → shard 3
         hash("my-photos", "cat.jpg")    → shard 2
         hash("my-photos", "dog.jpg")    → shard 1
         hash("my-photos", "party.jpg")  → shard 3
         hash("my-photos", "food.jpg")   → shard 2

       GET (Query 1 — fast, direct routing):
         GET /my-photos/cat.jpg
         → hash("my-photos", "cat.jpg") → shard 2
         → Go directly to shard 2, get metadata. One shard. Done.

       LIST (Query 2 — scatter-gather):
         aws s3 ls s3://my-photos/
         → We need ALL objects in "my-photos"
         → But they're spread across shard 1, 2, and 3!
         → Must query ALL shards in parallel:
           Shard 1 returns: beach.jpg, dog.jpg
           Shard 2 returns: cat.jpg, food.jpg
           Shard 3 returns: sunset.jpg, party.jpg
         → Combine results → return to client

     Tradeoff:
       GET/PUT is fast (direct to one shard), LIST is slower (all shards).
       But this is acceptable because:
       • Listing is much less frequent than GET/PUT
       • Scatter-gather queries run in parallel across shards
       • We optimized for the MOST COMMON operation (direct routing)
```

---

## 11. Deep Dive: Object Versioning

When versioning is enabled, uploading the same key creates a new version
instead of overwriting.

```
PUT script.txt (v1: "hello")    → object_id = UUID-aaa, version_id = 1
PUT script.txt (v2: "hello v2") → object_id = UUID-bbb, version_id = 2
PUT script.txt (v3: "hello v3") → object_id = UUID-ccc, version_id = 3

Metadata DB now has 3 rows for "script.txt":
  (bucket, "script.txt", v1) → UUID-aaa
  (bucket, "script.txt", v2) → UUID-bbb
  (bucket, "script.txt", v3) → UUID-ccc

GET script.txt         → returns v3 (latest)
GET script.txt?v=1     → returns v1

DELETE script.txt (versioned bucket):
  → Does NOT delete any data!
  → Adds a "delete marker" as v4:
    (bucket, "script.txt", v4) → DELETE_MARKER

  GET script.txt       → 404 (latest version is a delete marker)
  GET script.txt?v=2   → still returns v2 (old versions preserved!)

  To permanently delete: DELETE script.txt?v=2
  → Actually removes the row and the data for v2
```

> **Why delete markers?** They preserve the version history. An accidental
> delete doesn't destroy data — just hides it. You can "undelete" by
> removing the delete marker.

---

## 12. Deep Dive: Multipart Upload (Large Files)

A 5 GB file uploaded as a single HTTP request is fragile — any network hiccup
means starting over. Multipart upload solves this.

```
Flow:
  ① Client: POST /bucket/large-file.zip?uploads
     → Server returns: upload_id = "UP-12345"

  ② Client uploads parts in parallel (separate PUT call per part):
     PUT /bucket/large-file.zip?uploadId=UP-12345&partNumber=1  (5 MB)
     PUT /bucket/large-file.zip?uploadId=UP-12345&partNumber=2  (5 MB)
     PUT /bucket/large-file.zip?uploadId=UP-12345&partNumber=3  (3 MB)
     → Each part returns: etag (checksum of that part)
     → Parts can be uploaded IN PARALLEL from multiple threads/machines

  ③ Client sends "complete" call (separate POST):
     POST /bucket/large-file.zip?uploadId=UP-12345
     Body: { parts: [{partNumber: 1, etag: "..."}, {2, "..."}, {3, "..."}] }
     → Server verifies all parts received (etags match)
     → Assembles parts into final object
     → Creates single metadata entry pointing to all parts

  Why are ② and ③ separate calls? Why doesn't the server auto-assemble?
  • The CLIENT split the file, so only the client knows how many parts
    to expect. The server has no idea if 3 parts or 100 parts are coming.
  • Parts can arrive out of order, days apart, from different machines.
    The server can't guess when "all parts are done."
  • The client tracks its own uploads — once all PUTs return success,
    it knows it's done and sends the "complete" call (③).
  • This also lets the client ABORT mid-way (skip ③ → server cleans up
    parts) or retry a single failed part without re-uploading everything.

  Benefits:
  • Parts upload in parallel → faster
  • If part 2 fails → retry ONLY part 2 (not the whole file)
  • Can pause and resume uploads
  • Recommended for objects > 100 MB
```

---

## 13. Deep Dive: Garbage Collection

Over time, the Data Store accumulates orphaned or deleted data that needs cleanup.

### What Needs Garbage Collection?

```
① Orphaned data (data stored but metadata write failed)
   → Data exists in Data Store, but no metadata points to it
   → Caused by crash between step ⑥ and ⑦ in upload flow

② Deleted objects (non-versioned bucket)
   → Metadata removed, but data bytes still on disk
   → GC scans for unreferenced object_ids and reclaims space

③ Deleted versions (versioned bucket, permanent delete)
   → Specific version's data bytes need cleanup

④ Compaction of data files
   → After many deletes, data files have "holes" (deleted objects)
   → Compaction rewrites live objects into new files, reclaims space
   → Similar to LSM-tree compaction
```

### Compaction Process

```
Before compaction:
  ┌─────────────────────────────┐
  │ obj1 │ DELETED │ obj3 │ DELETED │ obj5 │ (empty) │
  └─────────────────────────────┘
  → 40% of file is wasted space

After compaction:
  ┌──────────────────┐
  │ obj1 │ obj3 │ obj5 │
  └──────────────────┘
  → Tight packing, no wasted space
  → Old file deleted, metadata updated to point to new file
```

> **When to compact?** When a data file's dead-object ratio exceeds a threshold
> (e.g., 30% deleted). Compaction runs in the background, never blocking reads.

---

## 14. Listing Objects in a Bucket

How listing routes across shards is covered in Section 10 (scatter-gather).
Here we cover the additional details: prefix filtering and pagination.

```
Prefix filtering:
  GET /my-photos?prefix=vacation/&maxKeys=1000

  Object names like "vacation/beach.jpg", "vacation/sunset.jpg"
  are just flat strings — there are no real directories in object storage.
  Prefix filtering = string matching on the object_name column.
  Each shard runs: WHERE object_name LIKE 'vacation/%'

Pagination:
  If > 1000 results, response includes a continuation_token.
  Next request: GET /my-photos?prefix=vacation/&continuation_token=xyz
  The token encodes the last object_name seen, so each shard can
  resume from where it left off.
```

---

## 15. Why These Choices? (Key Design Decisions)

### Decision #1: Separate Metadata Store from Data Store

**Why?** Metadata is tiny (0.68 TB) and needs fast lookups and transactions.
Data is massive (100 PB) and needs raw sequential disk throughput.
Combining them would force one system to do both poorly.

### Decision #2: Append-Only Data Files (Not One-File-Per-Object)

**Why?** Billions of small files would exhaust filesystem inodes and cause
random I/O. Packing objects into large append-only files gives sequential
writes (100× faster) and reduces filesystem overhead.

### Decision #3: Replication + Erasure Coding (Hybrid)

**Why?** Replication is simple and fast but 3× storage cost. Erasure coding
saves 50% storage but is complex. Use replication for hot data,
erasure coding for cold data — best of both worlds.

### Decision #4: Data Before Metadata (Upload Order)

**Why?** If metadata is written first and data upload fails, we have a
dangling pointer (metadata pointing to nothing). Orphaned data (data
without metadata) is safe — garbage collection cleans it up.

### Decision #5: Placement Across Data Centers

**Why?** Placing replicas in different DCs ensures that an entire DC failure
doesn't lose all copies. The Placement Service's virtual cluster map
enforces this topology-aware placement.

---

## 16. Interview Pro Tips

### Opening Statement
"S3-like object storage is a two-part system: a Metadata Store (tiny, ~TB scale, needs fast queries) and a Data Store (massive, ~PB scale, needs disk throughput). Objects are addressed by bucket+key, stored with UUID linking metadata to data. Durability comes from replication for hot data and erasure coding for cold data. Data is organized as append-only files on disk for sequential write performance."

### Key Talking Points
1. **Two halves:** Metadata Store (what/where) vs Data Store (actual bytes)
2. **Upload:** Data first, then metadata (avoid dangling pointers)
3. **Download:** Metadata lookup (UUID) → Data Store fetch
4. **Data organization:** Append-only files, not one-file-per-object
5. **Durability:** Replication (3×, simple, hot data) + Erasure coding (1.5×, cold data)
6. **Versioning:** New version = new UUID, delete = delete marker
7. **Multipart upload:** Parallel parts, retry individual parts, assemble at end
8. **Garbage collection:** Clean orphaned data, compact files with holes

### Common Follow-ups

**Q: How does the Placement Service decide which nodes to use?**
A: It maintains a virtual cluster map (hierarchy: DC → Host → Partition). When placing replicas, it ensures they're in different DCs. It monitors node health via heartbeats and avoids unhealthy nodes. If a node goes down, it triggers re-replication to maintain the replica count.

**Q: What happens if a Data Node dies permanently?**
A: The Placement Service detects missing heartbeats. It identifies all objects that had a replica on that node (from the replica map) and triggers re-replication from surviving replicas to a new node, restoring the target replica count.

**Q: How do you handle concurrent uploads to the same key?**
A: Last-writer-wins. If two clients upload to the same key simultaneously, both complete, but the last one to finish becomes the latest version. With versioning enabled, both versions are preserved.

**Q: Why not use HDFS or a distributed file system?**
A: HDFS is optimized for large sequential reads/writes (batch analytics). Object storage needs to handle both tiny (50 KB) and huge (5 GB) objects efficiently, with HTTP API access, per-object permissions, and flat key-value semantics — different access patterns.

---

## 17. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                S3-LIKE OBJECT STORAGE - COMPLETE FLOW                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  UPLOAD:  Client → LB → API Service                                         ║
║           → Auth (IAM)                                                        ║
║           → Data Store: Routing → Placement → Primary → Replicas             ║
║           → Metadata Store: Record (bucket, key, UUID, size, checksum)       ║
║           → Return success                                                    ║
║                                                                               ║
║  DOWNLOAD: Client → LB → API Service                                        ║
║            → Auth (IAM)                                                       ║
║            → Metadata Store: Lookup UUID by (bucket, key)                    ║
║            → Data Store: Fetch bytes by UUID                                 ║
║            → Return bytes                                                     ║
║                                                                               ║
║  DATA STORE INTERNALS:                                                        ║
║  ┌────────────────────────────────────────────────────────────────────────┐  ║
║  │ Data Routing Service → Placement Service → Data Nodes                  │  ║
║  │                                                                         │  ║
║  │ Placement Service:                                                      │  ║
║  │   Virtual cluster map: Root → DC → Host → Partition                    │  ║
║  │   Heartbeats from all nodes, topology-aware placement                  │  ║
║  │                                                                         │  ║
║  │ Data Nodes:                                                             │  ║
║  │   Append-only files (small objects packed into large files)             │  ║
║  │   Primary writes → replicate to Secondaries across DCs                 │  ║
║  │   Checksum verification on every read                                  │  ║
║  └────────────────────────────────────────────────────────────────────────┘  ║
║                                                                               ║
║  KEY DESIGN DECISIONS:                                                        ║
║  • Metadata Store (0.68 TB) separate from Data Store (100 PB)                ║
║  • Data before metadata on upload (orphaned data > dangling pointers)        ║
║  • Append-only files on disk (sequential writes, no inode exhaustion)        ║
║  • Replication for hot data, erasure coding for cold data                    ║
║  • Versioning via delete markers (safe delete, history preserved)            ║
║  • Multipart upload for large files (parallel, resumable)                    ║
║  • Garbage collection: compact files, clean orphans                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```
