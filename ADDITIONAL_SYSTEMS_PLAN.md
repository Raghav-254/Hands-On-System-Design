# Plan: Additional System Design Cheatsheets

This document outlines what to cover in each of the four new systems. Each will live in its own folder with `INTERVIEW_CHEATSHEET.md` (and optionally `README.md`), following the same pattern as existing systems (e.g. `digital_wallet_system/`, `payment_system/`).

---

## 1. Uber (Ride-Sharing)

**Folder:** `uber_system/`  
**File:** `uber_system/INTERVIEW_CHEATSHEET.md`

### Problem in one line
Design a system where riders request rides and drivers accept them; match riders to nearby drivers, track location in real time, and handle the full trip lifecycle.

### Functional requirements to cover
- Rider: request ride (pickup, dropoff, maybe ride type), see ETA, see driver location, pay, rate.
- Driver: go online/offline, receive ride requests, accept/decline, navigate, complete ride.
- Matching: assign a driver to a ride request (nearby, available).
- Real-time: driver location updates, ETA updates, ride status (requested → matched → in progress → completed).

### Non-functional requirements
- Scale: millions of riders/drivers; thousands of concurrent rides; frequent location updates.
- Latency: match within a few seconds; location updates every few seconds.
- Availability: high (ride request must not be lost).

### Sections to include in the cheatsheet
1. **Quick reference card** — Table: Component (Rider App, Driver App, Matching Service, Location Service, Trip Service, etc.) | Purpose | Key points.
2. **What are we building?** — Requirements (functional + non-functional), scope (single city vs global, UberX only vs multiple products).
3. **Back-of-the-envelope** — Riders/drivers/rides per second; location updates/sec; storage for locations/trips.
4. **API design** — Request ride, get nearby drivers, update location, accept ride, get trip status, complete trip (sample endpoints + request/response).
5. **High-level architecture** — Diagram: Rider/Driver clients → API Gateway / LB → Services (Matching, Location, Trip, Notification); datastores (driver location, trips, user data).
6. **Core challenge 1: Matching** — How to match rider to driver: pull (rider sees list) vs push (system assigns). Geospatial: find drivers within X km of rider (geohash, grid, or spatial index). Queue of requests vs real-time assignment. Suggested approach: match request to nearest available driver(s), notify driver(s), first to accept wins (or assign directly with timeout).
7. **Core challenge 2: Location updates** — High write volume (every 4–10 sec per driver). Where to store: time-series DB, or Redis/Cache with TTL. Push to rider app: WebSocket or long polling. Throttling/aggregation to reduce load.
8. **Core challenge 3: Trip lifecycle** — States: requested → matched → driver_en_route → in_progress → completed (or cancelled). Who owns state; idempotency for transition calls.
9. **Data model** — Users, Drivers (availability, last location), Trips (rider_id, driver_id, status, pickup/dropoff, timestamps), maybe DriverLocation (driver_id, lat/lng, timestamp).
10. **Scale & trade-offs** — Sharding by region/geohash; caching (driver locations, trip status); eventual consistency for “nearby drivers” vs strong consistency for trip state. ETA calculation (routing service or external).
11. **Interview talking points** — “How does matching work?” “How do you scale location updates?” “What if two drivers accept the same request?” (idempotency, single assigner).

### Key concepts to define
- Geohash / spatial indexing (find nearby drivers).
- Push vs pull for matching.
- Idempotency for ride acceptance (avoid double-assign).

---

## 2. Splitwise

**Folder:** `splitwise_system/`  
**File:** `splitwise_system/INTERVIEW_CHEATSHEET.md`

### Problem in one line
Users in a group record who paid for what and who owes whom; the system simplifies debts so that the minimum number of transactions settles everyone.

### Functional requirements to cover
- Users, groups, expenses (who paid, amount, split among whom, how: equal vs custom).
- Balances: per user (net position) and per pair (A owes B $X).
- Debt simplification: given a graph of “A owes B $X,” compute minimal set of transfers (e.g. A→B, B→C becomes A→C).
- Settle up: record a payment (A paid B $X), update balances.
- History: list of expenses and settlements.

### Non-functional requirements
- Correctness: balances always consistent; simplification is deterministic.
- Scale: large groups (100+ members), many expenses; read-heavy (view balances, history).

### Sections to include in the cheatsheet
1. **Quick reference card** — Table: Component (User Service, Expense Service, Balance Store, Simplification Engine, etc.) | Purpose | Key points.
2. **What are we building?** — Requirements; scope (simplification algorithm in-scope, multi-currency optional).
3. **Core concept: Expense and balances** — When A pays $100 for A,B,C (equal split): A is +$66.67, B and C are -$33.33 each. Store “who owes whom how much” (directed graph or balance matrix).
4. **Data model** — Users, Groups, Expenses (payer, amount, split_type, participants, splits), Balances (creditor, debtor, amount). Optionally: simplified_balances or only store raw expenses and compute on read.
5. **Debt simplification algorithm** — Goal: minimize number of transactions. Approach: net each person (total owed - total owes) → only “debtors” (negative net) and “creditors” (positive net) remain. Then: greedy — largest creditor gets paid by largest debtor until settled; or max-flow/min-transactions formulation. Include simple example (A owes B $10, B owes C $10 → A owes C $10).
6. **APIs** — Add expense, add settlement, get balances (per user, per group), get expense history, get “simplified” who-pays-whom.
7. **High-level architecture** — API → Expense Service (validates, writes expense) → Balance computation (on write or on read?) → Balance store / cache. Read path: get balances (maybe cached); simplification can be on read or periodic job.
8. **When to compute simplification** — On every expense (eager), on demand when user views “simplify” (lazy), or hybrid. Trade-off: freshness vs compute cost.
9. **Scale** — Groups sharded by group_id; balance reads cached; expense list paginated.
10. **Interview talking points** — “How do you represent who owes whom?” “How do you minimize transactions?” “How do you handle concurrent expenses in the same group?” (optimistic lock or version on group).

### Key concepts to define
- Net balance (sum of “I owe” minus “I am owed”).
- Debt simplification (minimize number of transfers).
- Eager vs lazy simplification.

---

## 3. BookMyShow

**Folder:** `bookmyshow_system/`  
**File:** `bookmyshow_system/INTERVIEW_CHEATSHEET.md`

### Problem in one line
Users browse movies, shows, and venues; select seats; and book tickets. The system must prevent double booking (two users booking the same seat) and handle high concurrency during popular releases.

### Functional requirements to cover
- Browse: movies, venues, screens, show times, seat layout.
- Seat selection: view available seats for a show; select seats; reserve (hold) for a short time; confirm (pay) and book.
- Booking lifecycle: available → held (locked for N minutes) → confirmed (paid) / expired (released back).
- Payments: integrate with payment gateway; idempotency for payment + booking.
- Notifications: confirmation, reminders.

### Non-functional requirements
- Consistency: same seat must not be double-booked (strong consistency for seat state).
- High concurrency: thousands of users selecting seats for the same show (e.g. big release).
- Latency: seat selection and hold should feel instant.

### Sections to include in the cheatsheet
1. **Quick reference card** — Table: Component (Show Service, Seat Inventory Service, Booking Service, Payment Service, Lock/Reservation, etc.) | Purpose | Key points.
2. **What are we building?** — Requirements; scope (single region vs multi-venue; seat selection vs general admission).
3. **Core challenge: Concurrency and double booking** — Two users select same seat: we need a single source of truth and atomic “reserve this seat” (e.g. DB row lock, or distributed lock, or optimistic locking with version).
4. **Seat states** — Available → Held (user X, expires at T) → Confirmed (booking_id) / Expired (back to available). Who releases held seats: TTL + background job, or on next read.
5. **API design** — Get shows by movie/venue/date; get available seats for show; hold seats (seat_ids, show_id, user_id, expiry_sec); confirm booking (hold_id, payment_id); release hold (hold_id). Idempotency key for confirm.
6. **Data model** — Venues, Screens, Seats (screen_id, row, number), Shows (movie_id, screen_id, time), Bookings (user_id, show_id, seat_ids, status, payment_id), Holds (hold_id, show_id, seat_ids, user_id, expires_at).
7. **High-level architecture** — Client → API Gateway → Show/Inventory Service (read-heavy, cache) + Booking Service (write path: hold, confirm). Seat inventory: DB with row-level locking or Redis with Lua script for atomic hold. Payment: async or sync with idempotency.
8. **Hold and release** — How long is hold (e.g. 10 min)? How to release: cron that finds expired holds and marks seats available; or lazy (on read, if expired then release). Avoid thundering herd when many holds expire.
9. **Scale** — Shard by show_id or venue_id; cache show metadata and seat layout (not availability); availability and hold in a store that supports atomic updates (DB or Redis). Payment idempotency to avoid double charge on retry.
10. **Interview talking points** — “How do you prevent double booking?” (atomic reserve, single authority). “How do you handle hold expiry?” “What if payment succeeds but booking write fails?” (compensating action or retry with idempotency).

### Key concepts to define
- Seat hold (temporary lock with TTL).
- Atomic reserve (compare-and-set or row lock).
- Idempotency for confirm (payment + booking).

---

## 4. URL Shortener (e.g. bit.ly)

**Folder:** `url_shortener_system/`  
**File:** `url_shortener_system/INTERVIEW_CHEATSHEET.md`

### Problem in one line
Given a long URL, return a short alias (e.g. `short.com/abc12`); when users open the short URL, redirect them to the original long URL. High read volume, lower write volume.

### Functional requirements to cover
- Create short URL: input long URL, optional custom alias, output short URL.
- Redirect: GET short URL → 301/302 redirect to long URL.
- Optional: custom alias (user picks “short.com/mylink”); expiry; analytics (click count, maybe by country/device).

### Non-functional requirements
- Scale: billions of redirects (reads) per day; millions of creates (writes) per day. Read-heavy.
- Latency: redirect must be very fast (single digit ms).
- Uniqueness: short code must be unique; custom alias must be unique if provided.

### Sections to include in the cheatsheet
1. **Quick reference card** — Table: Component (API Service, Redirect Service, Key Generation, Storage, Cache) | Purpose | Key points.
2. **What are we building?** — Requirements; scope (redirect only vs analytics, custom alias, expiry).
3. **Back-of-the-envelope** — Reads vs writes per day; storage size (e.g. 100M URLs × 500 bytes); cache hit ratio assumption (e.g. 80%).
4. **API design** — POST create (long_url, optional custom_alias) → short_url; GET /:shortCode → 302 Redirect with Location: long_url. Optional: GET analytics.
5. **Key generation** — How to generate short code (e.g. 6–7 chars): base62 (a–z, A–Z, 0–9). Options: (a) Random + collision check (retry if exists); (b) Single DB sequence + base62 encode (no collision, single writer); (c) Pre-generate batches (e.g. K keys per service instance from a central sequence or range). Mention trade-offs (random: simple but retries; sequence: no collision but bottleneck; range: scalable).
6. **Data model** — Table: short_code (PK), long_url, user_id (optional), created_at, expires_at (optional). Index on short_code for redirect; maybe on long_url if we need “find existing short URL for this long URL.”
7. **High-level architecture** — Write path: API → generate key → store (DB). Read path (redirect): GET → Cache (short_code → long_url); on miss → DB → cache → redirect. Cache: Redis/Memcached, TTL. DB: single table, shard by short_code if needed (range or hash).
8. **Redirect flow** — 301 (permanent) vs 302 (temporary): 301 allows browser cache, 302 allows us to count clicks every time. For analytics, 302. Response: HTTP 302, Location: long_url.
9. **Scale** — Cache first (most reads from cache); DB for persistence; key generation scaled via range allocation or pre-generated keys. Optional: separate read replicas for DB.
10. **Optional: Custom alias** — User provides “short.com/myblog.” Check uniqueness; store same as above. Conflict if already taken (409 or error message).
11. **Interview talking points** — “How do you generate the short code?” “How do you handle collisions?” “Why 302 vs 301?” “How do you scale reads?” (cache).

### Key concepts to define
- Base62 encoding (compact short code).
- Read-heavy: cache-first for redirects.
- Idempotency for create (same long_url + custom_alias → same short URL if we support “get or create”).

---

## Implementation order (suggested)

| Order | System        | Folder                  | Relative complexity |
|-------|---------------|-------------------------|---------------------|
| 1     | URL Shortener | `url_shortener_system/` | Low                 |
| 2     | Splitwise     | `splitwise_system/`     | Low–Medium          |
| 3     | BookMyShow    | `bookmyshow_system/`    | Medium              |
| 4     | Uber          | `uber_system/`          | Medium–High         |

After creating each `INTERVIEW_CHEATSHEET.md`, add the system to the main `README.md` table in the same format as the existing 16 systems.

---

## File layout (per system)

```
uber_system/
  INTERVIEW_CHEATSHEET.md   # Main cheatsheet (sections as above)
  README.md                 # Optional: 1–2 para + link to cheatsheet

splitwise_system/
  INTERVIEW_CHEATSHEET.md
  README.md

bookmyshow_system/
  INTERVIEW_CHEATSHEET.md
  README.md

url_shortener_system/
  INTERVIEW_CHEATSHEET.md
  README.md
```

Each cheatsheet should follow the style of existing ones: Quick reference card at top, then “What are we building?”, requirements, APIs, design sections, data model, scale, and interview talking points. Use bullets, tables, and simple ASCII diagrams where helpful.
