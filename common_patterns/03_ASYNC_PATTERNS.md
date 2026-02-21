# Async & Messaging Patterns

> How to decouple services, handle bursts, and process events reliably.

---

## 1. Kafka as Durable Buffer

**What**: Place Kafka between producers and consumers to absorb bursts, retain data for replay, and decouple processing speed. Kafka retains messages for a configurable period (e.g., 7 days) even after consumption.

**When to use**: Producer rate varies (spikes during deployments, marketing events). Consumer may be temporarily down. You need replay capability.
**When NOT to use**: Sub-millisecond latency requirements (Kafka adds ~5ms). Simple request-response flows with no downstream consumers.

### Applied in our systems

| System | What's Buffered | Why Kafka (not direct call) |
|--------|----------------|----------------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Raw log lines from 5,000 agents | Burst handling: a deployment can 10x log volume in minutes. Kafka absorbs the spike while Elasticsearch indexes at its own pace. 7-day retention = replay if processor crashes. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Play events from streaming service | Write-heavy: millions of plays/day. Can't call Cassandra synchronously from the streaming path — would add latency. Kafka decouples and enables batching. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | Click events for analytics | Analytics is not on the critical redirect path. Async via Kafka keeps redirect latency < 10ms. Analytics pipeline processes at its own pace. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Booking events (confirmed, expired) | E-ticket generation and notification are side effects. Don't slow down the confirm API. Kafka ensures they eventually happen. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Trip events (created, completed, cancelled) | Billing, analytics, and driver payouts are downstream consumers. The trip API shouldn't wait for all of them. |

**Key insight**: Kafka is NOT a message queue (like RabbitMQ). It's a **distributed commit log**. Messages are retained for days/weeks, not deleted on consumption. Multiple consumer groups read independently.

**Theory**: [database_fundamentals/04_REALTIME_UPDATES.md](../database_fundamentals/04_REALTIME_UPDATES.md) — Event-driven architecture

---

## 2. Consumer Groups (Parallel Consumers on Same Topic)

**What**: Multiple consumer groups read the same Kafka topic independently. Each group processes all messages, but within a group, each partition is consumed by exactly one consumer (parallelism).

**When to use**: Same data needs to be processed differently by different services.
**When NOT to use**: Single consumer that processes all messages.

### Applied in our systems

| System | Consumer Groups on Same Topic | Why Separate Groups |
|--------|------------------------------|---------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Log Processor** (indexes to ES) + **Alert Engine** (evaluates rules) — both consume `raw-logs` | Different processing speeds: indexing is slow (bulk batches), alerting is fast (per-message). Different failure modes: alert engine crash shouldn't stop indexing, and vice versa. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | **Fanout Service** (updates feed caches) + **Notification Service** (sends push) — both consume `post-events` | Fan-out to 10K users is slow. Notifications should fire immediately, not wait for fan-out to finish. |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Aggregation Pipeline** + **Raw Archive** — both consume `click-events` | Archive raw events to S3 for reconciliation. Aggregation pipeline processes for real-time dashboards. Independent concerns. |

**Key detail**: Within a group, Kafka assigns partitions to consumers. If you have 10 partitions and 5 consumers, each consumer gets 2 partitions. Adding more consumers than partitions = some sit idle.

---

## 3. Redis Pub/Sub vs Kafka

**What**: Both enable event-driven communication, but with fundamentally different guarantees.

| Aspect | Redis Pub/Sub | Kafka |
|--------|--------------|-------|
| **Durability** | None — if no subscriber is listening, message is lost | Durable — retained for days/weeks |
| **Replay** | Impossible | Replay from any offset |
| **Throughput** | Lower (single-threaded per shard) | Higher (partitioned, sequential disk I/O) |
| **Latency** | Sub-millisecond | ~5ms |
| **Use case** | Real-time fan-out where loss is OK | Reliable event processing |

### Applied in our systems

| System | Choice | Why |
|--------|--------|-----|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | **Redis Pub/Sub** for presence updates | "Alice is online" is ephemeral. If the update is lost, the next heartbeat (30s) corrects it. No need for durability. Sub-ms latency matters for real-time presence. |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | **Redis Pub/Sub** for location broadcasts | Friend locations are updated every 30s. A lost update is corrected by the next one. Durability is unnecessary for transient location data. |
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | **Kafka** for expense/settlement events | Can't lose a "Bob added a $200 expense" notification. Financial events must be durable. Outbox → Kafka → Notification service. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Kafka** for raw log transport | Logs must not be lost. Kafka retains for 7 days. Replay capability is critical for reprocessing. |

**Thumb rule**: If losing a message is acceptable (presence, location, typing indicator) → Redis Pub/Sub. If losing a message means data loss or missed notification → Kafka.

---

## 4. Stream Processing: Flink vs Spark vs Simple Consumer

**What**: Three approaches to processing event streams, each with different latency and complexity trade-offs.

| Aspect | Simple Kafka Consumer | Apache Flink | Apache Spark Streaming |
|--------|----------------------|-------------|----------------------|
| **Latency** | Milliseconds | Milliseconds | Seconds to minutes |
| **Windowing** | Manual (ring buffer) | Built-in: tumbling, sliding, session | Built-in (micro-batch) |
| **State on crash** | Lost → bounded replay | Checkpointed to RocksDB + HDFS | Checkpointed |
| **Exactly-once** | At-least-once | Exactly-once via checkpoints | Exactly-once via micro-batch |
| **Complex rules** | Must hand-code | Built-in: joins, CEP, pattern detection | Possible but high latency |
| **Ops overhead** | Just a JAR | Full Flink cluster | Full Spark cluster |

### Applied in our systems

| System | Choice | Why This (not the alternatives) |
|--------|--------|--------------------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Simple Kafka consumer** + in-memory ring buffer | Alert rules are simple: "ERROR count > 100 in 5 min." Just a counter per (rule, service). No Flink overhead needed. Graduate to Flink when rules need multi-stream joins or exactly-once. |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Flink** (or equivalent) | Complex: tumbling windows with watermarks, exactly-once aggregation, atomic commit (offset + DB write). Simple consumer can't handle watermark-based late event processing. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | **Spark** (batch aggregation) | Daily play count aggregation. Counts don't need real-time precision ("1.2M plays" updated daily is fine). Batch is simpler and cheaper than real-time streaming. |

**Progression**: Start with a simple Kafka consumer. Graduate to Flink when you need complex windowing, multi-stream joins, or exactly-once. Use Spark for batch analytics (not alerting).

---

## 5. Exactly-once Processing

**What**: Each event is processed exactly once — no duplicates, no losses. Different from API-level idempotency; this is about stream processing semantics.

**When to use**: Financial aggregation (ad click counting, payment processing). Data where double-counting has real consequences.
**When NOT to use**: Logging, analytics where approximate counts are acceptable.

### Applied in our systems

| System | How Exactly-once Is Achieved | Why This Approach |
|--------|------------------------------|-------------------|
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | **Atomic commit**: commit Kafka consumer offset + write aggregated result to DB in one transaction | If offset commits but DB write fails → reprocessing skips the event (undercounted). If DB writes but offset doesn't commit → event reprocessed (double-counted). Atomic commit = both or neither. |
| [Distributed MQ](../distributed_message_queue/INTERVIEW_CHEATSHEET.md) | **Idempotent producer** (`producer_id` + `sequence_number`) + **transactional consumer** | Producer retries can't create duplicates (broker deduplicates by sequence number). Consumer reads-processes-writes in one Kafka transaction. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | **At-least-once + ES deduplication** by `log_id` | True exactly-once is too expensive at 500K logs/s. Dedup on the storage side (Elasticsearch ignores duplicate `log_id`) is cheaper. Acceptable because a duplicate log line is harmless. |

**Key insight**: True exactly-once requires **atomic commit** (offset + side effect in one transaction). For systems where duplicates are harmless, at-least-once + dedup is simpler and cheaper.

---

## 6. Backpressure

**What**: When a consumer can't keep up with the producer, the system must decide: drop messages, buffer them, or slow down the producer.

**When to use**: Variable-rate producers (log spikes, viral events). Consumers with limited processing capacity.
**When NOT to use**: Producers and consumers that always run at matched speeds.

### Applied in our systems

| System | Mechanism | Why This Approach |
|--------|-----------|-------------------|
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Kafka absorbs burst (7-day retention). If ES is slow, consumer lag grows but no data loss. Agent buffers locally if Kafka itself is overwhelmed. | Three layers of buffering: agent disk → Kafka → consumer lag. Logs are too important to drop. The system prefers delayed processing over data loss. |
| [Distributed MQ](../distributed_message_queue/INTERVIEW_CHEATSHEET.md) | Pull-based consumers control their own consumption rate. No push = no overwhelming. | This is why Kafka chose pull over push. Push-based systems (RabbitMQ) must implement complex flow control. Pull = consumer decides when it's ready. |

---

## 7. Dead Letter Queue (DLQ)

**What**: Messages that fail processing after N retries are moved to a separate "dead letter" topic/queue for manual inspection and reprocessing.

**When to use**: When some messages are "poison pills" (malformed, triggering bugs) and you don't want them to block the entire pipeline.
**When NOT to use**: When all messages must be processed in order (DLQ breaks ordering).

### Applied in our systems

| System | What Goes to DLQ | Why |
|--------|-----------------|-----|
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Payment events that fail processing after 3 retries (e.g., PSP returns unexpected status) | Can't silently drop a payment event. Can't retry forever (might be a bug). DLQ = park it, alert an engineer, process manually. The rest of the pipeline continues. |

---

## 8. Batch Pre-computation

**What**: Run periodic batch jobs to pre-compute results that would be too expensive to compute in real-time. Contrasts with stream processing.

**When to use**: Aggregations where staleness is acceptable (daily counts, weekly rankings). Expensive computations (ML features, trie rebuilds).
**When NOT to use**: Real-time alerting. Data that must be accurate to the second.

### Applied in our systems

| System | What's Pre-computed | Why Not Real-time |
|--------|--------------------|--------------------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Daily play count aggregation (Spark batch) | Millions of play events/day. Real-time counter per song = hot key problem in Redis (Taylor Swift's song = millions of INCR/day). Daily batch is accurate enough for display ("1.2M plays"). |
| [Autocomplete](../autocomplete_system/INTERVIEW_CHEATSHEET.md) | Weekly trie rebuild from aggregated query logs | Trie is immutable once built. Rebuilding weekly from log data is simpler than real-time trie mutation (which requires complex concurrency). Blue-green swap for zero-downtime cutover. |
| [Ad Click Aggregation](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) | End-of-day reconciliation batch | Real-time aggregation may have gaps (watermark delays, consumer crashes). Daily batch replays raw data = "source of truth" correction pass. Catches any drift. |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Ranking score computation (ML features) | ML-based engagement probability is expensive to compute. Pre-compute scores periodically, apply at feed assembly time. |

---

## Quick Decision Table

| Problem | Pattern | Example |
|---------|---------|---------|
| Decouple producer/consumer, handle bursts | Kafka as durable buffer | Log Aggregation, Spotify |
| Same event processed by multiple services | Consumer groups | Log Aggregation (indexer + alerter) |
| Real-time ephemeral fan-out, loss OK | Redis Pub/Sub | Chat presence, Nearby Friends |
| Simple threshold alerting | Simple Kafka consumer | Log Aggregation |
| Complex windowed aggregation | Flink | Ad Click Aggregation |
| Offline analytics / reports | Spark batch | Spotify play counts |
| Financial counting, no duplicates | Exactly-once (atomic commit) | Ad Click Aggregation |
| Consumer can't keep up | Backpressure (Kafka pull + buffer) | Log Aggregation |
| Poison pill messages | Dead Letter Queue | Payment System |
| Expensive computation, staleness OK | Batch pre-computation | Autocomplete trie, Spotify counts |
