# Real-time & API Patterns

> How to push data to clients in real-time, and how to design robust APIs.

---

## 1. WebSocket

**What**: Persistent, bidirectional TCP connection between client and server. Both sides can send messages at any time without polling.

**When to use**: Bidirectional real-time communication. High-frequency updates (multiple per second). Low latency is critical.
**When NOT to use**: Infrequent updates (once per hour). Stateless backend preferred. Server → client only (use SSE instead).

### Applied in our systems

| System | Use Case | Why Not the Alternatives |
|--------|----------|--------------------------|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Real-time messaging between users | Bidirectional: client sends messages AND receives them. Polling would add 1-5s latency per message — unacceptable for chat. SSE is server→client only (can't send messages). |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Driver location updates to rider during trip (every 3-5s) | Continuous location stream. Polling every 3s = same request overhead without the persistent connection benefit. WebSocket is more efficient for frequent updates. Polling is acceptable fallback (simpler, stateless). |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | Bidirectional location sharing between friends | Send my location AND receive friends' locations on the same connection. Must be bidirectional. |
| [Distributed Email](../distributed_email_service/INTERVIEW_CHEATSHEET.md) | Real-time "new email" push to open Gmail tab | Server pushes "you have a new email" without client polling. User expects instant notification when Gmail tab is open. |

**Operational consideration**: WebSocket = stateful servers. Need sticky sessions (hash on `user_id`). Server restart = all connections drop → clients must reconnect. Service discovery (ZooKeeper/etcd) to track which user is connected to which server.

---

## 2. SSE (Server-Sent Events)

**What**: Server-to-client only, over HTTP. Server pushes events; client can't send via the same connection. Simpler than WebSocket.

**When to use**: Server → client notifications only. Simpler infrastructure needed (works over HTTP/2, through proxies).
**When NOT to use**: Bidirectional communication. Client needs to send data frequently.

### Applied in our systems

| System | Use Case | Why Not WebSocket |
|--------|----------|-------------------|
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Alternative for rider tracking (server pushes driver location) | Rider only receives location, doesn't send anything back on the tracking screen. SSE is simpler (HTTP-based, no upgrade handshake, works through corporate proxies). WebSocket is also fine here — it's a trade-off of simplicity vs capability. |

---

## 3. Long Polling

**What**: Client sends HTTP request, server holds it open until new data is available (or timeout). Client immediately re-requests after receiving a response.

**When to use**: Updates are infrequent but need to be delivered promptly. WebSocket infrastructure is not available or not justified.
**When NOT to use**: High-frequency updates (creates one HTTP request per update — inefficient). Bidirectional communication.

### Applied in our systems

| System | Use Case | Why Not WebSocket |
|--------|----------|-------------------|
| [Google Drive](../google_drive_system/INTERVIEW_CHEATSHEET.md) | Notification of file changes to sync clients | File changes are infrequent (maybe 3 times/day for most users). Maintaining a persistent WebSocket for "a file changed 3 times today" is wasteful. Long polling: hold request for 30-60s, respond when something changes, client re-requests. |
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Kafka `consumer.poll()` (server-side pattern) | Internal: chat server long-polls Kafka for new messages. Not client-facing, but same pattern: "block until data arrives." |

---

## Decision Table: WebSocket vs SSE vs Long Polling

| Factor | WebSocket | SSE | Long Polling |
|--------|-----------|-----|-------------|
| Direction | Bidirectional | Server → client | Server → client |
| Protocol | TCP (upgraded from HTTP) | HTTP | HTTP |
| Frequency | High (chat, location) | Medium (notifications) | Low (file sync) |
| Complexity | High (stateful servers) | Medium | Low |
| Proxy-friendly | No (some proxies block) | Yes (HTTP-based) | Yes (HTTP-based) |
| Best for | Chat, live tracking | Dashboard updates | Infrequent sync |

---

## 4. CDC (Change Data Capture)

**What**: Capture database changes (inserts, updates, deletes) from the DB's write-ahead log and publish them as events. Tools: Debezium, Maxwell, DynamoDB Streams.

**When to use**: Sync a secondary store (Elasticsearch, cache) without modifying application code. Capture all changes, including those from background jobs or manual SQL.
**When NOT to use**: All writes go through your application (app-level invalidation is simpler and more predictable). Real-time alerting (CDC adds latency).

### Applied in our systems

| System | What's Captured | Why CDC (not application-level) |
|--------|----------------|--------------------------------|
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Trip state changes → Kafka via Debezium | Outbox poller as alternative. CDC captures changes without modifying application code. Useful when multiple services write to the same DB. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Booking/hold events → Kafka | Alternative to outbox poller. CDC-based outbox: instead of polling the outbox table, Debezium tails the DB's WAL and publishes new outbox rows. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Playlist changes → Elasticsearch index | Keep search index in sync with metadata DB. CDC captures all changes, including admin edits that bypass the API. |
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Application-level invalidation used instead | All balance writes go through `ExpenseService`. `redis.delete("balances:group:{id}")` after DB commit is simpler. No CDC infrastructure needed. Short TTL (30s) as safety net. |

**CDC vs Transactional Outbox**: CDC captures changes from the DB log (no application code change). Outbox writes events explicitly in a transaction. CDC is more transparent; Outbox is more explicit and gives you control over event schema.

**Theory**: [database_fundamentals/04_REALTIME_UPDATES.md](../database_fundamentals/04_REALTIME_UPDATES.md) — CDC

---

## 5. Pre-signed URLs

**What**: Server generates a short-lived, authenticated URL that allows the client to upload to or download from S3/CDN directly, bypassing the API server for large files.

**When to use**: Large file transfer (audio, video, images). API server bandwidth is limited. Client-to-storage direct access.
**When NOT to use**: Small data (< 1 MB). Data requiring server-side processing before storage.

### Applied in our systems

| System | What's Pre-signed | Why Not Proxy Through Server |
|--------|-------------------|------------------------------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | CDN URL for audio file chunks | Audio is 5 MB+ per song. 100M users streaming simultaneously = 1 Tbps bandwidth. Proxying through API servers would require thousands of servers just for bandwidth. Pre-signed URL = client downloads directly from CDN edge. |
| [Video Streaming](../video_streaming_system/INTERVIEW_CHEATSHEET.md) | S3 upload URL (pre-signed PUT) + CDN download URL | Videos are GBs. Server can't hold a GB in memory to proxy. Client uploads directly to S3 via pre-signed PUT. Server only generates the URL and updates metadata. |
| [Google Drive](../google_drive_system/INTERVIEW_CHEATSHEET.md) | Block upload URLs (pre-signed PUT for each 4 MB block) | Files are split into blocks. Each block uploaded directly to S3. Server orchestrates but never touches the data. |

**How it works**: Server calls S3 API to generate URL with embedded credentials + expiry (e.g., 15 min). Client uses the URL directly. URL expires after TTL — no long-lived credentials on client.

---

## 6. Heartbeat / Presence Detection

**What**: Client periodically sends a "I'm alive" signal. Server sets a TTL-based key. If the heartbeat stops, the key expires and the user is considered offline.

**When to use**: Online/offline status. "Is this service/user alive?" Real-time presence in chat/social apps.
**When NOT to use**: Systems where presence doesn't matter.

### Applied in our systems

| System | Mechanism | Why This Approach |
|--------|-----------|-------------------|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Client sends heartbeat every 30s. Redis key `presence:{user_id}` with 60s TTL. If TTL expires → offline. | Push-based: user says "I'm alive." No polling needed. TTL handles ungraceful disconnects (battery dies, network drops) automatically. Debouncing: batch presence updates every 1s to avoid Redis overload. |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | Location update acts as implicit heartbeat. No update for 30s → hide from map. | Piggyback on location updates. No separate heartbeat needed. If location stops, user is either offline or stationary — either way, stop showing on friends' maps. |

---

## 7. Webhooks (Async Third-party Callback)

**What**: A server-to-server HTTP POST that a third-party sends to your endpoint when an event occurs. Reverse of polling: "don't call us, we'll call you."

**When to use**: Receiving async results from external services (PSP, SMS provider). Sending alerts to external systems (PagerDuty, Slack).
**When NOT to use**: Internal service-to-service communication (use Kafka or gRPC).

### Applied in our systems

| System | Who Calls Back | Why Webhook (not polling) |
|--------|---------------|--------------------------|
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | PSP (Stripe/PayPal) calls `POST /webhooks/payment` with payment result | Payment processing takes seconds to minutes (fraud checks, bank calls). Polling the PSP every second = 60 wasted requests per minute. PSP pushes the result when ready. Idempotency key in webhook payload to handle duplicates. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Alert Engine calls PagerDuty/Slack webhook when alert threshold breached | Fire-and-forget notification. Alert engine shouldn't block waiting for PagerDuty's response. Retry with backoff on failure. Cooldown to suppress duplicate alerts. |

**Webhook reliability**: Webhooks can fail (your server down, network issue). The sender typically retries with exponential backoff. Your endpoint must be **idempotent** (same webhook delivered twice should not cause duplicate processing).

---

## 8. Idempotency Keys in APIs

**What**: Client includes a unique key (UUID) in request header. Server checks if this key was already processed. If yes, return cached response. If no, process and store the key.

**When to use**: Non-idempotent write endpoints (POST to create, confirm, transfer). Any API called over unreliable networks.
**When NOT to use**: GET requests (naturally idempotent). PUT that sets absolute state (naturally idempotent). DELETE by specific ID (naturally idempotent).

**Pattern**:
```
POST /api/v1/expenses
Headers: Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000

Server:
1. Check idempotency_keys table for this key
2. If found → return stored response (no reprocessing)
3. If not → process request, store key + response in SAME DB transaction
```

**Key insight**: Store in the SAME transaction as the business write. If idempotency key is in Redis but business data is in MySQL, a crash between the two creates inconsistency (data committed but key not stored, or vice versa).

Detailed per-system usage is covered in [02_CONSISTENCY_PATTERNS.md](02_CONSISTENCY_PATTERNS.md) § Idempotency.

---

## 9. Rate Limiting

**What**: Limit the number of requests a client can make in a time window. Protects backend from abuse and overload.

**When to use**: Public APIs. High-traffic endpoints. Protection against DDoS and abuse.
**When NOT to use**: Internal service-to-service calls (use circuit breaker instead).

### Common algorithms

| Algorithm | How It Works | Best For |
|-----------|-------------|----------|
| Token Bucket | Refill N tokens/sec. Each request costs 1 token. | Allowing bursts up to bucket size. |
| Sliding Window | Count requests in a sliding time window. | Smooth rate limiting, no burst. |
| Fixed Window | Count requests per fixed interval (e.g., per minute). | Simple, but allows 2x burst at window boundary. |

### Applied in our systems

| System | Where | Limit Example |
|--------|-------|---------------|
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | API Gateway + per-show rate limit | Hot shows: cap concurrent hold requests to prevent overwhelming the seat lock. Virtual waiting room queues excess users. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | API Gateway, per API key | Prevent abuse: cap URL creation at 100/min per key. Redirects are not rate-limited (they should be fast). |
| [Autocomplete](../autocomplete_system/INTERVIEW_CHEATSHEET.md) | API Gateway | 20 queries/sec per user. Client-side debouncing (50ms) reduces request volume before rate limiting kicks in. |

---

## 10. Pagination (Cursor vs Offset)

**What**: Return results in pages instead of all at once. Two approaches: offset-based (`LIMIT 20 OFFSET 40`) and cursor-based (opaque token pointing to the last seen item).

| Aspect | Offset-based | Cursor-based |
|--------|-------------|-------------|
| **How** | `LIMIT 20 OFFSET 40` | `WHERE id > last_seen_id LIMIT 20` |
| **Performance** | Degrades on deep pages (DB scans offset rows) | Constant (index seek) |
| **Consistency** | Skips/duplicates if data changes between pages | Stable (always starts from known position) |
| **Best for** | Admin panels, small datasets | Feeds, timelines, large datasets |

### Applied in our systems

| System | Approach | Why |
|--------|----------|-----|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | **Cursor** (Cassandra clustering key = `created_at`) | Messages are ordered by time. "Load older messages" = cursor pointing to oldest visible message. Deep pagination is common (scrolling through history). |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | **Cursor** (opaque token encoding timestamp + post_id) | Feed is infinite scroll. Offset would cause duplicates when new posts are added between page requests. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Cursor** (Elasticsearch `search_after`) | Log search results can be millions. Offset pagination on Elasticsearch is limited to 10K results. `search_after` uses the sort values of the last document as cursor. |
| [Distributed Email](../distributed_email_service/INTERVIEW_CHEATSHEET.md) | **Cursor** (Cassandra partition: `user_id`, cluster: `email_id`) | Mailbox can have thousands of emails. Cursor-based for efficient deep pagination. |
| [S3 Object Storage](../s3_object_storage_system/INTERVIEW_CHEATSHEET.md) | **Cursor** (continuation token for listing objects) | S3 buckets can have billions of objects. Offset is impossible. Continuation token resumes from last key. |

---

## Quick Decision Table

| Problem | Pattern | Example |
|---------|---------|---------|
| Bidirectional real-time (chat, tracking) | WebSocket | Chat, Uber |
| Server → client notifications only | SSE | Uber (alternative) |
| Infrequent sync (few updates/day) | Long Polling | Google Drive |
| Sync secondary store from DB changes | CDC (Debezium) | Uber, Spotify |
| Large file upload/download | Pre-signed URLs | Spotify, Video Streaming |
| "Is the user online?" | Heartbeat + TTL | Chat, Nearby Friends |
| Async result from external service | Webhook | Payment PSP, PagerDuty |
| Prevent duplicate processing | Idempotency Key | All write APIs |
| Protect from abuse/overload | Rate Limiting | BookMyShow, URL Shortener |
| Efficient list traversal | Cursor-based pagination | Chat history, feeds, logs |
