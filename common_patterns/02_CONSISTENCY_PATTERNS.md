# Consistency Patterns

> How to keep data correct when multiple users or services modify it concurrently.

---

## 1. Pessimistic Locking (SELECT ... FOR UPDATE)

**What**: Acquire a database row lock BEFORE reading. Other transactions block until the lock is released. Guarantees no concurrent modification.

**When to use**: High contention on the same rows. Financial data where lost updates are unacceptable. Bounded hold time (transaction completes quickly).
**When NOT to use**: Low contention (lock overhead wasted). Read-heavy workloads (locks block readers in some isolation levels). Distributed systems where DB-level locking doesn't span nodes.

### Applied in our systems

| System | Use Case | Why Not Optimistic |
|--------|----------|--------------------|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Lock group's balance rows during `addExpense` — ensures `expenses` + `expense_splits` + `balances` update atomically | Financial data — a lost update means wrong balances. Optimistic locking would require users to retry expense creation on conflict, which is bad UX for money operations. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Lock `show_seats` rows during seat hold | Hot shows: 1000s of users competing for the same 5 seats. Optimistic locking would fail 99% of attempts → terrible UX. Pessimistic queues requesters and serves them sequentially. |

### Lock Ordering (Deadlock Prevention)

When locking multiple rows, always acquire locks in a **deterministic order**:

| System | What's Ordered | Why |
|--------|---------------|-----|
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Sort `seat_ids` before locking: `[S3, S1, S7] → [S1, S3, S7]` | User A locks S1→S3, User B locks S3→S1 = **deadlock**. If both sort first, both attempt S1→S3 = one waits, no deadlock. |

**Theory**: [database_fundamentals/02_DATABASE_LOGIC.md](../database_fundamentals/02_DATABASE_LOGIC.md) — Locking

---

## 2. Optimistic Locking (Version Column)

**What**: No lock at read time. On write, check `WHERE version = X`. If version changed since read, the write fails and the client retries with fresh data.

**When to use**: Low contention (conflicts are rare). Retries are acceptable UX. Read-heavy with occasional writes.
**When NOT to use**: High contention (too many retries). Financial operations where "retry" is bad UX.

### Applied in our systems

| System | Use Case | Why Not Pessimistic |
|--------|----------|--------------------|
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Collaborative playlist edits (`version` column) | Low contention — two friends rarely edit the same playlist at the exact same second. Retry on conflict is fine (re-fetch playlist, re-apply change). Pessimistic would block all playlist readers while one person edits. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Room inventory update: `UPDATE SET rooms = rooms - 1, version = version + 1 WHERE version = X` | Moderate contention. Retry on conflict is acceptable (user just sees "try again"). Pessimistic would serialize all bookings for the same hotel. |

---

## 3. Atomic Conditional Updates (UPDATE ... WHERE status = X)

**What**: Use the current state as a precondition in the UPDATE. If rows affected = 0, someone else already changed the state. No explicit lock needed — the DB's row-level atomicity handles it.

**When to use**: State machines where only one transition should succeed (e.g., REQUESTED → ACCEPTED).
**When NOT to use**: Multi-row updates. Complex state transitions requiring reads before writes.

### Applied in our systems

| System | Use Case | Why This (not locking) |
|--------|----------|------------------------|
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Driver accepts ride: `UPDATE trips SET status='ACCEPTED', driver_id=D WHERE trip_id=T AND status='REQUESTED'` | Two drivers tap "accept" simultaneously. Only one gets `rows_affected = 1`. The other sees `0` and gets a "ride already taken" response. No explicit lock, no retry — just a single atomic statement. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Seat state: `UPDATE show_seats SET status='HELD' WHERE show_id=S AND seat_id=X AND status='AVAILABLE'` | Same pattern. Only one user can hold a seat. The DB row-level atomicity prevents double-booking without explicit `SELECT FOR UPDATE`. |
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Order state: `UPDATE payment_orders SET status='EXECUTING' WHERE order_id=O AND status='CREATED'` | Prevents double-charge. If the payment is already executing, the second attempt sees `rows_affected = 0`. |

---

## 4. Idempotency

**What**: The same operation applied multiple times produces the same result as applying it once. Achieved via a client-generated unique key, stored in the DB, checked before processing.

**When to use**: Any non-idempotent write API (create, transfer, confirm). Network retries, at-least-once delivery.
**When NOT to use**: Naturally idempotent operations (GET, DELETE of a specific ID, PUT that sets absolute state).

### Applied in our systems

| System | Key | Stored Where | Why This Storage |
|--------|-----|-------------|------------------|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Client UUID in `Idempotency-Key` header | DB `idempotency_keys` table (same transaction as expense) | Must be atomic with expense write — if expense commits but HTTP response is lost, client retries and gets the same result without double-creating the expense. DB table (not Redis) because it must be in the same DB transaction. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Client UUID on driver accept | DB `idempotency_keys` table | Prevents double-accept if driver taps "accept" twice on flaky network. Second attempt returns the existing trip. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Client UUID on booking confirm | DB `idempotency_keys` table | Prevents double-booking if confirm call is retried after HTTP timeout. Must be in same transaction as booking write. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | Client UUID on create short URL | DB with UNIQUE constraint | Prevents creating two short codes for the same create request on retry. |
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Client UUID, checked at every layer | DB UNIQUE constraint + PSP idempotency key | Idempotency at EVERY hop: client → our API → PSP. Each layer has its own key. PSP also supports idempotency keys (Stripe does). |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Client UUID on reservation | DB UNIQUE constraint | Prevents double-booking on retry. |

**Key insight**: Store the idempotency key in the **same DB transaction** as the business write. If they're in different stores (e.g., Redis for idempotency, MySQL for data), a crash between the two creates inconsistency.

---

## 5. Transactional Outbox

**What**: Write the event AND the business data in the same DB transaction. A background poller (or CDC) reads the outbox table and publishes to Kafka. Solves the dual-write problem.

**When to use**: You need to update a DB AND publish an event, and can't afford to lose the event.
**When NOT to use**: Fire-and-forget notifications where losing an event is acceptable.

### Applied in our systems

| System | Events in Outbox | Why Not Direct Kafka Publish |
|--------|-----------------|------------------------------|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | `EXPENSE_ADDED`, `SETTLED_UP` | Dual-write problem: if DB commits but Kafka publish fails, the notification service never sends "Alice added an expense." Outbox = one DB transaction, then a poller reliably publishes. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | `TRIP_CREATED`, `TRIP_COMPLETED` | Billing and analytics must eventually see every trip. A missed event = driver not paid. Outbox ensures zero event loss. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | `BOOKING_CONFIRMED`, `HOLD_EXPIRED` | E-ticket generation and notification must not be lost. If Kafka publish fails after DB commit, the user has a booking but no e-ticket. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Playlist change events (for search index sync) | Elasticsearch index must eventually reflect playlist changes. Direct publish could lose events → stale search results. |

**The dual-write problem**: If you `commit to DB` then `publish to Kafka` as two separate steps, a crash between them means the event is lost. If you reverse the order, a crash means the event is published but data isn't in the DB. The outbox pattern eliminates this by making it one atomic DB write.

**Theory**: [database_fundamentals/04_REALTIME_UPDATES.md](../database_fundamentals/04_REALTIME_UPDATES.md) — Outbox pattern

---

## 6. Saga (Cross-Service / Cross-Partition Coordination)

**What**: A sequence of local transactions across services/partitions. Each step has a compensating action. If step N fails, run compensations for steps N-1 → 1.

**When to use**: Distributed transactions spanning multiple databases or services where 2PC is too slow.
**When NOT to use**: Single-database operations (just use a transaction). Operations where partial completion is dangerous and can't be compensated.

### Applied in our systems

| System | Saga Steps | Why Not 2PC |
|--------|-----------|-------------|
| [Digital Wallet](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) | Transfer: (1) Debit wallet A → (2) Credit wallet B. Compensation: if step 2 fails, credit A back. | Wallets A and B may be on different partitions (different physical machines). 2PC would require both to lock and coordinate — too slow at 1M TPS. Saga with compensation = eventual consistency. |
| [Hotel Reservation](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) | Reserve: (1) Reserve room → (2) Charge payment. Compensation: if payment fails, release room. | Payment is via external PSP (Stripe). Can't hold a DB lock while waiting for PSP response (could take seconds). Saga releases the lock and compensates on failure. |

**Theory**: [database_fundamentals/03_DISTRIBUTED_SYSTEMS.md](../database_fundamentals/03_DISTRIBUTED_SYSTEMS.md) — 2PC vs Saga

---

## 7. State Machine (Atomic State Transitions)

**What**: Model entity lifecycle as explicit states with defined transitions. Enforce transitions atomically using conditional updates (`UPDATE WHERE status = 'CURRENT_STATE'`).

**When to use**: Entities with a lifecycle (orders, trips, seats, payments). Need to prevent invalid transitions.
**When NOT to use**: Simple CRUD without lifecycle stages.

### Applied in our systems

| System | States | How Transitions Are Protected |
|--------|--------|-------------------------------|
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Trip: `REQUESTED → ACCEPTED → IN_PROGRESS → COMPLETED / CANCELLED` | `UPDATE trips SET status='ACCEPTED' WHERE trip_id=X AND status='REQUESTED'` — if `rows_affected = 0`, someone else already accepted. Prevents double-accept without locking. |
| [BookMyShow](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) | Seat: `AVAILABLE → HELD → CONFIRMED` | Same atomic conditional UPDATE. Seat can't jump from AVAILABLE to CONFIRMED — must pass through HELD (payment step). |
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Order: `CREATED → EXECUTING → SUCCESS / FAILED` | Each transition is an atomic UPDATE with status precondition. Prevents double-charge. Can't go from FAILED back to EXECUTING. |

**Key insight**: State machine + atomic conditional update is simpler than pessimistic locking for single-row state transitions. It's the go-to pattern for "only one actor should win."

---

## 8. Conflict Resolution

**What**: When two users modify the same data concurrently, decide whose change wins (or merge both).

**When to use**: Collaborative editing, offline sync, multi-device updates.
**When NOT to use**: Single-writer systems. Financial data (use locking instead — conflicts are unacceptable).

### Applied in our systems

| System | Strategy | Why This Strategy |
|--------|----------|-------------------|
| [Google Drive](../google_drive_system/INTERVIEW_CHEATSHEET.md) | Last-writer-wins + conflict copy | For file sync, LWW is simple and predictable. If two users edit offline, the later sync wins and the earlier becomes a "conflict copy." Users resolve manually. More complex merging (OT/CRDT) is overkill for file sync. |
| [Spotify](../spotify_system/INTERVIEW_CHEATSHEET.md) | Optimistic locking (version) → retry | Collaborative playlist: detect conflict via version mismatch, client re-fetches and re-applies. Works because playlist edits are infrequent — conflicts are rare. |
| [Nearby Friends](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) | Last-writer-wins (latest location) | Location is inherently "latest wins" — the newest GPS reading IS the correct location. No merge needed. Old values are immediately obsolete. |

**Theory**: [database_fundamentals/03_DISTRIBUTED_SYSTEMS.md](../database_fundamentals/03_DISTRIBUTED_SYSTEMS.md) — Conflict resolution, CRDTs

---

## 9. Snowflake ID / Distributed ID Generation

**What**: Generate globally unique, time-sortable IDs without a central coordinator. Format: `timestamp (41 bits) + machine_id (10 bits) + sequence (12 bits)` = 64-bit integer.

**When to use**: Distributed systems needing unique, sortable IDs at high throughput.
**When NOT to use**: Small-scale systems where DB auto-increment suffices.

### Applied in our systems

| System | ID Type | Why Not UUID / Auto-increment |
|--------|---------|-------------------------------|
| [Chat System](../chat_system/INTERVIEW_CHEATSHEET.md) | Message ID (Snowflake) | Need time-sortable IDs for message ordering without coordination. UUID is random (not sortable — can't determine which message came first). Auto-increment requires a single DB (bottleneck at 100K msgs/sec). |
| [News Feed](../news_feed_system/INTERVIEW_CHEATSHEET.md) | Post ID (Snowflake) | Same: chronological ordering for feed ranking. Snowflake embeds timestamp → natural sort order. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | Range-based allocation (variant of distributed ID) | Each server pre-allocates a range (e.g., 1M-2M IDs). No coordination per request. Same principle: distribute ID generation to avoid a single-point bottleneck. |

**Comparison**:

| Approach | Unique? | Sortable? | Coordination? | Compact? |
|----------|---------|-----------|---------------|----------|
| Auto-increment | Yes | Yes | Single DB bottleneck | Yes (integer) |
| UUID v4 | Yes | No (random) | None | No (128 bits, string) |
| Snowflake | Yes | Yes (time) | None (machine_id assigned once) | Yes (64 bits) |

---

## 10. Double-entry Ledger

**What**: Every financial transaction creates TWO rows: a debit and a credit. `SUM(all entries) = 0` always. Append-only — never update or delete entries.

**When to use**: Financial systems where money must never appear or disappear.
**When NOT to use**: Non-financial systems. Simple counters.

### Applied in our systems

| System | How It Works | Why Not Single-entry |
|--------|-------------|---------------------|
| [Payment System](../payment_system/INTERVIEW_CHEATSHEET.md) | Every payment creates: debit row (buyer wallet -$100) + credit row (seller wallet +$100). Ledger is append-only. Wallet balance = `SUM(entries WHERE wallet_id = X)`. | Single-entry (just `UPDATE balance SET amount = amount - 100`) can lose money on crashes. If buyer is debited but crash happens before crediting seller, $100 vanishes. With double-entry, any discrepancy is immediately detectable by checking `SUM = 0`. |
| [Digital Wallet](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) | Event sourcing is the extreme version: every state change is an append-only event. Current balance = replay all events. | Same principle: never mutate state, only append. Full audit trail. Every cent is traceable to a specific event. |

---

## 11. Strong vs Eventual Consistency — Decision Framework

| Question | If Yes → | If No → |
|----------|----------|---------|
| Is this financial data (money, inventory)? | **Strong** (ACID transaction) | Eventual might be OK |
| Can the user see stale data for 5 seconds? | **Eventual** | Strong |
| Does a wrong answer cause real harm? | **Strong** | Eventual |
| Is this a display-only read? | **Eventual** (cache is fine) | Depends |

### Applied in our systems

| System | Data | Consistency | Why |
|--------|------|-------------|-----|
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Balances | **Strong** (DB transaction) | Financial — wrong balance = user pays wrong amount. |
| [Splitwise](../splitwise_system/INTERVIEW_CHEATSHEET.md) | Notifications | **Eventual** (Kafka) | "Alice added expense" arriving 2s late is fine. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Trip state | **Strong** (atomic UPDATE) | Double-accept = two drivers go to same rider. |
| [Uber](../uber_system/INTERVIEW_CHEATSHEET.md) | Driver location | **Eventual** (Redis, updated every 3-5s) | Location is inherently approximate and transient. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | URL mappings | **Strong** (DB write) | Wrong redirect = broken link. |
| [URL Shortener](../url_shortener_system/INTERVIEW_CHEATSHEET.md) | Click analytics | **Eventual** (Kafka) | Dashboard showing clicks 10s late is fine. |
| [Log Aggregation](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) | Log indexing | **Eventual** (bulk index, ~5-10s delay) | Searching logs 5s after they occurred is acceptable. |

---

## Quick Decision Table

| Problem | Pattern | Example |
|---------|---------|---------|
| High contention, can't lose updates | Pessimistic locking | Splitwise, BookMyShow |
| Low contention, retries OK | Optimistic locking | Spotify playlists, Hotel |
| Single-row "only one wins" | Atomic conditional UPDATE | Uber accept, seat hold |
| Retries must not duplicate | Idempotency key in DB | All write APIs |
| DB + event must both succeed | Transactional outbox | Uber, BookMyShow, Splitwise |
| Cross-service transaction | Saga + compensation | Digital Wallet, Hotel |
| Entity has a lifecycle | State machine | Uber trip, BookMyShow seat |
| Two users edit same data | Conflict resolution (LWW / version) | Google Drive, Spotify |
| Need unique, sortable IDs at scale | Snowflake ID | Chat, News Feed |
| Money must balance to zero | Double-entry ledger | Payment, Digital Wallet |
