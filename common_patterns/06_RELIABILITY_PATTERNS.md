# Reliability Patterns

> How to handle failures, prevent cascading outages, and recover gracefully.

---

## 1. Retry with Exponential Backoff + Jitter

**What**: On transient failure, retry the operation with increasing delays (1s, 2s, 4s, 8s...). Add random jitter to avoid "thundering herd" when many clients retry at the same time.

**When to use**: Transient failures (network timeout, 503, temporary DB overload). Idempotent operations (or operations protected by idempotency keys).
**When NOT to use**: Permanent failures (400 Bad Request, 404 Not Found). Non-idempotent operations without idempotency keys (retry = double action).

### Applied in our systems

| System | What's Retried | Max Retries | Why Backoff + Jitter |
|--------|---------------|-------------|---------------------|
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | PSP API calls (Stripe/PayPal) | 3 retries, then DLQ | PSP may be temporarily overloaded. Without backoff, 1000 payment requests all retry at exactly the same second = overload the PSP again. Jitter spreads retries across a time window. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Agent → Kafka publish, Kafka → ES bulk index | Unlimited (buffer locally) | Agent retries until Kafka is back. Exponential backoff prevents flooding a recovering Kafka. Local disk spool ensures no data loss during retry window. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Push notifications (FCM/APNs) | 3 retries | Push notification services have transient failures. Backoff avoids rate limiting by the push provider. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Hold seat expiry (delayed queue) | N/A (TTL-based) | Jitter on hold duration: `10 min + random(0-60s)`. Without jitter, all holds created at 12:00 expire at 12:10:00 exactly → thundering herd of seat releases. |

**Formula**: `delay = min(base * 2^attempt, max_delay) + random(0, jitter)`

---

## 2. Circuit Breaker

**What**: Monitor failure rate of a downstream service. If failures exceed a threshold, "open" the circuit — immediately fail requests without calling the downstream. After a cooldown, "half-open" to test if the downstream is recovered.

**States**: `CLOSED (normal) → OPEN (fail fast) → HALF-OPEN (test) → CLOSED`

**When to use**: Calling external services or downstream microservices that may be slow or down. Preventing cascading failures.
**When NOT to use**: Internal function calls. Operations that MUST succeed (use retry instead).

### Applied in our systems

| System | What's Protected | Why Circuit Breaker (not just retry) |
|--------|-----------------|--------------------------------------|
| [Google Maps](../google_maps_system/INTERVIEW_CHEATSHEET.md) | External routing/traffic data providers | If a traffic data provider is down, retrying every request wastes time and threads. Circuit breaker = fail fast, serve cached/stale ETA. Open circuit for 30s, then test with one request. |
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | PSP (Stripe) calls | If Stripe is completely down, retrying all payment requests = connection pool exhaustion. Circuit breaker prevents thread starvation. Show "payments temporarily unavailable" instead of hanging. |

**Key insight**: Retry is for transient failures (one-off). Circuit breaker is for sustained failures (service is DOWN). They work together: retry within circuit breaker. When retries consistently fail → circuit opens.

---

## 3. Bulkhead / Resource Isolation

**What**: Isolate resources (thread pools, connection pools, memory) so that a failure in one component doesn't exhaust resources for other components.

**When to use**: Multiple downstream services with different reliability profiles. One slow service shouldn't block all others.
**When NOT to use**: Simple systems with one downstream dependency.

### Applied in our systems

| System | What's Isolated | Why |
|--------|----------------|-----|
| [General pattern](../database_fundamentals/06_SENIOR_GOTCHAS.md) | Separate connection pools per downstream service | If the payment service is slow and consumes all DB connections, the user service (which is healthy) can't get connections. Separate pools: payment pool exhausted ≠ user pool exhausted. |

**Analogy**: Watertight compartments in a ship. One compartment floods, the ship stays afloat. Without bulkheads, one leak sinks everything.

---

## 4. Graceful Degradation

**What**: When a component fails, serve a degraded experience instead of a full outage. Disable non-essential features, serve stale cache, show cached data.

**When to use**: Non-critical features that enhance but aren't essential. Cache data available as fallback.
**When NOT to use**: Core functionality that MUST work (e.g., can't degrade "transfer money" — it either works or it doesn't).

### Applied in our systems

| System | What Degrades | Fallback |
|--------|--------------|----------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Recommendations unavailable | Show "Top Charts" (pre-computed, cached) instead of personalized "Daily Mix." Users still get music, just not personalized. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Ranking service down | Serve chronological feed (sorted by timestamp) instead of ML-ranked feed. Users still see posts, just in default order. |
| [Proximity Service](../proximity_service/INTERVIEW_CHEATSHEET.md) | DB down | Serve cached geohash cells from Redis. Data may be up to 5 minutes stale, but users still see nearby businesses. Better than an error page. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | DB down during redirect | Serve from Redis cache. If the short URL is cached, redirect works. Only brand-new URLs (not yet cached) fail. |

---

## 5. Local Buffering / Spool

**What**: When the downstream is unreachable, buffer data locally on disk. Resume sending when the downstream recovers. Track position (offset/pointer) to avoid data loss or duplication.

**When to use**: Data that absolutely cannot be lost. Producer and consumer are decoupled and may have different availability.
**When NOT to use**: Ephemeral data where loss is acceptable (presence, typing indicators).

### Applied in our systems

| System | What's Buffered | When | Why |
|--------|----------------|------|-----|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Agent buffers log lines to local disk spool | Kafka is unreachable | Logs can't be lost during an outage — that defeats the purpose of a logging system. Agent tracks file offset (byte position). On Kafka recovery, resumes from last offset. No data loss, no duplication. |
| [Google Maps](../google_maps_system/INTERVIEW_CHEATSHEET.md) | Client buffers location updates locally when offline | No network connectivity | User is driving through a tunnel. GPS still works. Buffer location points locally, flush to server when network returns. |

---

## 6. Bounded Replay (Rebuild State from Kafka)

**What**: On restart, replay a bounded window of recent messages from Kafka to rebuild in-memory state. Kafka retains messages for days/weeks, making this possible.

**When to use**: In-memory state that can be derived from recent events. Crash recovery without persisting state to a separate store.
**When NOT to use**: State that requires the full history (use snapshots + replay instead).

### Applied in our systems

| System | What State | Replay Window | Why Not Persistent Store |
|--------|-----------|---------------|--------------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Alert engine's sliding window counters (in-memory ring buffer per alert rule) | Last 60 minutes from Kafka | At 500K logs/s, persisting every counter increment to Redis = 500K writes/s = Redis bottleneck + added latency per log. In-memory = microseconds. Kafka retention (7 days) is the durability layer. On restart, replay 60 min of logs to rebuild counters. Alerts delayed during replay but no state permanently lost. |
| [Distributed MQ](../distributed_message_queue/INTERVIEW_CHEATSHEET.md) | Consumer offsets and group state | Since last checkpoint | Consumer restarts and replays from last committed offset. Messages between last commit and crash are reprocessed (at-least-once). Exactly-once requires transactional consumer. |

---

## 7. Compensating Transactions

**What**: When a multi-step distributed operation fails partway through, undo the completed steps by running reverse operations (compensations). Part of the Saga pattern.

**When to use**: Cross-service or cross-partition transactions where 2PC is too slow. Each step can be reversed (e.g., credit back money, release held resource).
**When NOT to use**: Operations that can't be reversed (e.g., sent email, published tweet). Single-database transactions (just use ROLLBACK).

### Applied in our systems

| System | Forward Action | Compensation on Failure | Why |
|--------|---------------|------------------------|-----|
| [Digital Wallet](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) | Debit wallet A ($100) | Credit wallet A back ($100) | Wallets A and B are on different partitions. Can't use 2PC (holds locks across machines = too slow at 1M TPS). Saga: debit A → credit B. If credit B fails → compensate by crediting A back. Eventually consistent. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Hold seats | Release seats (hold expiry via TTL) | Payment might fail after seats are held. The Hold Manager automatically releases held seats after TTL expires. No manual compensation needed — TTL is the compensation mechanism. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Reserve room → charge payment | If payment fails → release room | Can't hold DB lock while waiting for PSP response (seconds). Reserve room, call PSP async. If PSP fails, compensate by releasing the reservation. |

**Key insight**: Compensations are NOT rollbacks. A rollback undoes uncommitted changes. A compensation creates a NEW transaction that reverses the effect of a COMMITTED transaction. The forward transaction's record remains in the log (audit trail).

---

## 8. Reconciliation

**What**: Periodically compare data across systems to detect and fix drift. Run as a batch job (daily, hourly) that cross-checks source of truth against dependent systems.

**When to use**: Financial systems where data must be correct across multiple stores. Systems with eventual consistency where drift can accumulate.
**When NOT to use**: Systems with strong consistency guarantees (no drift possible).

### Applied in our systems

| System | What's Reconciled | How | Why Needed |
|--------|------------------|-----|-----------|
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Internal ledger vs PSP records vs bank statements | Daily batch job: download PSP settlement file, compare every transaction. Mismatches flagged for manual review or auto-correction. | Three-way reconciliation: our DB, PSP (Stripe), bank. Network failures, webhook drops, and race conditions can cause state divergence between systems. Real-time retries catch 99.9% of issues. Reconciliation catches the remaining 0.1%. "Trust but verify." |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | Real-time aggregated counts vs batch-replayed counts | End-of-day batch replays raw click events from S3. Compares result with real-time aggregation output. | Real-time aggregation may have gaps: watermark delays, consumer crashes during rebalance, late events beyond watermark. Batch replay is the "source of truth" correction pass. |

---

## Common Failure Scenarios Across Systems

| Failure | Systems Affected | Pattern Used |
|---------|-----------------|-------------|
| DB down | All | Graceful degradation (serve from cache), retry |
| Kafka down | Log Aggregation, all event-driven | Local buffering (agent spool), retry |
| External API slow (PSP, CDN) | Payment, Spotify, Video | Circuit breaker, timeout, fallback |
| Network partition | All distributed | Kafka retention (replay), at-least-once, reconciliation |
| Process crash, in-memory state lost | Log Aggregation alerting | Bounded replay from Kafka |
| Duplicate requests (network retry) | All write APIs | Idempotency keys |
| Poison pill message | Payment | Dead letter queue |
| Data drift between systems | Payment, Ad Click | Reconciliation (daily batch) |

---

## Quick Decision Table

| Problem | Pattern | Example |
|---------|---------|---------|
| Transient failure, might work next time | Retry + backoff + jitter | Payment PSP, push notifications |
| Downstream is DOWN (sustained) | Circuit breaker | Google Maps, Payment |
| One slow service starves others | Bulkhead (separate pools) | Connection pool isolation |
| Feature down, serve best-effort | Graceful degradation | Spotify recommendations, News Feed ranking |
| Can't lose data, downstream unreachable | Local buffering / spool | Log agent, Maps offline |
| In-memory state lost on crash | Bounded replay from Kafka | Log Aggregation alerting |
| Multi-step fails midway | Compensating transaction | Digital Wallet, Hotel Reservation |
| Data drift between systems | Reconciliation (batch) | Payment ledger vs PSP |
