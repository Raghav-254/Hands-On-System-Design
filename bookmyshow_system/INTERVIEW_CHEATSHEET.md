# 🎟️ BookMyShow - Interview Cheatsheet

> Design a ticket booking system: users browse movies and shows, select seats, hold them briefly, then pay and confirm. The system must prevent double booking and handle high concurrency for popular releases.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **Show Service** | Movies, venues, screens, show times | Read-heavy; cache show metadata and seat layout; source for browse/search |
| **Seat Inventory Service** | Availability and hold state per seat | Single source of truth; atomic reserve; DB row lock or Redis + Lua |
| **Booking Service** | Hold seats, confirm booking, release hold | Orchestrates hold → payment → confirm; idempotency for confirm |
| **Payment Service** | Process payment for booking | External PSP (Stripe, Razorpay); idempotency key per confirm |
| **Hold Manager** | Temporary lock with TTL | Release expired holds (cron or lazy); prevent thundering herd |
| **Notification Service** | Confirm/cancel email, push | Async via Kafka; booking confirmation, hold expiry alert |
| **Kafka** | Event bus for async side effects | Booking events (HELD, CONFIRMED, RELEASED, CANCELLED) |

---

## The Story: Building BookMyShow

Users browse movies and show times, pick a screen and seats, **hold** them for a few minutes (e.g. 10 min), then **pay and confirm**. If they don't pay in time, seats are **released** back to available. The core challenge is **concurrency**: thousands of users may try to book the same popular show (e.g. a new Marvel movie opening night); we must **never** let two users confirm the same seat (double booking). The design focuses on atomic seat reservation, hold lifecycle, and reliable payment integration.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Browse**: Movies, venues, screens, show times (date + time slot). Search by city, movie, date.
- **Seat selection**: View seat layout (rows, sections, pricing tiers); see which seats are available / held / booked for a show; select one or more seats.
- **Hold**: Reserve selected seats for a short time (e.g. 10 minutes) so the user can pay. No one else can book those seats during hold.
- **Confirm (book)**: User pays; system converts hold to confirmed booking. Seat is permanently booked.
- **Release**: If user doesn't pay in time (or cancels), hold expires and seats go back to available.
- **Payment**: Integrate with payment gateway; idempotency so retries don't double-charge.
- **Notifications**: Booking confirmation (email/push), hold expiry warning.

### Non-Functional Requirements

- **Consistency**: Same seat must **never** be double-booked (strong consistency for seat state).
- **High concurrency**: Popular show — thousands of users selecting and holding seats at the same time.
- **Latency**: Seat selection and hold should feel instant (sub-second).
- **Availability**: System should handle spikes (movie launch day) without downtime.
- **Scale**: Multiple cities, thousands of venues, millions of users.

### Scope (What We're Not Covering)

- Recommendation engine (which movie to watch) — separate ML system.
- Pricing/dynamic pricing — assume prices are set per show/section.
- Reviews and ratings — separate service.
- Cancellation/refund flows after confirmed booking — mention briefly.

---

## 2. Back-of-the-Envelope Estimation

```
┌────────────────────────────────────────────────────────────────────────┐
│  Platform: nationwide (e.g. India)                                      │
│  Total movies showing:         ~2,000                                   │
│  Total venues:                 ~10,000                                  │
│  Shows per screen per day:     ~4                                       │
│  Screens per venue:            ~5                                       │
│  Seats per screen:             ~200                                     │
│                                                                         │
│  Total show instances/day:     10,000 × 5 × 4 = 200,000               │
│  Total seats/day:              200,000 × 200 = 40,000,000              │
│                                                                         │
│  Peak booking period:          6 PM – 10 PM (4 hours)                  │
│  Bookings/day (peak):          ~500,000 (0.5M)                         │
│  Peak bookings/sec:            500,000 / (4 × 3600) ≈ 35 TPS          │
│                                                                         │
│  BUT: for a POPULAR show (Marvel opening night), one show could        │
│  have 10,000 users competing for 200 seats simultaneously.             │
│  → That's 10,000 concurrent writes to the SAME show's seat rows.      │
│                                                                         │
│  Key insight: OVERALL TPS is moderate (~35).                            │
│  The CHALLENGE is HOT PARTITION — many users hitting the SAME show.    │
│  → Need atomic seat reservation that handles contention gracefully.    │
│  → Read path (browse shows, view seats) is MUCH heavier than writes.  │
└────────────────────────────────────────────────────────────────────────┘
```

| Metric | Value | Implication |
|--------|-------|-------------|
| Bookings/sec (overall) | ~35 TPS | Moderate; single DB can handle this |
| Hot show concurrency | ~10,000 users for 200 seats | Contention on same rows; need atomic reserve |
| Browse/search reads | 100x more than writes | Cache show metadata aggressively; CDN for images |
| Seat availability reads | Per-show, real-time | Short TTL or no cache (stale = user sees "available" then gets error) |
| Data per show_seat row | ~100 bytes | 200 seats × 100 = 20 KB per show; fits in one DB page |

---

## 3. Core Challenge: Concurrency and Double Booking

Two users select the same seat at the same time. We need:

1. **Single source of truth** for "this seat is available / held / confirmed" for a given show.
2. **Atomic operation** to transition a seat from AVAILABLE → HELD (or HELD → CONFIRMED). Only one request can succeed.

### Approaches to Atomicity

**Option 1: DB Row Lock (Pessimistic Locking)**

```sql
BEGIN TRANSACTION;
  SELECT * FROM show_seats
  WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE'
  FOR UPDATE;
  -- Row is now locked; other transactions on this row will WAIT

  UPDATE show_seats
  SET status = 'HELD', user_id = ?, hold_id = ?, expires_at = NOW() + INTERVAL 10 MINUTE
  WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE';
COMMIT;
-- Lock released; waiting transactions proceed (and find status = 'HELD' → fail)
```

| Pros | Cons |
|------|------|
| Strong consistency; simple | Lock contention under high load (hot show) |
| Standard SQL; no extra infra | Transactions hold locks → throughput drops with many concurrent requests |
| Works with multiple seats atomically | Deadlock risk if locking multiple seats in different order |

**Option 2: Optimistic Locking (Version Column)**

```sql
-- Read current version
SELECT version FROM show_seats WHERE show_id = ? AND seat_id = ?;  -- version = 5

-- Attempt update with version guard
UPDATE show_seats
SET status = 'HELD', user_id = ?, hold_id = ?, expires_at = NOW() + INTERVAL 10 MINUTE,
    version = version + 1
WHERE show_id = ? AND seat_id = ? AND status = 'AVAILABLE' AND version = 5;

-- rows_affected = 1 → success (we won)
-- rows_affected = 0 → someone else changed it → retry or fail
```

| Pros | Cons |
|------|------|
| No long-held locks; higher throughput | Must handle retries (and retry storms under contention) |
| Works well when contention is moderate | For very hot shows, many retries → wasted work |

**Option 3: Redis + Lua (Atomic Script)**

```lua
-- Key: seat:{show_id}:{seat_id}
-- Lua script runs atomically (Redis is single-threaded)
local status = redis.call('HGET', KEYS[1], 'status')
if status == 'AVAILABLE' then
    redis.call('HSET', KEYS[1], 'status', 'HELD', 'user_id', ARGV[1], 'hold_id', ARGV[2])
    redis.call('EXPIRE', KEYS[1], 600)  -- auto-expire in 10 min (TTL = hold duration)
    return 1  -- success
else
    return 0  -- seat not available
end
```

| Pros | Cons |
|------|------|
| Extremely fast (in-memory, single-threaded = atomic) | Redis is not durable by default; need to persist to DB |
| TTL auto-releases holds (no cron needed) | Must sync Redis ↔ DB for confirmed bookings |
| Handles hot shows well (100K+ ops/sec) | Added complexity (two stores to keep consistent) |

### Our Choice: DB Row Lock + Redis for Hot Shows

```
┌────────────────────────────────────────────────────────────────────────┐
│                     SEAT RESERVATION STRATEGY                          │
│                                                                        │
│  Normal shows (moderate concurrency):                                  │
│    → DB pessimistic lock (FOR UPDATE). Simple, strong, sufficient.    │
│                                                                        │
│  Hot shows (10,000 users, 200 seats):                                  │
│    → Redis + Lua for hold (fast atomic check-and-set).                │
│    → On confirm (after payment), write to DB (source of truth).       │
│    → Redis TTL auto-releases expired holds.                           │
│                                                                        │
│  In interview: "Start with DB row lock; for hot shows, add Redis     │
│  as a fast gate. DB remains source of truth for confirmed bookings." │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Seat States and Lifecycle

```
  AVAILABLE ──(user holds)──→ HELD (user_id, hold_id, expires_at)
                                    │
                    ┌───────────────┼───────────────┐
                    │               │               │
              (user pays)      (timeout)      (user cancels)
                    │               │               │
                    ▼               ▼               ▼
              CONFIRMED        AVAILABLE        AVAILABLE
              (booking_id)     (release)        (release)
```

- **AVAILABLE**: Seat can be held by any user.
- **HELD**: Seat is locked for one user until `expires_at` (e.g. now + 10 min). Only that user (via `hold_id`) can confirm. No one else can hold or book.
- **CONFIRMED**: Seat is permanently booked; `booking_id` stored. Irreversible (except through explicit cancellation/refund flow).

### Hold → Confirmed is a TWO-STEP Process

This is different from Uber (where accept is one step). Here, the user must **pay** between hold and confirm:

```
Step 1: AVAILABLE → HELD     (instant; atomic; no payment yet)
                               │
                               │ user has 10 minutes
                               ▼
Step 2: HELD → CONFIRMED     (only after payment succeeds)
```

Why two steps? Because the user needs time to enter payment details, and we don't want to charge them before they've committed. The hold gives them a window where their seat is safe.

---

## 5. API Design

### Browse APIs (Read-Heavy — Cached)

```
GET /v1/cities
GET /v1/movies?city_id=&date=
GET /v1/movies/{movie_id}/shows?city_id=&date=
GET /v1/venues/{venue_id}/screens

Response: cached (CDN or Redis); TTL 5–15 min. Static data.
```

### Seat Availability (Real-Time — Not Cached or Very Short TTL)

```
GET /v1/shows/{show_id}/seats
Response: {
  "show_id": "s_123",
  "seats": [
    { "seat_id": "A1", "row": "A", "number": 1, "section": "premium", "price": 500,
      "status": "AVAILABLE" },
    { "seat_id": "A2", "row": "A", "number": 2, "section": "premium", "price": 500,
      "status": "HELD" },
    { "seat_id": "A3", "row": "A", "number": 3, "section": "premium", "price": 500,
      "status": "CONFIRMED" }
  ]
}

Why NOT cache: User sees "available," clicks hold, but someone else already held it.
Stale availability = bad UX (error on hold attempt). Either no cache or TTL < 5 sec.
```

### Hold API

```
POST /v1/shows/{show_id}/hold
Headers: Authorization: Bearer <user_token>
Body: {
  "seat_ids": ["A1", "A4", "A5"],
  "user_id": "u_001"
}

Response: 201 Created
{
  "hold_id": "h_abc123",
  "show_id": "s_123",
  "seat_ids": ["A1", "A4", "A5"],
  "expires_at": "2024-01-15T10:15:00Z",
  "total_price": 1500,
  "status": "HELD"
}

Error: 409 Conflict — "Seat A4 is no longer available"
(If ANY requested seat is not available, the entire hold fails — atomically.)
```

### Confirm (Book) API

```
POST /v1/bookings/confirm
Headers:
  Authorization: Bearer <user_token>
  Idempotency-Key: "uuid-..."

Body: {
  "hold_id": "h_abc123",
  "payment_token": "tok_stripe_xyz"
}

Response: 200 OK
{
  "booking_id": "b_789",
  "hold_id": "h_abc123",
  "status": "CONFIRMED",
  "seats": ["A1", "A4", "A5"],
  "total_charged": 1500
}

Idempotency: Same Idempotency-Key → same booking_id, no second charge.
```

### Release / Cancel API

```
POST /v1/holds/{hold_id}/release
  (User cancels hold manually; or called by Hold Manager on expiry.)

Response: 200 OK { "status": "RELEASED", "seats_released": ["A1", "A4", "A5"] }
```

---

## 6. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS                                            │
│  ┌──────────────┐                                     ┌──────────────┐                │
│  │  Web App     │                                     │  Mobile App  │                │
│  │  (browse,    │                                     │  (browse,    │                │
│  │   select,    │                                     │   select,    │                │
│  │   pay)       │                                     │   pay)       │                │
│  └──────┬───────┘                                     └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────┼────────────────────────┘
          │  HTTPS                                             │ HTTPS
          ▼                                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY / LB                                         │
│              Auth, rate limit, route by path (/shows/*, /bookings/*)                │
└───────────┬──────────────────┬────────────────────────┬──────────────────────────────┘
            │                  │                        │
     ┌──────▼───────┐  ┌──────▼──────────┐  ┌──────────▼──────────┐
     │ Show Service  │  │ Booking Service │  │ Seat Inventory      │
     │ (read-heavy)  │  │ (orchestrator)  │  │ Service             │
     │               │  │                 │  │ (atomic reserve)    │
     │ • browse      │  │ • hold flow     │  │                     │
     │ • search      │  │ • confirm flow  │  │ • hold (atomic)     │
     │ • show detail │  │ • release flow  │  │ • confirm (atomic)  │
     │               │  │ • idempotency   │  │ • release           │
     └──────┬────────┘  └───┬────────┬────┘  └──────────┬──────────┘
            │               │        │                   │
            │               │        │ publish           │ read/write
            ▼               │        ▼                   ▼
     ┌──────────────┐       │  ┌──────────────┐   ┌────────────────────┐
     │ Show Cache   │       │  │    KAFKA      │   │  Seat Inventory DB │
     │ (Redis)      │       │  │              │   │  (MySQL/PG)        │
     │              │       │  │ Topics:      │   │                    │
     │ movies,      │       │  │ • booking-   │   │  show_seats        │
     │ venues,      │       │  │   events     │   │  (show_id, seat_id,│
     │ shows,       │       │  │              │   │   status, hold_id, │
     │ seat layout  │       │  └──────┬───────┘   │   user_id,         │
     └──────────────┘       │        │            │   expires_at)      │
                            │        │            └────────────────────┘
                            │   ┌────┴─────┐
                            │   │          │
                            │   ▼          ▼
                     ┌──────▼────────┐ ┌────────────┐ ┌────────────┐
                     │ Payment       │ │Notification│ │ Analytics  │
                     │ Service       │ │ Service    │ │ Service    │
                     │               │ │            │ │            │
                     │ Stripe /      │ │ Email,     │ │ Booking    │
                     │ Razorpay      │ │ Push (FCM) │ │ metrics    │
                     └───────────────┘ └────────────┘ └────────────┘

     ┌──────────────────────────────────────────────┐
     │          Hold Manager (Background)            │
     │  Cron or scheduler: find expired holds,       │
     │  release seats back to AVAILABLE              │
     └──────────────────────────────────────────────┘
```

### What Does Each Component Do?

| Component | Responsibility |
|-----------|----------------|
| **API Gateway** | Auth, rate limit, TLS termination. Route by path to Show, Booking, or Seat Inventory Service. |
| **Show Service** | Read-only: movies, venues, screens, show times. Read-heavy → aggressive caching (Redis, CDN). Layout (rows, seat positions) is static per screen. |
| **Booking Service** | Orchestrates the hold → payment → confirm flow. Checks idempotency. Calls Seat Inventory for atomic hold/confirm. Calls Payment Service for charge. Publishes booking events to Kafka. |
| **Seat Inventory Service** | Single source of truth for seat state (AVAILABLE / HELD / CONFIRMED) per (show_id, seat_id). All atomic transitions happen here. For hot shows, Redis + Lua gate in front of DB. |
| **Payment Service** | Calls external PSP (Stripe/Razorpay) to charge user. Idempotency key forwarded to PSP so retries don't double-charge. |
| **Hold Manager** | Background process: find holds where `expires_at < now`, release those seats back to AVAILABLE. Runs as cron (every 30–60 sec) or triggered by delayed queue. |
| **Kafka** | Decouples Booking Service from Notification and Analytics. Events: BOOKING_HELD, BOOKING_CONFIRMED, BOOKING_RELEASED, BOOKING_CANCELLED. |
| **Notification Service** | Consumes booking events from Kafka. Sends confirmation email, push notification, hold-expiring warning. |

### Sync vs Async Communication

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SYNC (HTTP request-response)                             │
│                                                                              │
│  Client ──→ API Gateway ──→ Show Service         (browse, search)           │
│  Client ──→ API Gateway ──→ Seat Inventory       (view availability)        │
│  Client ──→ API Gateway ──→ Booking Service      (hold, confirm, release)  │
│  Booking Service ──→ Seat Inventory Service      (atomic hold/confirm)     │
│  Booking Service ──→ Payment Service             (charge user)             │
│                                                                              │
│  WHY SYNC: User is waiting. Hold must be confirmed instantly.              │
│  Payment must complete before we confirm the booking.                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                     ASYNC (Kafka events)                                     │
│                                                                              │
│  Booking Service ──publish──→ Kafka "booking-events" topic                  │
│       Events: BOOKING_HELD, BOOKING_CONFIRMED, BOOKING_RELEASED            │
│                                                                              │
│  Kafka ──consume──→ Notification Service  (email, push)                     │
│  Kafka ──consume──→ Analytics Service     (booking metrics, revenue)        │
│                                                                              │
│  WHY ASYNC: Booking is complete; notification is a side effect.            │
│  Booking Service should not fail because email delivery is slow.           │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Decision | Choice | Why |
|----------|--------|-----|
| Booking Service → Seat Inventory | **Sync (HTTP)** | User is waiting for hold confirmation. Must be instant. |
| Booking Service → Payment Service | **Sync (HTTP)** | Must know if payment succeeded before confirming booking. |
| Booking Service → Notification | **Async (Kafka)** | Side effect. Don't block booking on email delivery. |
| Hold Manager → Seat Inventory | **Sync (internal)** | Releasing seats is a direct DB update. |
| Show Service reads | **Cached (Redis/CDN)** | Static data; doesn't change during the day. |
| Seat availability reads | **No cache or very short TTL** | Stale availability = user sees "available," clicks hold, gets error. |

---

## 7. Hold Flow — End-to-End (Step-by-Step)

```
① User browses shows:  GET /v1/movies?city_id=mumbai&date=2024-01-15
   │  Show Service returns from cache (Redis)
   │
② User selects show:   GET /v1/shows/s_123/seats
   │  Seat Inventory Service returns real-time availability from DB
   │  (NOT cached — must be fresh)
   │
③ User selects seats A1, A4, A5:  POST /v1/shows/s_123/hold
   │  { "seat_ids": ["A1","A4","A5"], "user_id": "u_001" }
   │
④ API Gateway → Booking Service
   │
⑤ Booking Service → Seat Inventory Service:
   │  "Hold seats A1, A4, A5 for show s_123, user u_001"
   │
⑥ Seat Inventory Service: ATOMIC operation (all-or-nothing)
   │
   │  BEGIN TRANSACTION;
   │    SELECT * FROM show_seats
   │    WHERE show_id='s_123' AND seat_id IN ('A1','A4','A5') AND status='AVAILABLE'
   │    FOR UPDATE;
   │
   │    -- If count < 3 → ROLLBACK (one or more seats not available → 409)
   │
   │    UPDATE show_seats
   │    SET status='HELD', user_id='u_001', hold_id='h_abc123',
   │        expires_at = NOW() + INTERVAL 10 MINUTE
   │    WHERE show_id='s_123' AND seat_id IN ('A1','A4','A5');
   │  COMMIT;
   │
⑦ Return hold_id, expires_at to Booking Service → Client
   │
⑧ Booking Service publishes BOOKING_HELD event to Kafka
   │  (Notification Service can send "You have 10 min to pay" push)
   │
⑨ User sees: "Seats A1, A4, A5 held. Pay within 10:00 minutes."
   │  Timer starts on client.
```

### Why All-or-Nothing for Multiple Seats?

If user selects 3 seats and only 2 are available, we don't hold 2 and reject 1. That would give the user a partial booking (e.g. 2 seats when they need 3 together). Instead: **either all seats are held, or none are**. This is achieved by the transaction: if any seat is not AVAILABLE, ROLLBACK.

---

## 8. Confirm Flow — End-to-End (Step-by-Step)

```
① User clicks "Pay" within hold window
   │  POST /v1/bookings/confirm
   │  { "hold_id": "h_abc123", "payment_token": "tok_stripe_xyz" }
   │  Idempotency-Key: "uuid-confirm-001"
   │
② API Gateway → Booking Service
   │
③ Booking Service: Idempotency check
   │  Lookup Idempotency-Key in store (DB table or Redis)
   │  If exists → return cached response (same booking_id, no re-charge)
   │
④ Booking Service: Validate hold
   │  Get hold from DB: hold_id='h_abc123'
   │  Check: hold exists? user matches? NOT expired (expires_at > NOW())?
   │  If expired → 410 Gone ("Hold expired, seats released")
   │
⑤ Booking Service → Payment Service: charge user
   │  POST /payment/charge { amount: 1500, token: "tok_...", idempotency_key: "uuid-..." }
   │  Payment Service → PSP (Stripe/Razorpay)
   │  PSP returns: success or failure
   │
⑥ If payment FAILED:
   │  Return 402 Payment Failed to user. Hold remains (user can retry with new card).
   │  Do NOT release seats yet — user still has time on their hold.
   │
⑦ If payment SUCCESS:
   │  Booking Service → Seat Inventory Service:
   │  Atomic transition: HELD → CONFIRMED for all seats in hold
   │
   │  UPDATE show_seats
   │  SET status='CONFIRMED', booking_id='b_789'
   │  WHERE show_id='s_123' AND hold_id='h_abc123' AND status='HELD';
   │
   │  INSERT INTO bookings (booking_id, hold_id, user_id, show_id, seats, payment_id, status)
   │  VALUES ('b_789', 'h_abc123', 'u_001', 's_123', '["A1","A4","A5"]', 'pay_456', 'CONFIRMED');
   │
   │  (Both in same DB transaction — atomic.)
   │
⑧ Save idempotency result: key → { status: 200, booking_id: "b_789" }
   │
⑨ Publish BOOKING_CONFIRMED event to Kafka
   │
⑩ Notification Service: email + push "Booking confirmed! Seats A1, A4, A5"
   │  Analytics Service: record booking metric
   │
⑪ Return booking confirmation to client
```

### What If Payment Succeeds but DB Write Fails?

This is the critical edge case. User is charged but booking isn't recorded.

```
Payment SUCCESS ──→ DB write FAILS ──→ ???
```

| Approach | How | Trade-off |
|----------|-----|-----------|
| **Retry with idempotency** | Retry the DB write (same booking_id). Payment already succeeded; PSP won't charge again (idempotency key). Keep retrying until DB write succeeds. | Simplest; works if DB failure is transient |
| **Compensating action (refund)** | If DB write fails after N retries → refund payment via PSP → release seats → notify user "Booking failed, refund issued" | More complex; user has bad experience |
| **Transactional outbox** | In the same DB transaction: write booking + write to outbox table. Outbox publisher sends to Kafka. If DB write fails, payment isn't recorded → retry from scratch. | Best for reliability but adds outbox infra |

**Our approach**: Retry DB write with idempotency key. If truly irrecoverable (DB down for extended period) → refund and notify user. The idempotency key ensures no double-charge on retries.

### Who Creates the Idempotency Key? Where Is It Stored?

**Who creates it?** The **client** generates a UUID before sending the confirm request. Why the client? If the request times out mid-flight, the client doesn't know if the server processed it. On retry, the client sends the **same key** → server deduplicates. If the server generated it, the client would need an extra round-trip first ("give me a key"), and if *that* timed out, same problem.

**Where is it stored?** In a **DB table** (same database as bookings), not Redis. Why?

```
BEGIN TRANSACTION;
  UPDATE show_seats SET status='CONFIRMED' WHERE hold_id=? AND status='HELD';
  INSERT INTO bookings (...) VALUES (...);
  INSERT INTO idempotency_keys (key, status_code, response_body, created_at)
    VALUES ('uuid-confirm-001', 200, '{"booking_id":"b_789",...}', NOW());
COMMIT;
```

All three writes are in the **same transaction**. If the transaction fails, **none** are saved — the idempotency key is NOT "used up," so the client can safely retry. If we stored the key in Redis instead, we'd have an edge case: Redis save succeeds but DB write fails → key is marked "used" but booking doesn't exist → client can't retry.

| | DB table (our choice) | Redis |
|---|---|---|
| **Atomicity with booking** | Same transaction → all or nothing | Separate system → edge case on partial failure |
| **Speed** | DB read (~1-5ms) | Sub-ms |
| **Cleanup** | Cron: DELETE WHERE created_at < NOW() - 24h | TTL auto-expires |
| **When to use** | Moderate TPS (~35 for BookMyShow) | High TPS (20K+, like Uber location) |

---

## 9. Hold Manager — Releasing Expired Holds

### The Problem

User holds seats but never pays. Those seats are "locked" and invisible to other users. We need to release them back to AVAILABLE after `expires_at`.

### Approach 1: Cron Job (Active Release)

```
Hold Manager (runs every 30-60 sec)
   │
   ▼
SELECT show_id, seat_id, hold_id FROM show_seats
WHERE status = 'HELD' AND expires_at < NOW();
   │
   ▼
For each expired hold:
  UPDATE show_seats SET status = 'AVAILABLE', hold_id = NULL, user_id = NULL, expires_at = NULL
  WHERE show_id = ? AND seat_id = ? AND status = 'HELD' AND hold_id = ?;
   │
   ▼
Publish BOOKING_RELEASED event to Kafka
  → Notification: "Your hold has expired. Seats released."
```

| Pros | Cons |
|------|------|
| Simple; predictable | Seats stay "held" up to cron interval past expiry (30-60 sec delay) |
| Works at moderate scale | **Thundering herd** (many holds created at the same time → all expire at the same time → cron releases all at once → sudden DB write spike that overwhelms the shard). Mitigate: process in batches with rate limiting, or add jitter to hold duration (10 min + random 0-60s). |

### Approach 2: Lazy Release (On Read)

```
When any user calls GET /shows/{show_id}/seats:
  For each seat with status=HELD:
    If expires_at < NOW():
      UPDATE status = 'AVAILABLE' (inline release)
      Return as AVAILABLE to caller
```

| Pros | Cons |
|------|------|
| No background process; simpler infra | Seats stay held until someone reads them (could be a long time for unpopular shows) |
| Spreads load naturally | Inconsistent release timing |

### Approach 3: Delayed Queue (Best for Hot Shows)

```
When a hold is created:
  Enqueue a delayed message: "release hold h_abc123 at 10:15 AM"
  (e.g. Redis delayed queue, SQS with delay, or Kafka with timestamp)

At 10:15 AM, message fires:
  Hold Manager receives → checks if hold still exists and is HELD
  → If yes: release seats, publish event
  → If no (already confirmed or manually released): no-op
```

| Pros | Cons |
|------|------|
| Precise timing; no polling | Need delayed queue infrastructure |
| No thundering herd (messages naturally staggered by hold creation time) | Slightly more complex setup |

**Our choice**: Cron job for simplicity + guard against thundering herd by processing in batches with rate limiting. Mention delayed queue as enhancement for hot shows.

---

## 10. Data Model

### Movies, Venues, Screens (Static — Cached)

```
┌────────────────────────────────────────────────────┐
│ movies                                              │
├──────────────────┬─────────────────────────────────┤
│ movie_id         │ PK, UUID                         │
│ title            │                                  │
│ genre            │                                  │
│ duration_min     │                                  │
│ language         │                                  │
│ release_date     │                                  │
└──────────────────┴─────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ venues                                              │
├──────────────────┬─────────────────────────────────┤
│ venue_id         │ PK, UUID                         │
│ name             │                                  │
│ city_id          │                                  │
│ address          │                                  │
└──────────────────┴─────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ screens                                             │
├──────────────────┬─────────────────────────────────┤
│ screen_id        │ PK, UUID                         │
│ venue_id         │ FK → venues                      │
│ name             │ e.g. "Screen 3"                  │
│ capacity         │ e.g. 200                         │
└──────────────────┴─────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ seats (layout — same for every show on this screen) │
├──────────────────┬─────────────────────────────────┤
│ seat_id          │ PK (within screen)               │
│ screen_id        │ FK → screens                     │
│ row              │ e.g. "A"                         │
│ number           │ e.g. 5                           │
│ section          │ e.g. "premium", "regular"        │
│ price            │ per section (or per show)         │
└──────────────────┴─────────────────────────────────┘
```

### Shows (Per Movie + Screen + Time)

```
┌────────────────────────────────────────────────────┐
│ shows                                               │
├──────────────────┬─────────────────────────────────┤
│ show_id          │ PK, UUID                         │
│ movie_id         │ FK → movies                      │
│ screen_id        │ FK → screens                     │
│ show_time        │ datetime                         │
│ date             │                                  │
└──────────────────┴─────────────────────────────────┘
```

### Show Seats (Source of Truth — Per Show × Per Seat)

```
┌────────────────────────────────────────────────────────────────────┐
│ show_seats                                                          │
├──────────────────┬────────────────────────────────────────────────┤
│ show_id          │ PK part 1, FK → shows                           │
│ seat_id          │ PK part 2, FK → seats                           │
│ status           │ AVAILABLE | HELD | CONFIRMED                    │
│ hold_id          │ NULL or FK → holds (if HELD)                    │
│ user_id          │ NULL or FK → users (if HELD or CONFIRMED)       │
│ expires_at       │ NULL or timestamp (if HELD; hold expiry)        │
│ booking_id       │ NULL or FK → bookings (if CONFIRMED)            │
│ version          │ for optimistic locking (if using that approach)  │
└──────────────────┴────────────────────────────────────────────────┘

Primary key: (show_id, seat_id). One row per seat per show.
All hold/confirm/release operations UPDATE this row.
```

### Holds and Bookings

```
┌────────────────────────────────────────────────────────────────────┐
│ holds                                                               │
├──────────────────┬────────────────────────────────────────────────┤
│ hold_id          │ PK, UUID                                         │
│ show_id          │ FK → shows                                       │
│ user_id          │ FK → users                                       │
│ seat_ids         │ JSON array (or separate hold_seats table)        │
│ expires_at       │ timestamp                                        │
│ created_at       │                                                  │
│ status           │ ACTIVE | CONFIRMED | RELEASED | EXPIRED          │
└──────────────────┴────────────────────────────────────────────────┘

┌────────────────────────────────────────────────────────────────────┐
│ bookings                                                            │
├──────────────────┬────────────────────────────────────────────────┤
│ booking_id       │ PK, UUID                                         │
│ hold_id          │ FK → holds                                       │
│ user_id          │ FK → users                                       │
│ show_id          │ FK → shows                                       │
│ seat_ids         │ JSON array                                       │
│ payment_id       │ from PSP                                         │
│ total_amount     │                                                  │
│ status           │ CONFIRMED | CANCELLED                            │
│ created_at       │                                                  │
└──────────────────┴────────────────────────────────────────────────┘
```

---

## 11. Consistency and Reliability

### Consistency Model

| Data | Consistency | Why |
|------|-------------|-----|
| Seat state (show_seats) | **Strong** (single DB row, FOR UPDATE) | Double booking is unacceptable. Only one user can hold/confirm a seat. |
| Show metadata | **Eventual** (cached, TTL 5–15 min) | Movies, venues, screens don't change during the day. Stale is fine. |
| Notifications | **Eventual** (Kafka consumer lag) | Side effect; booking is complete regardless of email delivery. |
| Booking record | **Strong** (same transaction as seat confirm) | Must be atomically consistent with seat state. |

### Transactional Outbox for Event Publishing

**Outbox table**: a queue *inside your database* — the event is written as a DB row in the same transaction as the booking, so both succeed or both fail. A separate publisher process reads the outbox and sends to Kafka. Even if Kafka is down, events are safe in the DB and published when it recovers. This guarantees no event is ever lost.

Same pattern as Uber: booking state change and event must not diverge.

```
Booking Service confirms booking:
   │
   ▼
BEGIN TRANSACTION;
  UPDATE show_seats SET status='CONFIRMED', booking_id=? WHERE hold_id=? AND status='HELD';
  INSERT INTO bookings (...) VALUES (...);
  INSERT INTO outbox (event_type, payload, created_at)
    VALUES ('BOOKING_CONFIRMED', '{"booking_id":"b_789","user_id":"u_001",...}', NOW());
COMMIT;
   │
   ▼ (all three rows in same DB → atomic)

Outbox Publisher (CDC / polling)
   ├── Reads outbox table
   ├── Publishes to Kafka "booking-events"
   └── Marks row as published
```

### What If Kafka Is Down?

Events accumulate in outbox table. Publisher retries. When Kafka recovers, backlog drains. Notifications and analytics are delayed but not lost. Booking state in DB is the source of truth — always correct regardless of Kafka.

### What If DB Is Down?

Hold and confirm both fail (return 503). User retries. Since hold has a TTL, no cleanup needed for failed holds — they never existed in DB. For confirms: idempotency key ensures retries are safe when DB recovers.

---

## 12. Failure Scenarios and Handling

| Scenario | What Breaks | Handling | Data Impact |
|----------|-------------|----------|-------------|
| **Two users hold same seat** | Concurrent writes to show_seats | **Pessimistic** (our choice): `SELECT ... FOR UPDATE` — one gets lock, other waits. First commits → HELD. Second finds status != AVAILABLE → 409. **Optimistic** (alternative): version column — `UPDATE ... WHERE version=5`. One gets rows_affected=1. Other gets 0 (version changed) → retry or fail. Both prevent double booking; pessimistic is simpler, optimistic has higher throughput under moderate contention. | Only one hold succeeds. No inconsistency. |
| **User holds but never pays** | Seats stuck as HELD | Hold Manager releases after expires_at. Cron (every 30-60s) or lazy release on next read. | Seats unavailable for hold_duration; then auto-released. |
| **Payment succeeds, DB write fails** | User charged but booking not recorded | Retry DB write with idempotency key. PSP won't re-charge (idempotency key). If still failing → refund via PSP + notify user. | Transient: retry fixes. Extended: refund. |
| **Payment fails** | User not charged | Return 402 to user. Hold remains (user can retry with different card). Seats NOT released — user still has time on hold. | No data change; hold intact. |
| **Hold expires during payment** | Payment in progress but hold is expired | Before confirming: check `expires_at > NOW()`. If expired → return 410 Gone, refund if already charged. | Race condition; explicit check prevents confirming expired hold. |
| **Kafka down** | Events not published | Outbox pattern: events in DB outbox. Published when Kafka recovers. Booking state correct regardless. | Notifications delayed. |
| **Notification Service down** | Email/push not sent | Events queue in Kafka. Service recovers → consumes backlog. Booking is confirmed regardless. | UX degraded (no email), not broken. |
| **DB slow under hot show load** | Hold requests timeout | Rate limit per show. Queue excess requests. Degrade gracefully: "High demand, try again." Optional: Redis gate for hot shows. | Users wait or retry. No data corruption. |

---

## 13. Scale and Sharding

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                          │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐           │
│  │ Show Service     │   │ Seat Inventory  │   │ Booking Service │           │
│  │ (read-heavy)     │   │ DB              │   │                  │           │
│  │                  │   │                  │   │ STATELESS        │           │
│  │ Cache in Redis/  │   │ Shard by SHOW_ID│   │ (just add more   │           │
│  │ CDN. Static data │   │ (all seats for  │   │  instances)      │           │
│  │ → very cacheable │   │ one show on     │   │                  │           │
│  │                  │   │ same shard)     │   │ Each instance    │           │
│  │                  │   │                  │   │ calls Seat Inv + │           │
│  │                  │   │ Hot show =      │   │ Payment          │           │
│  │                  │   │ hot shard; use  │   │                  │           │
│  │                  │   │ Redis gate      │   │                  │           │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘           │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐                                  │
│  │ Kafka            │   │ Notification     │                                  │
│  │                  │   │ Service          │                                  │
│  │ Partition by     │   │ Scale consumers  │                                  │
│  │ show_id (ordered │   │ independently    │                                  │
│  │ events per show) │   │ (Kafka consumer  │                                  │
│  │                  │   │  groups)         │                                  │
│  └─────────────────┘   └─────────────────┘                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Component | Shard/Scale Strategy | Why |
|-----------|---------------------|-----|
| **Seat Inventory DB** | Shard by **show_id**. All seats for one show on same shard. | Hold/confirm needs to atomically update multiple seats of the same show → must be on same shard for transaction. |
| **Show metadata** | Cache (Redis / CDN). No sharding needed — small dataset. | Read-heavy; static data. |
| **Booking Service** | Stateless → horizontal scale. | No in-memory state; each instance calls Seat Inventory + Payment. |
| **Kafka** | Partition by **show_id**. | Events for one show are ordered (HELD before CONFIRMED). |
| **Notification** | Scale via Kafka consumer group. | Independent of booking throughput. |

### Hot Show Problem

A Marvel opening night: 10,000 users → 200 seats → massive contention on one shard.

| Strategy | How |
|----------|-----|
| **Queue + rate limit** | Funnel hold requests through a queue; process N at a time per show. "You're in line, position 342." |
| **Redis gate** | Redis + Lua for fast atomic hold; only write to DB on confirm. Reduces DB contention. |
| **Virtual waiting room** | Before showing seats, put user in a waiting room (random delay). Spread out requests. |
| **Accept contention** | At 35 TPS overall, a hot show is ~100-200 holds/sec. DB row lock handles this; most requests get 409 quickly. |

---

## 14. Final Architecture (Putting It All Together)

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  Web App     │ ① GET /shows?movie=...                  │  Mobile App  │                │
│  │              │ ② GET /shows/{id}/seats                  │              │                │
│  │              │ ③ POST /shows/{id}/hold                  │              │                │
│  │              │ ⑧ POST /bookings/confirm                 │              │                │
│  └──────┬───────┘                                          └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│                     Auth, rate limit, TLS, route by path                                   │
└─────────┬──────────────────────┬───────────────────────────┬──────────────────────────────┘
          │                      │                           │
   ┌──────▼───────┐    ┌────────▼────────┐        ┌─────────▼──────────┐
   │ Show Service  │    │ Booking Service │        │ Seat Inventory     │
   │               │    │ (orchestrator)  │        │ Service            │
   │ ① browse      │    │                 │        │                    │
   │   (from cache)│    │ ④ validate hold │   ⑤───│ ⑥ atomic hold      │
   │               │    │ ⑨ idempotency   │   ⑫───│ ⑬ atomic confirm   │
   │               │    │ ⑩ call payment  │        │                    │
   └──────┬────────┘    └───┬────────┬────┘        └──────────┬─────────┘
          │                 │        │                         │
          ▼                 │        │ ⑭ publish               │ read/write
   ┌──────────────┐         │        ▼                         ▼
   │ Show Cache   │         │  ┌──────────────┐   ┌────────────────────┐
   │ (Redis/CDN)  │         │  │    KAFKA      │   │  Seat Inventory DB │
   └──────────────┘         │  │              │   │  (MySQL/PG)        │
                            │  │ booking-     │   │                    │
                            │  │ events       │   │  show_seats        │
                            │  └──────┬───────┘   │  holds             │
                            │        │            │  bookings          │
                            │   ┌────┴─────┐      │  outbox            │
                     ┌──────▼───▼──┐       │      └────────────────────┘
                     │ Payment     │  ┌────▼───────┐
                     │ Service     │  │Notification│  ┌────────────┐
                     │             │  │ Service    │  │ Analytics  │
                     │ ⑩⑪ Stripe / │  │            │  │ Service    │
                     │ Razorpay    │  │ Email,Push │  │            │
                     └─────────────┘  └────────────┘  └────────────┘

     ┌──────────────────────────────────────────────┐
     │          Hold Manager (Background)            │
     │  ⑮ Cron: find expired holds → release seats  │
     └──────────────────────────────────────────────┘
```

### Step-by-Step Flow (Numbered)

| Step | Action | Service | Protocol | Data Store |
|------|--------|---------|----------|------------|
| ① | User browses shows for a movie | Client → Show Service | HTTP GET | Show Cache (Redis/CDN) |
| ② | User views seat availability | Client → Seat Inventory Service | HTTP GET | Seat Inventory DB (real-time) |
| ③ | User selects seats, clicks "Hold" | Client → API GW → Booking Service | HTTP POST | |
| ④ | Booking Service validates request | Booking Service | Internal | |
| ⑤ | Booking Service calls Seat Inventory | Booking Service → Seat Inventory | Sync HTTP | |
| ⑥ | Atomic hold (FOR UPDATE → set HELD) | Seat Inventory Service | DB write | show_seats (UPDATE) |
| ⑦ | Return hold_id, expires_at | Seat Inventory → Booking → Client | HTTP response | |
| ⑧ | User clicks "Pay" within hold window | Client → API GW → Booking Service | HTTP POST | |
| ⑨ | Idempotency check | Booking Service | Lookup | Idempotency store |
| ⑩ | Call Payment Service (charge user) | Booking Service → Payment Service | Sync HTTP | |
| ⑪ | Payment Service charges via PSP | Payment Service → Stripe/Razorpay | External HTTP | |
| ⑫ | On payment success: confirm booking | Booking Service → Seat Inventory | Sync HTTP | |
| ⑬ | Atomic confirm (HELD → CONFIRMED) + insert booking | Seat Inventory Service | DB write (transaction) | show_seats + bookings + outbox |
| ⑭ | Publish BOOKING_CONFIRMED event | Outbox → Kafka | Async | Kafka |
| ⑮ | Hold Manager releases expired holds | Hold Manager (cron) | DB write | show_seats (UPDATE) |

---

## 15. Trade-off Summary

| Decision | Our Choice | Alternative | Why Our Choice |
|----------|-----------|-------------|----------------|
| **Seat reservation atomicity** | DB row lock (FOR UPDATE) | Optimistic locking / Redis + Lua | Simple, strong consistency. Hot shows: add Redis gate. |
| **Hold expiry** | Cron job (every 30-60s) | Lazy release / Delayed queue | Predictable; easy to implement. Delayed queue for enhancement. |
| **All-or-nothing hold** | Transaction: all seats or none | Partial hold (hold what's available) | User needs all seats together. Partial is bad UX. |
| **Payment before confirm** | Two-step: hold then pay | Charge immediately (no hold) | Hold gives user time to decide. Avoids charging and then refunding. |
| **Seat availability cache** | No cache (or TTL < 5s) | Cache with longer TTL | Stale availability = user clicks hold and gets error. Bad UX. |
| **Show metadata cache** | Redis / CDN (TTL 5-15 min) | No cache (always hit DB) | Static data; caching reduces DB load dramatically. |
| **Inter-service communication** | Kafka for side effects | Direct HTTP calls | Booking Service shouldn't block on email delivery. |
| **Event publishing** | Transactional outbox + CDC | Direct publish after commit | No lost events. If Kafka down, events in outbox. |
| **Sharding** | By show_id | By venue_id or city_id | All seats for one show must be on same shard (transaction). |
| **Hot show handling** | Rate limit + queue | Redis gate / Virtual waiting room | Start simple; add Redis or queue for extreme cases. |

---

## 16. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Better Approach |
|---------|---------------|-----------------|
| Caching seat availability with long TTL | User sees "available," clicks hold, gets error (seat was already held) | No cache or TTL < 5 sec for availability; cache layout and show info only |
| No atomic hold (check then set in two operations) | Race condition: two users both see AVAILABLE, both set HELD → double booking | Single atomic operation: DB FOR UPDATE, or Redis Lua script |
| Partial hold (hold 2 of 3 requested seats) | User needed 3 seats together; 2 is useless | All-or-nothing transaction: if any seat unavailable, hold none |
| No hold expiry mechanism | User holds seats, closes browser → seats locked forever | Cron job or delayed queue to release expired holds; TTL in Redis |
| Charging before hold (no two-step) | User is charged immediately; if they cancel, need refund → complex | Hold first (free); charge only on confirm |
| No idempotency on confirm | Network timeout → user retries → charged twice, two booking records | Idempotency key on confirm; PSP also gets idempotency key |
| Direct Kafka publish (no outbox) | DB write succeeds but Kafka fails → booking confirmed but no notification | Transactional outbox: booking + event in same DB transaction |
| Locking multiple seats in arbitrary order | Deadlock: user A locks seat 1 then 2; user B locks seat 2 then 1 → both wait | Always lock seats in sorted order (by seat_id) within transaction |
| Not validating hold ownership on confirm | Any user could confirm someone else's hold | Check hold_id belongs to authenticated user_id before confirming |
| Ignoring hold expiry during payment | Payment takes 30 sec; hold expires during payment → seats released, but payment succeeds → inconsistency | Check expires_at before AND after payment; if expired, refund |

---

## 17. Interview Talking Points

### "Walk me through the architecture"

> We have four core services: Show Service (read-heavy, cached), Booking Service (orchestrates hold → payment → confirm), Seat Inventory Service (single source of truth for seat state, atomic transitions), and Payment Service (charges via PSP). Sync HTTP calls for the critical path (hold, payment, confirm — user is waiting). Async Kafka events for side effects (notification, analytics). Seat Inventory DB sharded by show_id so all seats for one show are on the same shard for transactions. Show metadata aggressively cached; seat availability NOT cached (stale = bad UX).

### "How do you prevent double booking?"

> Single source of truth: the `show_seats` table with a row per (show_id, seat_id). Atomic transition from AVAILABLE to HELD using DB row lock (SELECT ... FOR UPDATE). Only one transaction can hold the lock at a time. Second user's transaction waits, then finds status != AVAILABLE → 409. For very hot shows, we can add a Redis + Lua gate (single-threaded, atomic check-and-set) with TTL auto-expiry.

### "How does the hold work?"

> User selects seats → POST /hold. Booking Service calls Seat Inventory which runs an all-or-nothing transaction: all requested seats must be AVAILABLE. If any is not, the entire hold fails (no partial booking). Successful hold sets status=HELD, records expires_at (now + 10 min), and returns hold_id. The user has 10 minutes to pay. Hold Manager (cron) releases expired holds.

### "What if payment succeeds but the booking DB write fails?"

> We retry the DB write with the same idempotency key. The PSP won't re-charge because it has our idempotency key. If the DB is truly down for an extended period, we refund via PSP and notify the user. We also use the transactional outbox pattern: the booking write and the Kafka event go in the same DB transaction, so if the write fails, the event isn't published either — keeping everything consistent.

### "How do you handle hold expiry?"

> A Hold Manager (cron job) runs every 30-60 seconds, queries `show_seats WHERE status='HELD' AND expires_at < NOW()`, and sets those seats back to AVAILABLE. To avoid thundering herd (thousands of holds expiring at once after a hot show goes on sale), we process in batches with rate limiting. Alternative: delayed queue (each hold creates a delayed message that fires at expires_at), which gives precise timing and natural staggering.

### "Why not cache seat availability?"

> Caching availability with even a 30-second TTL means a user could see seats as "available" that were just held by someone else. They click "Hold," get an error, and have a frustrating experience. We cache everything ELSE (movie info, venue info, seat layout — all static), but availability must come from the DB in real-time (or very short TTL < 5 sec).

### "How do you scale for a hot show?"

> Sharding by show_id means all seats for one show are on one shard — which is also the bottleneck. Options: (1) DB row lock handles moderate contention (100-200 concurrent holds/sec) well — most get 409 quickly. (2) For extreme cases (10K users, 200 seats), add a Redis + Lua gate for fast atomic hold, with DB write only on confirm. (3) Virtual waiting room: randomized delay before showing seats, spreading out demand. (4) Queue: funnel hold requests and process N at a time, showing users their position.

### "What events flow through Kafka?"

> BOOKING_HELD (trigger hold-expiry warning push), BOOKING_CONFIRMED (confirmation email, analytics), BOOKING_RELEASED (hold expired notification), BOOKING_CANCELLED (if we add post-booking cancellation). All published via transactional outbox — same DB transaction as the state change. Notification Service and Analytics consume independently. If Notification is down, events queue in Kafka; bookings are unaffected.

### "How is this different from Uber's ride booking?"

> Uber is one-to-one (one rider, one driver). BookMyShow is many-to-many contention (10,000 users, 200 seats). Uber's bottleneck is location writes (20K/sec) and geospatial matching. BookMyShow's bottleneck is hot-partition write contention on the same show's seats. Uber doesn't need a "hold" step — matching and accept are instant. BookMyShow needs hold because the user needs time to pay. Both use atomic conditional updates as the core consistency mechanism.
