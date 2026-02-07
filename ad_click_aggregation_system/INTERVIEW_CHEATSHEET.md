# 📊 Ad Click Event Aggregation - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 6

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Log Watcher** | Collects click events from ad servers | N/A | Runs on every ad-serving server |
| **Message Queue (Kafka)** | Buffers events between services | Disk (partitions) | Decouples ingestion from processing |
| **Aggregation Service** | MapReduce on streaming data | In-memory + checkpoints | Map → Aggregate → Reduce |
| **Raw Data DB** | Stores every raw click event | Cassandra | Source of truth for recalculation |
| **Aggregation DB** | Stores pre-computed results | Cassandra | Serves the query/dashboard API |
| **Query Service** | Serves dashboard API | N/A | Reads from Aggregation DB only |
| **Recalculation Service** | Replays raw data to fix results | N/A | Used when aggregation logic had a bug |

---

## The Story: Building an Ad Click Event Aggregation System

Let me walk you through how we'd build a system to aggregate billions of ad clicks in real-time for billing and analytics.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- Aggregate the number of clicks for an `ad_id` in the last M minutes
- Return the **top 100 most clicked** `ad_id` every minute
- Support aggregation **filtering by attributes** (country, device, ad format)
- Handle dataset at Facebook/Google scale

### Non-Functional Requirements

- **Correctness:** Aggregation results must be accurate (used for billing and RTB)
- **Handle delayed/duplicate events:** Late-arriving and duplicate clicks must be handled properly
- **Robustness:** Resilient to partial failures
- **Latency:** End-to-end latency should be a few minutes at most

### Back-of-the-Envelope Estimation

```
┌──────────────────────────────────────────────────────────────┐
│  DAU:                1 billion                               │
│  Clicks per user/day: 1                                      │
│  Daily ad clicks:    1 billion                               │
│                                                              │
│  Avg QPS:            1B / 100K seconds = ~10,000 QPS         │
│  Peak QPS:           5x average = ~50,000 QPS                │
│                                                              │
│  Event size:         ~0.1 KB                                 │
│  Daily storage:      0.1 KB × 1B = 100 GB                   │
│  Monthly storage:    ~3 TB                                   │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. API Design

### Query APIs (read from Aggregation DB)

```
GET /ads/{ad_id}/aggregated_count
  Params: from, to (minute timestamps)
  Returns: { ad_id, count }

  Example: GET /ads/ad123/aggregated_count?from=2024-01-01T10:00&to=2024-01-01T10:05
  Response: { "ad_id": "ad123", "count": 4523 }


GET /ads/popular_ads
  Params: window_size (minutes), top_n (default 100)
  Returns: [ { ad_id, count }, ... ]

  Example: GET /ads/popular_ads?window_size=5&top_n=100
  Response: [ { "ad_id": "ad3", "count": 12000 }, { "ad_id": "ad1", "count": 9500 }, ... ]


GET /ads/{ad_id}/aggregated_count?filter_id=country:US
  Params: from, to, filter_id
  Returns: { ad_id, count, filter_id }
```

---

## 3. Data Model

### Raw Data (every click event stored as-is)

```
┌────────────────────────────────────────────────────────┐
│  Raw Click Event                                       │
├──────────────┬─────────────────────────────────────────┤
│  ad_id       │  "ad_001"                               │
│  timestamp   │  2024-01-01 10:01:35                    │
│  user_id     │  "user_789"                             │
│  ip          │  "203.0.113.42"                         │
│  country     │  "US"                                   │
│  device      │  "mobile"                               │
│  ad_format   │  "banner"                               │
└──────────────┴─────────────────────────────────────────┘

Storage: Cassandra (write-heavy, append-only)
Partition key: ad_id
Clustering key: timestamp (DESC)
```

### Aggregated Data (pre-computed results)

```
Table: ad_click_counts
┌──────────┬───────────────┬────────┐
│  ad_id   │  click_minute │  count │
├──────────┼───────────────┼────────┤
│  ad_001  │  1704103260   │  352   │
│  ad_002  │  1704103260   │  198   │
└──────────┴───────────────┴────────┘

Table: ad_click_counts_filtered
┌──────────┬───────────────┬────────┬─────────────┐
│  ad_id   │  click_minute │  count │  filter_id  │
├──────────┼───────────────┼────────┼─────────────┤
│  ad_001  │  1704103260   │  210   │  country:US │
│  ad_001  │  1704103260   │  142   │  country:UK │
└──────────┴───────────────┴────────┴─────────────┘

Table: most_clicked_ads
┌──────────────┬────────────────────┬──────────────────┐
│  window_size │  update_time_minute│  most_clicked_ads│
├──────────────┼────────────────────┼──────────────────┤
│  1           │  1704103260        │  [ad3, ad1, ad2] │
│  5           │  1704103260        │  [ad1, ad3, ad5] │
└──────────────┴────────────────────┴──────────────────┘
```

> **Why store aggregated data separately?**
> Querying raw data for "clicks in last 5 minutes" at 10K QPS would mean scanning
> billions of rows every request. Pre-computed aggregates make queries O(1) lookups.

---

## 4. The Big Picture (High-Level Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║         AD CLICK EVENT AGGREGATION - HIGH-LEVEL DESIGN                           ║
╠═══════════════════════════════════════════════════════════════════════════════════╣
║                                                                                   ║
║  ┌─────────────┐     ┌──────────────┐     ┌─────────────────────┐               ║
║  │ Log Watcher  │────▶│  Message     │────▶│  Data Aggregation   │               ║
║  │ (on ad       │  ①  │  Queue       │  ②  │  Service            │               ║
║  │  servers)    │     │  (Kafka)     │     │  (MapReduce)        │               ║
║  └─────────────┘     └──────┬───────┘     └──────────┬──────────┘               ║
║                              │                        │                           ║
║                              │ ③                      │ ④                        ║
║                              ▼                        ▼                           ║
║                     ┌────────────────┐     ┌──────────────────┐                  ║
║                     │  DB Writer     │     │  Message Queue    │                  ║
║                     └────────┬───────┘     │  (Kafka)          │                  ║
║                              │             └────────┬─────────┘                  ║
║                              ▼                      │ ⑤                          ║
║                     ┌────────────────┐              ▼                             ║
║                     │  Raw Data DB   │     ┌────────────────┐                    ║
║                     │  (Cassandra)   │     │  DB Writer     │                    ║
║                     └────────────────┘     └────────┬───────┘                    ║
║                              ↑                      │                             ║
║                              │ ⑦                    ▼                            ║
║                     ┌────────────────┐     ┌────────────────┐                    ║
║                     │ Recalculation  │     │ Aggregation DB │                    ║
║                     │ Service        │     │ (Cassandra)    │                    ║
║                     └────────────────┘     └────────┬───────┘                    ║
║                                                     │                             ║
║                                                     ▼                             ║
║                                            ┌────────────────┐                    ║
║                                            │ Query Service  │                    ║
║                                            │ (Dashboard)    │                    ║
║                                            └────────────────┘                    ║
║                                                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝

Flow:
① Log Watcher collects click events → publishes to Kafka
② Aggregation Service consumes events → runs MapReduce
③ DB Writer stores raw events in Raw Data DB (for recalculation)
④ Aggregation results published to downstream Kafka topic
⑤ DB Writer consumes aggregated results → writes to Aggregation DB
⑥ Query Service reads from Aggregation DB → serves dashboard
⑦ Recalculation Service reads raw data → replays through aggregation
```

> **Why two Kafka queues?** The first Kafka buffers raw events (high throughput ingestion).
> The second Kafka buffers aggregation results (decouples aggregation from DB writes).
> This ensures the aggregation service isn't blocked by slow DB writes.

---

## 5. Deep Dive: Aggregation Pipeline

Now let's walk through how a click event flows through the system, step by step.

**The journey of a click event:**
> ① Event arrives → ② Assign to time window (Section 6) → ③ Aggregate with MapReduce (below)
> → ④ Apply filters (Section 10) → ⑤ Handle late/duplicate events (Section 8)
> → ⑥ Commit results exactly-once (Section 7) → ⑦ If something was wrong, recalculate (Section 9)

### Step 1: How Do We Aggregate? (MapReduce)

#### Use Case 1: Aggregate Click Count Per Ad

```
Input: All click events in a 1-minute window

         Inputs                Map              Aggregate           Output
    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
    │ Click events │    │ Group by     │    │ Count per    │    │ ad3: 4 clicks│
    │ (mixed ads)  │───▶│ ad_id        │───▶│ ad_id        │───▶│ ad1: 3 clicks│
    │              │    │              │    │              │    │ ad2: 2 clicks│
    └──────────────┘    └──────────────┘    └──────────────┘    └──────────────┘

Example:
  Events: [ad3, ad1, ad3, ad2, ad1, ad3, ad1, ad2, ad3]

  Map:    ad1 → [click, click, click]
          ad2 → [click, click]
          ad3 → [click, click, click, click]

  Aggregate: ad1 = 3, ad2 = 2, ad3 = 4
```

#### How Are Events Distributed to Nodes?

```
Kafka partitions determine which node gets which events:

  Raw events published to Kafka with key = ad_id
  → hash(ad_id) % num_partitions → partition assignment
  → Each aggregation node consumes one or more partitions

  Example (6 partitions, 3 nodes):
    Node 1 consumes: Partition 0, 1  → gets events for ad3, ad6, ad9, ad12, ad15
    Node 2 consumes: Partition 2, 3  → gets events for ad1, ad4, ad7, ad10, ad13
    Node 3 consumes: Partition 4, 5  → gets events for ad2, ad5, ad8, ad11, ad14

  Key property: Same ad_id ALWAYS goes to the same node
  → Per-ad count is computed entirely within one node (no cross-node shuffle!)
  → Only top-N requires merging across nodes (reduce step)
```

#### Use Case 2: Top N Most Clicked Ads (with Reduce)

```
Input: All click events in a 1-minute window, distributed across nodes

    ┌──────────────┐
    │  Kafka Topic │
    │ (partitioned │
    │  by ad_id)   │
    └──────┬───────┘
           │
    ┌──────┼──────────────┐           Each node consumes its
    ▼      ▼              ▼           assigned Kafka partitions
┌─────────────────────────┐ ┌─────────────────────────┐ ┌─────────────────────────┐
│ Node 1 (P0, P1)         │ │ Node 2 (P2, P3)         │ │ Node 3 (P4, P5)         │
│                          │ │                          │ │                          │
│ MAP (in-memory):         │ │ MAP (in-memory):         │ │ MAP (in-memory):         │
│   group by ad_id         │ │   group by ad_id         │ │   group by ad_id         │
│   ad3→[click,click,..]  │ │   ad1→[click,click,..]  │ │   ad2→[click,click,..]  │
│   ad6→[click,..]        │ │   ad4→[click,..]        │ │   ad5→[click,..]        │
│                          │ │                          │ │                          │
│ AGGREGATE (in-memory):   │ │ AGGREGATE (in-memory):   │ │ AGGREGATE (in-memory):   │
│   ad3:12, ad6:5, ad9:3  │ │   ad1:9, ad4:4, ad7:3   │ │   ad2:8, ad5:4, ad8:3   │
│                          │ │                          │ │                          │
│ LOCAL TOP-3 (min-heap):  │ │ LOCAL TOP-3 (min-heap):  │ │ LOCAL TOP-3 (min-heap):  │
│   ad3:12, ad6:5, ad9:3  │ │   ad1:9, ad4:4, ad7:3   │ │   ad2:8, ad5:4, ad8:3   │
└────────────┬─────────────┘ └────────────┬─────────────┘ └────────────┬─────────────┘
             │                            │                            │
             │    ← No network hop above (Map + Aggregate on same node)
             │    ← Only THIS step below requires network (tiny top-3 lists)
             │                            │                            │
                │  ← Only THIS step requires network!
                │    Each node sends its tiny top-3 list
                │    (3 entries, not millions of events)
                ▼
        ┌────────────┐
        │ REDUCE     │                REDUCE: merge 3 local top-3 lists
        │ Global     │                (9 candidates total) into a
        │ Top 3:     │                MIN-HEAP of size 3 → final answer
        │ ad3: 12    │
        │ ad1: 9     │
        │ ad2: 8     │
        └────────────┘
```

> **No shuffle needed (unlike Hadoop MapReduce):**
> In Hadoop, there's a costly "shuffle" phase that redistributes data between
> map and reduce nodes over the network. Here, Kafka's partitioning by ad_id
> eliminates this entirely — all events for an ad_id are already on one node.
>
> **Data structure — Min-Heap of size N:**
> - Each node maintains a min-heap of size N (e.g., 3)
> - For every ad_id count: if count > heap.min(), replace the minimum
> - Memory: O(N) per node — only stores top-N, not all ad counts
> - Reduce merges K heaps of size N → O(K×N) candidates → final heap of size N

---

## 6. Step 2: Which Time Window Does This Event Belong To?

Before we can aggregate, we need to decide: which minute bucket does each click belong to?

### Event Time vs Processing Time

```
Event time:      When the click actually happened (timestamp on the device)
Processing time: When the aggregation service processes the event

Example:
  User clicks ad at 10:01:00 (event time)
  Network delay...
  Event arrives at aggregation service at 10:03:30 (processing time)

  Which minute bucket does this click belong to?
  - Event time:      minute 10:01 ✓ (correct — reflects reality)
  - Processing time: minute 10:03 ✗ (wrong — inflates 10:03, misses 10:01)
```

**We use event time.** It reflects when the click actually happened, which is what billing and analytics care about.

| | Event Time | Processing Time |
|---|---|---|
| **Accuracy** | Correct — reflects real world | Skewed by delays |
| **Late events** | Needs watermarking (events may arrive after window closes) | No late events (everything is "on time" by definition) |
| **Complexity** | Higher — must handle out-of-order events | Simpler — just process as they arrive |
| **Use case** | Billing, analytics (accuracy matters) | Monitoring, alerting (speed matters more than precision) |

> **Tradeoff:** Event time is harder (needs watermarking, out-of-order handling)
> but essential for correctness. Processing time is simpler but gives wrong results
> when events are delayed — unacceptable for ad billing.

### Tumbling Window vs Sliding Window

```
Tumbling Window (non-overlapping, fixed size):

  Time:  |  min 0  |  min 1  |  min 2  |  min 3  |
         └─────────┘─────────┘─────────┘─────────┘
  Window: [0,1)      [1,2)     [2,3)     [3,4)
  
  Each event belongs to exactly ONE window.
  Simple: just bucket by minute.


Sliding Window (overlapping, "last M minutes"):

  Time:  |  min 0  |  min 1  |  min 2  |  min 3  |
         └─────────┘─────────┘─────────┘─────────┘

  "Last 3 minutes" at minute 3:  [1, 2, 3)
  "Last 3 minutes" at minute 4:  [2, 3, 4)

  Trick: Build from tumbling windows!
  sliding_count(last 3 min at minute 3) = tumbling[1] + tumbling[2] + tumbling[3]
```

> **Why tumbling first?** Store 1-minute tumbling window results in the DB.
> To answer "last M minutes," just SUM the last M tumbling buckets.
> This avoids re-aggregating raw events for every sliding window query.

---

## 7. Step 3: How Do We Guarantee Correctness? (Exactly-Once)

We've aggregated the events and assigned them to windows. But what if the aggregation service crashes mid-processing? We need to guarantee every click is counted exactly once — this is critical for billing.

### The Problem

If the aggregation service crashes mid-processing, we either:
- **Lose data** (if we committed offset before processing) — wrong billing!
- **Double count** (if we process but crash before committing offset) — wrong billing!

### The Solution: Atomic Commit

```
Upstream      Aggregation        HDFS/S3       Downstream
(Kafka)       Service                           (Kafka)
   │               │                │               │
   │  1. Poll      │                │               │
   │◀──────────────│                │               │
   │  2. Consume   │                │               │
   │  from offset  │                │               │
   │  100          │                │               │
   │──────────────▶│                │               │
   │               │  3.1 Verify    │               │
   │               │  offset        │               │
   │               │───────────────▶│               │
   │               │                │               │
   │               │  3. Aggregate  │               │
   │               │  events        │               │
   │               │  100 to 110    │               │
   │               │                │               │
   │               │   ┌─ Distributed Transaction ──────────┐
   │               │   │                │               │   │
   │               │   │ 4. Send result │               │   │
   │               │   │────────────────────────────────▶   │
   │               │   │                │               │   │
   │               │   │ 5. Save offset │               │   │
   │               │   │───────────────▶│               │   │
   │               │   │                │               │   │
   │               │   │ 6. Ack back    │               │   │
   │               │   │◀───────────────────────────────│   │
   │               │   └────────────────────────────────────┘
   │               │                │               │
   │  7. Ack with  │                │               │
   │  new offset   │                │               │
   │◀──────────────│                │               │
   │  110          │                │               │

Steps 4, 5, 6 happen as a DISTRIBUTED TRANSACTION:
  - Send aggregation result to downstream Kafka
  - Save the new offset to HDFS/S3
  - If ANY step fails → rollback → restart from offset 100
  - If ALL succeed → commit → next batch starts from 110

Why distributed transaction?
  Without it, two things can go wrong:

  Case 1: Result sent (step 4) ✓, but offset NOT saved (step 5) ✗
    → On restart, offset is still 100 → reprocesses 100-110 → DUPLICATE counts

  Case 2: Offset saved (step 5) ✓, but result NOT sent (step 4) ✗
    → On restart, offset is 110 → skips 100-110 → LOST counts

  Both are unacceptable for billing. The distributed transaction ensures
  either BOTH happen or NEITHER happens — no partial state.
```

> **Why save offset to HDFS/S3 instead of Kafka?**
> Storing offset externally alongside the aggregation checkpoint allows
> us to verify consistency: "Did I already process offset 100-110?"
> On restart, check HDFS → if offset 110 is saved, skip to 110.

---

## 8. Step 4: What About Late & Duplicate Events?

We chose event time for windowing (Section 6). But this creates a problem: events can arrive *after* their time window has already closed. And network retries can send the same event twice. How do we handle these?

### Late Events (Watermarking)

```
Problem: Events can arrive after their time window has closed.

  Real time:     min 0    min 1    min 2    min 3    min 4
                  │        │        │        │        │
  Event arrives:  │        │        │  ← Event from min 0 arrives here!
                  │        │        │     (2 minutes late)

Solution: Watermark = "I believe all events up to time T have arrived"

  Watermark delay = 2 minutes

  ┌────────────────────────────────────────────────────────────┐
  │  Event time vs Watermark:                                  │
  │                                                            │
  │  Event ≥ watermark          → ON TIME (process normally)  │
  │  Event ≥ watermark - delay  → LATE but accepted           │
  │  Event < watermark - delay  → TOO LATE, dropped           │
  └────────────────────────────────────────────────────────────┘

  Tradeoff: Longer watermark delay → fewer dropped events
                                   → higher end-to-end latency
```

> **Watermark does NOT solve everything.** Events with very long delays (e.g., device
> was offline for hours) will still be dropped. But designing a complex system for
> these low-probability events isn't worth the ROI. Instead, we correct the tiny
> inaccuracy with **end-of-day reconciliation** — a batch job that compares real-time
> aggregation results against raw data and fixes any drift (see Section 12).

### Duplicate Events (Deduplication)

```
Problem: Same click event sent twice (network retry, producer retry)

Solution options:
  1. Exact dedup: Store event_id in a short-lived cache (e.g., Redis)
     - Check "have I seen this event_id?" before counting
     - Expensive at scale (10K QPS × event IDs to track)

  2. Approximate dedup: Use idempotent processing
     - aggregation is "count per ad_id per minute"
     - If same event counted twice in same window → count is slightly off
     - Acceptable for analytics, NOT for billing

  3. Exactly-once via Kafka transactions (preferred)
     - Kafka producer dedup: producer_id + sequence_number
     - Combined with atomic commit → end-to-end exactly-once
```

---

## 9. Step 5: What If We Got It Wrong? (Recalculation)

Even with exactly-once processing, the aggregation *logic itself* could have a bug — maybe we miscounted, applied wrong filters, or had a code defect. We need a way to recompute historical results. This is why we store raw events.

### Why Recalculation?

```
Scenario: A bug in the aggregation logic caused wrong counts for the last 3 days.
          Need to recompute all aggregated results.

  This is why we store raw events in a separate database!
```

### How It Works

```
Normal path (real-time):
  Log Watcher → Kafka → Aggregation Service → Kafka → Aggregation DB

Recalculation path (batch):
  Raw Data DB → Recalculation Service → Kafka → Aggregation DB (overwrite)
       │                 │
       │     ① Read raw events for affected time range
       │     ② Replay through aggregation logic (fixed version)
       │     ③ Write corrected results to downstream Kafka
       │     ④ DB Writer overwrites stale aggregation data

  The recalculation service is a separate instance of the
  aggregation service, running the corrected logic on historical data.
```

> **Key design decision:** Always store raw events even though they're expensive (100 GB/day).
> Without them, recalculation is impossible — you'd have to ask users to click again!

---

## 10. Step 6: Filtering by Attributes

So far we've been counting total clicks per ad. But advertisers also want breakdowns: "How many clicks from the US? From mobile devices?" This runs alongside the main aggregation pipeline.

### How Filtering Works

```
Raw event has attributes: country, device_type, ad_format

Instead of pre-computing ALL possible filter combinations (explosion!),
we use a "star schema" approach:

  Unfiltered:    ad_id + minute → count
  Filter by 1:   ad_id + minute + filter_id → count

  filter_id examples:
    "country:US"
    "device:mobile"
    "format:banner"

Aggregation Service computes:
  For each minute window:
    1. Total count per ad_id (unfiltered)
    2. Count per ad_id per country
    3. Count per ad_id per device
    4. Count per ad_id per format

This is O(N × F) where F = number of filter dimensions (small, ~3-5)
```

> **Tradeoff:** Pre-computing filters increases processing and storage
> but makes queries instant (O(1) lookup instead of scanning raw data).

---

## 11. Scaling

### Message Queue (Kafka)

- Partition by `ad_id` hash → events for same ad go to same partition
- Add more partitions for higher throughput
- Consumer group: one aggregation node per partition

**Topic Physical Sharding:**

A single Kafka topic may not be enough at scale. Split into multiple topics by:

```
By geography:     topic_north_america, topic_europe, topic_asia
By business type: topic_web_ads, topic_mobile_ads, topic_video_ads
```

| | Pros | Cons |
|---|---|---|
| **Sharded topics** | Higher throughput, faster consumer rebalancing (fewer consumers per topic) | Extra complexity, higher maintenance cost |
| **Single topic** | Simpler to manage | Rebalancing is slow with many consumers, single topic bottleneck |

### Aggregation Service

- Horizontally scalable — each node processes a subset of partitions
- Stateless processing (state is in Kafka offsets + checkpoints)
- Scale by adding more aggregation nodes + Kafka partitions

### Database

- **Raw Data DB (Cassandra):** Partition by `ad_id`, clustering key `timestamp` (DESC)
  - Write-heavy (100 GB/day), append-only → Cassandra's sweet spot
  - TTL-based cleanup (retain 2-4 weeks for recalculation)

- **Aggregation DB (Cassandra):** Partition by `ad_id`, clustering key `click_minute`
  - Read pattern is simple **point queries**: `SELECT count WHERE ad_id = X AND minute = Y`
  - No complex joins, no full-table scans → Cassandra handles point reads well
  - Same technology as Raw Data DB → simpler operations (one DB to manage)
  - **Alternative:** Could use Redis for faster reads if latency is critical,
    but Cassandra already serves point queries in single-digit ms

### Hotspot Issue

```
Problem: A viral ad gets millions of clicks → single partition overloaded

Solutions:
  1. Add extra partitions: Break hot ad_id across sub-partitions
     Key: ad_id + random_suffix (e.g., "ad123_0", "ad123_1", ... "ad123_9")
     → Spreads load across 10 partitions
     → Query must aggregate across all sub-partitions (slightly more complex)

  2. Dedicated aggregation node: Route hot ad_ids to beefier machines
  
  3. Pre-aggregation at Log Watcher: Batch clicks locally before
     sending to Kafka (reduces QPS for hot ads)
```

---

## 12. What Can Go Wrong? (Failure Handling)

### Aggregation Service Crash

**Scenario:** Aggregation node crashes mid-processing
**Solution:**
- Restart from last committed offset (stored in HDFS/S3)
- Atomic commit ensures no partial results are visible
- Another node in the consumer group picks up the orphaned partitions

### Kafka Failure

**Scenario:** Kafka broker goes down
**Solution:**
- Kafka replication (ISR) ensures data is not lost
- Producers retry; consumers reconnect to new leader broker
- See Distributed Message Queue cheatsheet for details

### Database Write Failure

**Scenario:** Aggregation DB is temporarily unavailable
**Solution:**
- Results stay in downstream Kafka topic (buffered)
- DB Writer retries when database recovers
- No data loss because Kafka retains messages

### Incorrect Aggregation (Bug)

**Scenario:** Aggregation logic had a bug
**Solution:**
- Recalculation Service replays raw data with fixed logic
- Overwrites incorrect results in Aggregation DB
- This is why raw data storage is critical

### Monitoring & Reconciliation

**Why reconciliation?** Real-time streaming can silently produce wrong results —
dropped events, watermark cutoffs, race conditions, or subtle bugs. Without
verification, you'd never know billing is off by 2%.

**How it works:** Run a batch job (end-of-day) that re-aggregates from raw data
and compares against the real-time results. If they differ → alert and fix.

```
Example: Reconciliation for ad_001 on Jan 1st, minute 10:00

  Real-time aggregation result:     ad_001, minute 10:00 → 4,523 clicks
  Batch reconciliation (from raw):  ad_001, minute 10:00 → 4,531 clicks
                                                            ─────
                                                   Diff:     8 clicks missing!

  Why? 8 events arrived after the watermark cutoff and were dropped.

  Action:
  ┌──────────────────────────────────────────────────────────────────┐
  │  Diff < threshold (e.g., 0.1%)?                                 │
  │    YES → Log it, acceptable drift, no action                    │
  │    NO  → Alert! Investigate cause. Options:                     │
  │          1. Patch the 8 missing clicks into Aggregation DB      │
  │          2. Trigger full recalculation for the affected window  │
  └──────────────────────────────────────────────────────────────────┘

  Schedule: Run reconciliation daily (or every few hours for critical data)
  Compare: real-time counts vs batch counts for every (ad_id, minute) pair
```

> **This is the safety net.** Watermarking handles most late events, exactly-once
> handles crashes, but reconciliation catches everything else — the final
> guarantee that billing numbers are correct.

---

## 13. Lambda vs Kappa Architecture

### The Problem: Real-Time Alone Isn't Always Enough

```
Scenario: Your real-time streaming aggregation has been running for months.
One day, you discover a bug in the filtering logic — country "GB" was
being counted as "UK" for the last 3 days. All filtered counts are wrong.

  Options:
  A. Reprocess 3 days of raw data through the SAME streaming pipeline (Kappa)
  B. Have a SEPARATE batch pipeline that periodically reprocesses everything (Lambda)
```

### Lambda Architecture

```
                    ┌─────────────────────────┐
                    │      Batch Layer         │
              ┌────▶│  (Batch Engine: Spark)   │────┐
              │     └─────────────────────────┘    │
              │                                     ▼
┌──────┐  ┌───┴───┐                          ┌──────────┐     ┌───────┐
│Events│─▶│ Kafka │                          │ Serving  │────▶│ Query │
└──────┘  └───┬───┘                          │ Backend  │     └───────┘
              │                                     ▲
              │     ┌─────────────────────────┐    │
              └────▶│    Streaming Layer       │────┘
                    │  (Flink / Spark Stream)  │
                    └─────────────────────────┘

               ┌──────────┐          ┌──────────┐
               │ Raw Data │          │ Results  │
               └──────────┘          └──────────┘
```

**How it works (with example):**

```
Two parallel paths running ALL the time:

  Path 1 — Streaming (real-time, approximate):
    Click at 10:01 → Flink aggregates → "ad_001: 4,523 clicks" → Dashboard shows instantly
    May miss late events, may have small inaccuracies

  Path 2 — Batch (every few hours, accurate):
    Spark reads ALL raw data from 00:00 to now → recomputes from scratch
    "ad_001: 4,531 clicks" → overwrites the streaming result

  Serving layer: Uses streaming result for recent data (fast),
                 switches to batch result once batch catches up (accurate)

  Pros: Batch corrects streaming errors automatically
  Cons: TWO codebases doing the same thing (Flink code + Spark code)
        Must ensure both produce identical results — hard to maintain
```

### Kappa Architecture (Preferred)

```
┌──────┐  ┌───────┐  ┌─────────────────────────┐  ┌──────────┐  ┌───────┐
│Events│─▶│ Kafka │─▶│    Streaming Layer       │─▶│ Serving  │─▶│ Query │
└──────┘  └───────┘  │  (Flink / Spark Stream)  │  │ Backend  │  └───────┘
                      └─────────────────────────┘  └──────────┘

               ┌──────────┐          ┌──────────┐
               │ Raw Data │          │ Results  │
               └──────────┘          └──────────┘
```

**How it works (with example):**

```
Single path — streaming only:

  Normal:       Click at 10:01 → Flink aggregates → Dashboard (same as Lambda)

  Bug found:    "Country GB was counted as UK for last 3 days!"

  Recalculate:  Read 3 days of raw data from Raw Data DB
                → Feed it through the SAME Flink pipeline (with fixed code)
                → Overwrite incorrect results in Aggregation DB

  Same code handles both real-time AND recalculation.
  No separate batch codebase to maintain.

  Pros: Single codebase, no sync issues
  Cons: Replaying 3 days through streaming can be slow
        (but recalculation is rare — only on bugs)
```

| | Lambda | Kappa |
|---|---|---|
| **Codebases** | Two (batch + stream) — must produce same results | One (stream only) |
| **Accuracy** | Batch auto-corrects streaming drift | Relies on reconciliation to catch drift |
| **Recalculation** | Always running (batch reprocesses periodically) | On-demand (replay raw data when needed) |
| **Complexity** | High — two systems to maintain | Lower — single pipeline |
| **Best for** | Systems where batch is already needed (ML training + serving) | Systems where recalculation is rare (our case) |

> **For this system:** Kappa is preferred. Recalculation is rare (only on bugs),
> and we already store raw data for replay. Reconciliation catches drift.
> No need for a permanent batch layer running 24/7.

---

## 14. Why These Choices? (Key Design Decisions)

### Decision #1: Aggregate Then Store (Not Raw Query)

**Problem:** How to serve "clicks in last 5 min" at 10K QPS?

**Why pre-aggregate:** Querying billions of raw rows per request is impossible.
Pre-compute minute-level counts, then SUM last M buckets for sliding window.
Query becomes O(M) lookups instead of scanning billions of rows.

### Decision #2: Two Kafka Queues (Not One)

**Problem:** Can we use a single Kafka between log watcher and DB writer?

**Why two:** The aggregation service is CPU-intensive (MapReduce). If it writes
directly to the DB, a slow DB blocks aggregation. Two Kafkas decouple:
- First Kafka: raw event buffering (high throughput)
- Second Kafka: aggregation result buffering (decouples from DB writes)

### Decision #3: Cassandra for Both Databases

**Problem:** Which database for raw data and aggregation results?

**Why Cassandra:**
- **Raw Data DB:** Write-heavy (100 GB/day append-only), time-series-like, TTL support
- **Aggregation DB:** Write from batch, read from query service, simple key-value lookups
- Both benefit from Cassandra's linear scalability and tunable consistency

### Decision #4: Kappa Over Lambda

**Problem:** How to handle both real-time and batch recalculation?

**Why Kappa:** Single codebase (stream processing) handles both. Recalculation
replays raw data through the same stream pipeline. Lambda would require
maintaining two separate codebases (batch + stream) that must produce identical results.

### Decision #5: Store Raw Data Despite Cost

**Problem:** Raw data is 100 GB/day (3 TB/month). Why not discard after aggregation?

**Why keep:** Recalculation is impossible without raw data. If aggregation logic
has a bug, we'd lose billing accuracy permanently. 3 TB/month is cheap
compared to incorrect ad billing at scale.

---

## 15. Interview Pro Tips

### Opening Statement
"An ad click aggregation system ingests billions of click events through Kafka, runs MapReduce-style aggregation in minute-level windows, and stores pre-computed results for fast dashboard queries. The key challenges are exactly-once processing for billing accuracy, handling late-arriving events via watermarking, and supporting data recalculation when bugs are discovered."

### Key Talking Points
1. **Two Kafkas:** Decouple ingestion from aggregation from DB writes
2. **MapReduce:** Map (group by ad_id) → Aggregate (count) → Reduce (top N)
3. **Tumbling → Sliding:** Store 1-min buckets, SUM for sliding windows
4. **Exactly-Once:** Atomic commit (result + offset together)
5. **Watermarking:** Handle late events with configurable delay tolerance
6. **Recalculation:** Replay raw data through fixed logic
7. **Hotspot:** Sub-partition hot ad_ids to spread load

### Common Follow-ups

**Q: Why not use a stream processing framework like Flink directly?**
A: We would! Flink or Spark Streaming would implement the MapReduce logic. The architecture describes the overall system; Flink is the implementation choice for the Aggregation Service.

**Q: How do you handle a viral ad that gets 100x normal clicks?**
A: Sub-partition the hot ad_id (e.g., "ad123_0" through "ad123_9"), spread across 10 partitions. Pre-aggregate at the log watcher level to batch clicks before sending to Kafka.

**Q: Why Cassandra and not a time-series DB like InfluxDB?**
A: Either would work for the aggregation DB. Cassandra is preferred because it handles both raw data (write-heavy) and aggregated data (read-heavy) with a single technology stack, simplifying operations.

**Q: What if the recalculation takes too long?**
A: Parallelize — partition raw data by time range, run multiple recalculation instances. Since data is in Kafka/Cassandra, it can be read in parallel.

**Q: How do you ensure the dashboard shows consistent data during recalculation?**
A: Write recalculated results to a shadow table first. Once complete, atomically swap the shadow table with the live table (blue-green deployment for data).

---

## 16. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════════╗
║              AD CLICK AGGREGATION - COMPLETE FLOW                                ║
╠═══════════════════════════════════════════════════════════════════════════════════╣
║                                                                                   ║
║  ┌──────────┐   ┌─────────┐   ┌──────────────┐   ┌─────────┐   ┌─────────────┐ ║
║  │   Log    │──▶│  Kafka  │──▶│ Aggregation  │──▶│  Kafka  │──▶│  DB Writer  │ ║
║  │ Watcher  │   │  (raw)  │   │  Service     │   │ (agg)   │   └──────┬──────┘ ║
║  └──────────┘   └────┬────┘   │ (MapReduce)  │   └─────────┘          │        ║
║                       │        └──────────────┘                        ▼        ║
║                       │                                       ┌──────────────┐  ║
║                       ▼                                       │ Aggregation  │  ║
║                 ┌───────────┐                                 │ DB           │  ║
║                 │ DB Writer │                                 └──────┬───────┘  ║
║                 └─────┬─────┘                                        │          ║
║                       │                                              ▼          ║
║                       ▼                                       ┌──────────────┐  ║
║                 ┌───────────┐     ┌──────────────────┐        │   Query      │  ║
║                 │ Raw Data  │◀────│  Recalculation   │        │   Service    │  ║
║                 │ DB        │     │  Service          │        │  (Dashboard) │  ║
║                 └───────────┘     └──────────────────┘        └──────────────┘  ║
║                                                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════════╣
║                                                                                   ║
║  KEY FLOWS:                                                                       ║
║  ──────────                                                                       ║
║  ① Real-time: LogWatcher → Kafka → Aggregation → Kafka → DB Writer → Agg DB    ║
║  ② Query:     Dashboard → Query Service → Aggregation DB                         ║
║  ③ Recalc:    Raw Data DB → Recalculation Service → Kafka → Agg DB (overwrite)  ║
║                                                                                   ║
║  CRITICAL DESIGN DECISIONS:                                                       ║
║  ──────────────────────────                                                       ║
║  • Two Kafkas: decouple ingestion → aggregation → DB writes                      ║
║  • MapReduce: Map(ad_id) → Aggregate(count) → Reduce(top N)                     ║
║  • Tumbling windows: 1-min buckets, SUM for sliding window queries               ║
║  • Exactly-once: atomic commit (result + offset)                                 ║
║  • Watermark: accept late events within tolerance, drop the rest                 ║
║  • Raw data stored: enables recalculation when bugs found                        ║
║  • Kappa architecture: single streaming path, replay for batch                   ║
║                                                                                   ║
╚═══════════════════════════════════════════════════════════════════════════════════╝
```
