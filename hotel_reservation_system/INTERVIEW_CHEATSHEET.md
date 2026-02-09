# 🏨 Hotel Reservation System - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 7

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Hotel Service** | Hotel/room info, search | MySQL + Redis Cache | Read-heavy, cache-friendly |
| **Rate Service** | Dynamic pricing per date | MySQL (Rate DB) | Price = f(date, demand, room type) |
| **Reservation Service** | Core booking logic | MySQL (Reservation DB) | Concurrency control, ACID transactions |
| **Payment Service** | Process payments | Payment DB | Pay at reservation time, refund on cancel |
| **Hotel Management Service** | Admin CRUD operations | Same Hotel DB | Internal API, invalidates cache |
| **Public API Gateway** | Routes user requests | N/A | Auth, rate limiting, load balancing |
| **CDN** | Static content (images, JS) | Edge servers | Hotel images, website assets |

---

## The Story: Building a Hotel Reservation System

Let me walk you through how we'd build a hotel reservation system for a chain with 5,000 hotels and 1 million rooms.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- Show hotel-related pages (hotel info, photos, reviews)
- Show room detail pages (room types, amenities, availability)
- Reserve a room (check-in date, check-out date, room type)
- Cancel a reservation
- Support **10% overbooking** (sell 110% of capacity expecting cancellations)
- **Dynamic pricing** — room price varies by date based on demand
- Admin panel to add/remove/update hotel or room info
- Customers pay in full at reservation time

### Non-Functional Requirements

- **High concurrency:** Popular hotels during peak season — many users booking the same room simultaneously
- **Moderate latency:** A few seconds for reservation is acceptable
- **Data consistency:** No double-booking (two users cannot book the same last room)

### Back-of-the-Envelope Estimation

```
┌──────────────────────────────────────────────────────────────┐
│  Hotels:              5,000                                  │
│  Total rooms:         1,000,000                              │
│  Occupancy rate:      70%                                    │
│  Avg stay duration:   3 days                                 │
│                                                              │
│  Daily reservations:  (1M × 0.7) / 3 = ~240,000             │
│  Reservations/sec:    240,000 / 86,400 = ~3 TPS              │
│                                                              │
│  → This is NOT a high-throughput system!                     │
│  → The challenge is CONCURRENCY, not raw QPS.                │
│  → Multiple users trying to book the last room at the same   │
│    time during concerts, holidays, big events.               │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. API Design

### Hotel-Related APIs (Public)

```
GET /hotels/{hotel_id}
  Returns: Hotel info (name, address, stars, photos, amenities)
  Cache: YES (Redis) — hotel info rarely changes
```

### Room-Related APIs (Public)

```
GET /hotels/{hotel_id}/rooms
  Returns: List of room types for the hotel (name, description, amenities, photos)
  Cache: YES (Redis) — room type info rarely changes

GET /hotels/{hotel_id}/rooms/{room_type_id}
  Returns: Room type details (description, amenities, photos, max occupancy)
  Cache: YES (Redis)

GET /hotels/{hotel_id}/rooms/{room_type_id}/availability
  Params: check_in, check_out
  Returns: { available: true/false, rooms_left: 4, price_per_night: [...] }
  Cache: NO — changes with every booking

GET /hotels/{hotel_id}/rooms/{room_type_id}/rate
  Params: check_in, check_out
  Returns: Price per night for each date in range + total price
  Cache: Short TTL (prices change daily based on demand)
```

### Reservation APIs (Public)

```
POST /reservations
  Body: {
    hotel_id, room_type_id, check_in, check_out,
    user_id, idempotency_key
  }
  Returns: { reservation_id, status, total_price }

  → idempotency_key prevents double booking on network retry

GET /reservations/{reservation_id}
  Returns: Reservation details and status

DELETE /reservations/{reservation_id}
  Returns: Cancellation confirmation + refund status
```

### Admin APIs (Internal — behind Internal API gateway)

```
POST /admin/hotels                  → Add new hotel
PUT  /admin/hotels/{hotel_id}       → Update hotel info
DELETE /admin/hotels/{hotel_id}     → Remove hotel

POST /admin/rooms                   → Add room type
PUT  /admin/rooms/{room_type_id}    → Update room info/inventory
```

> **Why separate public and internal APIs?**
> Public API Gateway handles auth, rate limiting, and is internet-facing.
> Internal API is only accessible within the corporate network (admin operations).

---

## 3. Data Model

### Hotel & Room Tables (Hotel DB)

```
┌────────────────────────────────────────────────────┐
│ hotel                                              │
├──────────────┬─────────────────────────────────────┤
│ hotel_id     │ PK, auto-increment                  │
│ name         │ "Grand Plaza"                       │
│ address      │ "123 Main St, New York"             │
│ city         │ "New York"                          │
│ star_rating  │ 5                                   │
│ created_at   │ timestamp                           │
└──────────────┴─────────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ room_type                                          │
├──────────────┬─────────────────────────────────────┤
│ room_type_id │ PK, auto-increment                  │
│ hotel_id     │ FK → hotel                          │
│ name         │ "Standard King"                     │
│ description  │ "King bed, city view"               │
│ total_rooms  │ 10                                  │
└──────────────┴─────────────────────────────────────┘
```

### Room Inventory Table (Reservation DB) — THE KEY TABLE

```
┌─────────────────────────────────────────────────────────────────┐
│ room_inventory                                                  │
├──────────────────┬──────────────────────────────────────────────┤
│ hotel_id         │ FK → hotel                                   │
│ room_type_id     │ FK → room_type                               │
│ date             │ "2024-07-15"                                 │
│ total_inventory  │ 11 (10 rooms × 1.10 overbooking)             │
│ total_reserved   │ 7                                            │
│ version          │ 42 (for optimistic locking)                  │
├──────────────────┴──────────────────────────────────────────────┤
│ PK: (hotel_id, room_type_id, date)                              │
│                                                                 │
│ Available = total_inventory - total_reserved = 11 - 7 = 4       │
│                                                                 │
│ Overbooking: total_inventory = total_rooms × 1.10               │
│ If hotel has 10 Standard Kings → total_inventory = 11           │
│ We can sell up to 11 reservations per night for this room type  │
└─────────────────────────────────────────────────────────────────┘
```

### Reservation Table

```
┌────────────────────────────────────────────────────┐
│ reservation                                        │
├──────────────────┬─────────────────────────────────┤
│ reservation_id   │ PK, auto-increment              │
│ hotel_id         │ FK → hotel                      │
│ room_type_id     │ FK → room_type                  │
│ user_id          │ FK → user                       │
│ check_in_date    │ "2024-07-15"                    │
│ check_out_date   │ "2024-07-17"                    │
│ total_price      │ 600.00                          │
│ status           │ PENDING / CONFIRMED / CANCELLED │
│ idempotency_key  │ "abc-123-xyz" (unique)          │
│ created_at       │ timestamp                       │
└──────────────────┴─────────────────────────────────┘
```

> **Can't idempotency_key just BE the reservation_id?**
>
> Technically yes — the client could generate a UUID and the server uses it as the PK.
> But they're kept separate because they have different responsibilities:
>
> | | reservation_id | idempotency_key |
> |---|---|---|
> | **Generated by** | Server (auto-increment or UUID) | Client (UUID before sending request) |
> | **Purpose** | Identify the reservation in the system | Detect duplicate/retry requests |
> | **Lifetime** | Permanent — lives forever in DB | Temporary — only matters during the booking attempt |
> | **Format** | Controlled by server (e.g., "RES-00123") | Any UUID the client generates |
>
> **Why not merge them?** If the client generates the PK, the server loses control over
> ID format, uniqueness guarantees, and sequential ordering. Auto-increment IDs are also
> more efficient for MySQL indexes (B-Tree inserts are faster for sequential keys vs random UUIDs).

### Rate Table (Rate DB)

```
┌────────────────────────────────────────────────────┐
│ room_rate                                          │
├──────────────────┬─────────────────────────────────┤
│ hotel_id         │ FK → hotel                      │
│ room_type_id     │ FK → room_type                  │
│ date             │ "2024-07-15"                    │
│ price            │ 200.00                          │
├──────────────────┴─────────────────────────────────┤
│ PK: (hotel_id, room_type_id, date)                 │
│                                                     │
│ Dynamic pricing: price varies by date               │
│ Friday: $200, Saturday: $220, Sunday: $180          │
│ Price is set by revenue management algorithms       │
└─────────────────────────────────────────────────────┘
```

> **Why MySQL (not NoSQL)?**
> 1. **ACID needed:** Reservations require transactions — atomically check availability
>    AND reserve the room. MySQL with locking guarantees this. NoSQL cannot easily.
> 2. **Read-heavy workload:** Users browse hotels and rooms far more than they book
>    (~3 TPS writes vs thousands of read QPS). MySQL handles read-heavy workloads
>    well with read replicas + Redis cache in front.
> 3. **Relational model fits naturally:** Hotels → Room Types → Reservations have clear
>    relationships. Joins (e.g., "show all room types for this hotel") are efficient.

---

## 4. The Big Picture (High-Level Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║            HOTEL RESERVATION SYSTEM - HIGH-LEVEL DESIGN                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║     Public (User-facing)              │          Private (Admin)             ║
║                                        │                                     ║
║  ┌──────┐    ┌───────┐                │      ┌───────┐                      ║
║  │ CDN  │◀───│ User  │                │      │ Admin │                      ║
║  │      │    │(App/  │                │      └───┬───┘                      ║
║  └──────┘    │ Web)  │                │          │                           ║
║              └───┬───┘                │          ▼                           ║
║                  │                     │   ┌──────────────┐                  ║
║                  ▼                     │   │ Internal API │                  ║
║         ┌────────────────┐            │   └──────┬───────┘                  ║
║         │ Public API     │            │          │                           ║
║         │ Gateway        │            │          ▼                           ║
║         └───┬──┬──┬──┬──┘            │   ┌──────────────┐                  ║
║             │  │  │  │                │   │ Hotel Mgmt   │                  ║
║     ┌───────┘  │  │  └────────┐      │   │ Service      │                  ║
║     ▼          ▼  ▼           ▼      │   └──────────────┘                  ║
║  ┌───────┐ ┌──────┐ ┌──────────┐ ┌───────┐                                ║
║  │Hotel  │ │Rate  │ │Reservation│ │Payment│                                ║
║  │Service│ │Service│ │Service   │ │Service│                                ║
║  └───┬───┘ └──┬───┘ └────┬─────┘ └───┬───┘                                ║
║      │        │           │           │                                     ║
║  ┌───┴───┐ ┌──┴──┐  ┌────────────────┐ ┌───┴────┐                        ║
║  │ Cache │ │Rate │  │ Reservation DB │ │Payment │                        ║
║  │(Redis)│ │ DB  │  │                │ │Service │                        ║
║  │   +   │ └─────┘  │ • reservation  │ └────────┘                        ║
║  │Hotel  │          │ • room_inventory│                                   ║
║  │  DB   │          └────────────────┘                                   ║
║  └───────┘                                                                 ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

> **Reservation DB contains two tables:**
> - **reservation** — booking records (user, hotel, room type, dates, status, idempotency key)
> - **room_inventory** — availability per (hotel, room_type, date) with version column for optimistic locking
>
> Both must be in the **same database** so that reserving inventory + creating the
> reservation record can happen in a **single ACID transaction**.

> **What about Payment DB?**
> In most real systems, payment is handled by a **third-party payment processor**
> (Stripe, PayPal, Adyen) — not an in-house database. The Reservation Service calls
> the payment provider's API as part of the booking flow. We don't need a separate
> Payment DB. If we did track payment records internally (for auditing, refunds),
> they could live in the Reservation DB itself since the payment is tightly coupled
> to the reservation lifecycle.

> **Key design choice:** Each service has its own database (microservices pattern).
> Reservation DB is separate from Hotel DB because reservation writes need ACID
> transactions, while hotel data is read-heavy and cached.

---

## 5. Deep Dive: The Reservation Flow

This is the core of the system. Let's follow what happens when a user books a room.

```
User clicks "Book Now"
      │
      ▼
API Gateway → Reservation Service (ALL steps below happen here)
      │
      ▼
① Check idempotency key (query reservation table)
   "Have I seen this request before?"
   → YES: return existing reservation (prevents double booking on retry)
   → NO: continue
      │
      ▼
② Check availability (for EACH date in the range)
   SELECT total_inventory, total_reserved, version
   FROM room_inventory
   WHERE hotel_id = ? AND room_type_id = ? AND date = ?

   → available = total_inventory - total_reserved
   → If available ≤ 0 for ANY date → REJECT
      │
      ▼
③ Calculate total price
   SUM of price per night from Rate DB
   e.g., Fri $200 + Sat $220 + Sun $180 = $600
      │
      ▼
④ Reserve inventory (with OPTIMISTIC LOCKING — see Section 6)
   UPDATE room_inventory
   SET total_reserved = total_reserved + 1, version = version + 1
   WHERE hotel_id = ? AND room_type_id = ? AND date = ?
     AND version = {expected_version}

   → If version mismatch (rows affected = 0) → RETRY or REJECT
   → Do this for EACH date in the range
      │
      ▼
⑤ Create reservation record (status = PENDING)
      │
      ▼
⑥ Process payment
   → SUCCESS: update status to CONFIRMED
   → FAILURE: update status to REJECTED, release inventory (rollback)
```

---

## 6. Deep Dive: Concurrency Control

There are two concurrency problems to solve:
1. **Same user, duplicate request** — user clicks "Book" twice or network retries the request → solved by **idempotency key** (Section 8)
2. **Different users, same room** — two users try to book the last room at the same instant → solved by **database locking** (this section)

The idempotency key handles the easy case (check if key exists → return existing reservation). The hard case is below — preventing two *different* users from booking the last room.

### The Problem

```
User A and User B both see "1 room available" and click "Book" at the same time.

  Without locking:
    User A reads: available = 1     User B reads: available = 1
    User A books: reserved + 1      User B books: reserved + 1
    → Both succeed! But only 1 room exists → DOUBLE BOOKING!
```

### Option 1: Pessimistic Locking (SELECT FOR UPDATE)

```sql
-- Locks the row until transaction commits
BEGIN;
SELECT total_reserved, total_inventory
FROM room_inventory
WHERE hotel_id = ? AND room_type_id = ? AND date = ?
FOR UPDATE;                             ← Row is LOCKED

-- Check availability
IF total_inventory - total_reserved > 0 THEN
    UPDATE room_inventory SET total_reserved = total_reserved + 1 ...;
    INSERT INTO reservation ...;
END IF;
COMMIT;                                 ← Lock released
```

```
User A: SELECT FOR UPDATE → locks row → checks → books → COMMIT (releases lock)
User B: SELECT FOR UPDATE → WAITS (blocked) → lock released → reads updated data
        → sees 0 available → REJECTED ✓

Pros: Simple, guarantees no double booking
Cons: Blocks other users (high contention on popular rooms)
      Can cause deadlocks if multiple rows locked in different order
```

### Option 2: Optimistic Locking (Version Column) — PREFERRED

```sql
-- Read current state (no lock)
SELECT total_reserved, total_inventory, version
FROM room_inventory
WHERE hotel_id = ? AND room_type_id = ? AND date = ?;

-- Try to update with version check
UPDATE room_inventory
SET total_reserved = total_reserved + 1, version = version + 1
WHERE hotel_id = ? AND room_type_id = ? AND date = ?
  AND version = 42;                     ← Only succeeds if version unchanged

-- Check rows affected
IF rows_affected = 0 THEN
    -- Someone else modified → RETRY or REJECT
END IF;
```

```
User A: reads version=42 → UPDATE WHERE version=42 → SUCCESS (version→43)
User B: reads version=42 → UPDATE WHERE version=42 → 0 rows affected → RETRY
        reads version=43 → checks availability → books or rejects

Pros: No blocking! Non-locking reads → better throughput
Cons: Retry logic needed. Under very high contention, many retries = wasted work
```

### Option 3: Database Constraint

```sql
-- Add a CHECK constraint to the table
ALTER TABLE room_inventory
ADD CONSTRAINT check_availability
CHECK (total_reserved <= total_inventory);

-- Now just increment — DB rejects if constraint violated
UPDATE room_inventory
SET total_reserved = total_reserved + 1
WHERE hotel_id = ? AND room_type_id = ? AND date = ?;

-- If total_reserved would exceed total_inventory → DB throws error
-- App catches the error → return "no availability"
```

```
Pros: Simplest approach! No version column, no SELECT FOR UPDATE.
      The database itself enforces the rule — impossible to overbook.
Cons: Constraint violation = exception (not a clean "unavailable" response).
      Less control — hard to provide a good user message.
      Some DBs don't support CHECK constraints well (older MySQL).
```

### Option 4: Atomic UPDATE with WHERE Condition

```sql
-- Single atomic statement — no separate read needed
UPDATE room_inventory
SET total_reserved = total_reserved + 1
WHERE hotel_id = ? AND room_type_id = ? AND date = ?
  AND total_reserved < total_inventory;    ← DB checks atomically

-- Check rows affected
IF rows_affected = 0 THEN
    -- No availability (or row doesn't exist) → REJECT
END IF;
```

```
Pros: Single statement, atomic, no version column, no locks, no retries.
      Simplest correct solution — the WHERE clause prevents overbooking.
Cons: Can't distinguish "no availability" from "row not found".
      No retry opportunity — if two users race, loser is immediately rejected
      (with optimistic lock, loser can retry with fresh version).
```

### Comparison

| | Pessimistic (FOR UPDATE) | Optimistic (Version) | DB Constraint | Atomic UPDATE |
|---|---|---|---|---|
| **Blocking** | Yes — others wait | No | No | No |
| **Extra column** | No | Yes (version) | No | No |
| **Retry possible** | N/A (waits) | Yes (read new version) | No | No |
| **Complexity** | Medium | Medium | Low | **Lowest** |
| **Contention** | Handles well | Retries under contention | Exception on violation | Loser rejected immediately |
| **Best for** | High contention | Low-moderate contention | Simple systems | Simple systems |
| **Our choice** | Fallback for hotspots | **Default** | Alternative | Alternative |

> **Why optimistic locking as default?** At ~3 TPS average, version conflicts are rare.
> Non-blocking reads give better throughput than pessimistic locking. Compared to the
> simpler options (DB constraint, atomic UPDATE), optimistic locking gives us the
> ability to **retry on conflict** rather than immediately rejecting the user — a better
> user experience during brief spikes of contention.

---

## 7. Deep Dive: Overbooking

```
Why overbooking?
  Hotels know that ~10% of reservations get cancelled.
  If a hotel has 100 rooms and only sells 100, they'll have
  ~10 empty rooms on any given night (lost revenue).

  Solution: Sell 110 reservations for 100 rooms.
  Expected: 10 cancellations → 100 guests show up → 100 rooms filled perfectly.

How it's implemented:
  total_inventory = total_rooms × overbooking_factor

  Example:
    total_rooms = 100
    overbooking_factor = 1.10
    total_inventory = 110

  The room_inventory table stores 110 (not 100) as the capacity.
  Reservation service doesn't need any special logic — it just checks
  total_reserved < total_inventory as usual.

Risk:
  If fewer than expected cancel → more guests than rooms!
  Hotels handle this by upgrading guests to better rooms,
  offering compensation, or walking guests to nearby hotels.
```

---

## 8. Deep Dive: Idempotent Reservations

### The Problem

```
User clicks "Book" → request sent → network timeout → user clicks "Book" again
→ Did the first request go through? Will the second create a duplicate booking?
```

### The Solution: Idempotency Key

```
Client generates a unique idempotency_key per booking attempt (e.g., UUID).

  First request:   POST /reservations { ..., idempotency_key: "abc-123" }
    → Server: "abc-123" not seen before → process reservation → save with key
    → Response: { reservation_id: "RES-001", status: "CONFIRMED" }

  Retry request:   POST /reservations { ..., idempotency_key: "abc-123" }
    → Server: "abc-123" already exists → skip processing → return existing result
    → Response: { reservation_id: "RES-001", status: "CONFIRMED" }  (same!)

Implementation:
  reservation table has UNIQUE constraint on idempotency_key
  Before processing: SELECT * FROM reservation WHERE idempotency_key = ?
  If found → return existing reservation
  If not → proceed with booking
```

> **Who generates the key?** The client (browser/app). It creates a UUID when
> the user first clicks "Book" and reuses it for retries. The server just
> checks if it's seen this key before.

> **Is the idempotency check slow?** No — the `idempotency_key` column has a
> UNIQUE **secondary index** (PK is `reservation_id`). The lookup
> `SELECT ... WHERE idempotency_key = ?` is a B-Tree index scan → **O(log n)**,
> which takes microseconds even with millions of rows. The UNIQUE constraint
> also serves double duty: it makes the lookup fast (indexed), and it enforces
> uniqueness at the DB level (a second INSERT with the same key would fail).

---

## 9. Deep Dive: Caching

### What to Cache (and What NOT to Cache)

```
┌─────────────────────────┬───────────────────┬──────────────────────────────┐
│ Data                    │ Hotel Chain (5K)  │ Booking.com Scale            │
├─────────────────────────┼───────────────────┼──────────────────────────────┤
│ Hotel info (name, addr) │ YES (Redis)       │ YES (Redis)                  │
│ Room types (amenities)  │ YES (Redis)       │ YES (Redis)                  │
│ Room images             │ YES (CDN)         │ YES (CDN)                    │
│ Room availability       │ NO ✗              │ YES ✓ (Inventory Cache)      │
│ Reservation data        │ NO ✗              │ NO ✗                         │
│ Pricing                 │ Maybe             │ YES (short TTL)              │
└─────────────────────────┴───────────────────┴──────────────────────────────┘
```

### Cache Strategy: Cache-Aside (for Hotel/Room Info)

```
Read path:
  ① App checks Redis cache
  ② Cache HIT → return immediately
  ③ Cache MISS → read from MySQL → write to Redis → return

Write path (admin updates hotel):
  ① Admin updates hotel in MySQL
  ② Invalidate Redis cache for that hotel
  ③ Next read will populate cache from DB (cache-aside)
```

### Inventory Cache (at Booking.com Scale)

At hotel chain scale (~3 TPS), reads go directly to the Inventory DB — no cache needed.
But at Booking.com scale, availability checks (reads) are **orders of magnitude** higher
than bookings (writes). Millions of users browsing → only thousands actually booking.

```
┌──────────────────────┐
│  Reservation Service │
└──────┬──────────┬────┘
       │          │
  Query inventory  Update inventory
  (read available   (reserve / cancel)
   rooms)           │
       │            ▼
       ▼       ┌──────────────┐
┌──────────┐   │ Inventory DB │──── Async update ────▶ Inventory Cache
│ Inventory│   │   (MySQL)    │                        (Redis)
│  Cache   │   │ Source of    │
│ (Redis)  │   │   truth      │
└──────────┘   └──────────────┘

Key format:  hotelID_roomTypeID_{date}
Value:       number of available rooms
```

> **Who reads from Inventory DB?** Only the Reservation Service — and only for
> **writes** (reserve/cancel). For reads (check availability), the Reservation
> Service queries the **Inventory Cache (Redis)** instead. The DB is the source
> of truth; the cache is for fast reads.

```
Flow:
  ① User checks availability → Reservation Service → reads from Redis cache
  ② User books a room → Reservation Service → writes to Inventory DB
     (optimistic lock on DB — this is where consistency matters)
  ③ After DB write succeeds → async update Redis cache
     (DB triggers cache update, or Reservation Service updates both)
```

### New Challenge: Cache-DB Consistency

```
Problem:
  User A books last room → DB updated (reserved=10) → cache still says available=1
  User B checks availability → reads stale cache → sees "1 room available"
  User B tries to book → goes to DB → optimistic lock succeeds/fails correctly

Is this okay?
  YES — the cache is used for DISPLAY purposes (showing availability to browsers).
  The actual booking always goes through the DB with optimistic locking.
  Worst case: user sees "available" but gets "sold out" at booking time.
  This is acceptable — same experience as any e-commerce site.

  The alternative (no cache) would mean every availability check hits the DB,
  which doesn't scale at Booking.com level.
```

---

## 10. Deep Dive: Data Consistency Across Services

### The Problem

Reservation involves multiple services: Reservation Service + Payment Service.
What if payment succeeds but reservation DB update fails?

### Solution: Saga Pattern (Choreography)

```
Happy path:
  ① Reservation Service: Create reservation (PENDING)
  ② Reservation Service: Reserve inventory (optimistic lock)
  ③ Payment Service: Charge credit card
  ④ Reservation Service: Update status → CONFIRMED

Failure at step ③ (payment fails):
  ④ Reservation Service: Release inventory (compensating action)
  ⑤ Reservation Service: Update status → REJECTED

Failure at step ④ (DB update fails after payment):
  → Payment Service: Issue refund (compensating action)
  → Retry the DB update
```

> **Why not distributed transactions (2PC)?**
> Two-phase commit is slow and blocks resources. At ~3 TPS, the saga pattern
> with compensating transactions is simpler and sufficient.

---

## 11. Scaling: From Hotel Chain to Booking.com

What changes if we're not building for one hotel chain (5K hotels) but for
a platform like Booking.com (millions of hotels, tens of millions of rooms)?

### Database Scaling

```
Hotel chain (5K hotels):
  → Single MySQL instance is fine (~3 TPS)
  → Read replicas for hotel browsing queries

Booking.com scale (millions of hotels):
  → Shard Reservation DB by hotel_id
    Each shard handles reservations for a subset of hotels
  → Hotel DB: Read replicas + Redis cache (hotel info is read-heavy)
  → Rate DB: Shard by hotel_id (each hotel has independent pricing)

  Sharding key = hotel_id
  Why? All queries for a reservation are scoped to one hotel.
  No cross-hotel joins needed.
```

### Service Scaling

```
Hotel chain:
  → Single instance of each service behind load balancer

Booking.com:
  → Multiple instances of each service
  → Reservation Service is stateless → easy horizontal scaling
  → API Gateway handles routing and rate limiting
  → Separate read/write paths:
    Reads (browsing hotels): Many replicas + heavy caching
    Writes (reservations): Fewer instances, focused on consistency
```

### Caching at Scale

```
Hotel chain: Single Redis instance (hotel info only, no inventory cache)
Booking.com: Redis Cluster
  → Cache hotel info, room types, images
  → Cache room inventory (availability) — see Section 9
    Reads (availability checks) hit cache, writes hit DB
    Stale cache is acceptable — booking still goes through DB with locking
  → Cache popular search results
  → CDN for static assets (hotel images worldwide)
```

---

## 12. What Can Go Wrong? (Failure Handling)

### Double Booking

**Scenario:** Two users book the last room simultaneously
**Solution:** Optimistic locking (version check) ensures only one succeeds. The other gets a "room unavailable" error and retries or sees updated availability.

### Payment Failure After Inventory Reserved

**Scenario:** Inventory deducted but payment fails
**Solution:** Compensating transaction — release inventory back, set reservation to REJECTED. Use a timeout: if reservation stays PENDING for > 10 minutes, auto-release inventory.

### Service Crash Mid-Reservation

**Scenario:** Server crashes between reserving inventory and creating reservation record
**Solution:** Idempotency key ensures retry is safe. Stale PENDING reservations are cleaned up by a periodic job (release inventory for expired PENDING reservations).

### Cache Inconsistency

**Scenario:** Admin updates hotel info but cache still shows old data
**Solution:** Cache-aside with explicit invalidation. Admin write → invalidate cache → next read populates from DB. Set TTL as safety net (e.g., 1 hour).

---

## 13. Why These Choices? (Key Design Decisions)

### Decision #1: MySQL Over NoSQL

**Problem:** Which database for reservations?

**Why MySQL:** Reservations need ACID transactions (check + reserve must be atomic).
Relational model fits naturally (hotels → room_types → reservations).
At ~3 TPS, a single MySQL instance handles the write load easily.

### Decision #2: Optimistic Locking Over Pessimistic

**Problem:** How to prevent double booking?

**Why optimistic:** At ~3 TPS, version conflicts are rare. Non-blocking reads give
better throughput. Pessimistic locking (FOR UPDATE) blocks concurrent readers
unnecessarily. We can fall back to pessimistic for extreme hotspot cases.

### Decision #3: Microservices Over Monolith

**Problem:** How to organize the system?

**Why microservices:** Hotel browsing (read-heavy, cacheable) has completely different
scaling needs from reservations (write-heavy, transactional). Separating them lets
us cache aggressively for reads without worrying about reservation consistency.

### Decision #4: Room Type Inventory (Not Individual Rooms)

**Problem:** Track availability per room or per room type?

**Why room type:** Guests book a "Standard King," not "Room 302." Tracking per room
type simplifies inventory management (one row per type per date instead of one row
per room per date). Specific room assignment happens at check-in.

### Decision #5: Overbooking at Inventory Level

**Problem:** How to implement overbooking?

**Why at inventory level:** Simply set `total_inventory = rooms × 1.10`. No special
booking logic needed — the reservation service just checks `reserved < inventory`
as usual. The overbooking factor is configurable per hotel/room type.

---

## 14. Interview Pro Tips

### Opening Statement
"A hotel reservation system is fundamentally a booking system with ACID transactional requirements. At ~3 TPS, the challenge isn't throughput — it's concurrency control for the same room. I'd use MySQL with optimistic locking for reservations, microservices with separate databases, Redis caching for hotel info, and idempotent APIs to handle retries safely."

### Key Talking Points
1. **Concurrency control:** Optimistic locking (version column) for reservations
2. **Overbooking:** total_inventory = rooms × 1.10, no special logic
3. **Idempotency:** Client-generated key prevents double booking on retry
4. **Cache strategy:** Cache hotel info (read-heavy), NOT availability (changes every booking)
5. **Dynamic pricing:** Price per (hotel, room_type, date) — varies daily
6. **Microservices:** Separate read path (Hotel Service + Cache) from write path (Reservation Service)
7. **Scaling:** Shard by hotel_id for Booking.com scale

### Common Follow-ups

**Q: Why not use Redis for availability instead of MySQL?**
A: Availability changes with every booking and needs ACID guarantees. Redis doesn't support transactions across multiple keys reliably. A stale Redis value could lead to overbooking beyond the allowed limit.

**Q: How would you handle a flash sale (1000 users booking 10 rooms)?**
A: Switch to pessimistic locking (SELECT FOR UPDATE) for that specific hotel/date. Or use a distributed lock (Redis SETNX) to serialize access. Queue the requests and process sequentially.

**Q: Why not assign specific rooms at booking time?**
A: Flexibility. If a guest cancels Room 302 and another books the same type, we don't need to reassign. Room assignment at check-in lets the hotel optimize (e.g., put families near elevators, late check-outs on accessible floors).

**Q: How do you handle timezone issues with dates?**
A: All dates stored in hotel's local timezone. Check-in and check-out dates are date-only (no time). The rate and inventory tables key on date, not datetime.

**Q: What if Booking.com scale needs to handle 100K hotels per shard?**
A: Shard by hotel_id using consistent hashing. Each shard is an independent MySQL cluster with read replicas. Cross-shard queries (search across all hotels) are handled by a separate search service (Elasticsearch), not the reservation DB.

---

## 15. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║              HOTEL RESERVATION SYSTEM - COMPLETE FLOW                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌──────┐     ┌─────────┐     ┌───────────┐     ┌──────────┐               ║
║  │ User │────▶│  API    │────▶│ Hotel     │────▶│ Redis    │               ║
║  │      │     │ Gateway │     │ Service   │     │ Cache    │               ║
║  └──────┘     └────┬────┘     └───────────┘     └──────────┘               ║
║                    │                                   │                     ║
║                    │          ┌───────────┐      ┌─────┴─────┐              ║
║                    ├─────────▶│ Rate      │      │ Hotel DB  │              ║
║                    │          │ Service   │      │ (MySQL)   │              ║
║                    │          └─────┬─────┘      └───────────┘              ║
║                    │                │                                        ║
║                    │          ┌─────┴─────┐                                 ║
║                    │          │ Rate DB   │                                 ║
║                    │          │ (MySQL)   │                                 ║
║                    │          └───────────┘                                 ║
║                    │                                                        ║
║                    │          ┌──────────────┐   ┌──────────────┐           ║
║                    ├─────────▶│ Reservation  │──▶│ Reservation  │           ║
║                    │          │ Service      │   │ DB (MySQL)   │           ║
║                    │          └──────┬───────┘   │              │           ║
║                    │                 │           │ • reservation│           ║
║                    │                 │           │ • room_invent│           ║
║                    │                 │           └──────────────┘           ║
║                    │                 │                                      ║
║                    │          ┌──────┴───────┐   ┌──────────────┐           ║
║                    └─────────▶│ Payment      │──▶│ Payment DB   │           ║
║                               │ Service      │   │ (MySQL)      │           ║
║                               └──────────────┘   └──────────────┘           ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  KEY FLOWS:                                                                   ║
║  ──────────                                                                   ║
║  ① Browse: User → API GW → Hotel Service → Redis/MySQL → Hotel info         ║
║  ② Price:  User → API GW → Rate Service → Rate DB → Dynamic price           ║
║  ③ Book:   User → API GW → Reservation Service:                             ║
║            Check idempotency → Check availability → Optimistic lock          ║
║            → Reserve inventory → Payment → Confirm                           ║
║  ④ Cancel: User → API GW → Reservation Service → Release inventory          ║
║            → Trigger refund via Payment Service                               ║
║                                                                               ║
║  CRITICAL DESIGN DECISIONS:                                                   ║
║  ──────────────────────────                                                   ║
║  • MySQL for reservations (ACID transactions needed)                         ║
║  • Optimistic locking (version column) for concurrency control               ║
║  • Overbooking at inventory level (total_inventory = rooms × 1.10)           ║
║  • Idempotency key for safe retries (client-generated UUID)                  ║
║  • Cache hotel info (Redis), NOT availability                                ║
║  • Microservices: separate read path (cached) from write path (transactional)║
║  • Scale to Booking.com: shard by hotel_id                                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```
