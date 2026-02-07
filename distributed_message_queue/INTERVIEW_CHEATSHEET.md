# Distributed Message Queue - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview Volume 2 - Chapter 4

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Broker** | Stores and serves messages | Disk (segment files) | Leader handles reads/writes, followers replicate |
| **Producer** | Sends messages to brokers | In-memory buffer | Batches messages, routes by key hash |
| **Consumer** | Reads messages from brokers | Offset tracking | Pull model, consumer groups, partition assignment |
| **ZooKeeper** | Metadata & coordination | Disk + Memory | Broker registry, leader election, consumer groups |
| **Topic** | Logical message grouping | Metadata | Divided into partitions for parallelism |
| **Partition** | Ordered message log | Append-only segments | Unit of parallelism and replication |
| **ISR** | In-Sync Replicas | Broker state | Only caught-up replicas eligible for leader election |

---

## The Story: Building a Distributed Message Queue (Like Kafka)

Let me walk you through how we'd build a distributed, persistent message queue from scratch.

---

## 1. What Are We Building? (Requirements)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. Producers send messages to a message queue                              ║
║  2. Consumers consume messages from a message queue                         ║
║  3. Messages can be consumed repeatedly or only once                        ║
║  4. Historical data can be truncated (configurable retention)               ║
║  5. Message size is in the kilobyte range (text only)                       ║
║  6. Messages delivered in the order they were produced (per partition)      ║
║  7. Delivery semantics configurable: at-least-once, at-most-once,          ║
║     or exactly-once                                                         ║
║                                                                               ║
║  OUT OF SCOPE:                                                              ║
║  • Message priority queues                                                  ║
║  • Delayed/scheduled messages (can be discussed as extension)               ║
║  • Message filtering by tags (can be discussed as extension)                ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  • High Throughput: Support use cases like log aggregation (millions/sec)   ║
║  • Low Latency: Configurable — for traditional message queue use cases      ║
║  • Scalable: Distributed, handle sudden surges in message volume            ║
║  • Persistent & Durable: Data persisted on disk, replicated across nodes   ║
║  • High Availability: No single point of failure                            ║
║  • Data Retention: Configurable (e.g., 2 weeks default)                    ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  KEY INSIGHT                                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  This is NOT a traditional message queue (like RabbitMQ).                    ║
║  It's a HYBRID: message queue + event streaming platform.                   ║
║                                                                               ║
║  Traditional MQ: Message deleted after consumption. No replay.              ║
║  Our System: Messages persisted, replayable, ordered, retained.             ║
║  Think: Kafka, Pulsar, Redpanda.                                            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Core Concepts

### The Complete Picture (How Everything Fits Together)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                                                                               ║
║  TOPICS (logical grouping of messages)                                       ║
║  ─────────────────────────────────────                                       ║
║  Topic "orders"  → 2 partitions, replication factor = 3                     ║
║  Topic "logs"    → 2 partitions, replication factor = 2                     ║
║                                                                               ║
║                                                                               ║
║  BROKERS (physical servers that store partition data)                        ║
║  ───────────────────────────────────────────────────                         ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────┐    ║
║  │ BROKER-1                                                            │    ║
║  │                                                                      │    ║
║  │ ┌──────────────────────────────────────┐  ┌──────────────────────┐ │    ║
║  │ │ orders-P0 (★ LEADER)                 │  │ orders-P1 (follower) │ │    ║
║  │ │                                       │  │                      │ │    ║
║  │ │ Segments:                             │  │ Segments:            │ │    ║
║  │ │ ┌─────────────────────────────┐      │  │ ┌──────────────────┐ │ │    ║
║  │ │ │ seg-0 [0][1][2]...[999]    │ old  │  │ │ seg-0 [0][1]..  │ │ │    ║
║  │ │ ├─────────────────────────────┤      │  │ └──────────────────┘ │ │    ║
║  │ │ │ seg-1 [1000][1001]..       │active│  └──────────────────────┘ │    ║
║  │ │ │       ↑ new writes here     │      │                           │    ║
║  │ │ └─────────────────────────────┘      │  ┌──────────────────────┐ │    ║
║  │ └──────────────────────────────────────┘  │ logs-P0 (★ LEADER)  │ │    ║
║  │                                            │ Segments:            │ │    ║
║  │                                            │ ┌──────────────────┐ │ │    ║
║  │                                            │ │ seg-0 [0][1]..   │ │ │    ║
║  │                                            │ └──────────────────┘ │ │    ║
║  │                                            └──────────────────────┘ │    ║
║  └─────────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────┐    ║
║  │ BROKER-2                                                            │    ║
║  │                                                                      │    ║
║  │ ┌──────────────────────────┐  ┌──────────────────────────────────┐ │    ║
║  │ │ orders-P0 (follower)     │  │ orders-P1 (★ LEADER)            │ │    ║
║  │ │ pulls from Broker-1      │  │                                   │ │    ║
║  │ │ ┌──────────────────────┐ │  │ Segments:                        │ │    ║
║  │ │ │ seg-0 [0][1][2]..   │ │  │ ┌──────────────────────────────┐ │ │    ║
║  │ │ │ seg-1 [1000][1001].. │ │  │ │ seg-0 [0][1][2][3][4]..    │ │ │    ║
║  │ │ └──────────────────────┘ │  │ │       ↑ new writes here      │ │ │    ║
║  │ └──────────────────────────┘  │ └──────────────────────────────┘ │ │    ║
║  │                                └──────────────────────────────────┘ │    ║
║  │                                                                      │    ║
║  │ ┌──────────────────────────┐                                        │    ║
║  │ │ logs-P0 (follower)       │                                        │    ║
║  │ │ pulls from Broker-1      │                                        │    ║
║  │ └──────────────────────────┘                                        │    ║
║  └─────────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────┐    ║
║  │ BROKER-3                                                            │    ║
║  │                                                                      │    ║
║  │ ┌──────────────────────────┐  ┌──────────────────────────────────┐ │    ║
║  │ │ orders-P0 (follower)     │  │ orders-P1 (follower)             │ │    ║
║  │ │ pulls from Broker-1      │  │ pulls from Broker-2              │ │    ║
║  │ └──────────────────────────┘  └──────────────────────────────────┘ │    ║
║  │                                                                      │    ║
║  │ ┌──────────────────────────┐                                        │    ║
║  │ │ logs-P1 (★ LEADER)      │  ← logs topic only has rep factor 2  │    ║
║  │ │ ┌──────────────────────┐ │    so no logs-P1 on Broker-1/2       │    ║
║  │ │ │ seg-0 [0][1][2]..   │ │                                        │    ║
║  │ │ └──────────────────────┘ │                                        │    ║
║  │ └──────────────────────────┘                                        │    ║
║  └─────────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║                                                                               ║
║  HIERARCHY (zoom in):                                                        ║
║  ────────────────────                                                        ║
║                                                                               ║
║  Broker                                                                      ║
║    └── Partition (one topic's partition, leader or follower)                 ║
║          └── Segment files (fixed-size chunks of the log)                   ║
║                └── Messages [offset 0] [offset 1] [offset 2] ...           ║
║                                 │          │          │                       ║
║                              key,value  key,value  key,value                ║
║                                                                               ║
║                                                                               ║
║  LEADER MAP (who handles reads/writes for each partition):                  ║
║  ─────────────────────────────────────────────────────────                   ║
║                                                                               ║
║  ┌─────────────┬────────────────┬─────────────────────────────┐             ║
║  │  Partition   │  Leader Broker │  Followers (replicas)       │             ║
║  ├─────────────┼────────────────┼─────────────────────────────┤             ║
║  │  orders-P0  │  Broker-1 ★    │  Broker-2, Broker-3         │             ║
║  │  orders-P1  │  Broker-2 ★    │  Broker-1, Broker-3         │             ║
║  │  logs-P0    │  Broker-1 ★    │  Broker-2                   │             ║
║  │  logs-P1    │  Broker-3 ★    │  (rep factor 2, 1 follower) │             ║
║  └─────────────┴────────────────┴─────────────────────────────┘             ║
║                                                                               ║
║  → Leaders are SPREAD across brokers (no single bottleneck)                 ║
║  → Each broker is leader for SOME partitions, follower for others          ║
║  → Followers pull from leader to stay in sync (ISR)                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Topics, Partitions, and Segments

**Offset:** A unique, sequential ID (0, 1, 2, ...) assigned to each message within a partition. It acts as the message's position in the log — consumers track their offset to know where to resume reading.

```
Topic "orders" (logical grouping)
│
├── Partition-0 (ordered log, on Broker-1 as leader)
│   ├── segment-0  [offset 0-999]    ← oldest, may be truncated by retention
│   ├── segment-1  [offset 1000-1999]
│   └── segment-2  [offset 2000-...]  ← active segment (new writes go here)
│
├── Partition-1 (ordered log, on Broker-2 as leader)
│   ├── segment-0  [offset 0-999]
│   └── segment-1  [offset 1000-...]
│
└── Partition-2 (ordered log, on Broker-3 as leader)
    └── segment-0  [offset 0-...]

Key points:
• Each partition has its OWN leader broker (leaders spread across brokers)
  → Distributes write load (no single broker is a bottleneck)
  → All 3 brokers store all partitions, but LEADER differs per partition
  Broker-1                  Broker-2                  Broker-3
┌──────────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ P-0 (LEADER)  ✍️ │     │ P-0 (follower)   │     │ P-0 (follower)   │
│ P-1 (follower)   │     │ P-1 (LEADER)  ✍️ │     │ P-1 (follower)   │
│ P-2 (follower)   │     │ P-2 (follower)   │     │ P-2 (LEADER)  ✍️ │
└──────────────────┘     └──────────────────┘     └──────────────────┘
Every broker stores ALL 3 partitions (replication factor = 3),
but each partition has a DIFFERENT leader → write load is distributed.
• Partition = unit of parallelism (more partitions = more consumers in parallel)
• Within a partition: messages are STRICTLY ORDERED by offset
• Across partitions: NO ordering guarantee
• Segment files: Fixed-size chunks of the partition log, stored on disk
• Old segments deleted when retention expires (e.g., after 2 weeks)
```

### Message Structure

```
┌─────────────────────────────────────────────────────────────┐
│                        MESSAGE                               │
├──────────────┬──────────────────────────────────────────────┤
│  key         │  Determines which partition (hash(key) % N)  │
│  value       │  The actual payload (event data, log, etc.)  │
│  topic       │  Which topic this message belongs to          │
│  timestamp   │  When the message was created                │
│  offset      │  Assigned by broker on write (0, 1, 2, ...)  │
│  partition    │  Assigned by producer routing                │
└──────────────┴──────────────────────────────────────────────┘
```

**The key field is the most important design decision:**

The key determines which partition a message goes to. If order matters between messages, they MUST share the same key.

```
Example: E-commerce order events

  Order #100 events: created → paid → shipped → delivered
  Order #200 events: created → paid → cancelled

  Key strategy: use order_id as key
    Message("order-100", "created",   "orders") → hash("order-100") % 3 → P-0
    Message("order-100", "paid",      "orders") → hash("order-100") % 3 → P-0  ← same!
    Message("order-100", "shipped",   "orders") → hash("order-100") % 3 → P-0  ← same!
    Message("order-200", "created",   "orders") → hash("order-200") % 3 → P-1
    Message("order-200", "cancelled", "orders") → hash("order-200") % 3 → P-1  ← same!

  Partition-0: [order-100:created, order-100:paid, order-100:shipped]  ✓ ordered
  Partition-1: [order-200:created, order-200:cancelled]                ✓ ordered

  Order 100's events are always in order (same partition).
  Order 200's events are always in order (same partition).
  Cross-order ordering? Doesn't matter — they are independent.
```

**Common key strategies:**

| Use Case | Key | Why |
|----------|-----|-----|
| Order processing | `order_id` | All events for one order stay ordered |
| User activity | `user_id` | All actions by one user stay ordered |
| IoT sensors | `device_id` | All readings from one device stay ordered |
| Log aggregation | `null` (round-robin) | No ordering needed, maximize throughput |

**Rule of thumb:** If message A must be processed before message B, give them the same key.

### Producer Routing

```
Producer decides which partition a message goes to:

  hash(message.key) % numPartitions → target partition

  • key = "user-100" → hash → Partition-0
  • key = "user-200" → hash → Partition-1
  • key = "user-100" → hash → Partition-0  (SAME partition! ordering preserved)
  • key = null        → round-robin         (no ordering guarantee)

Why this matters:
  All messages with the same key go to the same partition
  → Guarantees ordering for that key (e.g., all events for user-100 in order)
```

---

## 3. The Big Picture (High-Level Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║            DISTRIBUTED MESSAGE QUEUE - HIGH-LEVEL DESIGN                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║              ┌──────────────────────────────────────────┐                    ║
║              │         ZooKeeper                         │                    ║
║              │  (helps brokers coordinate with           │                    ║
║              │   each other — who's alive, who's         │                    ║
║              │   leader, cluster-wide config)            │                    ║
║              └─────────────────┬────────────────────────┘                    ║
║                                │ heartbeats + watches                        ║
║                                │                                              ║
║  ┌──────────────┐      ┌──────┴───────────────────────────────────┐         ║
║  │  Producers   │      │              Brokers                      │         ║
║  │              │      │                                            │         ║
║  │ ┌──────────┐ │      │  ┌──────────────────────────────────────┐ │         ║
║  │ │  Buffer  │ │      │  │  Data Storage                        │ │         ║
║  │ └────┬─────┘ │ ───▶ │  │  (segment files on disk)             │ │  ───▶  ║
║  │      │       │      │  └──────────────────────────────────────┘ │         ║
║  │ ┌────┴─────┐ │      │                                            │         ║
║  │ │ Routing  │ │      │  ┌──────────────────────────────────────┐ │         ║
║  │ └──────────┘ │      │  │  State Storage                       │ │         ║
║  └──────────────┘      │  │  (consumer offsets, etc)              │ │         ║
║                         │  └──────────────────────────────────────┘ │         ║
║  ┌──────────────┐      └──────────────────────────────────────────┘         ║
║  │  Consumers   │                                                            ║
║  │ (Consumer    │◀─── pull model: consumers fetch from brokers              ║
║  │  Groups)     │                                                            ║
║  └──────────────┘                                                            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. Deep Dive: Producer

### Producer Flow

```
┌─────────────────────────────────────────────────────────┐
│                      PRODUCER                            │
│                                                          │
│  ① Create message (key, value, topic)                   │
│        │                                                 │
│        ▼                                                 │
│  ② Buffer (batching for throughput)                     │
│     Flush when: batch is full OR linger timeout expires  │
│        │                                                 │
│        ▼                                                 │
│  ③ Routing: hash(key) % numPartitions → target partition│
│        │                                                 │
│        ▼                                                 │
│  ④ Group messages by target broker                      │
│     e.g., buffer has 6 messages:                         │
│       → Broker-1: [msg-1, msg-3, msg-6]  (Partition-0) │
│       → Broker-2: [msg-2, msg-5]         (Partition-1) │
│       → Broker-3: [msg-4]                (Partition-2) │
│       3 batch network calls instead of 6!               │
│                                                          │
└──────────────────────┬──────────────────────────────────┘
                       │
                       ▼
⑤ Send one batch per leader broker (in parallel)
                       │
                       ▼
⑥ Wait for ACK from each broker (based on configured ack level)
```

**Batch Size Tradeoff (Latency vs Throughput):**

| | Small Batch (e.g., 1-10 msgs) | Large Batch (e.g., 1000+ msgs) |
|---|---|---|
| **Latency** | Low — messages sent almost immediately | High — waits to fill batch before sending |
| **Throughput** | Low — more network calls, more overhead per msg | High — fewer calls, better compression, amortized overhead |
| **Network** | More TCP round trips | Fewer TCP round trips |
| **Use case** | Real-time alerts, chat | Log aggregation, analytics |

Two producer configs control this:
- `batch.size`: Max bytes per batch (flush when full)
- `linger.ms`: Max time to wait before flushing a partial batch

```
linger.ms = 0:   Send immediately (lowest latency, lowest throughput)
linger.ms = 50:  Wait up to 50ms to collect more messages (balanced)
linger.ms = 500: Wait up to 500ms (highest throughput, higher latency)
```

### ACK Levels (Durability vs Latency Tradeoff)

```
ACK=0 (fire-and-forget):
  Producer ──send──▶ Broker
  (no wait, no retry. Fastest. May lose messages.)
  Use case: Metrics, logging where occasional loss is OK

ACK=1 (leader only):
  Producer ──send──▶ Leader Broker ──persist──▶ ACK back
  (wait for leader to persist. If leader crashes before replication → data lost)
  Use case: Most applications (balanced latency + durability)

ACK=all (all ISR):
  Producer ──send──▶ Leader ──replicate──▶ All ISR Followers ──ACK back
  (wait for ALL in-sync replicas. Slowest. Strongest durability.)
  Use case: Financial transactions, billing, critical data

  ┌──────────┬────────────┬─────────────────┬──────────────────────┐
  │ ACK      │ Latency    │ Durability      │ When to Use          │
  ├──────────┼────────────┼─────────────────┼──────────────────────┤
  │ ack=0    │ Lowest     │ May lose msgs   │ Metrics, logs        │
  │ ack=1    │ Medium     │ Leader crash    │ Most apps            │
  │ ack=all  │ Highest    │ Strongest       │ Financial, critical  │
  └──────────┴────────────┴─────────────────┴──────────────────────┘
```

---

## 5. Deep Dive: Broker & Storage

### Why Not a Traditional Database?

This system is BOTH read-heavy AND write-heavy simultaneously:
- **Writes:** Millions of messages/sec appended
- **Reads:** Multiple consumer groups reading the same data independently

No traditional database handles this well:
- **Relational DB (Postgres/MySQL):** B-Tree indexes make writes slow at scale (random I/O for index updates). Row-level locking limits write throughput.
- **NoSQL (Cassandra):** Better write throughput but adds unnecessary overhead — we don't need complex queries, secondary indexes, or data model flexibility. Just append and read sequentially.
- **Redis:** In-memory = too expensive for TB-scale persistent data with 2-week retention.

**Solution: Write-Ahead Log (WAL) — append-only segment files on disk.**

This is essentially the same concept as a database's WAL, but used as the primary storage instead of just a recovery mechanism. It's the simplest, fastest structure for our access pattern: append at tail, read sequentially from any offset.

### How Messages Are Stored On Disk

```
Broker-1 filesystem:
  data_storage/
    Topic-A/
      Partition-0/
        segment-0    ← offsets 0-999 (old, may be truncated)
        segment-1    ← offsets 1000-1999
        segment-2    ← offsets 2000-...  (active, writes go here)
      Partition-1/
        segment-0
        ...
    Topic-B/
      ...
  state_storage/
    consumer_offsets  ← which offset each consumer group has reached

Messages are APPENDED to the active segment (sequential writes only).
When segment reaches max size → new segment created.
Old segments deleted after retention period (e.g., 2 weeks).
```

**Why append-only sequential writes?**
- Disk sequential I/O is fast (~600 MB/s) vs random I/O (~100 MB/s)
- No random seeks needed
- OS page cache works efficiently with sequential reads
- This is the key to Kafka's high throughput

### Replication (Leader-Follower)

**Why replication?** If a broker crashes and it was the only copy of a partition's data, all messages in that partition are lost. Replication keeps copies of each partition on multiple brokers so the system survives broker failures.

**How it works:**
- Each partition has ONE leader and multiple followers (replicas)
- **Leader:** Handles all reads and writes for that partition
- **Followers:** Continuously PULL data from the leader to stay in sync
- **Replication factor:** How many total copies (e.g., 3 = 1 leader + 2 followers)

> **Key clarification:** Leader/follower is a **per-partition** role, NOT a per-broker role.
> A single broker can be **leader for some partitions and follower for others**.
> e.g., Broker-1 is leader for orders-P0 but follower for orders-P1.
> This is how write load gets distributed across the cluster — no single broker bottleneck.

```
Example: Topic-A, Partition-0 with replication factor = 3

  Broker-1 (LEADER):    [0][1][2][3][4][5][6][7]  ← latest offset = 7
  Broker-2 (follower):  [0][1][2][3][4][5][6]      ← pulling, slightly behind
  Broker-3 (follower):  [0][1][2][3]                ← pulling, lagging more
```

### ISR (In-Sync Replicas)

**What is ISR?** The set of replicas (including the leader) that are "caught up" with the leader — meaning they have all committed messages. Replicas that fall too far behind are removed from ISR.

> **Key clarification:** ISR is tracked **per-partition**, not per-broker.
> Broker-2 could be in ISR for `orders-P0` but NOT in ISR for `logs-P0`.
> Each partition independently maintains its own ISR set.

**How does a replica get kicked out of ISR?**
Configurable threshold: `replica.lag.time.max.ms` — if a follower hasn't fetched from the leader within this time, it's removed from ISR. (It can rejoin once it catches up.)

```
  Example: ISR for orders-P0 (threshold = 3 messages behind)

  Broker-1 (Leader):      [0][1][2][3][4][5][6][7]
                                          │
                              ┌───────────┴───────────┐
                              │ fetch                 │ fetch
                              ▼                       ▼
  Broker-2 (IN ISR):      [0][1][2][3][4][5][6]    ← 1 behind → within threshold ✓
  Broker-3 (NOT in ISR):  [0][1][2][3]              ← 4 behind → exceeds threshold ✗

  ISR for orders-P0 = {Broker-1, Broker-2}
```

**Why ISR matters:**

1. **Leader election:** Only ISR members can become the new leader.
   - If Broker-1 crashes → Broker-2 (in ISR) becomes leader ✓
   - Broker-3 (not in ISR) cannot become leader (it's missing data)

2. **Committed offset:** A message is "committed" only when ALL ISR replicas have it.
   - In the example above: committed offset = 5 (Broker-1 and Broker-2 both have up to 5)
   - Offsets 6 and 7 are on the leader but NOT yet committed (Broker-2 hasn't caught up)
   - **Consumers can only read up to the committed offset** (offset 5)
   - This prevents consumers from reading data that could be lost if the leader crashes

3. **ACK=all uses ISR:** When producer sets `ack=all`, the ACK is sent only after all ISR replicas have the message → guarantees committed data survives any single broker failure.

**What if a follower catches up?** It's added back to ISR.
**What if ALL ISR members crash?** Two options (configurable):
- Wait for ISR member to recover (prioritize durability, risk downtime)
- Elect any surviving replica (prioritize availability, risk data loss)

```
Summary:
┌────────────────────────────────────────────────────────────┐
│  Replication = copies data across brokers (fault tolerance)│
│  ISR = subset of replicas that are caught up (consistency) │
│  Committed offset = safe point (all ISR have it)          │
│  Leader election = only from ISR (no data loss)           │
│  Consumers read up to committed offset only               │
└────────────────────────────────────────────────────────────┘
```

---

## 6. Deep Dive: Consumer

### Why Pull Model? (Not Push)

```
① Consumer joins consumer group and subscribes to topic
      │
      ▼
② Coordinator broker assigns partitions to consumer
   (partition-2 dispatched to this consumer)
      │
      ▼
③ Consumer fetches messages from assigned partition's leader broker
   "Give me messages from offset 100 onwards, max 50 messages"
      │
      ▼
④ Process messages, then commit offset
   (how offset is committed determines delivery semantics)
```

**Pull vs Push Tradeoff:**

| | Pull (what we chose) | Push |
|---|---|---|
| **Pace control** | Consumer controls its own speed | Broker decides — can overwhelm slow consumers |
| **Batching** | Consumer fetches large batches efficiently | Broker must decide batch size per consumer |
| **Replay** | Consumer can re-read from any offset | Not possible — once pushed, broker moves on |
| **Backpressure** | Built-in — consumer just slows down fetches | Broker must implement complex flow control |
| **Downside** | Slight delay if consumer polls too slowly (tunable via `fetch.min.bytes`, `fetch.max.wait.ms`) | Lower latency — broker pushes immediately |

> **Bottom line:** Pull wins for Kafka's use cases (high throughput, replay, diverse consumer speeds).
> Push works better for traditional MQs like RabbitMQ where low latency matters more than replay.

### Consumer Groups & Partition Assignment

```
Topic "orders" with 4 partitions, Consumer Group "processors" with 2 consumers:

  BEFORE (2 consumers):
  ┌────────────────────────────────────────────────┐
  │ Consumer-A: Partition-0, Partition-1           │
  │ Consumer-B: Partition-2, Partition-3           │
  └────────────────────────────────────────────────┘

  Consumer-C joins → REBALANCE triggered:
  ┌────────────────────────────────────────────────┐
  │ Consumer-A: Partition-0                        │
  │ Consumer-B: Partition-1, Partition-2           │
  │ Consumer-C: Partition-3                        │
  └────────────────────────────────────────────────┘

  Consumer-B crashes → REBALANCE triggered:
  ┌────────────────────────────────────────────────┐
  │ Consumer-A: Partition-0, Partition-1           │
  │ Consumer-C: Partition-2, Partition-3           │
  └────────────────────────────────────────────────┘

Rules:
• Each partition → exactly ONE consumer in the group
• One consumer can handle multiple partitions
• If consumers > partitions → some consumers are idle
• Rebalance happens on: join, leave, crash, partition change
```

### Consumer Rebalancing

**What triggers it?** Consumer joins, leaves, crashes, or partitions change.

**Who handles it?** The **coordinator broker** — one of the regular brokers assigned to manage this consumer group. It detects crashes via missed heartbeats and redistributes partitions among remaining consumers.

> **Don't confuse coordinator broker with ZooKeeper:**
>
> | | ZooKeeper | Coordinator Broker |
> |---|---|---|
> | **What it coordinates** | Brokers (who's alive, partition leaders, ISR) | Consumers within a consumer group |
> | **What it is** | Separate external cluster | One of the existing message queue brokers |
> | **Example job** | "Broker-3 crashed → elect new leader for orders-P2" | "Consumer-B crashed → reassign its partitions to Consumer-A" |

---

## 7. Deep Dive: ZooKeeper / Coordination Service

ZooKeeper (or KRaft in modern Kafka) is the **brain** of the cluster — it doesn't touch any message data, but without it, brokers wouldn't know who's alive, who's the leader, or where to route anything.

### What Does ZooKeeper Store?

```
ZooKeeper Data (all metadata, NO message data):

┌────────────────────────────────────────────────────────────────────┐
│                                                                    │
│  1. BROKER REGISTRY                                               │
│     /brokers/ids/                                                 │
│       ├── 1  → { host: "broker1.prod", port: 9092, status: UP }  │
│       ├── 2  → { host: "broker2.prod", port: 9092, status: UP }  │
│       └── 3  → { host: "broker3.prod", port: 9092, status: UP }  │
│                                                                    │
│  2. TOPIC & PARTITION METADATA                                    │
│     /brokers/topics/                                              │
│       ├── orders → { partitions: 4, replication_factor: 3 }      │
│       └── logs   → { partitions: 2, replication_factor: 2 }      │
│                                                                    │
│  3. LEADER MAP (per partition)                                    │
│     /brokers/topics/orders/partitions/                            │
│       ├── partition-0 → { leader: broker-1, ISR: [1,2,3] }       │
│       ├── partition-1 → { leader: broker-2, ISR: [1,2] }        │
│       ├── partition-2 → { leader: broker-3, ISR: [2,3] }        │
│       └── partition-3 → { leader: broker-1, ISR: [1,3] }                  │
│                                                                    │
│  4. CONSUMER GROUP METADATA                                       │
│     /consumers/groups/                                            │
│       └── payment-processors → {                                  │
│             coordinator: broker-2,                                │
│             members: [consumer-A, consumer-B],                    │
│             assignment: { P0→A, P1→B, P2→A, P3→B }              │
│           }                                                       │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

### What Actions Does ZooKeeper Perform?

| Action | Trigger | What Happens |
|--------|---------|--------------|
| **Leader Election** | Broker crash detected (missed heartbeat) | Picks new leader from ISR, updates leader map |
| **Broker Registration** | Broker starts up | Broker registers itself; ZooKeeper adds to registry |
| **Broker Failure Detection** | Heartbeat timeout | Marks broker dead, triggers leader election for affected partitions |
| **Consumer Group Metadata** | Consumer group created | Stores group membership; actual rebalancing is done by the coordinator broker (not ZooKeeper) |
| **Topic Creation** | Admin creates topic | Stores partition count, replication factor, assigns partitions to brokers |
| **Config Changes** | Admin updates config | Brokers subscribe (watch) and get notified of changes |

### How Brokers Interact With ZooKeeper

```
                 ┌───────────────────┐
                 │    ZooKeeper      │
                 │    Cluster        │
                 │  (3 or 5 nodes)   │
                 └──┬──────┬──────┬──┘
        heartbeat   │      │      │   heartbeat
        + watch     │      │      │   + watch
                    ▼      ▼      ▼
              Broker-1  Broker-2  Broker-3

  On startup:   Broker registers itself → ZooKeeper adds to /brokers/ids/
  Ongoing:      Broker sends heartbeats → ZooKeeper knows it's alive
  On crash:     Heartbeat stops → ZooKeeper detects → triggers leader election
                (per partition — only for partitions whose leader was on the crashed broker)
  Watches:      Brokers subscribe to metadata changes (new topics, leader changes)
```

### Why ZooKeeper and Not a Database?

Coordination metadata (leaders, ISR, broker liveness) needs **three things** a regular database can't provide well:

1. **Ephemeral nodes** — auto-delete when a broker disconnects → instant crash detection
2. **Watches** — brokers get push notifications on metadata changes (new leader, config update) without polling
3. **Strong consistency with low latency** — consensus protocol (ZAB) ensures all brokers see the same leader map

A relational DB or Redis could store this data, but you'd have to build crash detection, push notifications, and consensus on top — which is exactly what ZooKeeper already provides out of the box.

> **Evolution — KRaft mode:** Modern Kafka eliminates ZooKeeper by embedding
> Raft consensus directly into brokers. No separate cluster to manage.

---

## 8. Data Storage Overview

All data in the system falls into **three categories**, stored in different places:

```
┌──────────────────────────────────────────────────────────────────────────┐
│                        WHERE IS EVERYTHING STORED?                      │
├──────────────────────┬──────────────────────┬────────────────────────────┤
│       WHAT           │       WHERE          │     REFERENCE              │
├──────────────────────┼──────────────────────┼────────────────────────────┤
│                      │                      │                            │
│  Message Data        │  Broker local disk   │  → Section 5:             │
│  (the actual msgs)   │  Append-only segment │    "How Messages Are      │
│                      │  files (WAL)         │     Stored On Disk"        │
│                      │                      │                            │
├──────────────────────┼──────────────────────┼────────────────────────────┤
│                      │                      │                            │
│  State Storage       │  Broker local disk   │  → Section 5:             │
│  (consumer offsets,  │  (stored alongside   │    "How Messages Are      │
│   "I processed up    │   message data)      │     Stored On Disk"        │
│   to offset 150")    │                      │                            │
│                      │                      │                            │
├──────────────────────┼──────────────────────┼────────────────────────────┤
│                      │                      │                            │
│  Cluster Metadata    │  ZooKeeper           │  → Section 7:             │
│  (leaders, ISR,      │  (or KRaft in        │    "Deep Dive: ZooKeeper"  │
│   broker registry,   │   modern Kafka)      │                            │
│   consumer groups,   │                      │                            │
│   topic configs)     │                      │                            │
│                      │                      │                            │
└──────────────────────┴──────────────────────┴────────────────────────────┘
```

**Why this separation matters:**

| Storage | Access Pattern | Why This Store |
|---------|---------------|----------------|
| Segment files (messages) | Sequential write, sequential read | Disk I/O optimized for throughput |
| State storage (consumer offsets) | Frequent small writes | Stored on brokers alongside message data |
| ZooKeeper (metadata) | Rare writes, frequent reads | Needs strong consistency via distributed consensus |

> **Key insight:** The message broker never queries ZooKeeper on the hot path.
> Metadata is cached locally on brokers and updated via watches (push notifications).
> The hot path (produce/consume) only touches local disk segment files.

---

## 9. Delivery Semantics

> **Key terms:**
> - **Commit** = Consumer tells the broker "I'm done with this offset, save my position."
>   It's like saving a bookmark. On restart, the consumer resumes from the last committed offset.
> - **Process** = The business logic the consumer does with the message
>   (e.g., charge a credit card, write to a database, send a notification).

### At-Most-Once

```
Consumer:
  1. Fetch message at offset 10
  2. Commit offset 10 ← BEFORE processing (bookmark saved)
  3. Process message (e.g., send email)
  4. Crash! ← Message lost (bookmark already at 11, won't re-fetch offset 10)

  Risk: Message may never get processed (lost)
  Why: ack=0 means producer doesn't wait for broker confirmation either

Use case: Monitoring metrics where occasional data loss is OK.
Producer setting: ack=0
```

### At-Least-Once

```
Consumer:
  1. Fetch message at offset 10
  2. Process message (e.g., charge credit card)
  3. Commit offset 10 ← AFTER processing (bookmark saved only after work is done)
  4. Crash before commit! ← Bookmark still at 9, so on restart fetches offset 10 AGAIN
     → credit card charged twice (duplicate)

  Risk: Message may get processed more than once (duplicate)
  Fix: Make processing idempotent (e.g., check "already charged order #123?" before charging)

Use case: Most applications. Handle duplicates with idempotent operations.
Producer setting: ack=1 or ack=all
```

### Exactly-Once

```
Hardest to achieve. Requires:
• Idempotent producer (dedup on broker using producer_id + sequence_number)
• Transactional consumer (atomically commit offset + write result in same transaction)
  → Either both succeed or both fail — no partial state
• OR: Consumer-side deduplication (unique keys, database constraints)

  Risk: None (but most complex and slowest)

Use case: Financial transactions, billing.
```

---

## 10. How We Scale

### Brokers

- Add more brokers → partitions automatically redistributed
- Each broker handles subset of partitions
- No single broker is a bottleneck

### Partitions (Horizontal Scaling of Topics)

- More partitions = more parallelism
- Each partition can be on a different broker
- Each partition can be consumed by a different consumer
- **Increasing partitions:** Easy — new partitions get new messages, old ones stay
- **Decreasing partitions:** Hard — must wait for retention to expire on decommissioned partitions

### Consumers

- Add more consumers to a group (up to #partitions)
- Beyond #partitions → idle consumers (no benefit)
- Different consumer groups read independently (each group gets all messages)

### Storage

- Segment files with retention-based deletion
- Old segments auto-deleted after configured period
- No compaction needed for most use cases (log compaction for special cases)

---

## 11. What Can Go Wrong? (Failure Handling)

### Broker Failure

**Scenario:** Leader broker crashes
**Solution:**
- ZooKeeper detects via missed heartbeat
- Elects new leader from ISR (in-sync replicas only)
- Producers and consumers redirect to new leader
- Data safe if ack=all was used (committed data exists on all ISR members)

### Consumer Failure

**Scenario:** Consumer crashes mid-processing
**Solution:**
- Coordinator **broker** (not ZooKeeper) detects via missed heartbeat
- Triggers consumer group rebalance
- Failed consumer's partitions reassigned to remaining consumers
- New consumer resumes from last committed offset
- With at-least-once: May reprocess some messages (idempotent handling needed)

### ZooKeeper/Coordination Failure

**Scenario:** ZooKeeper cluster loses quorum
**Solution:**
- Brokers continue serving existing connections (cached metadata)
- No new leader elections, no rebalancing until ZooKeeper recovers
- Modern Kafka (KRaft mode): Eliminates ZooKeeper dependency entirely

### Split Brain (Network Partition)

**Scenario:** Broker can talk to some replicas but not others
**Solution:**
- ISR ensures only caught-up replicas can become leader
- `min.insync.replicas` config: Refuse writes if not enough ISR members
- Prevents data divergence between partitions

---

## 12. Why These Choices? (Key Design Decisions)

### Decision #1: Pull Model (Not Push)

**Problem:** How do consumers get messages?

**Why Pull:** Consumer controls pace, supports batch fetching, enables replay, built-in backpressure.
**Why Not Push:** Overwhelms slow consumers, no replay, broker must track each consumer's speed.
See **Section 6** for detailed tradeoff table.

### Decision #2: Append-Only Log (Not Delete After Consume)

**Problem:** What happens to messages after consumption?

**Why Append-Only Log:**
- Multiple consumer groups can read same data independently
- Replay capability (reprocess if consumer had a bug)
- Simple storage model (sequential writes only)
- Retention-based cleanup (automatic)

**Trade-off:** Uses more disk than traditional MQ, but disk is cheap

### Decision #3: Partition-Based Ordering (Not Global Ordering)

**Problem:** How to guarantee message ordering?

**Why Per-Partition Ordering:**
- Global ordering requires single partition → no parallelism
- Per-partition ordering: hash(key) → same partition → ordered for that key
- Most use cases need ordering per entity (user, order) not globally
- Allows horizontal scaling while maintaining meaningful ordering

### Decision #4: ZooKeeper for Coordination

**Problem:** How to manage distributed state?

**Why not a database?** Coordination needs ephemeral nodes (auto-delete on crash), watches (push notifications on changes), and strong consistency — a regular DB can't provide these out of the box.
**Why ZooKeeper:** Provides all three natively + battle-tested at scale.
**Evolution:** KRaft mode eliminates ZooKeeper by embedding Raft consensus into brokers.
See **Section 7** for full deep dive.

---

## 13. Advanced Topics

### Delayed / Scheduled Messages

**Problem:** "Send this notification 30 minutes after order is placed"

**Why it's hard:** Messages are stored in append-only order by offset. A delayed message at offset 10 can't be skipped while delivering offset 11 — it breaks sequential consumption.

**Solution:** Use dedicated delay queues per delay level:

```
Producer sends message with delay = 30min
      │
      ▼
Broker writes to temporary topic: "delay-30min"
      │
      ▼
Delay Service checks periodically:
  "Any messages in delay-30min whose time has arrived?"
      │
      ▼
If yes → re-publish to the actual target topic
      │
      ▼
Consumer reads from target topic as normal
```

- Pre-defined delay levels (e.g., 1s, 5s, 30s, 1m, 5m, 30m) — each gets its own internal topic
- Delay service scans these topics and moves messages to the real topic when ready

### Message Filtering by Tags

**Problem:** "I only want payment messages, not refund messages from the orders topic"

**Key idea:** Don't filter based on message payload (too expensive — broker would need to deserialize every message). Instead, attach **tags as metadata** to each message. The broker can read metadata cheaply without touching the payload.

```
Message structure with tags:
┌─────────────────────────────────────────┐
│  metadata:  tag = "payment"             │ ← broker reads this (cheap)
│  payload:   { order_id: 123, ... }      │ ← broker does NOT parse this
└─────────────────────────────────────────┘

Consumer subscribes with filter: tag = "payment"
      │
      ▼
Broker checks each message's tag metadata:
  - tag = "payment" → deliver ✓
  - tag = "refund"  → skip ✗
```

- **Single tag:** Filter in one dimension (e.g., `tag = "payment"`)
- **Multiple tags:** Filter across dimensions (e.g., `tag = "payment" AND region = "US"`)
- **Complex logic** (math formulas, scripts): Possible but heavyweight — avoid at broker level

> **Tradeoff:** Filtering at broker reduces bandwidth to consumers but adds CPU
> work on the broker. For very high throughput, separate topics may still be better.

---

## 13. Interview Pro Tips

### Opening Statement
"A distributed message queue is a persistent, replicated, partitioned log system. The key challenges are ensuring ordering within partitions, configurable delivery semantics, and high throughput via sequential disk I/O and batching. The architecture consists of brokers storing partitioned topic data, producers routing by key hash, and consumers organized in groups with pull-based consumption."

### Key Talking Points
1. **Topics & Partitions:** Unit of parallelism, ordering only within partition
2. **Producer Routing:** hash(key) % partitions — same key = same partition = ordered
3. **Consumer Groups:** Each partition → exactly one consumer, rebalancing on join/leave
4. **ISR & Replication:** Only in-sync replicas can become leader, committed offset
5. **ACK Levels:** ack=0/1/all — latency vs durability tradeoff
6. **Sequential I/O:** Why Kafka is fast — append-only writes, OS page cache
7. **Delivery Semantics:** At-most/at-least/exactly-once — offset commit timing

### Common Follow-ups

**Q: How does Kafka achieve high throughput?**
A: Sequential disk writes (append-only, no random seeks), OS page cache, zero-copy transfer (sendfile syscall), producer batching, and partition-level parallelism.

**Q: What happens if all ISR replicas crash?**
A: Two options — (1) wait for an ISR member to come back (prioritize durability, risk downtime), or (2) elect any surviving replica as leader (prioritize availability, risk data loss). Configurable via `unclean.leader.election.enable`.

**Q: How is exactly-once delivery achieved?**
A: Idempotent producer (broker deduplicates using producer_id + sequence_number) + transactional consumer (atomically commit processing result + offset update in a single transaction).

**Q: How do you handle message ordering across partitions?**
A: You don't — ordering is only guaranteed within a partition. Design your key strategy so related messages (same user, same order) go to the same partition via hash(key).

---

## 14. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║          DISTRIBUTED MESSAGE QUEUE - COMPLETE ARCHITECTURE                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌──────────────┐                                                           ║
║  │  Producer    │                                                           ║
║  │ ┌──────────┐ │    ① Route: hash(key) % partitions                       ║
║  │ │  Buffer  │ │    ② Send to leader broker                               ║
║  │ │  Routing │ │    ③ Wait for ACK (0 / 1 / all)                          ║
║  │ └──────────┘ │                                                           ║
║  └──────┬───────┘                                                           ║
║         │                                                                    ║
║         │  produce                                                           ║
║         ▼                                                                    ║
║  ┌──────────────────────────────────────────────────────────────────────┐   ║
║  │                        BROKER CLUSTER                                │   ║
║  │                                                                       │   ║
║  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   ║
║  │  │  Broker-1    │  │  Broker-2    │  │  Broker-3    │              │   ║
║  │  │              │  │              │  │              │              │   ║
║  │  │ Topic-A P-0  │  │ Topic-A P-0  │  │ Topic-A P-0  │              │   ║
║  │  │ (LEADER)     │  │ (follower)   │  │ (follower)   │              │   ║
║  │  │ ┌──────────┐ │  │ ┌──────────┐ │  │ ┌──────────┐ │              │   ║
║  │  │ │[0][1][2] │ │  │ │[0][1][2] │ │  │ │[0][1]    │ │              │   ║
║  │  │ │[3][4][5] │ │  │ │[3][4][5] │ │  │ │          │ │              │   ║
║  │  │ └──────────┘ │  │ └──────────┘ │  │ └──────────┘ │              │   ║
║  │  │              │  │              │  │              │              │   ║
║  │  │ Topic-A P-1  │  │ Topic-A P-1  │  │ Topic-A P-1  │              │   ║
║  │  │ (follower)   │  │ (LEADER)     │  │ (follower)   │              │   ║
║  │  │              │  │              │  │              │              │   ║
║  │  └──────────────┘  └──────────────┘  └──────────────┘              │   ║
║  │                          ▲                                          │   ║
║  │                          │ metadata, leader election                │   ║
║  │                          ▼                                          │   ║
║  │                 ┌─────────────────┐                                 │   ║
║  │                 │   ZooKeeper     │                                 │   ║
║  │                 │ • Broker list   │                                 │   ║
║  │                 │ • Leader map    │                                 │   ║
║  │                 │ • Topic config  │                                 │   ║
║  │                 │ • Consumer grps │                                 │   ║
║  │                 └─────────────────┘                                 │   ║
║  └──────────────────────────────────────────────────────────────────────┘   ║
║         │                                                                    ║
║         │  consume (pull)                                                    ║
║         ▼                                                                    ║
║  ┌──────────────────────────────────────────────────────────────────────┐   ║
║  │                     CONSUMER GROUPS                                   │   ║
║  │                                                                       │   ║
║  │  Group "processors":                 Group "analytics":              │   ║
║  │  ┌─────────┐ ┌─────────┐           ┌─────────┐                     │   ║
║  │  │Consumer │ │Consumer │           │Consumer │                     │   ║
║  │  │  A      │ │  B      │           │  D      │                     │   ║
║  │  │ P-0     │ │ P-1     │           │ P-0,P-1 │                     │   ║
║  │  └─────────┘ └─────────┘           └─────────┘                     │   ║
║  │                                                                       │   ║
║  │  Each group gets ALL messages independently.                         │   ║
║  │  Within a group, partitions are divided among consumers.            │   ║
║  └──────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
║─────────────────────────────────────────────────────────────────────────────║
║                                                                               ║
║  KEY FLOWS:                                                                  ║
║  ① Produce: Producer → Buffer → Route by key → Leader Broker → ACK        ║
║  ② Replicate: Leader → Followers pull → ISR tracks caught-up replicas      ║
║  ③ Consume: Consumer joins group → assigned partitions → pull messages     ║
║  ④ Commit: Consumer processes → commits offset → determines semantics      ║
║  ⑤ Rebalance: Consumer join/leave → Coordinator reassigns partitions       ║
║  ⑥ Failover: Leader crash → ZooKeeper elects new leader from ISR          ║
║                                                                               ║
║  CRITICAL DESIGN DECISIONS:                                                  ║
║  • Pull model (consumer controls pace, supports replay)                     ║
║  • Append-only log (sequential I/O = high throughput)                       ║
║  • Per-partition ordering (parallelism + meaningful ordering)               ║
║  • ISR-based replication (durability without blocking on slow replicas)     ║
║  • Configurable ACK levels (latency vs durability tradeoff)                 ║
║  • Consumer groups (multiple independent readers of same data)              ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

**Good luck with your interview!** 🚀
