# 🔗 URL Shortener - Interview Cheatsheet

> Design a system like bit.ly: given a long URL, return a short alias; redirect users to the original URL when they open the short link.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **API Gateway** | Route requests, auth, rate limit | TLS termination; rate limit creates (prevent abuse); pass-through for redirects |
| **URL Service** | Create short URL (write path) | Validates input, generates or accepts short code, stores mapping, returns short URL |
| **Redirect Service** | Resolve short URL (read path) | Cache-first; on miss → DB; 301 or 302 redirect to long URL |
| **Key Generator (Range Allocator)** | Produce unique short codes | Pre-allocate ID ranges to each instance; encode to base62; no collision |
| **URL DB (MySQL/PostgreSQL)** | Persist short_code → long_url | Source of truth; shard by short_code hash if needed |
| **Cache (Redis)** | Speed up redirects | short_code → long_url; high hit ratio (80%+); LRU eviction |
| **Analytics Service** | Track clicks (count, country, device) | Async via Kafka; doesn't block redirect path |
| **Kafka** | Event bus for async side effects | Redirect events → Analytics; decouples read path from analytics write |

---

## The Story: Building a URL Shortener

Users submit long URLs and get back short links (e.g. `short.com/abc12`). When someone clicks the short link, we redirect them to the original URL. The system is **read-heavy**: billions of redirects per day, millions of creates. The design focuses on fast redirects (cache-first), scalable key generation (no collision, no bottleneck), and reliable analytics (async via Kafka). Staff-level depth means we go into key generation strategies with trade-offs, cache consistency, failure handling, and scale decisions.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Create short URL**: Input long URL; output short URL (e.g. 6–7 character code). Optional: custom alias (user picks the code).
- **Redirect**: GET short URL → redirect user to original long URL (HTTP 301 or 302).
- **Analytics** (optional): Click count, country, device, referrer — per short URL.
- **Expiry** (optional): Short URL expires after a date; redirect returns 410 Gone.

### Non-Functional Requirements

- **Scale**: Billions of redirects (reads) per day; millions of creates (writes) per day — **100:1 read-heavy**.
- **Latency**: Redirect must be very fast (single-digit ms); create can be a bit slower (~50ms).
- **Uniqueness**: Every short code must be unique; custom alias must be unique if provided.
- **Availability**: Redirect path must be highly available (every ms matters; revenue depends on redirects working).
- **Durability**: URL mappings must not be lost (user shared the short link everywhere).

### Scope (What We're Not Covering)

- User accounts and dashboard (manage my links) — mention briefly; separate CRUD service.
- Link previews / unfurling (Open Graph) — separate metadata service.
- Spam/malware detection — mention as a filter in the write path; separate ML service.

---

## 2. Back-of-the-Envelope Estimation

```
┌────────────────────────────────────────────────────────────────────────┐
│  Creates per day:        100 million new short URLs                     │
│  → Creates/sec:          100M / 86,400 ≈ 1,200 writes/sec             │
│  → Peak (5×):            ~6,000 writes/sec                             │
│                                                                        │
│  Redirects per day:      10 billion                                    │
│  → Redirects/sec:        10B / 86,400 ≈ 120,000 reads/sec             │
│  → Peak (5×):            ~600,000 reads/sec                            │
│                                                                        │
│  Read:Write ratio:       100:1 (read-heavy)                            │
│                                                                        │
│  Storage (5 years):      100M/day × 365 × 5 = 182B records            │
│                          × 500 bytes avg = ~91 TB                      │
│  (with short_code index: +50% = ~136 TB total)                         │
│                                                                        │
│  Cache (20% of URLs = 80% of traffic):                                 │
│  182B × 20% = 36B entries × 500 bytes = ~18 TB cache                  │
│  (In practice: most redirects hit recent/popular URLs;                 │
│   even 100 GB cache covers the hot set with 80%+ hit rate)             │
│                                                                        │
│  Key space: base62, 7 chars → 62^7 = 3.5 trillion combinations       │
│  182B URLs over 5 years → 5% of key space. Plenty of room.            │
└────────────────────────────────────────────────────────────────────────┘
```

**Key insights**: Redirect latency and read throughput dominate. Cache is critical — most traffic is served from Redis. Write volume (1,200/sec) is well within a single DB's capacity; sharding is for storage, not write throughput. 7-character base62 gives us 3.5 trillion codes — more than enough for decades.

---

## 3. Core Challenge: Key Generation

Short code must be **unique** and **compact** (7 chars). Character set: **base62** (a–z, A–Z, 0–9) → 62^7 = 3.5 trillion combinations.

This is the single most interesting design decision in the interview. Three approaches:

### Option A: Random + Collision Check

```
URL Service receives create request
   │
   ▼
Generate random 7-char base62 string (e.g. "aB3x9Kp")
   │
   ▼
SELECT * FROM url_mappings WHERE short_code = 'aB3x9Kp'
   │
   ├── Not found → INSERT. Done.
   └── Found → Generate new random string, try again.
```

| Pros | Cons |
|------|------|
| Simplest to implement | Collision probability grows with data volume |
| No central bottleneck | Retry loop adds latency under high load |
| Codes look random (unpredictable) | At 182B URLs, ~5% collision rate (with 7-char base62) |

### Option B: Single DB Sequence (auto-increment)

```
URL Service receives create request
   │
   ▼
INSERT INTO url_mappings (long_url, ...) → auto-increment ID = 987654321
   │
   ▼
Encode 987654321 to base62 → "Zk8P3" (short code)
   │
   ▼
UPDATE url_mappings SET short_code = 'Zk8P3' WHERE id = 987654321
```

| Pros | Cons |
|------|------|
| Zero collisions | Single DB = write bottleneck at scale |
| Sequential codes (predictable — someone can enumerate) | Codes are guessable (security concern) |
| Simple | Single point of failure |

### Option C: Range-Based Allocation (Our Choice)

```
                    ┌──────────────────────┐
                    │   Range Allocator    │
                    │   (ZooKeeper / DB)   │
                    │                      │
                    │  Next range: 5M+1    │
                    └───┬──────────┬───────┘
                        │          │
               allocate 1M         allocate 1M
                  range              range
                        │          │
                ┌───────▼──┐  ┌───▼──────────┐
                │ Instance A│  │ Instance B   │
                │           │  │              │
                │ Range:    │  │ Range:        │
                │ 1 – 1M   │  │ 1M+1 – 2M    │
                │           │  │              │
                │ Next ID:  │  │ Next ID:      │
                │ counter++ │  │ counter++     │
                │           │  │              │
                │ base62(ID)│  │ base62(ID)    │
                │ = code    │  │ = code        │
                └───────────┘  └──────────────┘
```

Each app instance gets a **range** of IDs (e.g. 1M IDs). It increments a local counter within its range — no DB call, no collision, no coordination. When the range is exhausted, it requests a new range from the allocator.

| Pros | Cons |
|------|------|
| Zero collisions | Range Allocator is a dependency (but called infrequently) |
| No single-writer bottleneck | If instance crashes, unused IDs in its range are wasted (acceptable) |
| Local counter = no DB call per create | Slightly more complex |
| Scales horizontally | Codes are sequential within a range (can shuffle if needed) |

**Range Allocator**: A simple service backed by ZooKeeper, etcd, or a single DB row (`SELECT ... FOR UPDATE` → increment by range_size). Called once per million creates — not a bottleneck. If it's down, instances use their remaining range; request new range when it recovers.

| Approach | Collision | Scalability | Predictable codes? | Complexity |
|----------|-----------|-------------|-------------------|------------|
| Random + retry | Possible | Good | No (random) | Low |
| Single sequence | None | Bottleneck | Yes (guessable) | Low |
| **Range allocation** | **None** | **High** | Partially (shuffle) | Medium |

> **In the interview**: "We use range-based allocation. Each instance gets a block of 1 million IDs from a central allocator (backed by ZooKeeper or a DB row with atomic increment). Locally, it increments a counter and encodes to base62. No collision, no per-create coordination, scales horizontally. If an instance crashes, at most 1M IDs are wasted — trivial vs. 3.5 trillion key space."

---

## 4. API Design

### Create Short URL

```
POST /v1/shorten
Content-Type: application/json
Idempotency-Key: <client-generated UUID>

Body: {
  "long_url": "https://www.example.com/very/long/path?query=value",
  "custom_alias": "mylink",       // optional — user picks the code
  "expires_at": "2026-12-31"      // optional
}

Response: 201 Created
{
  "short_url": "https://short.com/aB3x9Kp",
  "short_code": "aB3x9Kp",
  "long_url": "https://www.example.com/...",
  "created_at": "2026-02-15T10:00:00Z",
  "expires_at": null
}
```

- If `custom_alias` is provided, use it (check uniqueness). If taken → 409 Conflict.
- If not provided, generate a new unique short code via Range Allocator → base62.
- **Idempotency**: Same `Idempotency-Key` → return same short URL. Prevents duplicate codes on retry.
- **Rate limit**: Per API key; prevent abuse (bulk creation by scrapers/spammers).
- **Validation**: Reject malformed URLs, blocklisted domains, URLs that are already short.

### Redirect (Open Short URL)

```
GET /:shortCode
  e.g. GET /aB3x9Kp

Response: 302 Found (or 301 Moved Permanently)
Location: https://www.example.com/very/long/path?query=value

(No response body needed. Browser follows redirect.)
```

- **302** (temporary): Every click hits our server → we can count clicks and change the long URL later.
- **301** (permanent): Browser caches redirect → fewer hits to our server, but we lose per-click analytics.
- If short code not found → 404 Not Found.
- If expired → 410 Gone.

### Analytics (Optional)

```
GET /v1/analytics/:shortCode

Response: 200 OK
{
  "short_code": "aB3x9Kp",
  "total_clicks": 45231,
  "clicks_today": 1204,
  "top_countries": [{"US": 22000}, {"IN": 8500}, {"UK": 5100}],
  "top_referrers": [{"twitter.com": 15000}, {"direct": 12000}]
}
```

Analytics is a **read** on pre-aggregated data. Not on the redirect critical path.

---

## 5. Data Model

### Table: url_mappings (Source of Truth)

```
┌────────────┬──────────────────────┬──────────┬────────────┬────────────┬───────────────────┐
│ short_code │ long_url             │ user_id  │ created_at │ expires_at │ idempotency_key   │
│ (PK)       │ (NOT NULL)           │ (opt.)   │            │ (opt.)     │ (UNIQUE, opt.)    │
├────────────┼──────────────────────┼──────────┼────────────┼────────────┼───────────────────┤
│ aB3x9Kp    │ https://example.com  │ u_123    │ 2026-01-01 │ NULL       │ idem_abc          │
│ mylink     │ https://other.com    │ u_456    │ 2026-01-02 │ 2027-01-02 │ idem_xyz          │
└────────────┴──────────────────────┴──────────┴────────────┴────────────┴───────────────────┘
```

- **PK on short_code**: Used for redirect lookup (fast hash lookup); also enforces uniqueness.
- **Unique index on idempotency_key**: Prevents duplicate creates from retries.
- Optional: index on `long_url` hash if we support "get-or-create" (same long URL → return existing short code).
- Optional: index on `user_id` for "list my links."

### Table: click_events (Analytics — Write-Heavy)

```
┌──────────┬────────────┬─────────┬─────────┬──────────┬───────────┐
│ event_id │ short_code │ clicked │ country │ device   │ referrer  │
│ (PK)     │ (FK)       │ _at     │         │          │           │
├──────────┼────────────┼─────────┼─────────┼──────────┼───────────┤
│ 1        │ aB3x9Kp    │ ...     │ US      │ mobile   │ twitter   │
│ 2        │ aB3x9Kp    │ ...     │ IN      │ desktop  │ direct    │
└──────────┴────────────┴─────────┴─────────┴──────────┴───────────┘
```

- NOT on the redirect critical path. Written async by Analytics Service (consuming from Kafka).
- Can be stored in a columnar store (ClickHouse, BigQuery) for fast aggregation.
- Or: pre-aggregate counters (short_code → daily count) and skip raw events.

### Table: id_ranges (Range Allocator)

```
┌──────────────┬────────────────┐
│ next_range   │ range_size     │
│ (single row) │                │
├──────────────┼────────────────┤
│ 5000001      │ 1000000        │
└──────────────┴────────────────┘
```

- Atomic: `SELECT next_range FOR UPDATE; UPDATE next_range = next_range + range_size;`
- Called once per million creates per instance — not a bottleneck.

---

## 6. High-Level System Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  Web/Mobile  │ ① POST /v1/shorten                      │  Browser     │                │
│  │  (create)    │                                          │  (redirect)  │                │
│  │              │                                          │ ② GET /abc12 │                │
│  └──────┬───────┘                                          └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│           Auth, rate limit (creates), TLS, route by path                                   │
└─────────┬────────────────────────────────────────────────────────┬────────────────────────┘
          │                                                        │
   ┌──────▼───────┐                                       ┌───────▼──────────┐
   │ URL Service   │                                       │ Redirect Service │
   │ (write path)  │                                       │ (read path)      │
   │               │                                       │                  │
   │ ③ validate    │                                       │ ⑦ check cache    │
   │ ④ get code    │                                       │ ⑧ cache miss →   │
   │ ⑤ write DB    │                                       │    read DB       │
   │ ⑥ return      │                                       │ ⑨ set cache      │
   │   short_url   │                                       │ ⑩ return 302     │
   └───┬──────┬────┘                                       │ ⑪ publish event  │
       │      │                                            └───┬─────────┬───┘
       │      │                                                │         │
       │      │ ⑤ write                                        │         │ ⑪ publish
       │      ▼                                                │         ▼
       │  ┌──────────────┐                                     │   ┌──────────────┐
       │  │  URL DB      │ ⑧ cache miss                       │   │    KAFKA      │
       │  │  (MySQL/PG)  │◄────────────────────────────────────┘   │              │
       │  │              │                                         │ topic:        │
       │  │  url_mappings│     ⑦ cache hit                        │ redirect-     │
       │  │  id_ranges   │         │                               │ events       │
       │  └──────────────┘         │                               └──────┬───────┘
       │                           │                                      │
       │  ④ get range              │                                      │
       ▼                           ▼                                      ▼
  ┌──────────────┐        ┌────────────────┐                      ┌────────────┐
  │Range Allocator│        │ Redis Cache    │                      │ Analytics  │
  │(ZK / DB row) │        │ (Cluster)      │                      │ Service    │
  │               │        │                │                      │            │
  │ next_range    │        │ short_code →   │                      │ aggregate  │
  │ atomic incr   │        │ long_url       │                      │ clicks,    │
  └──────────────┘        │ TTL / LRU      │                      │ country,   │
                          └────────────────┘                      │ device     │
                                                                  └────────────┘
```

### What Does Each Component Do?

| Component | Responsibility | Why Separate? |
|-----------|---------------|---------------|
| **API Gateway** | Auth, rate limit, TLS, routing | Standard edge layer; protect backend from abuse |
| **URL Service** | Create short URL (write path) | Validates URL, calls Range Allocator, writes DB |
| **Redirect Service** | Resolve short code (read path) | Cache-first lookup; returns 302. Optimized for latency. |
| **Range Allocator** | Allocate ID ranges to URL Service instances | Prevents collision without per-create coordination |
| **URL DB** | Source of truth: short_code → long_url | Durable storage; handles cache misses |
| **Redis Cache** | In-memory lookup: short_code → long_url | 80%+ of redirects served from cache; sub-ms latency |
| **Kafka** | Async event bus for redirect events | Redirect Service doesn't block on analytics write |
| **Analytics Service** | Consume redirect events, aggregate stats | Separate concern; different write pattern (append-heavy) |

---

## 7. Sync vs. Async Communication

| Caller → Callee | Protocol | Sync / Async | Why |
|-----------------|----------|-------------|-----|
| Client → URL Service (create) | HTTP POST | **Sync** | User is waiting for the short URL |
| URL Service → Range Allocator | HTTP / ZK call | **Sync** (but rare — once per 1M creates) | Need ID before generating code |
| URL Service → URL DB (INSERT) | SQL | **Sync** | Must confirm write before returning short_url |
| Client → Redirect Service | HTTP GET | **Sync** | User is waiting for redirect (needs to be fast) |
| Redirect Service → Redis | Redis GET | **Sync** | Cache lookup on critical path (~0.5ms) |
| Redirect Service → URL DB | SQL SELECT | **Sync** | Cache miss path; user is waiting |
| Redirect Service → Kafka | Kafka PRODUCE | **Async (fire-and-forget)** | Analytics; redirect returns immediately, event processed later |
| Kafka → Analytics Service | Kafka CONSUME | **Async** | Aggregate clicks at its own pace; doesn't block redirect |

**Design principle**: The redirect path is latency-critical. Everything on it is sync except analytics (async via Kafka). The create path is less latency-sensitive; sync DB write is fine at 1,200/sec.

---

## 8. Create Short URL Flow (End-to-End)

```
Client                  URL Service              Range Allocator       URL DB
  │                         │                         │                   │
  │ ① POST /v1/shorten      │                         │                   │
  │ (long_url, idem_key)    │                         │                   │
  │─────────────────────────→                         │                   │
  │                         │                         │                   │
  │                    ② Validate URL                 │                   │
  │                    (format, blocklist)             │                   │
  │                         │                         │                   │
  │                    ③ Check idempotency key         │                   │
  │                    (SELECT WHERE idem_key=?)       │                   │
  │                    → if found, return existing     │                   │
  │                         │                         │                   │
  │                    ④ Custom alias?                 │                   │
  │                    → YES: check uniqueness         │                   │
  │                    → NO: get next ID from range    │                   │
  │                         │                         │                   │
  │                         │ (if range exhausted)    │                   │
  │                         │────── allocate range ───→                   │
  │                         │◄───── [5M+1, 6M] ──────│                   │
  │                         │                         │                   │
  │                    ⑤ Encode ID to base62           │                   │
  │                    (e.g. 5000001 → "aB3x9Kp")     │                   │
  │                         │                         │                   │
  │                         │────── INSERT ───────────────────────────────→
  │                         │◄───── OK ──────────────────────────────────│
  │                         │                         │                   │
  │ ⑥ 201 Created           │                         │                   │
  │ {short_url, short_code} │                         │                   │
  │◄─────────────────────────                         │                   │
```

| Step | Detail |
|------|--------|
| ① | Client sends long URL + optional custom alias + idempotency key |
| ② | Reject malformed URLs, blocklisted domains (phishing, spam) |
| ③ | Idempotency: same key → return previously created short URL. Prevents duplicates on retry |
| ④ | Custom alias: check DB for uniqueness (409 if taken). Auto-generated: increment local counter, encode to base62 |
| ⑤ | Base62 encoding: `0-9, a-z, A-Z` → 62 characters. 7 chars = 3.5T codes |
| ⑥ | Return short URL to client |

---

## 9. Redirect Flow (End-to-End)

```
Browser               Redirect Service             Redis Cache          URL DB          Kafka
  │                         │                          │                   │               │
  │ ① GET /aB3x9Kp          │                          │                   │               │
  │─────────────────────────→                          │                   │               │
  │                         │                          │                   │               │
  │                    ② GET short_code                │                   │               │
  │                         │──────────────────────────→                   │               │
  │                         │                          │                   │               │
  │                         │◄── HIT: long_url ────────│                   │               │
  │                         │   (or MISS)              │                   │               │
  │                         │                          │                   │               │
  │              ③ (on MISS only)                      │                   │               │
  │                         │──────────── SELECT ──────────────────────────→               │
  │                         │◄─────────── long_url ────────────────────────│               │
  │                         │                          │                   │               │
  │                    ④ SET cache                     │                   │               │
  │                         │──────────────────────────→                   │               │
  │                         │                          │                   │               │
  │                    ⑤ Check expiry                  │                   │               │
  │                    (expires_at < NOW → 410 Gone)   │                   │               │
  │                         │                          │                   │               │
  │ ⑥ 302 Found             │                          │                   │               │
  │ Location: long_url      │                          │                   │               │
  │◄─────────────────────────                          │                   │               │
  │                         │                          │                   │               │
  │                    ⑦ Publish redirect event (async, fire-and-forget)   │               │
  │                         │──────────────────────────────────────────────────────────────→
  │                         │                          │                   │               │
```

| Step | Detail |
|------|--------|
| ① | Browser hits `short.com/aB3x9Kp` |
| ② | Redis lookup: O(1), ~0.5ms. **80%+ of traffic ends here** (cache hit → skip DB) |
| ③ | Cache miss: query URL DB. ~2-5ms. Store result in cache for next time. |
| ④ | SET in Redis with TTL (e.g. 24h or indefinite for permanent links). LRU eviction handles memory. |
| ⑤ | If link has `expires_at` and it's past → return 410 Gone, remove from cache |
| ⑥ | Return 302 (or 301) with `Location` header. Browser follows redirect. **Total: 1-5ms** |
| ⑦ | Fire-and-forget: publish `{short_code, timestamp, IP, user-agent, referrer}` to Kafka. Analytics Service consumes and aggregates. Does NOT block the redirect response. |

---

## 10. Redirect: 301 vs. 302

| Code | Meaning | Browser Behavior | Use Case |
|------|---------|-----------------|----------|
| **301** | Moved Permanently | Caches redirect; may not hit our server again | Don't need per-click analytics; want to reduce server load |
| **302** | Found (Temporary) | Does NOT cache; every click hits our server | Need click analytics; may change the long_url later |

For **analytics** (click count, country, device): use **302**. For **reducing load** and never changing long_url: **301** is fine.

> **In the interview**: "We default to 302 because we want per-click analytics and the ability to update the long_url later (e.g. A/B testing). If the user explicitly requests permanent and we don't need analytics, we can use 301."

---

## 11. Cache Strategy

### Why Cache Is Critical

At 120K reads/sec, hitting the DB for every redirect is not feasible. With 80% cache hit rate, only 24K reads/sec reach the DB — easily handled by a single MySQL instance.

### Cache Design

```
┌─────────────────────────────────────────────────────────┐
│                    REDIS CACHE                            │
│                                                           │
│  Key:    short_code (e.g. "aB3x9Kp")                    │
│  Value:  long_url (e.g. "https://example.com/...")       │
│  TTL:    24 hours (for non-permanent links)              │
│          No TTL (for permanent links; rely on LRU)       │
│                                                           │
│  Eviction: LRU (Least Recently Used)                     │
│  Memory:   ~100 GB covers hot set (80%+ hit rate)        │
│                                                           │
│  Read pattern:  GET short_code → long_url                │
│  Write pattern: SET short_code long_url [EX ttl]         │
│                 (on cache miss after DB lookup)           │
│  Invalidation:  DELETE short_code                        │
│                 (if long_url is updated or link deleted)  │
└─────────────────────────────────────────────────────────┘
```

### Cache Consistency

| Scenario | Problem | Handling |
|----------|---------|----------|
| Link created | Not in cache yet | First redirect triggers cache miss → DB → SET cache. Fine — creates are rare vs. reads. |
| Long URL updated | Cache has stale value | Write-through: URL Service updates DB AND deletes cache key. Next redirect re-populates. |
| Link deleted / expired | Cache still has entry | URL Service deletes cache key on delete. Expiry: checked on read (or TTL in Redis). |
| Cache eviction (LRU) | Popular link evicted | Next redirect: cache miss → DB → re-populate. Transient one-time miss. |

**We don't pre-warm the cache.** The cache fills naturally from redirects. Popular links get cached quickly; cold links stay in DB only. This is simple and effective.

---

## 12. Consistency and Reliability

### Consistency Model

| Data | Consistency | Why |
|------|-------------|-----|
| URL mapping (short_code → long_url) | **Strong** (DB is source of truth) | Wrong redirect is unacceptable. Cache is read-through; misses hit DB. |
| Cache | **Eventual** (seconds behind DB) | On update/delete, cache key is deleted. Brief window where stale value is served. Acceptable. |
| Click analytics | **Eventual** (Kafka consumer lag) | Analytics doesn't affect redirect. Seconds of delay is fine. |
| Range allocator state | **Strong** (atomic increment) | Two instances getting the same range = duplicate codes. FOR UPDATE prevents this. |

### What If the DB Goes Down?

- **Redirects**: Cache continues serving. 80%+ of traffic unaffected. Cache misses return 503 (link might exist but we can't verify). No data loss.
- **Creates**: Return 503. Client retries. Range allocator state is in the same DB; both are unavailable together. When DB recovers, resume from where we left off.

### What If Redis Goes Down?

- **Redirects**: All traffic falls through to DB. At 120K reads/sec, DB will be overwhelmed. Mitigation: Redis Cluster with replicas (failover to replica); or multiple Redis instances with consistent hashing. If all Redis is down, rate-limit redirects to protect DB.
- **Creates**: Unaffected (create path doesn't use cache).

### What If Kafka Goes Down?

- **Redirects**: Unaffected — Kafka is fire-and-forget for analytics. Redirect still returns 302. Click events are lost (or buffered locally and retried).
- **Analytics**: Delayed or missing clicks. Not critical. Once Kafka recovers, new events flow normally.
- **Enhancement**: Buffer events locally (in-memory or disk) when Kafka is unavailable; flush when it recovers. Trades a small amount of memory for durability.

---

## 13. Failure Scenarios and Handling

| Scenario | What Breaks | Handling | Data Impact |
|----------|-------------|----------|-------------|
| **Custom alias already taken** | User's chosen code exists in DB | Return 409 Conflict. User picks a different alias. | No change |
| **Create retry (network timeout)** | Client doesn't know if first request succeeded | Idempotency key: same key → return existing short URL. No duplicate codes. | No duplicate |
| **Range allocator down** | URL Service can't get new range | Service continues with remaining IDs in current range. If range exhausted → 503 until allocator recovers. | Creates blocked temporarily; redirects unaffected |
| **DB slow on redirect (cache miss)** | Redirect latency increases | Cache handles 80%+ of traffic. For the 20% cache miss, add read replicas. If replica is also slow → degrade gracefully (return 503 for that request). | Transient latency increase |
| **Cache eviction storm** | Popular links evicted from Redis (OOM) | LRU ensures least-used links are evicted. Monitor cache hit rate. Scale Redis or increase memory. | Temporary cache miss spike |
| **Short code not found** | User enters invalid or deleted short code | Return 404 Not Found. If the code was deleted, also clean up cache. | No data change |
| **Expired link accessed** | Link past `expires_at` | Return 410 Gone. Delete from cache. Optional: show "This link has expired" page. | No redirect |
| **Kafka down** | Click events not published | Redirect still works (fire-and-forget). Events lost unless locally buffered. | Analytics gap during outage |
| **Spam/abuse** | Bulk creation of short links to malicious sites | Rate limit per API key. URL validation (blocklist). Scan with malware detection service. | Block abusive creates |
| **DB full (storage)** | 91 TB over 5 years | Shard by short_code hash. Archive old / expired links to cold storage. | Handled by sharding + archival |

---

## 14. Scale and Sharding

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                          │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐           │
│  │ URL DB (MySQL)   │   │ Redis Cache     │   │ URL / Redirect  │           │
│  │                  │   │                  │   │ Service          │           │
│  │ Shard by         │   │ Redis Cluster   │   │ STATELESS        │           │
│  │ hash(short_code) │   │ (consistent     │   │ (just add more   │           │
│  │                  │   │  hashing)        │   │  instances       │           │
│  │ 1,200 writes/sec │   │                  │   │  behind LB)      │           │
│  │ → single DB is   │   │ 120K reads/sec   │   │                  │           │
│  │ fine initially;  │   │ → Redis handles  │   │ Each instance    │           │
│  │ shard for storage│   │ easily; cluster  │   │ has a local      │           │
│  │ (91 TB / 5 yr)   │   │ for HA           │   │ ID range         │           │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘           │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐           │
│  │ Range Allocator  │   │ Kafka            │   │ Analytics Svc   │           │
│  │                  │   │                  │   │                  │           │
│  │ Single row in DB │   │ Partition by     │   │ Scale consumers │           │
│  │ (or ZK/etcd)     │   │ short_code hash  │   │ independently   │           │
│  │ Called 1x / 1M   │   │ (or random for   │   │ (Kafka consumer │           │
│  │ creates; not a   │   │  load balance)   │   │  groups)         │           │
│  │ bottleneck       │   │                  │   │                  │           │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘           │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Component | Shard/Scale Strategy | Why |
|-----------|---------------------|-----|
| **URL DB** | Shard by **hash(short_code)** when storage exceeds single DB capacity (~91 TB over 5 years). Single DB handles 1,200 writes/sec easily. | Redirect lookup is by short_code (hash → shard → row). Even distribution. |
| **Redis Cache** | Redis Cluster with consistent hashing. Multiple nodes. | 120K reads/sec; single Redis handles it, but cluster for HA and memory capacity. |
| **URL Service** | Stateless → horizontal scale behind LB. Each instance has a pre-allocated ID range. | No shared state. Range is local to instance. |
| **Redirect Service** | Stateless → horizontal scale behind LB. | Each instance reads cache/DB. No in-memory state. |
| **Range Allocator** | Single row in DB (or ZooKeeper). Not a bottleneck — called once per 1M creates. | Simple. If it's briefly unavailable, instances use remaining range. |
| **Kafka** | Partition by short_code hash or random. | Analytics events don't need ordering guarantees. Random partitioning for even load. |
| **Analytics Service** | Kafka consumer group; scale by adding consumers. | Independent of redirect throughput. Process at its own pace. |

---

## 15. Final Architecture (Putting It All Together)

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  API Client  │ ① POST /v1/shorten                      │  Browser     │                │
│  │              │ (long_url, idem_key)                     │              │                │
│  └──────┬───────┘                                          │ ⑧ GET /abc12 │                │
│         │                                                  └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│                     Auth, rate limit (creates), TLS                                        │
└─────────┬────────────────────────────────────────────────────────┬────────────────────────┘
          │                                                        │
   ┌──────▼───────┐                                       ┌───────▼──────────┐
   │ URL Service   │                                       │ Redirect Service │
   │               │                                       │                  │
   │ ② validate   │                                       │ ⑨ GET cache      │
   │ ③ idem check │                                       │ ⑩ miss → DB      │
   │ ④ get ID     │                                       │ ⑪ SET cache      │
   │ ⑤ base62     │                                       │ ⑫ 302 redirect   │
   │ ⑥ INSERT DB  │                                       │ ⑬ publish event  │
   │ ⑦ return URL │                                       │    (async)       │
   └───┬──────────┘                                       └───┬─────────┬───┘
       │                                                      │         │
       │ ⑥ INSERT                     ⑨ cache GET            │         │ ⑬
       │                                   │              ⑩ DB│         │
       ▼                                   ▼                  │         ▼
  ┌──────────────┐                ┌────────────────┐          │   ┌──────────────┐
  │  URL DB      │                │ Redis Cache    │          │   │    KAFKA      │
  │  (MySQL/PG)  │◄───────────────│ Cluster        │          │   │              │
  │              │  ⑩ cache miss  │                │          │   │ redirect-    │
  │  url_mappings│                │ short_code →   │◄─────────┘   │ events       │
  │  id_ranges   │                │ long_url       │              └──────┬───────┘
  │              │                │ LRU, TTL       │                     │
  └──────┬───────┘                └────────────────┘                     │
         │                                                               ▼
    ④ get range                                                  ┌────────────┐
         │                                                       │ Analytics  │
         ▼                                                       │ Service    │
  ┌──────────────┐                                               │            │
  │Range Allocator│                                               │ ClickHouse │
  │(ZK / DB row) │                                               │ / BigQuery │
  └──────────────┘                                               └────────────┘
```

### Step-by-Step Flow (Numbered)

| Step | Action | Service | Protocol | Data Store |
|------|--------|---------|----------|------------|
| ① | Client sends POST /v1/shorten (long_url + idempotency key) | Client → API GW → URL Service | HTTP POST | |
| ② | Validate URL (format, blocklist, already short?) | URL Service | Internal | |
| ③ | Check idempotency key (SELECT WHERE idem_key = ?) | URL Service | DB read | URL DB |
| ④ | Get next ID from local range (or allocate new range if exhausted) | URL Service (→ Range Allocator if needed) | Local / Sync | id_ranges |
| ⑤ | Encode ID to base62 (7 chars) | URL Service | Local | |
| ⑥ | INSERT url_mapping (short_code, long_url, idem_key, ...) | URL Service | DB write | URL DB |
| ⑦ | Return 201 {short_url, short_code, long_url} | URL Service → Client | HTTP response | |
| ⑧ | Browser navigates to `short.com/abc12` | Browser → API GW → Redirect Service | HTTP GET | |
| ⑨ | Check Redis cache: GET "abc12" | Redirect Service | Redis GET | Redis Cache |
| ⑩ | Cache miss: SELECT long_url FROM url_mappings WHERE short_code = 'abc12' | Redirect Service | DB read | URL DB |
| ⑪ | Set cache: SET "abc12" → long_url (with TTL) | Redirect Service | Redis SET | Redis Cache |
| ⑫ | Return 302 with Location header | Redirect Service → Browser | HTTP 302 | |
| ⑬ | Publish redirect event to Kafka (fire-and-forget) | Redirect Service → Kafka | Async | Kafka |
| ⑭ | Analytics Service consumes event, aggregates | Analytics Service | Kafka consume | ClickHouse |

---

## 16. Trade-off Summary

| Decision | Our Choice | Alternative | Why Our Choice |
|----------|-----------|-------------|----------------|
| **Key generation** | Range-based allocation (base62) | Random + retry / DB sequence | No collision, no bottleneck, scales horizontally. Random has retries; sequence is a single writer. |
| **Short code length** | 7 characters (62^7 = 3.5T) | 6 chars (56B) / 8 chars | 7 gives 3.5T codes — decades of headroom. 6 is tight at scale; 8 is longer URLs than needed. |
| **Redirect status code** | 302 (temporary) | 301 (permanent) | 302 gives us per-click analytics and ability to update long_url. 301 reduces load but loses analytics. |
| **Cache** | Redis (read-through, LRU) | Memcached / no cache | Redis is versatile (TTL, LRU, cluster). 80%+ of redirects from cache. No cache → DB overwhelmed at 120K reads/sec. |
| **DB** | MySQL / PostgreSQL | NoSQL (DynamoDB, Cassandra) | Simple key-value lookup. RDBMS is familiar, supports ACID for idempotency. NoSQL is also viable — mention as alternative. |
| **Analytics** | Async via Kafka → ClickHouse | Sync (increment counter in DB on redirect) | Sync counter would add latency to every redirect and create hot rows. Async = redirect is fast, analytics is separate. |
| **Cache invalidation** | Write-through (delete cache key on update/delete) | TTL-based only | Explicit invalidation ensures no stale redirects. TTL as backup. |
| **Range allocator** | DB row with FOR UPDATE | ZooKeeper / etcd | Simpler; uses existing DB. ZK/etcd for higher availability (mention as alternative). |
| **Sharding (DB)** | By hash(short_code) | By creation date / user_id | Lookup is by short_code; hash ensures even distribution. Date-based = hot partition for recent data. |
| **Expiry handling** | Checked on redirect + lazy deletion | Cron job to delete expired links | Lazy: no background job needed. Cron: for storage reclamation (schedule as batch job). |
| **Spam prevention** | Rate limit + URL blocklist | CAPTCHA / link scanning | Rate limit is simple and effective. Blocklist catches known bad domains. ML scanning for advanced. |

---

## 17. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Better Approach |
|---------|---------------|-----------------|
| Using a single DB sequence for IDs | Write bottleneck; single point of failure. At 1,200 creates/sec it might work, but doesn't scale when you add regions. | Range-based allocation: each instance has a local counter. |
| Random codes without collision handling | At 182B records (5 years), collision probability is non-trivial with 7-char base62. Retries add latency. | Use deterministic approach (range-based) with zero collisions. |
| Not caching redirects | 120K reads/sec directly to DB will overwhelm it. | Redis cache: 80%+ hit rate. DB only on miss. |
| Synchronous analytics on redirect path | Incrementing a counter in DB adds ~5ms to every redirect. At 120K/sec, that's a hot row. | Async: publish event to Kafka; Analytics Service aggregates separately. |
| Using UUID as short code | UUID is 36 chars — defeats the purpose of "short" URL. | Base62 encoding of sequential ID → 7 chars. |
| No idempotency on create | Client retries due to timeout → two short codes for the same request. | Idempotency key in request; check before generating code. |
| Storing analytics in same DB as URL mappings | Different access patterns: URL lookup is point-query; analytics is aggregate-heavy. | Separate store: ClickHouse / BigQuery for analytics. RDBMS for URL mappings. |
| No rate limiting on create API | Spammers create millions of links to malicious sites. | Rate limit per API key; URL blocklist; abuse detection. |
| 301 redirect when you need analytics | Browser caches 301; subsequent clicks never reach our server. | Use 302 for analytics; 301 only when explicitly permanent. |

---

## 18. Interview Talking Points

### "Walk me through the architecture"

> Two paths: write (create short URL) and read (redirect). Write path: URL Service validates the URL, gets next ID from a pre-allocated range (range-based allocation), encodes to base62, INSERTs into MySQL, returns the short URL. Read path: Redirect Service checks Redis cache first (80%+ hit rate, ~0.5ms). On miss, queries MySQL, populates cache, returns 302. Analytics: Redirect Service fire-and-forgets an event to Kafka; Analytics Service consumes and aggregates into ClickHouse. Stateless services behind a load balancer; Redis Cluster for cache; MySQL sharded by short_code hash for storage.

### "How do you generate the short code?"

> Range-based allocation. A central allocator (backed by a single DB row with atomic increment) hands out blocks of 1 million IDs to each URL Service instance. The instance maintains a local counter — incrementing it is just a variable increment, no network call. When the range is exhausted, it requests a new one. The ID is encoded to base62 (7 chars → 3.5 trillion combinations). No collisions by design, no single-writer bottleneck. If an instance crashes, at most 1M unused IDs are wasted — trivial vs. the 3.5T key space.

### "How do you handle collisions?"

> With range-based allocation, there are NO collisions. Each instance has a non-overlapping range. The only collision risk is with custom aliases — handled by a UNIQUE constraint on short_code. If the user's chosen alias is taken, we return 409 Conflict. For random approach (alternative), you'd retry with a new random string on collision — but we avoid that by design.

### "Why 302 vs 301?"

> 302 (temporary) means the browser does NOT cache the redirect — every click hits our server. This gives us per-click analytics (country, device, referrer) and the ability to update the long_url later (A/B testing, link rotation). 301 (permanent) lets the browser cache it — reduces our server load but we lose visibility into clicks. We default to 302 and offer 301 as an option for users who want it.

### "How do you scale reads?"

> Cache-first architecture. Redis sits in front of the DB. At 120K reads/sec, 80%+ are cache hits (~0.5ms). The remaining 24K cache misses hit the DB, which is comfortable. We use Redis Cluster for HA and memory capacity. If Redis is down, we degrade (rate-limit to protect DB, serve what we can). We also add read replicas to the DB for additional read capacity on cache misses.

### "What if the same long URL is shortened twice?"

> By default, we generate a new short code each time — two different short URLs for the same long URL. This is simpler and covers most use cases (different users, different contexts). Optional: get-or-create mode — hash the long URL, look up the hash, return existing short code if found. This saves storage but adds a DB lookup to every create. We'd offer it as an API option, not the default.

### "How do you handle analytics at this scale?"

> Redirect Service publishes a fire-and-forget event to Kafka on every redirect: `{short_code, timestamp, IP, user-agent, referrer}`. This does NOT block the 302 response. Analytics Service consumes from Kafka and writes to a columnar store (ClickHouse or BigQuery) optimized for aggregation. Pre-aggregation: we maintain daily/hourly counters per short_code. The `/v1/analytics/:shortCode` endpoint reads from this pre-aggregated data. This keeps the redirect path fast and analytics independent.

### "What happens when a link expires?"

> On redirect, we check `expires_at`. If past → return 410 Gone, delete from cache. We don't proactively scan for expired links (lazy deletion). For storage reclamation, a periodic batch job archives or deletes expired mappings from the DB. This keeps the redirect path simple and avoids running a continuous background process for expiry.

### "How is this different from designing a cache / KV store?"

> A URL shortener is a specialized key-value store with two unique challenges: (1) key generation — the key must be short, unique, and human-readable (base62 encoding, range allocation), and (2) read-heavy with a redirect concern (301 vs. 302, cache strategy). A generic cache doesn't have the key generation problem or the HTTP redirect semantics. But the read path (cache-first, LRU eviction, TTL) is very similar to designing a caching layer.
