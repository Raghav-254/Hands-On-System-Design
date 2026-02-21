# 🎵 Spotify - Interview Cheatsheet

> Design a music streaming service where users can search for songs, stream audio in real-time, create and share playlists, and receive personalized recommendations — all at scale for hundreds of millions of users.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **API Gateway** | Route requests, auth, rate limit | TLS, route by path, per-user rate limit |
| **Song Metadata Service** | CRUD for songs, artists, albums | Relational DB; read-heavy, heavily cached |
| **Streaming Service** | Serve audio chunks to clients | Generates pre-signed CDN URLs; does NOT proxy audio bytes |
| **Search Service** | Full-text search across catalog | Elasticsearch; fuzzy matching, autocomplete, popularity-weighted ranking |
| **Playlist Service** | Playlist CRUD, collaborative editing | Many-to-many (playlist ↔ songs); optimistic locking for concurrent edits |
| **Recommendation Service** | Personalized playlists (Discover Weekly, Daily Mix) | Offline pipeline (Spark) → precomputed; served from cache |
| **Play History Service** | Record and query play events | Write-heavy → Kafka → Cassandra; feeds recommendations and "recently played" |
| **Object Storage (S3)** | Store audio files | Pre-encoded at multiple bitrates (128/256/320 kbps); immutable blobs |
| **CDN** | Edge-cache audio files close to users | Top 1% songs cached at edge; long-tail served from origin |
| **Metadata DB (MySQL/PG)** | Songs, artists, albums, playlists | Shard by song_id or playlist_id |
| **Cache (Redis)** | Song metadata, playlist data, search suggestions | Short TTL; invalidate on writes |
| **Kafka** | Event bus for play events, analytics | Play events → recommendations, royalty calculation, analytics |

---

## The Story: Building Spotify

Users want to search for any song, tap play, and hear audio within seconds — whether they're on WiFi or a spotty mobile connection. Behind the scenes, 100M+ songs sit in object storage, each encoded at multiple bitrates. A CDN distributes popular tracks to edge servers worldwide. When a user taps play, the client doesn't download the whole file — it fetches audio in small chunks, buffering ahead to handle network jitter. Meanwhile, every play event streams through Kafka to feed recommendation pipelines, royalty calculations, and analytics. The design focuses on **how audio gets from storage to the user's ears** (CDN + chunking + adaptive bitrate), **search at scale** (Elasticsearch), **playlist management** (many-to-many data modeling, collaborative editing), and **personalization** (offline ML pipelines). Staff-level depth means we cover the streaming protocol, CDN caching strategy, data model relationships, consistency trade-offs, and failure scenarios.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Search**: Find songs, artists, albums, and playlists by name. Support fuzzy matching ("beatlse" → "Beatles") and autocomplete.
- **Stream music**: User taps play → audio starts within 200ms. Adaptive bitrate based on network. Seamless playback (no buffering pauses).
- **Playlists**: Create, edit, delete playlists. Add/remove/reorder songs. Collaborative playlists (multiple users can edit).
- **Recommendations**: Personalized playlists (Discover Weekly, Daily Mix). Based on listening history and similar users.
- **Play history**: "Recently Played" list. Tracks every play for analytics and recommendations.
- **Artist/album catalog**: Browse artist discographies, album track lists.

### Non-Functional Requirements

- **Scale**: 500M total users, 200M DAU, 100M songs in catalog.
- **Latency**: Audio playback start < 200ms. Search results < 100ms. Metadata reads < 50ms.
- **Availability**: Streaming must be highly available (99.99%). Brief search lag is tolerable.
- **Bandwidth efficiency**: Adaptive bitrate to avoid wasting bandwidth on poor connections.
- **Durability**: Audio files must never be lost. Play history must be durable for royalty calculations.

### Scope (What We're Not Covering)

- Offline downloads / DRM — mention briefly; client-side encryption + license server.
- Ads (free tier) — separate ad-serving system.
- Social features (following, sharing) — standard social graph + activity feed.
- Podcast support — similar to music but different metadata schema.
- Payment / subscription management — standard billing system.

---

## 2. Back-of-the-Envelope Estimation

### Storage

```
Songs:     100M songs × 3 bitrates × 5 MB avg = 1.5 PB (object storage)
Metadata:  100M songs × 1 KB = 100 GB (relational DB)
           10M artists × 2 KB = 20 GB
           20M albums × 1 KB = 20 GB
Playlists: 4B playlists × 0.5 KB avg = 2 TB
           (playlist_songs rows: 4B × 50 avg songs × 50 bytes = 10 TB)
Play history: 4B events/day × 200 bytes × 365 days = ~290 TB/year (Cassandra)
```

### Bandwidth (Streaming)

```
200M DAU × 30 min/day average listening = 6B minutes/day
At 128 kbps (16 KB/s): 6B × 60s × 16 KB = ~5.6 PB/day outbound
Average bandwidth: ~520 Gbps
Peak (2× average): ~1 Tbps
→ This is why CDN is essential. Origin servers alone cannot serve this.
```

### QPS

```
Play requests: 200M DAU × 20 songs/day = 4B/day ≈ 46K req/s
Search:        200M DAU × 5 searches/day = 1B/day ≈ 12K req/s
Playlist ops:  200M DAU × 2 ops/day = 400M/day ≈ 5K req/s
Play events:   Same as play requests ≈ 46K writes/s to Kafka
Metadata reads: 200M DAU × 30 reads/day = 6B/day ≈ 70K req/s (mostly cached)
```

### Key Takeaways

| Dimension | Value | Implication |
|-----------|-------|-------------|
| Audio storage | 1.5 PB | Object storage (S3), not DB |
| Outbound bandwidth | ~1 Tbps peak | CDN is mandatory, not optional |
| Play events | 46K writes/s | Write-heavy → Kafka → Cassandra |
| Metadata reads | 70K/s (mostly cached) | Redis cache in front of DB |
| Catalog size | 100M songs | Search index must handle 100M docs |

---

## 3. Core Concept: How Music Streaming Works

### Why Not Download the Whole File?

A 4-minute song at 320 kbps = ~10 MB. Downloading fully before playing means a multi-second wait. Instead, we **stream**: fetch the audio in small chunks, start playing as soon as the first chunk arrives, and keep fetching ahead (buffering) while the user listens.

### The Streaming Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                    HOW AUDIO STREAMING WORKS                         │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  1. User taps "Play" on song X                                      │
│     │                                                                │
│     ▼                                                                │
│  2. Client → Streaming Service: "I want to play song_id=X"          │
│     │                                                                │
│     ▼                                                                │
│  3. Streaming Service:                                               │
│     ├── Look up song metadata (bitrate files, S3 paths)             │
│     ├── Choose bitrate based on client's network quality             │
│     ├── Generate pre-signed CDN URL (time-limited, auth token)      │
│     └── Return URL to client                                         │
│     │                                                                │
│     ▼                                                                │
│  4. Client fetches audio DIRECTLY from CDN (not through our servers)│
│     ├── HTTP Range request: bytes 0-65535 (first 64KB chunk)        │
│     ├── Start playing as soon as first chunk decoded                 │
│     ├── Prefetch next chunks in background                          │
│     └── Buffer 10-30 seconds ahead                                   │
│     │                                                                │
│     ▼                                                                │
│  5. CDN serves the chunk:                                            │
│     ├── Cache HIT → serve from edge (< 20ms)                       │
│     └── Cache MISS → fetch from S3 origin, cache, then serve       │
│     │                                                                │
│     ▼                                                                │
│  6. Client reports play event → Kafka → Play History                │
│                                                                      │
│  KEY INSIGHT: Our servers never touch audio bytes.                   │
│  Streaming Service only issues a signed URL. CDN does the heavy     │
│  lifting. This keeps our server costs low and latency minimal.      │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Adaptive Bitrate

```
┌───────────────────────────────────────────────────┐
│              ADAPTIVE BITRATE SELECTION             │
├───────────────────────────────────────────────────┤
│                                                     │
│  Network Speed        Bitrate      File Size (4min)│
│  ─────────────        ───────      ────────────────│
│  < 200 kbps           128 kbps     ~4 MB           │
│  200 kbps - 1 Mbps    256 kbps     ~8 MB           │
│  > 1 Mbps             320 kbps     ~10 MB          │
│                                                     │
│  Decision made by CLIENT, not server:              │
│  • Client monitors download speed of recent chunks │
│  • If speed drops → switch to lower bitrate mid-   │
│    song (next chunk fetched at lower quality)      │
│  • If speed improves → switch up                   │
│                                                     │
│  Each song is pre-encoded at all 3 bitrates and    │
│  stored as 3 separate files in S3.                 │
│                                                     │
│  S3 paths:                                          │
│    /songs/{song_id}/128.mp3                        │
│    /songs/{song_id}/256.mp3                        │
│    /songs/{song_id}/320.mp3                        │
│                                                     │
└───────────────────────────────────────────────────┘
```

### Pre-signed CDN URLs

The Streaming Service does NOT proxy audio. It generates a **pre-signed URL** — a CDN URL with an embedded authentication token and expiry. The client uses this URL to fetch audio directly from the CDN. This keeps our servers out of the data path.

```
Pre-signed URL example:
https://cdn.spotify.internal/songs/abc123/256.mp3
  ?token=eyJhbGciOiJIUz...
  &expires=1708012800

If the URL expires or the token is invalid, CDN returns 403.
Client requests a new URL from Streaming Service.
```

Why pre-signed and not just public?
- **Access control**: Only paying users (or free-tier with ads) should stream.
- **Expiry**: URLs expire after ~1 hour. Prevents URL sharing.
- **Analytics**: Token encodes user_id for server-side logging at CDN level.

---

## 4. API Design

### Play Song

```
POST /api/v1/songs/{song_id}/play
Headers: Authorization: Bearer <token>
         X-Network-Quality: high | medium | low

Response 200:
{
  "stream_url": "https://cdn.spotify.internal/songs/abc123/256.mp3?token=...",
  "bitrate": 256,
  "duration_ms": 240000,
  "expires_at": "2025-02-15T12:30:00Z",
  "song": {
    "song_id": "abc123",
    "title": "Bohemian Rhapsody",
    "artist": "Queen",
    "album": "A Night at the Opera",
    "album_art_url": "https://cdn.spotify.internal/art/album_456.jpg"
  }
}
```

- Client sends network quality hint; server selects bitrate accordingly.
- Response includes metadata so the UI can display song info immediately.
- `stream_url` points to CDN, NOT to our servers.

### Search

```
GET /api/v1/search?q=bohemian+rhaps&type=song,artist,album&limit=10
Headers: Authorization: Bearer <token>

Response 200:
{
  "songs": [
    {"song_id": "abc123", "title": "Bohemian Rhapsody", "artist": "Queen", "album": "A Night at the Opera"}
  ],
  "artists": [
    {"artist_id": "art_001", "name": "Queen", "image_url": "..."}
  ],
  "albums": [
    {"album_id": "alb_001", "title": "A Night at the Opera", "artist": "Queen", "year": 1975}
  ]
}
```

- `type` parameter lets client request specific entity types.
- Partial/fuzzy matching: "bohemian rhaps" matches "Bohemian Rhapsody".
- Results ranked by relevance (text match score) × popularity (play count).

### Playlist CRUD

```
POST /api/v1/playlists
{
  "name": "Road Trip Mix",
  "is_collaborative": false
}
→ 201: {"playlist_id": "pl_001", ...}

POST /api/v1/playlists/{playlist_id}/songs
Headers: X-Idempotency-Key: <uuid>
{
  "song_id": "abc123",
  "position": 0
}
→ 200: {"playlist_id": "pl_001", "total_songs": 15}

DELETE /api/v1/playlists/{playlist_id}/songs/{song_id}
→ 204

PUT /api/v1/playlists/{playlist_id}/songs/reorder
{
  "song_id": "abc123",
  "new_position": 5
}
→ 200
```

- Adding a song uses an idempotency key to prevent duplicate additions on retry.
- Reorder updates the `position` column in the `playlist_songs` join table.
- Collaborative playlists: any member can add/remove/reorder. Optimistic locking via `version` column prevents lost updates.

### Get Recommendations

```
GET /api/v1/recommendations/daily-mix?limit=30
Headers: Authorization: Bearer <token>

Response 200:
{
  "playlist_name": "Daily Mix 1",
  "songs": [
    {"song_id": "abc123", "title": "Bohemian Rhapsody", "artist": "Queen", ...},
    ...
  ],
  "generated_at": "2025-02-15T00:00:00Z"
}
```

- Recommendations are precomputed offline and cached. This endpoint reads from cache/DB, no real-time ML.

---

## 5. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SPOTIFY ARCHITECTURE                                 │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐                                                                │
│  │  Client   │ (Mobile / Desktop / Web)                                     │
│  └────┬─────┘                                                                │
│       │                                                                      │
│       │ HTTPS                                                                │
│       ▼                                                                      │
│  ┌──────────────┐                                                            │
│  │ API Gateway   │ (Auth, Rate Limit, Routing)                              │
│  └──────┬───────┘                                                            │
│         │                                                                    │
│    ┌────┴────────┬──────────────┬────────────────┬───────────────┐           │
│    ▼             ▼              ▼                ▼               ▼           │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐ ┌─────────────┐     │
│  │Streaming │ │ Search   │ │ Playlist  │ │ Recommend │ │Play History │     │
│  │Service   │ │ Service  │ │ Service   │ │ Service   │ │Service      │     │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘ └─────┬─────┘ └──────┬──────┘     │
│       │             │             │             │              │             │
│       │             │             │             │              │             │
│  ┌────▼─────┐ ┌────▼──────┐ ┌───▼────┐  ┌────▼─────┐  ┌────▼──────┐      │
│  │  S3      │ │Elastic-   │ │Metadata│  │Redis     │  │  Kafka    │      │
│  │ (audio)  │ │search     │ │DB (PG) │  │Cache     │  │           │      │
│  └────┬─────┘ └───────────┘ └────────┘  └──────────┘  └─────┬────┘      │
│       │                                                       │             │
│       ▼                                                       ▼             │
│  ┌──────────┐                                          ┌──────────────┐     │
│  │   CDN    │ ◄── Client fetches audio directly        │  Cassandra   │     │
│  │ (edge)   │                                          │ (play history)│     │
│  └──────────┘                                          └──────────────┘     │
│                                                                              │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │  Offline Pipeline (Spark / Hadoop)                                    │    │
│  │  Reads play history from Cassandra → Collaborative filtering          │    │
│  │  → Writes precomputed recommendations to Redis / Metadata DB         │    │
│  └──────────────────────────────────────────────────────────────────────┘    │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Component Responsibilities

| Component | Responsibility | Why this technology? |
|-----------|---------------|---------------------|
| **Streaming Service** | Look up song files, generate pre-signed CDN URL | Stateless; never touches audio bytes |
| **Search Service** | Full-text search with fuzzy matching and autocomplete | Elasticsearch: inverted index, BM25 ranking, built-in fuzzy |
| **Playlist Service** | CRUD, collaborative editing, song ordering | Relational DB for strong consistency on playlist state |
| **Recommendation Service** | Serve precomputed personalized playlists | Read from cache; offline pipeline does the heavy ML |
| **Play History Service** | Record play events, serve "recently played" | Kafka for ingestion; Cassandra for write-heavy time-series storage |
| **S3** | Durable audio file storage | Immutable blobs; 11 nines durability; cheap at PB scale |
| **CDN** | Edge-cache audio for low-latency delivery | Serves ~99% of audio traffic; reduces origin load by 100× |
| **Elasticsearch** | Search index for songs, artists, albums | 100M docs; sub-100ms fuzzy search |
| **Metadata DB (PostgreSQL)** | Songs, artists, albums, playlists | ACID for playlist edits; relational joins for catalog queries |
| **Redis** | Cache metadata, recommendations, search suggestions | Sub-ms reads; reduces DB load |
| **Kafka** | Event bus for play events | Decouples ingestion from processing; durable, replayable |
| **Cassandra** | Play history storage | Write-optimized (LSM-tree); partitioned by user_id for time-range queries |
| **Spark (offline)** | Recommendation pipeline | Batch processing on full play history; outputs precomputed playlists |

---

## 6. Sync vs. Async Communication

| Hop | Protocol | Sync/Async | Why |
|-----|----------|------------|-----|
| Client → Streaming Service (get stream URL) | HTTP POST | **Sync** | User is waiting to hear audio |
| Client → CDN (fetch audio chunks) | HTTP GET (Range) | **Sync** | Audio must arrive to play |
| Client → Search Service | HTTP GET | **Sync** | User is waiting for results |
| Client → Playlist Service (add song) | HTTP POST | **Sync** | User needs confirmation |
| Play History Service → Kafka | Kafka PRODUCE | **Async (fire-and-forget)** | Play event is a side effect; shouldn't block playback |
| Kafka → Cassandra (play history write) | Kafka CONSUME | **Async** | Eventual storage; minor lag acceptable |
| Kafka → Recommendation Pipeline | Kafka CONSUME | **Async** | Offline batch; hours of lag acceptable |
| Playlist Service → Redis (invalidate cache) | Redis DEL | **Sync (fast, ~0.5ms)** | Ensure stale playlist not served |
| Metadata Service → Elasticsearch (index update) | Async (outbox) | **Async** | Search index is eventually consistent with DB |

**Rule of thumb**: Anything the user is waiting on = sync. Side effects (analytics, recommendations, notifications) = async.

---

## 7. Play Song Flow (End-to-End)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    PLAY SONG — STEP BY STEP                          │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ① Client taps "Play" on "Bohemian Rhapsody"                       │
│     │                                                                │
│     ▼                                                                │
│  ② POST /api/v1/songs/abc123/play                                   │
│     Header: X-Network-Quality: high                                  │
│     │                                                                │
│     ▼                                                                │
│  ③ Streaming Service:                                                │
│     ├── Check Redis cache for song metadata                         │
│     │   Cache HIT → song metadata (S3 paths, duration, etc.)       │
│     │   Cache MISS → query Metadata DB → populate cache             │
│     ├── Select bitrate: high network → 320 kbps                     │
│     ├── Build S3 path: /songs/abc123/320.mp3                        │
│     ├── Generate pre-signed CDN URL (1-hour expiry)                 │
│     └── Return stream_url + song metadata to client                 │
│     │                                                                │
│     ▼                                                                │
│  ④ Client receives stream_url, starts fetching from CDN             │
│     GET https://cdn.../songs/abc123/320.mp3                         │
│     Range: bytes=0-65535  (first 64KB chunk)                        │
│     │                                                                │
│     ▼                                                                │
│  ⑤ CDN edge server:                                                  │
│     ├── Cache HIT (popular song) → serve immediately (~10ms)       │
│     └── Cache MISS → fetch from S3 origin → cache → serve (~100ms) │
│     │                                                                │
│     ▼                                                                │
│  ⑥ Client decodes first chunk → audio starts playing               │
│     ├── Prefetch next chunks in background (buffer 10-30s ahead)    │
│     └── Monitor download speed → adjust bitrate if needed           │
│     │                                                                │
│     ▼                                                                │
│  ⑦ After ~30 seconds of playback, client fires play event          │
│     POST /api/v1/play-events                                        │
│     { "song_id": "abc123", "duration_ms": 30000, "bitrate": 320 }  │
│     │                                                                │
│     ▼                                                                │
│  ⑧ Play History Service → Kafka "play-events" topic                │
│     │                                                                │
│     ├──► Cassandra (play history for "recently played")             │
│     ├──► Recommendation pipeline (offline batch)                    │
│     └──► Royalty calculation service                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Why Fire the Play Event After 30 Seconds?

A "play" counts for royalties and recommendations only if the user listened for ≥ 30 seconds. If they skip after 5 seconds, no play event is recorded. This prevents users from gaming the system and inflating play counts.

---

## 8. Search Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SEARCH — STEP BY STEP                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ① User types "bohemian rhaps" in search bar                        │
│     │                                                                │
│     ▼                                                                │
│  ② Client sends: GET /api/v1/search?q=bohemian+rhaps&type=song     │
│     │                                                                │
│     ▼                                                                │
│  ③ Search Service:                                                   │
│     ├── Check Redis for cached results (key: "search:bohemian rhaps")│
│     │   Cache HIT (popular query) → return immediately              │
│     │   Cache MISS → continue to Elasticsearch                      │
│     │                                                                │
│     ▼                                                                │
│  ④ Elasticsearch query:                                              │
│     {                                                                │
│       "multi_match": {                                               │
│         "query": "bohemian rhaps",                                   │
│         "fields": ["title^3", "artist^2", "album"],                 │
│         "type": "best_fields",                                       │
│         "fuzziness": "AUTO"                                          │
│       }                                                              │
│     }                                                                │
│     │                                                                │
│     │  Field weights: title (3×) > artist (2×) > album (1×)        │
│     │  Fuzziness: AUTO → allows 1-2 character edits                 │
│     │                                                                │
│     ▼                                                                │
│  ⑤ Elasticsearch returns candidates ranked by text relevance        │
│     │                                                                │
│     ▼                                                                │
│  ⑥ Search Service re-ranks by:                                      │
│     final_score = 0.7 × text_relevance + 0.3 × popularity_score    │
│     (popularity = log(total_play_count))                             │
│     │                                                                │
│     ▼                                                                │
│  ⑦ Cache results in Redis (TTL: 5 min) → return to client          │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Autocomplete

For keystroke-by-keystroke suggestions (as the user types), we use a **prefix index** in Redis or Elasticsearch's `completion` suggester. The client debounces (waits 100-200ms after last keystroke) before sending the request.

### Keeping the Search Index in Sync

When a new song is added or metadata changes, we need to update Elasticsearch. Two approaches:

| Approach | How | Trade-off |
|----------|-----|-----------|
| **Transactional outbox** | Song write → DB + outbox row → CDC/poller → Elasticsearch | Guaranteed eventual consistency; slight lag |
| **Direct update** | Song write → DB, then Elasticsearch update | Simpler but if ES update fails, index drifts |

We use the **outbox approach** — same pattern as Splitwise/Uber/BookMyShow. Search results may lag a few seconds behind catalog updates, which is acceptable.

---

## 9. Data Model

```
┌──────────────────────────────────────────────────────────────────────┐
│                         DATA MODEL                                    │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│  ┌─────── Metadata DB (PostgreSQL) ──────────────────────────────┐   │
│  │                                                                │   │
│  │  artists                                                       │   │
│  │  ┌───────────┬───────┬───────────┬─────────────┐              │   │
│  │  │ artist_id │ name  │ image_url │ bio         │              │   │
│  │  │ (PK)      │       │           │             │              │   │
│  │  └───────────┴───────┴───────────┴─────────────┘              │   │
│  │                                                                │   │
│  │  albums                                                        │   │
│  │  ┌──────────┬───────────┬───────┬──────────────┐              │   │
│  │  │ album_id │ artist_id │ title │ release_year │              │   │
│  │  │ (PK)     │ (FK)      │       │              │              │   │
│  │  └──────────┴───────────┴───────┴──────────────┘              │   │
│  │  artist_id is FK → one-to-many: one artist has many albums.   │   │
│  │                                                                │   │
│  │  songs                                                         │   │
│  │  ┌─────────┬──────────┬───────────┬──────────┬────────────┐   │   │
│  │  │ song_id │ album_id │ title     │ duration │ genre      │   │   │
│  │  │ (PK)    │ (FK)     │           │ (ms)     │            │   │   │
│  │  ├─────────┼──────────┼───────────┼──────────┼────────────┤   │   │
│  │  │ s_001   │ alb_001  │ Bohemian  │ 354000   │ ROCK       │   │   │
│  │  │         │          │ Rhapsody  │          │            │   │   │
│  │  └─────────┴──────────┴───────────┴──────────┴────────────┘   │   │
│  │  album_id is FK → one-to-many: one album has many songs.      │   │
│  │                                                                │   │
│  │  song_files (one song → multiple bitrate files)                │   │
│  │  ┌─────────┬─────────┬──────────────────────────────┐         │   │
│  │  │ song_id │ bitrate │ s3_path                      │         │   │
│  │  │ (FK)    │ (kbps)  │                              │         │   │
│  │  ├─────────┼─────────┼──────────────────────────────┤         │   │
│  │  │ s_001   │ 128     │ /songs/s_001/128.mp3         │         │   │
│  │  │ s_001   │ 256     │ /songs/s_001/256.mp3         │         │   │
│  │  │ s_001   │ 320     │ /songs/s_001/320.mp3         │         │   │
│  │  └─────────┴─────────┴──────────────────────────────┘         │   │
│  │  Composite PK: (song_id, bitrate). One-to-many from songs.    │   │
│  │                                                                │   │
│  │  playlists                                                     │   │
│  │  ┌─────────────┬──────────┬──────┬────────────────┬─────────┐ │   │
│  │  │ playlist_id │ owner_id │ name │ is_collaborative│ version │ │   │
│  │  │ (PK)        │ (FK)     │      │                │         │ │   │
│  │  └─────────────┴──────────┴──────┴────────────────┴─────────┘ │   │
│  │  version column for optimistic locking on collaborative edits.│   │
│  │                                                                │   │
│  │  playlist_songs (many-to-many join table: playlists ↔ songs)  │   │
│  │  ┌─────────────┬─────────┬──────────┐                         │   │
│  │  │ playlist_id │ song_id │ position │  (composite PK)         │   │
│  │  ├─────────────┼─────────┼──────────┤                         │   │
│  │  │ pl_001      │ s_001   │ 0        │                         │   │
│  │  │ pl_001      │ s_002   │ 1        │                         │   │
│  │  │ pl_001      │ s_003   │ 2        │                         │   │
│  │  └─────────────┴─────────┴──────────┘                         │   │
│  │  Many-to-many with payload (position for ordering).           │   │
│  │  Same pattern as Splitwise's expense_splits — join table      │   │
│  │  with extra data. See: database_fundamentals/02_DATABASE_LOGIC│   │
│  │                                                                │   │
│  │  users                                                         │   │
│  │  ┌─────────┬───────┬────────┬──────────────┐                  │   │
│  │  │ user_id │ name  │ email  │ subscription │                  │   │
│  │  │ (PK)    │       │        │ FREE/PREMIUM │                  │   │
│  │  └─────────┴───────┴────────┴──────────────┘                  │   │
│  │                                                                │   │
│  │  idempotency_keys                                              │   │
│  │  ┌──────────────────┬──────────────────┬─────────────┐        │   │
│  │  │ idempotency_key  │ response_payload │ created_at  │        │   │
│  │  │ (PK)             │                  │             │        │   │
│  │  └──────────────────┴──────────────────┴─────────────┘        │   │
│  │                                                                │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  ┌─────── Play History (Cassandra) ──────────────────────────────┐   │
│  │                                                                │   │
│  │  play_events                                                   │   │
│  │  ┌─────────┬───────────────────┬─────────┬──────────┬────────┐│   │
│  │  │ user_id │ played_at         │ song_id │ duration │bitrate ││   │
│  │  │(part.key)│ (clustering, DESC)│         │ (ms)     │        ││   │
│  │  ├─────────┼───────────────────┼─────────┼──────────┼────────┤│   │
│  │  │ u_001   │ 2025-02-15 12:00  │ s_001   │ 240000   │ 320    ││   │
│  │  │ u_001   │ 2025-02-15 11:45  │ s_002   │ 195000   │ 256    ││   │
│  │  └─────────┴───────────────────┴─────────┴──────────┴────────┘│   │
│  │  Partition key: user_id → all plays for one user on same node.│   │
│  │  Clustering key: played_at DESC → recent plays first.         │   │
│  │  Write-heavy (46K/s) → Cassandra's LSM-tree handles this.    │   │
│  │                                                                │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                       │
│  ┌─────── Search Index (Elasticsearch) ──────────────────────────┐   │
│  │                                                                │   │
│  │  songs_index                                                   │   │
│  │  {                                                             │   │
│  │    "song_id": "s_001",                                        │   │
│  │    "title": "Bohemian Rhapsody",                              │   │
│  │    "artist_name": "Queen",                                    │   │
│  │    "album_title": "A Night at the Opera",                     │   │
│  │    "genre": "rock",                                           │   │
│  │    "play_count": 2500000000,                                  │   │
│  │    "release_year": 1975                                       │   │
│  │  }                                                             │   │
│  │  Synced from Metadata DB via transactional outbox + CDC.      │   │
│  │                                                                │   │
│  └────────────────────────────────────────────────────────────────┘   │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

### Relationship Summary

| Relationship | Type | How Modeled |
|-------------|------|-------------|
| artist → albums | One-to-many | `albums.artist_id` FK |
| album → songs | One-to-many | `songs.album_id` FK |
| song → song_files | One-to-many | `song_files.song_id` FK |
| playlists ↔ songs | Many-to-many (with position) | `playlist_songs` join table |
| user → playlists | One-to-many | `playlists.owner_id` FK |
| user → play_events | One-to-many (time-series) | Cassandra partition key = `user_id` |

### Redis Cache Data Model

```
Metadata cache:
  Key:   song:{song_id}
  Value: JSON (title, artist, album, duration, s3_paths)
  TTL:   1 hour

Playlist cache:
  Key:   playlist:{playlist_id}
  Value: JSON (name, owner, songs with positions)
  TTL:   5 minutes (shorter because playlists change more often)

Search cache:
  Key:   search:{normalized_query}
  Value: JSON array of results
  TTL:   5 minutes

Recommendations cache:
  Key:   reco:{user_id}:daily-mix
  Value: JSON array of song_ids
  TTL:   24 hours (regenerated daily by offline pipeline)
```

---

## 10. Concurrency

### Collaborative Playlist Edits

Two users edit the same collaborative playlist at the same time. User A adds a song; User B reorders songs.

```
Problem:
  User A reads playlist (version=5), adds song at position 3
  User B reads playlist (version=5), moves song from position 1 to position 5
  Both submit with version=5 → one will overwrite the other's change

Solution: Optimistic Locking
  UPDATE playlists SET version = version + 1, ...
  WHERE playlist_id = ? AND version = ?

  If version has changed (another user edited first), the UPDATE matches 0 rows.
  Service detects this → returns 409 Conflict → client re-fetches and retries.
```

Why optimistic (not pessimistic)?
- Playlist edits are **infrequent and low-contention** — two users rarely edit the same playlist in the same second.
- Pessimistic locking (SELECT FOR UPDATE) would hold a DB lock, blocking other reads. Overkill for this use case.
- Contrast with Splitwise's balance updates — those ARE high-contention (same balance rows), so pessimistic locking makes sense there.

### Play Count Aggregation

Play counts are updated 46K times/second globally. We do NOT update a `play_count` column in the songs table in real-time — that would create a massive write hotspot on popular songs.

Instead:
1. Play events flow through Kafka → Cassandra (raw events).
2. A periodic batch job (every few minutes) aggregates counts and updates `play_count` in the Metadata DB.
3. The Elasticsearch index picks up updated counts via CDC.

This means play counts are **eventually consistent** (lagging a few minutes), which is perfectly acceptable.

---

## 11. Consistency and Reliability

| Data | Consistency Level | Why |
|------|------------------|-----|
| Song metadata (title, artist, album) | **Strong** (DB write) | Source of truth for catalog. Incorrect metadata = wrong song info shown. |
| Playlist state (songs, ordering) | **Strong** (DB write + optimistic lock) | Users expect their edits to persist immediately. |
| Search results | **Eventual** (outbox → Elasticsearch) | Brief lag between catalog update and search index. Acceptable. |
| Play history | **Eventual** (Kafka → Cassandra) | Minor lag for "recently played." Acceptable. |
| Recommendations | **Eventual** (batch pipeline, regenerated daily) | Users don't expect real-time recommendation updates. |
| Play count | **Eventual** (batch aggregation) | Displaying "2.5B plays" vs "2.500001B plays" doesn't matter. |
| Cache (Redis) | **Eventual** (invalidated on write, TTL safety net) | Brief stale window. Acceptable. |
| Audio files (S3) | **Strong** (immutable, 11 nines durability) | Once uploaded, audio files never change. No consistency concern. |

**Cache invalidation**: Done by application code (`redis.delete(key)`) right after DB transaction commits. Same approach as Splitwise — all writes go through the app, and a short TTL acts as safety net.

### Kafka Topics

| Topic | Partition Key | Value | Producer | Consumer |
|-------|-------------|-------|----------|----------|
| `play-events` | `user_id` | `{user_id, song_id, duration_ms, bitrate, timestamp}` | Play History Service | Cassandra writer, Recommendation pipeline, Royalty service |
| `catalog-events` | `song_id` | `{event_type, song_id, metadata}` | Outbox publisher | Elasticsearch indexer |

Partitioned by `user_id` for play events (all plays for one user are ordered), by `song_id` for catalog events.

---

## 12. Failure Scenarios and Handling

| Failure | Risk | Mitigation | User impact |
|---------|------|------------|-------------|
| **CDN edge down** | Audio not served from that edge | CDN automatically routes to next-closest edge. Multiple CDN providers (multi-CDN). | Slight latency increase; seamless if multi-CDN. |
| **S3 origin down** | New audio not fetchable | S3 is multi-AZ. CDN cache covers popular songs. Only cold long-tail songs affected. | Rare songs temporarily unavailable. |
| **Metadata DB down** | Song info not readable | Redis cache serves reads during outage. Writes fail. | Read-only mode; playlist edits fail temporarily. |
| **Elasticsearch down** | Search unavailable | Fallback to DB-based LIKE query (slower, degraded). Cache serves popular queries. | Search slower/degraded, not broken. |
| **Kafka down** | Play events not ingested | Client buffers events locally; retries. Events are non-blocking for playback. | Playback unaffected. "Recently played" may lag. |
| **Pre-signed URL expired** | Client can't fetch audio | Client detects 403 from CDN → requests new URL from Streaming Service. | Brief pause (~200ms) while new URL is fetched. |
| **Collaborative edit conflict** | Lost update on playlist | Optimistic locking → 409 Conflict → client re-fetches and retries. | User sees "playlist was updated, refreshing." |
| **Cassandra node down** | Play history writes fail for some partitions | Cassandra replication (RF=3). Writes go to other replicas. | No user impact if quorum is met. |

---

## 13. Scale and Sharding

```
┌──────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                   │
│                                                                       │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐    │
│  │ Metadata DB      │   │ Cassandra       │   │ Elasticsearch   │    │
│  │ (PostgreSQL)     │   │ (Play History)  │   │ (Search)        │    │
│  │                  │   │                  │   │                  │    │
│  │ Shard by         │   │ Partition by     │   │ Shard by         │    │
│  │ song_id (catalog)│   │ user_id          │   │ song_id          │    │
│  │ playlist_id      │   │                  │   │                  │    │
│  │ (playlists)      │   │ 46K writes/s     │   │ 100M docs        │    │
│  │                  │   │ spread across     │   │ across 10-20     │    │
│  │ Read replicas    │   │ cluster           │   │ shards           │    │
│  │ for read-heavy   │   │                  │   │                  │    │
│  │ catalog queries  │   │ RF=3 for         │   │ Replicas for     │    │
│  │                  │   │ durability       │   │ read throughput   │    │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘    │
│                                                                       │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐    │
│  │ Redis Cache      │   │ CDN             │   │ Services         │    │
│  │                  │   │                  │   │                  │    │
│  │ Cluster mode     │   │ 50+ global      │   │ STATELESS        │    │
│  │ Hash slot by key │   │ edge PoPs       │   │ → auto-scale     │    │
│  │                  │   │                  │   │ behind LB        │    │
│  │ Eviction: LRU    │   │ Top 1% songs    │   │                  │    │
│  │                  │   │ cached at edge   │   │ Each instance    │    │
│  │ Hot key:         │   │                  │   │ is identical     │    │
│  │ replicate across │   │ Long-tail from   │   │                  │    │
│  │ multiple slots   │   │ origin           │   │                  │    │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘    │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

| Component | Shard/Scale Strategy | Why |
|-----------|---------------------|-----|
| **Metadata DB** | Shard by `song_id` (catalog), `playlist_id` (playlists). Read replicas for catalog reads. | Catalog is read-heavy (70K/s); replicas offload reads. Playlists sharded by ID for write distribution. |
| **Cassandra** | Partition by `user_id`. RF=3. | All play history for one user on same partition → efficient "recently played" query. Write-heavy workload spread across cluster. |
| **Elasticsearch** | 10-20 shards for 100M song docs. Replicas for read throughput. | Parallel search across shards. Replicas handle 12K search QPS. |
| **Redis** | Cluster mode, hash by key. Hot keys (popular song metadata) replicated. | Prevents single-node bottleneck. Hot songs don't overload one slot. |
| **CDN** | 50+ global edge PoPs. Top 1% songs (by play count) pre-warmed at edges. | 1M songs cover 80% of plays (power law). Edge caching reduces origin traffic by 100×. |
| **Services** | Stateless → horizontal auto-scale behind LB. | No in-memory state. Scale up/down based on QPS. |

### Hot Song Problem

When a new hit drops (e.g., Taylor Swift album), millions of users play the same songs simultaneously. This creates:
1. **CDN hotspot**: Solved by CDN's multi-tier caching. Popular content replicated across all edges.
2. **Metadata hotspot**: Redis cache absorbs repeated reads for the same song. TTL prevents thundering herd on cache miss (use locking or request coalescing).
3. **Play event flood**: Kafka partitioned by `user_id`, not `song_id`, so writes for one popular song spread across all partitions.

---

## 14. Final Architecture (Putting It All Together)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    SPOTIFY — COMPLETE ARCHITECTURE                           │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐                                                                │
│  │  Client   │                                                               │
│  └─────┬────┘                                                                │
│        │ ①②③                                                                 │
│        ▼                                                                     │
│  ┌──────────────┐                                                            │
│  │ API Gateway   │                                                           │
│  └──────┬───────┘                                                            │
│    ┌────┴────────┬──────────────┬────────────────┬───────────────┐           │
│    │             │              │                │               │           │
│    ▼             ▼              ▼                ▼               ▼           │
│  ┌──────────┐ ┌──────────┐ ┌───────────┐ ┌───────────┐ ┌─────────────┐     │
│  │Streaming │ │ Search   │ │ Playlist  │ │ Recommend │ │Play History │     │
│  │Service   │ │ Service  │ │ Service   │ │ Service   │ │Service      │     │
│  └────┬─────┘ └────┬─────┘ └─────┬─────┘ └─────┬─────┘ └──────┬──────┘     │
│       │             │             │             │              │             │
│       │ ④           │ ⑤           │ ⑥           │ ⑧            │ ⑦          │
│       ▼             ▼             ▼             ▼              ▼             │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  ┌──────────┐         │
│  │  Redis   │ │Elastic-  │ │Metadata  │ │  Redis   │  │  Kafka   │         │
│  │ (meta    │ │search    │ │DB (PG)   │ │ (reco    │  │(play-    │         │
│  │  cache)  │ │          │ │          │ │  cache)  │  │ events)  │         │
│  └────┬─────┘ └──────────┘ └──────────┘ └──────────┘  └────┬─────┘         │
│       │ miss                                                 │              │
│       ▼                                                      │              │
│  ┌──────────┐                                                │              │
│  │Metadata  │                                          ┌─────┴──────┐       │
│  │DB (PG)   │                                          │            │       │
│  └──────────┘                                          ▼            ▼       │
│                                                  ┌──────────┐ ┌─────────┐  │
│  ┌──────────────────────────────────────┐        │Cassandra │ │ Spark   │  │
│  │ Audio Delivery Path (separate)       │        │(history) │ │(offline)│  │
│  │                                      │        └──────────┘ └────┬────┘  │
│  │  Client ──④──► CDN ──miss──► S3      │                          │       │
│  │                 │                    │                          │       │
│  │           edge cache                 │               ⑨ write reco      │
│  │           (popular songs)            │                          │       │
│  └──────────────────────────────────────┘                          ▼       │
│                                                              ┌──────────┐  │
│                                                              │  Redis   │  │
│                                                              │ (reco)   │  │
│                                                              └──────────┘  │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Numbered Flow Summary

| Step | What Happens | From → To | Protocol | Infra |
|------|-------------|-----------|----------|-------|
| ① | User taps Play / searches / opens playlist | Client → API Gateway | HTTPS | |
| ② | Gateway authenticates, rate limits, routes | API Gateway → Service | HTTP | |
| ③ | Service processes request | Service | Internal | |
| ④ | Streaming: check cache for song metadata; generate pre-signed CDN URL. Client fetches audio from CDN. | Streaming Service → Redis/DB; Client → CDN → S3 | HTTP | Redis, CDN, S3 |
| ⑤ | Search: query Elasticsearch (fuzzy + popularity re-rank) | Search Service → Elasticsearch | HTTP | ES cluster |
| ⑥ | Playlist: read/write playlist state in DB; invalidate cache | Playlist Service → Metadata DB + Redis | SQL + Redis | PG, Redis |
| ⑦ | Play event recorded (after 30s of playback) | Play History Service → Kafka | Kafka PRODUCE | Kafka |
| ⑧ | Recommendations served from cache (precomputed) | Recommendation Service → Redis | Redis GET | Redis |
| ⑨ | Offline pipeline reads history, computes recommendations, writes to cache | Spark → Cassandra → Redis | Batch | Spark |

---

## 15. Trade-off Summary

| Decision | Chosen | Alternative | Why |
|----------|--------|------------|-----|
| **Audio delivery** | Pre-signed CDN URL (server never touches audio) | Server-proxied streaming | CDN handles bandwidth at scale; proxying would require 1Tbps server capacity |
| **Bitrate selection** | Client-side adaptive | Server decides | Client knows its own network better; can switch mid-song without round-trip |
| **Search engine** | Elasticsearch | DB LIKE queries / Solr | ES built for fuzzy full-text search at scale; LIKE doesn't scale to 100M rows |
| **Play history storage** | Cassandra | PostgreSQL | 46K writes/s, append-only, time-series → Cassandra's LSM-tree is ideal; PG would struggle |
| **Playlist concurrency** | Optimistic locking | Pessimistic locking | Low contention; pessimistic would hold DB locks unnecessarily |
| **Recommendations** | Offline batch (Spark) → precomputed | Real-time ML per request | Real-time ML at 200M DAU is too expensive; daily batch is good enough |
| **Search index sync** | Transactional outbox + CDC | Direct ES update after DB write | Guaranteed consistency; no lost index updates if ES is temporarily down |
| **Play count updates** | Batch aggregation (every few minutes) | Real-time counter increment | Real-time updates on a hot counter (popular songs) would create write hotspot |
| **Cache strategy** | App-level invalidation + TTL | CDC-based invalidation | Simple; all writes go through app. TTL as safety net. |

---

## 16. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|------------------|
| Streaming audio through your servers | Your servers become the bandwidth bottleneck (1 Tbps!) | Pre-signed CDN URLs. Server only issues the URL; CDN delivers audio. |
| Storing audio in the database | Databases are not designed for large binary blobs at PB scale | Object storage (S3) for audio; DB only stores metadata. |
| Updating play_count in real-time per play | Hot song = millions of concurrent writes to same row | Batch aggregation via Kafka → periodic DB update. |
| Using SQL LIKE for search | LIKE '%query%' can't use indexes; full table scan on 100M rows | Elasticsearch with inverted index and fuzzy matching. |
| Single bitrate for all users | Users on 3G get buffering; users on WiFi get unnecessary low quality | Pre-encode at multiple bitrates; client selects adaptively. |
| No idempotency on playlist add | Network retry adds the same song twice | Client-generated idempotency key; DB stores it in same transaction. |
| Pessimistic locking on playlists | Holds DB lock during the entire edit; blocks other readers | Optimistic locking (version column). Playlist edits are low-contention. |
| Storing play history in PostgreSQL | 46K writes/s of append-only time-series data | Cassandra with partition by user_id, clustering by timestamp DESC. |

---

## 17. Interview Talking Points

### "Walk me through the architecture"

> Five core services: Streaming Service generates pre-signed CDN URLs (never touches audio bytes), Search Service queries Elasticsearch with fuzzy matching and popularity re-ranking, Playlist Service manages CRUD with optimistic locking for collaborative edits, Play History Service ingests events through Kafka into Cassandra, and Recommendation Service serves precomputed personalized playlists from Redis cache. Audio lives in S3, delivered via CDN. Metadata in PostgreSQL, cached in Redis. Play events flow through Kafka to feed Cassandra (history), Spark (recommendations), and royalty calculation.

### "How does streaming work?"

> User taps play → Streaming Service looks up song metadata, selects bitrate based on client's network quality, generates a pre-signed CDN URL with a 1-hour expiry, and returns it. The client fetches audio directly from the CDN using HTTP Range requests — our servers never proxy audio bytes. The client buffers 10-30 seconds ahead and monitors download speed to adapt bitrate mid-song if needed. CDN edge servers cache popular songs; cache miss goes to S3 origin.

### "How do you handle a viral song that millions play simultaneously?"

> Three layers handle this: (1) CDN replicates popular content across all edge PoPs — the song is served from edge cache, not origin. (2) Song metadata is cached in Redis, so 70K metadata reads/s don't hit the DB. (3) Play events are partitioned by user_id in Kafka, not song_id, so writes for one popular song spread evenly across all Kafka partitions. Play count is updated via batch aggregation, not per-play increment, avoiding a write hotspot.

### "Why not real-time recommendations?"

> At 200M DAU, running an ML model per user per request is prohibitively expensive. Instead, we run a Spark batch job that reads play history from Cassandra, computes collaborative filtering (users who listened to X also listened to Y), and writes precomputed playlists to Redis. Recommendations refresh daily — users don't expect them to change in real-time. The trade-off: if a user's taste changes drastically today, Discover Weekly won't reflect it until tomorrow.

### "How do collaborative playlists handle concurrent edits?"

> Optimistic locking with a version column. Each edit reads the current version, applies the change, and updates with a `WHERE version = read_version` condition. If another user edited in between, the version won't match, the update affects 0 rows, and we return 409 Conflict. The client re-fetches the playlist and retries. This works because playlist edits are low-contention — two users rarely edit the same playlist in the same second. Contrast with Splitwise's balance updates where pessimistic locking is needed due to higher contention.

### "How do you keep Elasticsearch in sync with the catalog DB?"

> Transactional outbox pattern — same as we use in Splitwise, Uber, and BookMyShow. When a new song is inserted into PostgreSQL, an outbox row is written in the same transaction. A CDC process (or poller) reads the outbox and publishes to Kafka. An Elasticsearch indexer consumes from Kafka and updates the search index. If Elasticsearch is temporarily down, events queue in Kafka and are processed when it recovers. Search results may lag a few seconds behind catalog updates.

### "Why Cassandra for play history instead of PostgreSQL?"

> Play events are append-only, write-heavy (46K writes/s), and queried by user + time range ("show me Alice's last 50 plays"). Cassandra's LSM-tree is optimized for sequential writes, and partitioning by user_id with clustering by timestamp DESC gives efficient time-range queries. PostgreSQL could handle this with partitioning, but at 4B events/day, Cassandra's horizontal scalability and tunable consistency (we only need eventual for play history) make it the better fit.

### "What if the CDN is down?"

> Multi-CDN strategy — we contract with 2-3 CDN providers. If one CDN's edge is unreachable, DNS-level or client-level failover routes to another. For a regional outage, the CDN's own anycast routing sends traffic to the next-closest healthy PoP. If all edges fail for a song, the client falls back to fetching from S3 origin (higher latency but functional). Popular songs are replicated across so many edges that a total cache miss is extremely rare.
