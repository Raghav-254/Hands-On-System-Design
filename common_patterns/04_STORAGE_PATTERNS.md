# Storage Patterns

> How to choose the right database and storage strategy for each piece of data.

---

## 1. SQL (PostgreSQL / MySQL)

**What**: Relational database with ACID transactions, SQL queries, joins, and strong consistency.

**When to use**: Structured data with relationships. Multi-table transactions needed. Complex queries (joins, aggregations). Data integrity is critical.
**When NOT to use**: Write-heavy (> 50K writes/sec). Unstructured or schema-less data. Time-series with high cardinality.

### Applied in our systems

| System | Data | Why SQL (not NoSQL) |
|--------|------|---------------------|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Expenses, balances, groups, settlements | ACID transactions across `expenses` + `expense_splits` + `balances` in one operation. Cassandra can't do multi-table transactions. Joins between groups and members. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Shows, seats, bookings, holds | `SELECT ... FOR UPDATE` for seat locking requires ACID. Seat availability = join `shows` with `show_seats`. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Trips, users, drivers | Trip state machine needs atomic conditional updates. Driver-rider relationship = FK. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Hotels, room types, reservations | Reservation = transaction (check availability + decrement + create booking atomically). |
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Payment orders, ledger entries, wallets | Double-entry ledger requires ACID: debit + credit in one transaction. Sum of ledger must always = 0. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | URL mappings, ID ranges | UNIQUE constraint on `short_code`. Range allocation needs `SELECT ... FOR UPDATE`. Simple, structured data. |

---

## 2. Wide-Column Store (Cassandra / DynamoDB)

**What**: Distributed NoSQL optimized for writes. Data modeled by partition key + clustering key. No joins, no multi-row transactions.

**When to use**: Write-heavy (> 50K writes/sec). Time-series or append-heavy. Query pattern is known and simple (get by partition key + time range).
**When NOT to use**: Complex queries, joins, multi-table transactions. Ad-hoc analytical queries.

### Applied in our systems

| System | Data | Why Cassandra (not SQL) |
|--------|------|------------------------|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Messages (partition: `channel_id`, cluster: `created_at`) | Append-heavy: millions of messages/day. Never updated. Query by channel + time range. PostgreSQL would struggle with write throughput. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Play history (partition: `user_id`, cluster: `played_at DESC`) | Write-heavy: millions of play events/day. Query: "what did user X play recently?" = partition key + clustering key scan. No joins needed. |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | Location history (partition: `user_id`, cluster: `timestamp`) | Continuous location updates (every 30s × millions of users). Time-series. 7-day TTL for auto-deletion. |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | Aggregated click counts (partition: `ad_id`, cluster: `timestamp DESC`) | High write throughput. Query: "clicks for ad X in last hour" = partition scan. TTL for retention. |
| [Distributed Email](../distributed_email_service/INTERVIEW_CHEATSHEET.md) | Emails (partition: `user_id`, cluster: `folder_id, email_id`) | Billions of emails. Partition by user for fast mailbox access. Bigtable/Cassandra handles petabyte-scale. |

**Key modeling rule**: Design Cassandra tables around **query patterns**, not entities. One table per query. Denormalization is expected.

---

## 3. Search (Elasticsearch)

**What**: Distributed search engine built on Lucene. Inverted index for full-text search. Supports fuzzy matching, field weighting, autocomplete, and aggregations.

**When to use**: Full-text search across large text fields. Fuzzy/typo-tolerant search. Log search. Autocomplete.
**When NOT to use**: Primary data store (no ACID, eventual consistency). Simple key-value lookups. Transactional writes.

### Applied in our systems

| System | What's Indexed | Why ES (not SQL LIKE) |
|--------|---------------|----------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Log entries (21 TB/day) — full-text on `message`, keyword on `service`, `level` | `SELECT * FROM logs WHERE message LIKE '%timeout%'` on 21 TB = impossible. ES inverted index finds matches in milliseconds. Time-based indices enable efficient retention (drop old index). |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Songs, artists, albums — fuzzy matching on names, popularity re-ranking | Search "Tylor Swft" → "Taylor Swift" (fuzziness). Field weighting: title match scores higher than album match. SQL can't do this without external tooling. |
| [Distributed Email](../distributed_email_service/INTERVIEW_CHEATSHEET.md) | Email body and subject — full-text search within user's mailbox | Users search "meeting notes from John" across thousands of emails. Cassandra can't do full-text search. ES is the secondary index. |

**Key insight**: Elasticsearch is a **secondary index**, not a source of truth. The source of truth is always the primary DB (PostgreSQL, Cassandra). ES is populated via CDC, outbox, or synchronous dual-write.

---

## 4. Blob / Object Storage (S3)

**What**: Store large binary files (audio, video, images, archives) as objects with metadata. Cheap, durable (11 nines), virtually unlimited capacity.

**When to use**: Files > 1 MB. Immutable content. Archival / cold storage. Content addressed by key.
**When NOT to use**: Structured queryable data. Frequent updates to the same object. Low-latency random reads.

### Applied in our systems

| System | What's Stored | Why S3 (not DB) |
|--------|--------------|-----------------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Audio files at multiple bitrates (128/256/320 kbps) | Songs are 5 MB+. Storing in PostgreSQL = BLOB column = terrible performance. S3 is designed for large immutable objects. CDN pulls from S3 origin. |
| [Video Streaming](../video_streaming_system/INTERVIEW_CHEATSHEET.md) | Transcoded video segments, original uploads | Videos are GBs. S3 handles multipart upload natively. Integrates with CDN. Pre-signed URLs for direct client upload (server never touches the file). |
| [Google Drive](../google_drive_system/INTERVIEW_CHEATSHEET.md) | File blocks (4 MB chunks, content-addressed by SHA-256 hash) | Deduplication: identical blocks across users stored once. S3 durability (11 nines). Delta sync: only upload/download changed blocks. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Archived log indices (cold tier, > 30 days) | S3 is 10x cheaper than Elasticsearch storage. Archived logs are rarely accessed but must be retained for compliance. Restore to ES if needed. |
| [S3 Object Storage](../s3_object_storage_system/INTERVIEW_CHEATSHEET.md) | Everything — this IS the S3 system | Metadata/data separation. Erasure coding (4+2) for durability with less overhead than 3x replication. |

---

## 5. Redis Sorted Set

**What**: Redis data structure that maps members to scores, maintained in sorted order. O(log N) for add, remove, rank lookup.

**When to use**: Real-time rankings, leaderboards, rate limiting (sliding window). Data that fits in memory.
**When NOT to use**: Data larger than available RAM. Complex queries requiring joins.

### Applied in our systems

| System | Use Case | Why Redis Sorted Set (not SQL) |
|--------|----------|-------------------------------|
| [Leaderboard](../realtime_leaderboard_system/INTERVIEW_CHEATSHEET.md) | Player rankings: `ZADD leaderboard score user_id` → `ZREVRANK user_id` = instant rank | SQL alternative: `SELECT COUNT(*) FROM scores WHERE score > ?` = O(N) full table scan per rank query. Redis sorted set = O(log N) via skip list. At 25M players, Redis returns rank in microseconds; SQL takes seconds. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Per-user feed cache: `ZADD feed:{user_id} timestamp post_id` | Feed must be sorted by time. Sorted set supports range queries (latest N posts) and trimming (`ZREMRANGEBYRANK` to cap at 800 entries). |

---

## 6. Hot / Warm / Cold Storage Tiering

**What**: Move data between storage tiers based on age and access frequency. Recent data on fast/expensive storage, old data on slow/cheap storage.

**When to use**: Time-series data with clear access patterns (90% of queries hit last 24h). Cost optimization at scale.
**When NOT to use**: Data accessed uniformly regardless of age.

### Applied in our systems

| System | Hot | Warm | Cold | Why |
|--------|-----|------|------|-----|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Today's ES index on SSD | 1-30 day indices on HDD | > 30 days archived to S3 | 90% of log queries hit last 24 hours. SSDs are expensive ($0.10/GB). HDDs are 5x cheaper. S3 is 10x cheaper. Elasticsearch ILM automates the transition. |
| [Video Streaming](../video_streaming_system/INTERVIEW_CHEATSHEET.md) | Popular videos on CDN edge | Less popular on CDN origin | Rarely watched on S3 only | View-count threshold determines CDN promotion. No point caching a video with 10 views on 200 edge servers. |

---

## 7. Event Sourcing

**What**: Store every state change as an immutable event. Current state = replay all events from the beginning (or from last snapshot). Never update or delete events.

**When to use**: Full audit trail is mandatory. Need to reproduce any past state. Very high write throughput (append-only is fastest).
**When NOT to use**: Simple CRUD. Systems where "current state" is the only thing that matters. Complex event schemas that change frequently.

### Applied in our systems

| System | What's Event-sourced | Why Event Sourcing (not traditional DB) |
|--------|---------------------|----------------------------------------|
| [Digital Wallet](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) | Every transfer, deposit, withdrawal = an immutable event in RocksDB | Financial system: regulators require full audit trail. "Show me every transaction that led to this balance" = replay events. Traditional DB overwrites state — no history. Also: append-only sequential writes to RocksDB are extremely fast (1M+ TPS). |

**Why most systems DON'T use event sourcing**: Complexity. You need snapshots (can't replay 1B events on startup), schema versioning for events, CQRS for reads. Only worth it when auditability + high TPS justify the overhead.

---

## 8. CQRS (Command Query Responsibility Segregation)

**What**: Use different models (and often different stores) for reads vs writes. Write model is optimized for correctness; read model is optimized for query speed.

**When to use**: Read and write patterns are fundamentally different. Write store is not efficient for read queries.
**When NOT to use**: Simple CRUD where the same table serves both reads and writes efficiently.

### Applied in our systems

| System | Write Model | Read Model | Why Separate |
|--------|------------|------------|--------------|
| [Digital Wallet](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) | Event Store (append-only log in RocksDB) | Query Store (materialized balances) | Events are the source of truth (auditability, replay). But "what's Alice's balance?" by replaying 100K events = too slow. A separate read-optimized store is derived from events. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Post DB (MySQL — single row per post) | Feed Cache (Redis — pre-computed list of post IDs per user) | Writing a post is simple (one row). Reading a feed requires merging posts from 500 followees — doing this join on every read is too slow. Pre-computed feed cache avoids the fan-in. |

---

## 9. Denormalization (Deliberate Data Duplication)

**What**: Store derived/pre-computed data alongside normalized data for faster reads. Accept the overhead of keeping both in sync.

**When to use**: Read-heavy. Computing the derived data on every read is too slow. All writes go through one service (sync is manageable).
**When NOT to use**: Write-heavy (sync cost outweighs read benefit). Multiple services writing to the same data (sync becomes hard).

### Applied in our systems

| System | What's Denormalized | Source of Truth | Why Duplicate |
|--------|--------------------|-----------------|--------------| 
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | `balances` table (pairwise balance summary) | `expenses` + `expense_splits` tables | Computing "Alice owes Bob $50" by scanning all expenses = O(N). Eagerly updated `balances` table gives O(1) read. Acceptable because all writes go through `ExpenseService`. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Pre-computed feed cache per user (Redis) | Posts table + follow graph | Fan-in on every read (merge 500 followees' posts, sort, rank) = seconds of latency. Pre-computed cache = milliseconds. Storage cost is worth it. |
| [Leaderboard](../realtime_leaderboard_system/INTERVIEW_CHEATSHEET.md) | Redis Sorted Set (ranking) | `point_history` in MySQL | Real-time rank lookup needs O(log N). MySQL `SELECT COUNT(*) WHERE score > X` = O(N) scan. Redis is the denormalized, purpose-built ranking store. |

**Theory**: [database_fundamentals/02_DATABASE_LOGIC.md](../database_fundamentals/02_DATABASE_LOGIC.md) — Normalization vs Denormalization

---

## Quick Decision Table: "Which DB for This Data?"

| Data Characteristics | Best Choice | Example |
|---------------------|-------------|---------|
| Structured, relationships, transactions | **PostgreSQL / MySQL** | Splitwise expenses, BookMyShow bookings |
| Write-heavy, time-series, no joins | **Cassandra** | Chat messages, Spotify plays, location history |
| Full-text search, fuzzy matching | **Elasticsearch** | Log search, Spotify song search |
| Large binary files, archival | **S3** | Audio files, videos, log archives |
| Real-time ranking, sorted data in memory | **Redis Sorted Set** | Leaderboard |
| Append-only events, full audit trail | **RocksDB / Event Store** | Digital Wallet |
| High-speed cache, ephemeral data | **Redis** (cache-aside) | URL Shortener, Splitwise balances |
| Key-value with simple access patterns | **DynamoDB / Redis** | Session store, feature flags |

**Thumb rule**: Start with PostgreSQL. Move to a specialized store only when PostgreSQL can't handle the specific requirement (write throughput, full-text search, file storage, etc.).
