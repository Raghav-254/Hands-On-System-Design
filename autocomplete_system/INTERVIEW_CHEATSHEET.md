# Search Autocomplete System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview - Chapter 13

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Trie** | Core data structure | In-memory | Top-k cached at each node |
| **AnalyticsLog** | Collect raw queries | Kafka/Kinesis | Billions of events/day |
| **Aggregator** | Aggregate frequencies | Spark/Flink | Deduplication, time decay |
| **TrieWorker** | Build/update trie | Batch job | Weekly rebuild |
| **TrieDB** | Persistent storage | Redis/DynamoDB | Key: prefix, Value: top-k |
| **TrieCache** | Fast lookups | In-memory | Weekly snapshot from DB |
| **ShardManager** | Distribute load | Coordination | Smart sharding by frequency |

---

## 1. Requirements Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Prefix matching ONLY (beginning of query)                                 ║
║  • Return top 5 suggestions by popularity                                    ║
║  • No spell check or autocorrect                                            ║
║  • Lowercase alphabetic characters only                                      ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Latency: < 100ms (or causes stuttering)                                  ║
║  • Scale: 10M DAU                                                           ║
║  • Highly available                                                         ║
║  • Scalable                                                                 ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  READ PATH (Autocomplete queries - every keystroke):                        ║
║  • 10M DAU × 10 searches/day × 6 keystrokes/search = 600M queries/day      ║
║  • QPS: 600M / 86400 = ~7,000 QPS                                          ║
║  • Peak: 2× average = ~14,000 QPS                                          ║
║                                                                               ║
║  WRITE PATH (Analytics logs - only on selection/submit):                    ║
║  • 10M DAU × 10 searches/day = 100M log entries/day                        ║
║  • ~1,200 writes/sec to Kafka                                              ║
║                                                                               ║
║  Storage: ~1M unique queries × 50 bytes = ~50MB for trie                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Trie Data Structure (Core Algorithm)

### Basic Trie Structure

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TRIE STRUCTURE (Figure 13-8)                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║                              root                                            ║
║                           /       \                                          ║
║                          b         w                                         ║
║                       /  |  \       \                                        ║
║                     be   bu  ...    wi                                       ║
║                    / \    \          \                                       ║
║                 bee bes  buy:14     win:11                                  ║
║                  |    \                                                      ║
║             beer:10  best:35                                                ║
║                                                                               ║
║  Each node contains:                                                        ║
║  • children: Map<Character, TrieNode>                                       ║
║  • isEndOfWord: boolean                                                     ║
║  • frequency: int (for word-ending nodes)                                   ║
║  • topKCache: List<Suggestion> ← KEY OPTIMIZATION!                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Top-K Caching at Each Node (Critical Optimization!)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TOP-K CACHING (Figure 13-10)                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WITHOUT CACHING:                                                           ║
║  ─────────────────                                                          ║
║  Query "be" → traverse to node → DFS all children → collect all words      ║
║            → sort by frequency → return top 5                               ║
║  Time: O(p + c) where p=prefix length, c=chars under prefix                ║
║  For popular prefix: could be millions of chars!                            ║
║                                                                               ║
║  WITH CACHING:                                                              ║
║  ──────────────                                                             ║
║  Query "be" → traverse to node → return cached top-5                        ║
║  Time: O(p) where p=prefix length                                          ║
║  Instant! Just return the pre-computed list.                               ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │  Key         Cached Top-K                                               │ ║
║  │  ─────────   ─────────────────────────────────────────────────────────  │ ║
║  │  "b"         [(best, 35), (bet, 29), (bee, 20), (be, 15), (buy, 14)]   │ ║
║  │  "be"        [(best, 35), (bet, 29), (bee, 20), (be, 15), (beer, 10)]  │ ║
║  │  "bee"       [(bee, 20), (beer, 10)]                                    │ ║
║  │  "bes"       [(best, 35)]                                               │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  TRADE-OFF: Space for time                                                  ║
║  • More storage (top-k at every node)                                       ║
║  • But O(1) query time!                                                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Time Complexity

| Operation | Without Cache | With Top-K Cache |
|-----------|---------------|------------------|
| Search (get top k) | O(p + c) | **O(p)** |
| Insert | O(n) | O(n × k) |
| Update frequency | O(n) | O(n × k) |

Where: p = prefix length, c = total chars under prefix, n = word length, k = top-k

---

## 3. System Architecture

### High-Level Design (Figure 13-11)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  QUERY PATH (READ)                                                          ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌────────┐     ┌──────────────┐     ┌────────────┐     ┌──────────────┐     ║
║  │  User  │ ──► │Load Balancer │ ──► │ API Server │ ──► │ Trie Cache   │     ║
║  │        │  ①  │              │  ②  │            │  ③  │              │     ║
║  └────────┘     └──────────────┘     └────────────┘     └──────┬───────┘     ║
║                                                                 │            ║
║       User types "be"                              ④ Cache miss (rare)      ║
║       in search box                                             │            ║
║                                                                 ▼            ║
║                                                          ┌──────────────┐    ║
║                                                          │   Trie DB    │    ║
║                                                          └──────────────┘    ║
║                                                                               ║
║  LATENCY BREAKDOWN:                                                         ║
║  • Network (user → LB): ~10ms                                               ║
║  • Cache hit: ~1ms                                                          ║
║  • Cache miss (DB): ~5-10ms                                                 ║
║  • Total: < 100ms ✓                                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Data Pipeline (Figure 13-9)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATA PIPELINE (WRITE)                                                       ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌────────────┐   ┌────────────┐   ┌──────────────┐   ┌────────────┐        ║
║  │ Analytics  │──►│ Aggregators│──►│  Aggregated  │──►│  Workers   │        ║
║  │   Logs     │   │ (Spark)    │   │    Data      │   │            │        ║
║  └────────────┘   └────────────┘   └──────────────┘   └─────┬──────┘        ║
║       │                                                      │               ║
║   Raw search                                          Build new trie        ║
║   queries                                                    │               ║
║   (Kafka)                                                    ▼               ║
║                                                       ┌──────────────┐       ║
║                                                       │   Trie DB    │       ║
║                                                       └──────┬───────┘       ║
║                                                              │               ║
║                                                     Weekly Snapshot          ║
║                                                              │               ║
║                                                              ▼               ║
║                                                       ┌──────────────┐       ║
║                                                       │  Trie Cache  │       ║
║                                                       └──────────────┘       ║
║                                                                               ║
║  TIMING:                                                                     ║
║  • Analytics: Real-time (every keystroke logged)                            ║
║  • Aggregation: Hourly/Daily (batch)                                        ║
║  • Trie rebuild: Weekly (to balance freshness vs stability)                 ║
║  • Cache refresh: After each rebuild                                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. Data Collection & Aggregation

### Analytics Log

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WHAT TO LOG (Important!)                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ❌ WRONG: Log every keystroke                                              ║
║     User types: b → be → bes → best → best b → best bu → best buy          ║
║     7 log entries for 1 search = noisy, wasteful!                           ║
║                                                                               ║
║  ✓ CORRECT: Log only on USER ACTION                                         ║
║     1. User CLICKS on a suggestion from the dropdown                        ║
║     2. User SUBMITS the search (presses Enter)                              ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  LOG ENTRY FORMAT:                                                          ║
║  {                                                                           ║
║    "query": "best buy",           ← The selected/submitted query            ║
║    "timestamp": "2024-01-15T10:30:45Z",                                     ║
║    "user_id": "user123",                                                    ║
║    "source": "suggestion_click",   ← Or "search_submit"                     ║
║    "suggestion_position": 2,       ← Which position was clicked (1-5)       ║
║    "device": "mobile",                                                      ║
║    "location": "US"                                                         ║
║  }                                                                           ║
║                                                                               ║
║  VOLUME (corrected):                                                        ║
║  • 10M DAU × 10 searches = 100M log entries/day (not 2B!)                  ║
║  • ~1.2K writes/sec to Kafka (manageable)                                  ║
║                                                                               ║
║  WHY THIS MATTERS:                                                          ║
║  • Keystroke logging = counts abandoned/incomplete queries                  ║
║  • Selection logging = counts what users ACTUALLY wanted                    ║
║  • Much cleaner signal for ranking                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Kafka Partitioning Strategy

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  KAFKA TOPIC: "search-events"                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  PARTITION KEY OPTIONS:                                                     ║
║  ───────────────────────                                                    ║
║                                                                               ║
║  Option 1: Partition by USER_ID                                             ║
║  • Same user's queries go to same partition                                ║
║  • Good for per-user deduplication                                         ║
║  • Even distribution (users are random)                                    ║
║                                                                               ║
║  Option 2: Partition by QUERY FIRST LETTER (Recommended)                   ║
║  • All "a*" queries → Partition 0                                          ║
║  • All "b*" queries → Partition 1                                          ║
║  • Enables parallel aggregation by prefix                                  ║
║  • Each aggregator handles one letter range                                ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │  Kafka Topic: search-events (26 partitions)                            │ ║
║  │                                                                         │ ║
║  │  ┌───────┐ ┌───────┐ ┌───────┐     ┌───────┐                          │ ║
║  │  │ P0: a │ │ P1: b │ │ P2: c │ ... │P25: z │                          │ ║
║  │  └───┬───┘ └───┬───┘ └───┬───┘     └───┬───┘                          │ ║
║  │      │         │         │             │                               │ ║
║  │      ▼         ▼         ▼             ▼                               │ ║
║  │  Aggregator Aggregator Aggregator  Aggregator                         │ ║
║  │      0         1         2            25                               │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  CONFIGURATION:                                                             ║
║  • Partitions: 26 (one per letter) or multiples (52, 78)                  ║
║  • Retention: 7 days (for replay/reprocessing)                            ║
║  • Replication factor: 3 (durability)                                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Aggregation Deep-Dive (Spark/Flink)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  AGGREGATION PIPELINE (MapReduce Pattern)                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  STEP 1: READ FROM KAFKA                                                    ║
║  ─────────────────────────                                                  ║
║  Spark/Flink reads from Kafka partitions in parallel                       ║
║                                                                               ║
║  STEP 2: MAP (Per-record transformation)                                    ║
║  ───────────────────────────────────────                                    ║
║  Input:  {query: "best buy", user: "u1", time: t1}                         ║
║  Output: (key: "best buy", value: 1)                                       ║
║                                                                               ║
║  STEP 3: FILTER (Deduplication)                                            ║
║  ──────────────────────────────                                            ║
║  • Window: 60 seconds                                                      ║
║  • Key: (user_id, query)                                                   ║
║  • If same (user, query) within window → skip                             ║
║                                                                               ║
║  // Flink pseudo-code                                                      ║
║  stream                                                                    ║
║    .keyBy(event -> event.userId + ":" + event.query)                      ║
║    .window(TumblingEventTimeWindows.of(Time.seconds(60)))                 ║
║    .reduce((a, b) -> a)  // Keep only first in window                     ║
║                                                                               ║
║  STEP 4: REDUCE (Count by query)                                           ║
║  ────────────────────────────────                                          ║
║  Input:  [(best buy, 1), (best buy, 1), (beer, 1), (best buy, 1)]         ║
║  Output: {best buy: 3, beer: 1}                                           ║
║                                                                               ║
║  // Flink pseudo-code                                                      ║
║  stream                                                                    ║
║    .keyBy(event -> event.query)                                           ║
║    .window(TumblingEventTimeWindows.of(Time.days(7)))                     ║
║    .reduce((a, b) -> a.count + b.count)                                   ║
║                                                                               ║
║  STEP 5: TIME DECAY (Weekly batch job)                                     ║
║  ──────────────────────────────────────                                    ║
║  For each query in aggregated data:                                        ║
║    if last_searched > 7 days ago:                                         ║
║      frequency *= 0.5  (decay by 50%)                                     ║
║    if frequency < threshold:                                              ║
║      remove from dataset  (prune low-frequency)                           ║
║                                                                               ║
║  STEP 6: OUTPUT TO AGGREGATED DATA STORE                                   ║
║  ────────────────────────────────────────                                  ║
║  Write results to intermediate storage (HDFS, S3)                         ║
║  Format: {query: string, frequency: int, last_updated: timestamp}         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Aggregation Strategies

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  AGGREGATION STRATEGIES                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. DEDUPLICATION                                                           ║
║     • Same user, same query, within 60s = count as 1                        ║
║     • Prevents counting rapid retyping                                      ║
║                                                                               ║
║  2. TIME WINDOW                                                             ║
║     • Aggregate by week (balance freshness vs stability)                    ║
║     • Recent weeks weighted more than old weeks                             ║
║                                                                               ║
║  3. TIME DECAY                                                              ║
║     • Queries not searched recently get frequency reduced                   ║
║     • Prevents stale suggestions                                            ║
║     • Example: frequency *= 0.5 if not searched in 7 days                  ║
║                                                                               ║
║  4. FREQUENCY THRESHOLD (Prune noise)                                       ║
║     • Remove queries with frequency < 10/week                              ║
║     • Prevents spam/typos from polluting trie                              ║
║     • Keeps trie size manageable                                           ║
║                                                                               ║
║  RAW:        ["best buy", "best buy", "best", "best buy", "beer"]          ║
║  AGGREGATED: {"best buy": 3, "best": 1, "beer": 1}                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 5. Trie Storage & Memory Management

### Where is the Trie Stored? (Clarification)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TRIE STORAGE LAYERS (Important Clarification!)                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  The "trie" exists in TWO forms:                                            ║
║                                                                               ║
║  1. TRIE DB (Persistent, Key-Value format)                                  ║
║     ─────────────────────────────────────                                   ║
║     • NOT a tree structure on disk                                         ║
║     • Key-value pairs: prefix → top-k suggestions                          ║
║     • Storage: Redis, DynamoDB, or Cassandra                               ║
║     • Source of truth for serving                                          ║
║                                                                               ║
║     Example:                                                                ║
║     "b"    → [("best", 35), ("beer", 30), ("bee", 20)]                     ║
║     "be"   → [("best", 35), ("beer", 30), ("bee", 20)]                     ║
║     "bee"  → [("bee", 20), ("beer", 10)]                                   ║
║                                                                               ║
║  2. TRIE CACHE (In-memory, tree OR key-value)                              ║
║     ─────────────────────────────────────────                               ║
║     • For ultra-fast lookups (< 1ms)                                       ║
║     • Can be actual tree structure OR Redis cache                          ║
║     • Refreshed weekly from Trie DB                                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Trie Worker Data Flow (HDFS/S3 → Memory → Trie DB)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WEEKLY REBUILD FLOW - DETAILED                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  STEP 1: AGGREGATOR WRITES TO HDFS/S3                                       ║
║  ──────────────────────────────────────                                     ║
║  Aggregator (Spark/Flink) outputs to distributed storage:                  ║
║                                                                               ║
║  s3://autocomplete-data/aggregated/2024-week-03/                           ║
║    ├── part-00000.parquet  (queries starting with a-f)                     ║
║    ├── part-00001.parquet  (queries starting with g-l)                     ║
║    ├── part-00002.parquet  (queries starting with m-r)                     ║
║    └── part-00003.parquet  (queries starting with s-z)                     ║
║                                                                               ║
║  Each file contains: {query: string, frequency: int}                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 2: TRIE WORKERS READ FROM HDFS/S3 (Batch Read, NOT Query)            ║
║  ───────────────────────────────────────────────────────────────            ║
║                                                                               ║
║  // Worker pseudo-code                                                      ║
║  List<QueryFrequency> data = s3Client.readParquet(                         ║
║      "s3://autocomplete-data/aggregated/2024-week-03/part-00000.parquet"  ║
║  );                                                                         ║
║                                                                               ║
║  // This is a BULK READ of the entire partition file                       ║
║  // NOT querying a database - just downloading a file                      ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 3: FILTER BY THRESHOLD (Before building trie!)                       ║
║  ────────────────────────────────────────────────────                       ║
║                                                                               ║
║  ⚠️ CRITICAL: Threshold is applied HERE, before in-memory trie build       ║
║                                                                               ║
║  data = data.stream()                                                      ║
║      .filter(q -> q.frequency > THRESHOLD)  // e.g., > 100                 ║
║      .collect(toList());                                                   ║
║                                                                               ║
║  100M queries → FILTER → 1M queries (99% reduction!)                       ║
║                                                                               ║
║  Why here?                                                                  ║
║  • Can't build 100M-query trie in memory                                   ║
║  • Filter BEFORE loading into memory                                       ║
║  • Only popular queries get into the trie                                  ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 4: BUILD TRIE IN MEMORY                                              ║
║  ─────────────────────────────                                              ║
║                                                                               ║
║  Trie trie = new Trie();                                                   ║
║  for (QueryFrequency qf : data) {                                          ║
║      trie.insert(qf.query, qf.frequency);                                  ║
║  }                                                                          ║
║  trie.computeTopKForAllNodes();  // Pre-compute top-5 at each node         ║
║                                                                               ║
║  Memory: 1M queries × 50 bytes ≈ 50MB (fits easily!)                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 5: CONVERT TO KEY-VALUE & WRITE TO TRIE DB                           ║
║  ────────────────────────────────────────────────                           ║
║                                                                               ║
║  // Traverse trie, emit KV pairs                                           ║
║  for (TrieNode node : trie.allNodes()) {                                   ║
║      String prefix = node.getPrefix();                                     ║
║      List<Suggestion> topK = node.getTopK();                               ║
║      trieDB.put(prefix, topK);  // Write to Redis/DynamoDB                 ║
║  }                                                                          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 6: ATOMIC SWAP (Blue-Green Deployment)                               ║
║  ───────────────────────────────────────────                                ║
║                                                                               ║
║  • Write to Trie DB v2 (while v1 is serving)                               ║
║  • Flip routing: API servers now read from v2                              ║
║  • Delete v1 (or keep as backup)                                           ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Worker Parallelism

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  PARALLEL TRIE BUILDING (Sharded Workers)                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  For large scale, multiple workers build trie shards in parallel:          ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │                                                                         │ ║
║  │  HDFS/S3 Aggregated Data                                               │ ║
║  │  ├── part-00000.parquet (a-f)  ──→  Worker 0 ──→  Trie DB Shard 0     │ ║
║  │  ├── part-00001.parquet (g-l)  ──→  Worker 1 ──→  Trie DB Shard 1     │ ║
║  │  ├── part-00002.parquet (m-r)  ──→  Worker 2 ──→  Trie DB Shard 2     │ ║
║  │  └── part-00003.parquet (s-z)  ──→  Worker 3 ──→  Trie DB Shard 3     │ ║
║  │                                                                         │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  Each worker:                                                               ║
║  • Reads ONE partition file (bulk download from S3)                        ║
║  • Filters by threshold                                                    ║
║  • Builds trie for its letter range                                       ║
║  • Writes to its Trie DB shard                                            ║
║                                                                               ║
║  Result: 4 workers build full trie 4x faster                               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### What if Trie is Too Large for Memory?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  HANDLING LARGE TRIES (Yes, this happens!)                                  ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  PROBLEM:                                                                   ║
║  • Google has billions of unique queries                                   ║
║  • Full trie might be 100s of GBs                                         ║
║  • Won't fit in single server memory                                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SOLUTION 1: FREQUENCY THRESHOLD (Most common)                             ║
║  ──────────────────────────────────────────────                            ║
║  • Only keep queries with frequency > threshold (e.g., > 100/week)        ║
║  • Prunes 99% of queries (long tail)                                      ║
║  • Keeps trie size manageable (~50-100MB)                                 ║
║                                                                               ║
║  Example:                                                                   ║
║  • 100M unique queries → after pruning → 1M queries                       ║
║  • 1M queries × 50 bytes = ~50MB (fits in memory!)                        ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SOLUTION 2: SHARDING (Already discussed)                                  ║
║  ─────────────────────────────────────────                                  ║
║  • Split trie across multiple servers                                      ║
║  • Each server holds part of the alphabet                                  ║
║  • Shard 0: a-f, Shard 1: g-l, etc.                                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SOLUTION 3: HYBRID (Trie Cache + Trie DB)                                 ║
║  ─────────────────────────────────────────                                  ║
║  • Keep POPULAR prefixes in cache (hot data)                              ║
║  • Less common prefixes → fetch from Trie DB on demand                    ║
║  • LRU eviction for cache                                                 ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │  Query: "xyz"                                                          │ ║
║  │                                                                         │ ║
║  │  1. Check Trie Cache → MISS (rare prefix)                              │ ║
║  │  2. Fetch from Trie DB → "xyz" → [(xyz corp, 15)]                     │ ║
║  │  3. Return to user                                                     │ ║
║  │  4. (Optional) Add to cache for next time                             │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SOLUTION 4: COMPRESSED TRIE (Radix Tree / Patricia Trie)                  ║
║  ─────────────────────────────────────────────────────────                  ║
║  • Merge single-child chains into one node                                ║
║  • "b" → "e" → "s" → "t" becomes "best" (single node)                    ║
║  • Reduces memory by 50-70%                                               ║
║                                                                               ║
║  Standard Trie:           Compressed (Radix):                              ║
║       root                     root                                         ║
║        │                        │                                           ║
║        b                       best                                         ║
║        │                                                                    ║
║        e                                                                    ║
║        │                                                                    ║
║        s                                                                    ║
║        │                                                                    ║
║        t                                                                    ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  REAL-WORLD EXAMPLE (Google):                                              ║
║  • Uses all 4 techniques                                                   ║
║  • Threshold: Only queries with significant volume                        ║
║  • Sharding: Across 1000s of servers                                      ║
║  • Hybrid: Hot prefixes in memory, cold in Bigtable                       ║
║  • Compressed: Optimized data structures                                  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

### Trie DB (Key-Value Format)

### Key-Value Storage (Figure 13-10)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TRIE DB STORAGE FORMAT                                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Instead of storing tree structure, store as KEY-VALUE:                     ║
║                                                                               ║
║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
║  │  Key (prefix)   Value (top-k suggestions)                               │ ║
║  │  ─────────────  ────────────────────────────────────────────────────── │ ║
║  │  "b"            [("best", 35), ("bet", 29), ("bee", 20), ("be", 15)]   │ ║
║  │  "be"           [("best", 35), ("bet", 29), ("bee", 20), ("be", 15)]   │ ║
║  │  "bee"          [("bee", 20), ("beer", 10)]                            │ ║
║  │  "beer"         [("beer", 10)]                                          │ ║
║  │  "bes"          [("best", 35)]                                          │ ║
║  │  "best"         [("best", 35)]                                          │ ║
║  │  ...                                                                    │ ║
║  └─────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  BENEFITS:                                                                  ║
║  • Direct O(1) lookup by prefix                                            ║
║  • Easy sharding (shard by first letter)                                   ║
║  • Cache-friendly (each prefix is independently cacheable)                 ║
║  • Can use Redis, DynamoDB, or Cassandra                                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 6. Sharding Strategy (Figure 13-15)

### Simple vs Smart Sharding

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  SHARDING APPROACHES                                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  APPROACH 1: SIMPLE (Even Split by Letter)                                  ║
║  ─────────────────────────────────────────                                  ║
║  Shard 0: a-f                                                               ║
║  Shard 1: g-l                                                               ║
║  Shard 2: m-r                                                               ║
║  Shard 3: s-z                                                               ║
║                                                                               ║
║  PROBLEM: Uneven load!                                                      ║
║  • 's' has 12% of queries (shopping, search, social...)                    ║
║  • 'x' has 0.5% of queries                                                 ║
║  • Shard 3 is overloaded!                                                  ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  APPROACH 2: SMART (Based on Query Distribution)                            ║
║  ───────────────────────────────────────────────                            ║
║  Analyze historical data → create balanced shards                           ║
║                                                                               ║
║  Shard 0: s (12% alone)                                                     ║
║  Shard 1: c, p (8% + 7% = 15%)                                             ║
║  Shard 2: a, b, m, t (6% + 6% + 5% + 5% = 22%)                            ║
║  Shard 3: d, f, h, l, r, w (rest)                                          ║
║  Shard 4: e, g, i, j, k, n, o, q, u, v, x, y, z (low frequency)           ║
║                                                                               ║
║  Each shard handles ~20% of queries!                                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Shard Map Manager

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  SHARD ROUTING (Figure 13-15)                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────┐   ① What shard?    ┌─────────────────┐                     ║
║  │ Web Servers │ ─────────────────► │ Shard Map       │                     ║
║  └──────┬──────┘                    │ Manager         │                     ║
║         │                           └─────────────────┘                     ║
║         │ ② Route to shard                                                  ║
║         ▼                                                                    ║
║  ┌─────────┬─────────┬─────────┬─────────┐                                  ║
║  │ Shard 0 │ Shard 1 │ Shard 2 │ Shard 3 │                                  ║
║  │   's'   │ 'c','p' │ 'a','b' │  rest   │                                  ║
║  └─────────┴─────────┴─────────┴─────────┘                                  ║
║                                                                               ║
║  Shard Map Manager stores:                                                  ║
║  { 's' → Shard 0, 'c' → Shard 1, 'p' → Shard 1, ... }                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 7. Trie Update Strategy (Figure 13-13)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  HOW TO UPDATE THE TRIE                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  OPTION 1: REAL-TIME UPDATE                                                 ║
║  ─────────────────────────────                                              ║
║  Every query → update frequency → update all ancestor caches                ║
║                                                                               ║
║  PROBLEM:                                                                   ║
║  • 24K QPS × update all ancestors = too slow!                              ║
║  • Trie becomes inconsistent during updates                                ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  OPTION 2: WEEKLY REBUILD (Recommended)                                     ║
║  ───────────────────────────────────────                                    ║
║                                                                               ║
║  1. Collect queries all week                                                ║
║  2. Aggregate frequencies                                                   ║
║  3. Build NEW trie (takes hours)                                           ║
║  4. Atomically swap: old trie → new trie                                   ║
║  5. Refresh cache                                                           ║
║                                                                               ║
║  BENEFITS:                                                                  ║
║  • No impact on read performance                                           ║
║  • Consistent data                                                         ║
║  • Can be done during off-peak hours                                       ║
║                                                                               ║
║  BEFORE:                          AFTER (beer: 10 → 30):                    ║
║  ┌─────────────────────┐          ┌─────────────────────┐                   ║
║  │ "be": [(best,35),   │          │ "be": [(best,35),   │                   ║
║  │        (bet,29),    │    →     │        (beer,30),   │                   ║
║  │        (bee,20),    │          │        (bet,29),    │                   ║
║  │        (beer,10)]   │          │        (bee,20)]    │                   ║
║  └─────────────────────┘          └─────────────────────┘                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 8. Database Choice Tradeoffs

### Why Redis/In-Memory for Trie Cache?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TRIE CACHE: WHY IN-MEMORY?                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  REQUIREMENT: < 100ms latency                                               ║
║                                                                               ║
║  Storage Option    Latency    Works?                                        ║
║  ───────────────   ─────────  ──────                                        ║
║  In-memory         ~1ms       ✓ Yes                                         ║
║  Redis             ~1-5ms     ✓ Yes                                         ║
║  MySQL             ~10-50ms   ✗ Borderline                                  ║
║  Disk-based        ~100ms+    ✗ No                                          ║
║                                                                               ║
║  SOLUTION: Redis cluster for Trie Cache                                     ║
║  • Fast enough for latency requirement                                      ║
║  • Shared across API servers                                                ║
║  • Easy to refresh from Trie DB                                            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Kafka for Analytics Logs?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  ANALYTICS LOGS: WHY KAFKA?                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  REQUIREMENTS:                                                              ║
║  • 2B log entries/day (23K writes/sec)                                     ║
║  • Durable (can't lose data)                                               ║
║  • Multiple consumers (aggregators, analytics, ML)                         ║
║                                                                               ║
║  WHY KAFKA:                                                                 ║
║  ✓ High throughput (millions/sec)                                          ║
║  ✓ Durable (writes to disk)                                                ║
║  ✓ Multiple consumer groups                                                ║
║  ✓ Replay capability (reprocess old data)                                  ║
║                                                                               ║
║  ALTERNATIVE: Kinesis (AWS managed)                                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 9. Interview Quick Answers

**Q: Do you log every keystroke for analytics?**
> "No! We only log when the user SELECTS a suggestion or SUBMITS the search. Logging keystrokes would create 20x more data and count incomplete/abandoned queries. We want to track what users actually wanted, not every character they typed. This gives a cleaner signal for ranking."

**Q: How do you partition the analytics Kafka topic?**
> "By query's first letter. This enables parallel aggregation - each consumer handles one letter range. 'best buy' goes to partition 'b', 'amazon' to partition 'a'. This is better than user_id partitioning because it aligns with how the trie is structured and sharded."

**Q: Where is the trie actually stored?**
> "The trie exists in two forms: (1) Trie DB - a key-value store where each prefix maps to its top-k suggestions. This is the persistent source of truth. (2) Trie Cache - either an in-memory tree or Redis cache for ultra-fast lookups. Weekly rebuilds generate the trie from aggregated data, then store to Trie DB, then refresh the cache."

**Q: What if the trie is too large to fit in memory?**
> "Four solutions: (1) Frequency threshold - only keep queries searched > 100 times/week, prunes 99% of long-tail queries. (2) Sharding - split trie by prefix across servers. (3) Hybrid approach - hot prefixes in cache, cold prefixes fetched from Trie DB on demand. (4) Compressed trie (Radix/Patricia) - merge single-child chains, reduces memory 50-70%."

**Q: How does the aggregation pipeline work?**
> "It's a classic MapReduce pattern in Spark/Flink: (1) Read from Kafka partitions in parallel, (2) Deduplicate - same user+query within 60s counts as 1, (3) Reduce - count by query within weekly window, (4) Time decay - reduce frequency for queries not searched recently, (5) Prune - remove queries below threshold. Output goes to HDFS/S3 for trie workers to consume."

**Q: How do Trie Workers get the aggregated data?**
> "Trie Workers do a BULK READ from HDFS/S3 - they download parquet/ORC files, not query a database. Each worker reads one partition file (e.g., queries a-f), filters by threshold BEFORE building the trie in memory, builds the trie, converts to KV format (prefix → top-k), and writes to Trie DB. This is a batch job, not real-time."

**Q: When is the frequency threshold applied?**
> "BEFORE building the trie in memory, not after. The worker reads aggregated data from S3, immediately filters out queries with frequency < threshold (e.g., < 100/week), then builds the trie. This is critical - we can't load 100M queries into memory. After filtering, we might have only 1M queries, which fits in ~50MB of memory."

**Q: How does autocomplete achieve < 100ms latency?**
> "Two key optimizations: (1) Top-k caching at each trie node - instead of DFS to find all words then sort, we pre-compute top-k at each node, so lookup is O(prefix length), (2) In-memory cache (Redis) - cache hit is ~1ms. Cache miss goes to Trie DB which is also key-value optimized."

**Q: How is the trie updated when search trends change?**
> "Weekly rebuild, not real-time. We collect queries all week in Kafka, aggregate with Spark/Flink (dedup, time decay), build a new trie offline, then atomically swap. Real-time updates would be too slow at 24K QPS and cause inconsistency."

**Q: How do you handle uneven load across letters (e.g., 's' vs 'x')?**
> "Smart sharding based on historical query distribution. Instead of evenly splitting a-z across shards, we analyze that 's' gets 12% of queries and give it its own shard, while low-frequency letters like 'x', 'y', 'z' share a shard. This balances load."

**Q: Why cache top-k at each node instead of computing on query?**
> "Without caching, for prefix 'a' we'd need to DFS through millions of words. With caching, we just return the pre-computed list - O(1). Trade-off is storage (more data per node), but storage is cheap, latency is critical."

**Q: How do you handle trending queries (e.g., 'olympics' during Olympics)?**
> "Time-weighted aggregation. Recent queries get higher weight. Also, for major events, we can trigger early trie rebuild (daily instead of weekly). Some systems have a 'trending' layer that bypasses the normal trie for hot topics."

**Q: What happens if a cache server crashes?**
> "Cache can be rebuilt from Trie DB (which is the source of truth). We use Redis cluster with replication. If primary fails, replica promotes. Worst case: cold cache, slightly higher latency until warmed up."

**Q: How do you prevent abuse (bot queries, spam)?**
> "Rate limiting per user (max 20 queries/sec). Also, we only log queries with 2+ characters to filter out noise. For the trie, we filter out queries with very low frequency (<10/week) to prevent spam pollution."

---

## 10. Visual Architecture Summary

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                    SEARCH AUTOCOMPLETE SYSTEM ARCHITECTURE                   │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────┐                                           ┌───────────┐         │
│  │  User   │◄──────────────────────────────────────────│    CDN    │         │
│  │ (types) │──┐                                        │ (static)  │         │
│  └─────────┘  │                                        └───────────┘         │
│               │                                                               │
│               ▼                                                               │
│        ┌─────────────┐                                                        │
│        │    Load     │                                                        │
│        │  Balancer   │                                                        │
│        └──────┬──────┘                                                        │
│               │                                                               │
│        ┌──────┴──────┐                                                        │
│        ▼             ▼                                                        │
│  ┌───────────┐ ┌───────────┐                                                  │
│  │ API Serv  │ │ API Serv  │  (Stateless, rate limiting)                     │
│  └─────┬─────┘ └─────┬─────┘                                                  │
│        │             │                                                        │
│        └──────┬──────┘                                                        │
│               │                                                               │
│               ▼                                                               │
│  ┌────────────────────────────────────────────────────────────────┐          │
│  │                    TRIE CACHE (Redis Cluster)                  │          │
│  │  Key: prefix → Value: top-k suggestions                       │          │
│  │  Cache hit: ~1ms                                               │          │
│  └─────────────────────────────┬──────────────────────────────────┘          │
│                                │ (cache miss)                                 │
│                                ▼                                              │
│  ┌────────────────────────────────────────────────────────────────┐          │
│  │                      TRIE DB (Sharded)                         │          │
│  │  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐       │          │
│  │  │ Shard 0  │  │ Shard 1  │  │ Shard 2  │  │ Shard 3  │       │          │
│  │  │   's'    │  │  'c','p' │  │ 'a','b'  │  │  rest    │       │          │
│  │  └──────────┘  └──────────┘  └──────────┘  └──────────┘       │          │
│  └────────────────────────────────────────────────────────────────┘          │
│                                ▲                                              │
│                                │ (weekly rebuild)                             │
│                                │                                              │
│  ┌─────────────────────────────┴──────────────────────────────────┐          │
│  │                    DATA PIPELINE                               │          │
│  │  Analytics → Aggregators → Workers → Trie Builder             │          │
│  │  (Kafka)     (Spark)                                          │          │
│  └────────────────────────────────────────────────────────────────┘          │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘
```

---

## 11. Scalability Strategies

| Dimension | Strategy | Notes |
|-----------|----------|-------|
| **Read scalability** | Redis cluster, multiple API servers | Add servers behind LB |
| **Storage scalability** | Shard by prefix first letter | Smart sharding for balance |
| **Write scalability** | Kafka partitions, parallel aggregators | Horizontal scaling |
| **Global users** | Geo-distributed caches | CDN for cache, regional Trie DB |
| **Popular prefixes** | Pre-warm cache, dedicated cache nodes | Hot-spot optimization |

---

## 12. Common Failure Scenarios

| Failure | Impact | Mitigation |
|---------|--------|------------|
| **Cache server crash** | Higher latency | Redis cluster, rebuild from DB |
| **Trie DB shard down** | Prefix range unavailable | Replicas per shard |
| **Aggregator fails** | Stale trie (uses last week's data) | Multiple aggregator instances |
| **Trie rebuild fails** | Continue using old trie | Rollback capability, alerting |
| **Kafka down** | No new query logging | Kafka cluster, local buffer |

