# Scaling Patterns

> How to handle more reads, more writes, and more data than a single machine can manage.

---

## 1. Redis Caching (Cache-Aside)

**What**: Application checks Redis first. On miss, reads from DB, writes result to Redis with TTL. On write, invalidate (delete) the cache key.

**When to use**: Read-heavy workloads (read:write ratio > 10:1). Data that tolerates short staleness.
**When NOT to use**: Data that must be real-time accurate (e.g., seat availability during booking). Frequently updated data where invalidation cost > cache benefit.

### Applied in our systems

| System | Use Case | Why This Pattern (not the alternative) |
|--------|----------|----------------------------------------|
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | Cache `short_code → long_url` mappings | 100:1 read:write ratio. Cache-first redirect avoids DB hit on every click. LRU eviction because key space is huge but access follows power law (popular links get most clicks). |
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Cache group balances (`balances:group:{id}`) | Dashboard reads far exceed expense writes. 30s TTL as safety net. App-level `redis.delete()` on write — no CDC needed since all balance writes go through one service. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Cache search results by query string | Same queries repeated millions of times ("Taylor Swift"). Short TTL (60s) because search index updates frequently. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Seat availability for hot shows | 100K concurrent viewers. Redis gate + Lua script to check availability atomically without hitting DB. Short TTL (< 5s) because accuracy matters here. |
| [Proximity Service](../proximity_service/INTERVIEW_CHEATSHEET.md) | Geohash cell → list of businesses | Businesses don't change often. Cache entire geohash cells for 5 min. Lazy repopulation on miss. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Room availability per hotel | Display-only cache. Stale data acceptable for browsing. DB is always checked at booking time. |

**Theory**: [database_fundamentals/05_ARCHITECTURAL_MAPPING.md](../database_fundamentals/05_ARCHITECTURAL_MAPPING.md) — Redis patterns

---

## 2. CDN (Content Delivery Network)

**What**: Cache static/semi-static content at edge servers close to users. Reduces latency and offloads origin servers.

**When to use**: Large files (audio, video, images, tiles), content accessed globally.
**When NOT to use**: Dynamic, personalized content. Small JSON API responses (API Gateway handles those).

### Applied in our systems

| System | What's Cached at CDN | Why CDN (not origin server) |
|--------|---------------------|----------------------------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Audio files (5MB+ per song) at multiple bitrates | 100M users streaming simultaneously. Can't proxy through API servers — bandwidth would be 1 Tbps. CDN edge = lower latency + offload. Pre-signed URLs for auth. |
| [Video Streaming](../video_streaming_system/INTERVIEW_CHEATSHEET.md) | Transcoded video segments (.ts files) | Videos are GBs. Origin can't serve 1M concurrent viewers. CDN handles 99% of traffic. Multi-CDN for resilience. |
| [Google Maps](../google_maps_system/INTERVIEW_CHEATSHEET.md) | Map tiles (zoom/x/y grid) | Billions of tile requests/day. Tiles are static until map data changes (rare). CDN with 200+ edge locations. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Images and video thumbnails attached to posts | Media is large and immutable once uploaded. CDN keeps API servers focused on feed logic. |

**Key detail**: CDN + Pre-signed URLs is covered in [05_REALTIME_AND_API.md](05_REALTIME_AND_API.md).

---

## 3. Sharding

**What**: Split data across multiple DB instances by a shard key. Each shard holds a subset of data.

**When to use**: Single DB can't handle the write throughput or data volume.
**When NOT to use**: Small datasets. Data that requires frequent cross-shard joins or transactions.

### Applied in our systems

| System | Shard Key | Why This Key (not another) |
|--------|-----------|----------------------------|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | `group_id` | All operations (add expense, settle, read balances) are scoped to one group. No cross-group transactions. Every query includes `group_id`. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | `show_id` | All seat operations are per-show. A hot show stays on one shard — handled via Redis gate, not cross-shard distribution. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | `hash(short_code)` | Uniform distribution across shards. Reads are by `short_code` (the hot path). No need for range queries. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | `service_id` (Kafka), time-based (ES) | Kafka: ordering per service matters for alerting. ES: time-based indices enable efficient retention (drop entire old index). |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Region (Redis for locations), `ride_id` (Trip DB) | Location queries are regional. Trip data is accessed by `ride_id`. Different data, different shard keys. |
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | `hash(channel_id)` (Kafka), `channel_id` (Cassandra) | All messages in a channel go to one partition = ordering guaranteed. Channel is the natural access pattern. |
| [Autocomplete](../autocomplete_system/INTERVIEW_CHEATSHEET.md) | By query distribution (smart sharding) | Not alphabetical (letter 'a' would be 10x larger than 'x'). Shard by traffic volume to balance load. |
| [Leaderboard](../realtime_leaderboard_system/INTERVIEW_CHEATSHEET.md) | By score range or hash | Score-range sharding: each shard owns a score band. Global rank = sum offsets from higher shards. Hash sharding: scatter-gather for top-N. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | `hotel_id` | Bookings are always for one hotel. No cross-hotel transactions. Consistent hashing for even distribution. |

**Thumb rule for choosing shard key**: Pick the entity that ALL your queries include. If every query has `WHERE group_id = X`, shard by `group_id`.

**Theory**: [database_fundamentals/03_DISTRIBUTED_SYSTEMS.md](../database_fundamentals/03_DISTRIBUTED_SYSTEMS.md) — Sharding strategies

---

## 4. Read Replicas

**What**: Replicate the primary DB to read-only copies. Reads go to replicas, writes go to primary.

**When to use**: Read-heavy workloads where slight replication lag is acceptable.
**When NOT to use**: Write-heavy workloads. Use cases requiring read-after-write consistency (user creates then immediately reads).

### Applied in our systems

| System | Why Read Replicas | Replication Lag Acceptable? |
|--------|------------------|-----------------------------|
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | 100:1 read:write. Redirects are reads. | Yes — a new short URL being invisible for 1-2 seconds is fine. |
| [Proximity Service](../proximity_service/INTERVIEW_CHEATSHEET.md) | Business searches are reads. Business updates are rare. | Yes — a new restaurant appearing 5 seconds late is fine. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Song metadata reads far exceed writes (new song uploads). | Yes — a newly uploaded song being undiscoverable for a few seconds is fine. |

---

## 5. Write Scaling

**What**: When writes exceed what a single DB can handle, distribute writes across partitions or use write-optimized storage.

**When to use**: High write throughput (> 10K writes/sec), append-heavy workloads.
**When NOT to use**: Low write volume where a single MySQL instance suffices.

### Applied in our systems

| System | Technique | Why This Technique |
|--------|-----------|-------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Kafka partitions (distribute across brokers) + ES bulk indexing | 500K log lines/sec. No single node can handle this. Kafka partitions parallelize ingestion. ES bulk API (1000 docs/batch) amortizes indexing overhead. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Cassandra (LSM-tree, partition by `user_id`) | Millions of play events/day. Cassandra's LSM-tree is write-optimized: sequential disk writes, no random I/O. |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | Kafka + Cassandra (partition by `ad_id`, cluster by timestamp) | Billions of click events. Cassandra handles append-heavy, time-series writes. Pre-aggregation reduces write volume. |
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Cassandra (partition by `channel_id`, cluster by `created_at`) | Messages are append-only, never updated. Cassandra's LSM-tree handles sequential writes at scale. |

---

## 6. Fan-out (Write vs Read vs Hybrid)

**What**: When one event needs to reach many consumers (e.g., a post reaching all followers), you can either pre-compute (fan-out on write) or compute at read time (fan-out on read).

**When to use fan-out on write**: Consumer count is bounded and small (< 10K). Read latency must be low.
**When to use fan-out on read**: Consumer count is huge or unbounded (celebrities). Write amplification is unacceptable.
**When to use hybrid**: Mix of both — most users fan-out on write, celebrities fan-out on read.

### Applied in our systems

| System | Strategy | Why This Strategy (not the alternative) |
|--------|----------|----------------------------------------|
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | **Hybrid**: fan-out on write for users with < 10K followers; fan-out on read for celebrities | Pure fan-out on write: a celebrity with 10M followers = 10M Redis writes per post (minutes of lag, write amplification). Hybrid: most users are pre-computed (fast reads), celebrities are merged at read time. |
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | **Fan-out on write** to group members | Group size is bounded (< 500 members). Fan-out on write means each message goes to at most 500 caches — manageable. Read time should be instant (messaging UX). |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | **Fan-out on write** via Redis Pub/Sub | When Alice updates her location, publish to her channel. All friends subscribed to that channel receive it immediately. Friend count is bounded (< 5K). |

**The celebrity problem**: The reason News Feed uses hybrid and not pure fan-out on write. If Taylor Swift has 10M followers, fan-out on write = 10M cache writes per post. That's the "thundering herd" at write time.

---

## 7. Geospatial Indexing

**What**: Convert 2D coordinates (lat/lng) into a 1D index that supports efficient "find things nearby" queries.

**When to use**: Any "find nearby" feature — drivers, restaurants, friends, businesses.
**When NOT to use**: Non-location data. Exact-match lookups (just use a hash index).

### Applied in our systems

| System | Technique | Why This Technique |
|--------|-----------|-------------------|
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Redis GEO (`GEOADD`, `GEORADIUS`) — Geohash under the hood | Find available drivers within 5 km in real-time. Redis GEO gives O(log N + M) radius queries (N = total, M = results). In-memory = sub-millisecond. Geohash turns 2D location into 1D string for Redis sorted set. |
| [Proximity Service](../proximity_service/INTERVIEW_CHEATSHEET.md) | Geohash prefix matching + neighbor cells | Find businesses near a lat/lng. Geohash prefix = spatial proximity: longer prefix = smaller area. Search center cell + 8 neighbors to handle edge cases at cell boundaries. |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | Geohash for filtering, Haversine for precision | Filter by geohash prefix (fast), then compute exact distance with Haversine formula (accurate). Two-step: coarse filter + precise check. |
| [Google Maps](../google_maps_system/INTERVIEW_CHEATSHEET.md) | Routing tiles (pre-computed graph partitions) | Can't run Dijkstra on the entire road graph per request. Pre-partition into tiles. Load only relevant tiles for a route. Hierarchical routing: Level 0 (local roads) → Level 2 (highways) for long routes. |

**Key insight**: Geohash = 2D → 1D. A geohash like `9q8yyk` represents a small rectangle. Shared prefix = spatial proximity. `9q8yy` (5 chars) ≈ 5 km² area. `9q8yyk` (6 chars) ≈ 1 km² area.

**Theory**: [database_fundamentals/05_ARCHITECTURAL_MAPPING.md](../database_fundamentals/05_ARCHITECTURAL_MAPPING.md) — Spatial indexing

---

## Quick Decision Table

| Problem | Pattern | Example |
|---------|---------|---------|
| Reads are slow / DB overloaded | Redis cache (cache-aside) | URL Shortener, Splitwise |
| Large files need low-latency global delivery | CDN + pre-signed URLs | Spotify, Video Streaming |
| Single DB can't handle data volume | Shard by access pattern entity | Splitwise (group_id), BookMyShow (show_id) |
| Reads >> writes (100:1+) | Read replicas | URL Shortener, Proximity |
| Writes >> reads or append-heavy | Cassandra / Kafka partitions | Chat, Spotify play history |
| One event must reach many consumers | Fan-out (write for bounded, hybrid for celebrities) | News Feed (hybrid), Chat (write) |
| "Find things nearby" | Geospatial index (Geohash, Redis GEO) | Uber, Proximity |
