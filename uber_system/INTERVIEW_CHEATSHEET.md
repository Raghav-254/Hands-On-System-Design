# 🚗 Uber (Ride-Sharing) - Interview Cheatsheet

> Design a system where riders request rides and drivers accept them; match riders to nearby drivers, track location in real time, and handle the full trip lifecycle from request to completion.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **Rider App / Driver App** | Client apps | Real-time updates (WebSocket or long poll); location, ETA, trip status |
| **API Gateway** | Route requests, auth | Load balance; rate limit; TLS |
| **Matching Service** | Assign driver to ride request | Geo: find nearby available drivers; push or pull; idempotency for accept |
| **Location Service** | Store and serve driver location | High write volume; Redis GEO or geohash index; TTL for offline |
| **Trip Service** | Trip lifecycle (request → match → in progress → complete) | State machine; single source of truth; atomic state transitions |
| **Notification Service** | Notify driver (new request), rider (driver assigned, ETA) | Push (FCM/APNs); in-app WebSocket or polling; fan-out via queue |
| **ETA / Routing Service** | Compute ETA (driver→pickup, pickup→dropoff) | Internal or external (e.g. Google Maps); cache or on-demand |

---

## The Story: Building Uber

A rider requests a ride (pickup, dropoff); the system finds **nearby available drivers** and either shows them to the rider (pull) or **assigns** one (push). The driver accepts (or is auto-assigned); both see real-time **location** and **ETA**. After the trip, payment and rating. The design focuses on **matching** (geo + availability), **location updates** (high write, real-time), and **trip state** (no double-assign). Staff-level depth means we go into geospatial indexing, step-by-step flows, failure handling, and scale trade-offs.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Rider**: Request ride (pickup, dropoff, ride type); see nearby drivers or assigned driver; see ETA and driver location; pay; rate driver.
- **Driver**: Go online/offline; receive ride requests (or see queue); accept/decline; navigate; complete ride; get paid.
- **Matching**: Connect a ride request to a driver (nearby, available). Either rider sees list and driver accepts, or system assigns and notifies.
- **Real-time**: Driver location updates (every 4–10 sec); ETA updates; trip status (requested → matched → driver en route → in progress → completed).

### Non-Functional Requirements

- **Scale**: Millions of riders and drivers; thousands of concurrent trips; very high location update rate (e.g. 100K+ writes/sec for locations).
- **Latency**: Match within a few seconds; location/ETA updates every few seconds.
- **Availability**: Ride request must not be lost; trip state must be consistent (no double-assign).
- **Consistency**: Trip state strongly consistent; location can be eventually consistent (seconds).

### Scope (What We're Not Covering)

- Payment and pricing (surge, fare calculation) — can integrate with payment system.
- Maps and turn-by-turn navigation — assume external or internal routing API.
- Multi-city / multi-region deployment — design is per-region; routing and replication can extend.

---

## 2. Back-of-the-Envelope Estimation

```
┌────────────────────────────────────────────────────────────────────┐
│  Region: 1 major city (e.g. SF Bay Area)                            │
│  Active drivers:         ~100,000                                   │
│  Location update rate:   1 per driver per 5 sec                    │
│  → Location writes:      100,000 / 5 = 20,000 writes/sec           │
│                                                                     │
│  Ride requests (peak):   ~10,000 / min = ~170 requests/sec         │
│  Concurrent trips:       ~50,000                                    │
│  Trip state updates:     accept, start, complete ~3 per trip        │
│  → Trip writes:          much lower than location writes           │
│                                                                     │
│  Key insight: LOCATION WRITES dominate.                             │
│  → Storage for "where are drivers" must be optimized for write     │
│    and geo-query (find nearby). DB alone is a bottleneck;          │
│    Redis or similar with GEO support is the right fit.              │
└────────────────────────────────────────────────────────────────────┘
```

| Metric | Value | Implication |
|--------|-------|-------------|
| Location writes/sec | ~20K (single region) | Redis cluster or sharded cache; not primary DB |
| Ride requests/sec | ~170 | Trip Service + Matching can use DB for state |
| Read: location (rider sees driver) | High | Cache/same store as write; or WebSocket push |
| Data per driver location | ~50 bytes (driver_id, lat, lng, ts) | 100K × 50 = ~5 MB in memory for one snapshot |

---

## 3. API Design

### Rider APIs

```
POST /v1/rides/request
Headers: Authorization: Bearer <rider_token>

Body: {
  "pickup":  { "lat": 37.7749, "lng": -122.4194 },
  "dropoff": { "lat": 37.7849, "lng": -122.4094 },
  "ride_type": "uber_x"
}

Response: 201 Created
{
  "ride_id": "r_abc123",
  "status": "SEARCHING",
  "eta_seconds": null,
  "driver": null,
  "price_estimate": { "amount": 12.50, "currency": "USD", "display": "$12-15" }
}
```

- **Price**: Rider typically sees an **estimated fare** before or when requesting (e.g. "~$12–15"). We can return it in the request response; it may be computed by a **Pricing Service** (distance, ride_type, surge, base fare) or a separate `GET /v1/rides/quote?pickup=...&dropoff=...` before the rider taps "Request." Final charge is determined at trip completion (actual distance/time). Including `price_estimate` in the response lets the rider confirm the range before matching.

```
GET /v1/rides/{ride_id}
  Returns: {
    "ride_id", "status",
    "driver": { "driver_id", "name", "lat", "lng", "eta_to_pickup_seconds" },
    "pickup":  { "lat", "lng" },   // location (optionally "address" for display)
    "dropoff": { "lat", "lng" },
    "eta_to_dropoff_seconds"
  }
  (Polled every 3–5 sec by rider app for live driver location and ETA.
   Used for the full ride lifecycle: SEARCHING → MATCHED → IN_PROGRESS → COMPLETED.
   After start: driver lat/lng and eta_to_dropoff_seconds keep updating until trip ends.)
```

### Driver APIs

```
POST /v1/drivers/me/location
Body: { "lat": 37.7750, "lng": -122.4195 }
  (Sent every 4–10 sec by driver app; throttled client-side)
  Response: 204 No Content

POST /v1/drivers/me/status
Body: { "status": "ONLINE" | "OFFLINE" | "BUSY" }
  (BUSY when driver has accepted a ride and until trip completes)

POST /v1/rides/{ride_id}/accept
Headers: Idempotency-Key: "uuid-..."
  (Only one driver's accept can succeed; others get 409 Conflict)
Response: 200 OK { "ride_id", "status": "MATCHED", "rider", "pickup", "dropoff" }

POST /v1/rides/{ride_id}/start    (driver at pickup, rider in car)
POST /v1/rides/{ride_id}/complete (driver ends trip)
```

---

## 4. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                                    CLIENTS                                            │
│  ┌──────────────┐                                     ┌──────────────┐                │
│  │  Rider App   │                                     │  Driver App  │                │
│  │  (request,   │                                     │  (location,  │                │
│  │   poll/ws)   │                                     │   accept,    │                │
│  └──────┬───────┘                                     │   status)    │                │
│         │                                             └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────┼────────────────────────┘
          │  HTTPS                                             │ HTTPS
          ▼                                                    ▼
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              API GATEWAY / LB                                         │
│              Auth, rate limit, route by path (/rides/*, /drivers/*)                  │
└───────────┬──────────────────┬────────────────────────┬──────────────────────────────┘
            │                  │                        │
     ┌──────▼───────┐  ┌──────▼──────────┐  ┌──────────▼──────────┐
     │  Matching    │  │  Location       │  │  Trip Service       │
     │  Service     │  │  Service        │  │  (state machine)    │
     │  (stateless) │  │  (high write)   │  │                     │
     └──────┬───────┘  └──┬──────┬───────┘  └───┬────────────┬────┘
            │             │      │               │            │
  ┌─────────┘      ┌──────┘      │          ┌────┘            │
  │  HTTP          │ write       │ read     │ read/write      │ publish
  ▼                ▼             ▼          ▼                 ▼
┌──────────────────────┐   ┌────────────────────┐   ┌────────────────────┐
│  Redis Cluster       │   │  Trip DB (MySQL /  │   │      Kafka         │
│  (per region)        │   │  PostgreSQL)       │   │                    │
│                      │   │                    │   │  Topics:           │
│  • GEO sorted set    │   │  • trips           │   │  • trip-events     │
│    (GEOADD,GEORADIUS)│   │  • users           │   │  • location-events │
│  • driver:{id} hash  │   │  • drivers         │   │  • notification-   │
│  • idempotency keys  │   │  • idempotency_keys│   │    events          │
│  • TTL for offline   │   │                    │   │                    │
└──────────────────────┘   └────────────────────┘   └──────┬─────────────┘
                                                           │ consume
                           ┌───────────────────────────────┤
                           │                               │
                    ┌──────▼──────────┐          ┌─────────▼──────────┐
                    │ Notification    │          │ ETA / Routing      │
                    │ Service         │          │ Service            │
                    │                 │          │                    │
                    │ • FCM / APNs   │          │ • Google Maps or   │
                    │ • WebSocket     │          │   internal routing │
                    │   push to rider │          │ • Cache by cell    │
                    └─────────────────┘          └────────────────────┘

     ┌──────────────────────────────────────────────┐
     │          Payment Service (external)           │
     │  Triggered on TRIP_COMPLETED event from Kafka │
     │  Charge rider, pay driver (separate system)   │
     └──────────────────────────────────────────────┘
```

### What Does Each Component Do?

| Component | Responsibility |
|-----------|----------------|
| **API Gateway** | Authenticate rider/driver; rate limit; route to Matching, Location, or Trip Service by path. Single entry point; TLS termination. |
| **Matching Service** | Given pickup (lat, lng), query Location Service for nearby available drivers (Redis GEORADIUS). Pick best driver (push model), call Trip Service to assign. Stateless; scales horizontally. |
| **Location Service** | Receive driver location updates (POST); write to Redis (GEOADD). Serve "find nearby" (GEORADIUS). Optionally publish location-events to Kafka for analytics/history. |
| **Trip Service** | Single source of truth for trip state. Create trip, accept (atomic UPDATE), start, complete. Publishes trip-events to Kafka on every state transition. Enforces valid transitions and idempotency. |
| **Kafka** | Event bus. Decouples Trip Service from downstream consumers (Notification, Payment, Analytics). Enables replay, audit trail, and independent scaling of consumers. |
| **Notification Service** | Consumes trip-events from Kafka; sends push notification (FCM/APNs) and/or in-app message (WebSocket). Decoupled — Trip Service doesn't wait for notification delivery. |
| **ETA / Routing Service** | Given origin and destination, return duration (and polyline). Consumes location-events or called on-demand. Cache by (origin_cell, dest_cell). |
| **Payment Service** | Consumes TRIP_COMPLETED events from Kafka. Charges rider, credits driver. Separate bounded context (see payment system design). |

### Sync vs Async Communication

Not everything is HTTP request-response. Here's the communication matrix:

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                     SYNC (HTTP request-response)                             │
│                                                                              │
│  Rider App ──→ API Gateway ──→ Trip Service      (create ride, poll status) │
│  Driver App ──→ API Gateway ──→ Location Service  (send location update)     │
│  Driver App ──→ API Gateway ──→ Trip Service      (accept ride)             │
│  Trip Service ──→ Matching Service               (find & assign a driver)   │
│  Matching Service ──→ Location Service            (find nearby drivers)      │
│  Matching Service ──→ Trip Service                (assign driver)            │
│  Trip Service ──→ ETA Service                     (get ETA on demand)       │
│                                                                              │
│  WHY SYNC: Caller needs the result immediately to respond to the client.    │
├─────────────────────────────────────────────────────────────────────────────┤
│                     ASYNC (Kafka events)                                     │
│                                                                              │
│  Trip Service ──publish──→ Kafka "trip-events" topic                        │
│       Events: TRIP_MATCHED, TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED   │
│                                                                              │
│  Kafka ──consume──→ Notification Service  (send push to rider/driver)       │
│  Kafka ──consume──→ Payment Service       (charge on TRIP_COMPLETED)        │
│  Kafka ──consume──→ Analytics Service     (metrics, dashboards)             │
│                                                                              │
│  Location Service ──publish──→ Kafka "location-events" topic                │
│       (batch, for analytics — NOT for real-time matching; matching          │
│        reads Redis directly)                                                 │
│                                                                              │
│  WHY ASYNC: Producer doesn't need to wait. Consumers process independently. │
│  Trip Service should not fail or slow down because Notification is slow.    │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Decision | Choice | Why |
|----------|--------|-----|
| Trip Service → Matching Service | **Sync (HTTP)** | Matching is on the **critical path** — rider is staring at the screen waiting for a driver. Can't queue this in Kafka and hope a consumer picks it up soon. If Matching is down, we can't fulfill the ride anyway. |
| Matching → Location Service | **Sync (HTTP)** | Matching needs the list of nearby drivers *right now* to make a decision. Can't wait for an async response. |
| Trip Service → Notification | **Async (Kafka)** | Trip Service's job is done after state change; notification is a side effect. If we made it sync, Trip Service would be blocked by FCM/APNs latency. |
| Trip Service → Payment | **Async (Kafka)** | Payment is a separate bounded context. Trip completion should not fail because payment service is slow. Payment consumes TRIP_COMPLETED event and processes independently. |
| Driver location → Redis | **Sync (HTTP write)** | Driver expects 204 acknowledgment. Write to Redis is fast (<1ms). |
| Location → Kafka | **Async (publish)** | Only for analytics/history; real-time matching reads Redis directly. |

---

## 5. Request Ride — End-to-End Flow (Step-by-Step)

```
① Rider app: POST /v1/rides/request { pickup, dropoff, ride_type }
   │
② API Gateway: auth, route to Matching Service (or Trip Service for create)
   │
③ Trip Service: INSERT trip (ride_id, rider_id, pickup, dropoff, status=SEARCHING)
   │
④ Trip Service calls Matching Service (sync HTTP — rider is waiting)
   │   Matching queries Location Service: "drivers within 3 km of pickup, status=ONLINE"
   │   → Redis GEORADIUS pickup_lat pickup_lng 3 km
   │   → Filter by driver status (from driver hash in Redis or DB)
   │
⑤a PULL model: Return list of driver_ids to rider; rider app shows "Finding driver..."
   │            Notify top N drivers (e.g. 5) via Notification Service: "New ride request"
   │            First driver to POST /rides/{id}/accept wins
   │
⑤b PUSH model: Matching Service picks "best" driver (e.g. closest), calls Trip Service
   │            to assign: UPDATE trip SET driver_id=?, status=MATCHED WHERE ...
   │            Notify that driver: "You've been assigned a ride"
   │            Notify rider: "Driver John is on the way"
   │
⑥ Rider app: Polls GET /v1/rides/{ride_id} every 3–5 sec
   │          Gets driver info (lat, lng, eta_to_pickup) from Trip Service
   │          (Trip Service reads driver location from Location Service or cache)
   │
⑦ Driver app: Receives push + in-app; driver taps "Accept"
   │            POST /v1/rides/{ride_id}/accept with Idempotency-Key
   │
⑧ Trip Service: **Idempotency first** — lookup by Idempotency-Key (e.g. in a separate
   │   idempotency table or cache: key → response). If we already processed this key
   │   for this accept (same driver retry) → return cached 200 and **do not** run UPDATE.
   │   Then: UPDATE trips SET driver_id=?, status='MATCHED', matched_at=NOW()
   │   WHERE ride_id=? AND status='SEARCHING'
   │   (No idempotency_key in this SQL — idempotency is request-level dedup before the write.)
   │   If rows_affected = 0 → 409 Conflict ("already matched"); optionally cache (key → 409).
   │   If rows_affected = 1 → 200 OK, store (key → 200 + trip details) for retries, return to driver
   │   Publish TripMatched(ride_id, driver_id)
   │
⑨ Notification Service: On TripMatched → push to rider "Driver John is on the way"
   │
⑩ Rider and driver see live location and ETA (polling or WebSocket) until trip completes
```

### Where is the idempotency key stored?

We need a **separate store** keyed by the client-supplied Idempotency-Key so we can return the same response on retries without running the UPDATE again. Two common options:

**Option A: Table in the same DB as trips**

```
┌────────────────────────────────────────────────────────────────────────────┐
│ idempotency_keys                                                           │
├──────────────────┬────────────────────────────────────────────────────────┤
│ idempotency_key  │ PK, UUID (the key from request header)                 │
│ request_type     │ e.g. 'ride_accept'                                      │
│ ride_id          │ (optional; for cleanup or lookup by ride)               │
│ response_status  │ 200 or 409                                               │
│ response_body    │ JSON (trip details for 200; error for 409)              │
│ created_at       │ TTL for cleanup (e.g. delete after 24 hours)            │
└──────────────────┴────────────────────────────────────────────────────────┘
```

- **Lookup**: SELECT response_status, response_body FROM idempotency_keys WHERE idempotency_key = ?. If row exists → return stored response; do not run UPDATE.
- **After UPDATE**: If rows_affected = 1, INSERT into idempotency_keys (key, request_type, ride_id, 200, response_body). If rows_affected = 0, optionally INSERT (key, 409, ...) so retries get same 409.

**Option B: Redis**

- Key: `idempotency:ride_accept:{idempotency_key}` (or hash of key).
- Value: JSON `{ "status": 200, "body": { ... } }` or `{ "status": 409 }`.
- TTL: e.g. 24 hours. Fast lookup; no need to hit DB for dedup.

In both cases the **trips** table is not used to store the idempotency key; the key lives in this dedicated store so we can detect duplicate requests before touching trips.

### Complete Trip Lifecycle Flow (Request → Completion → Payment)

The ride request flow above (steps ①–⑩) covers SEARCHING → MATCHED. Here's the **full lifecycle** showing every state transition, which service owns it, and what events flow through Kafka:

```
TIME ─────────────────────────────────────────────────────────────────────────────────►

RIDER APP          API GW        TRIP SVC         MATCHING SVC      LOCATION SVC
─────────          ──────        ────────         ────────────      ────────────
POST /request ───→ route ──────→ INSERT trip
                                 status=SEARCHING
                                      │
                                      │ sync HTTP call (critical path — rider is waiting)
                                      ▼
                                 call Matching ──→ find nearby ──→ GEORADIUS Redis
                                                   drivers          │
                                                   pick best ◄─────┘
                                                   driver
                                                      │
                                                      │ call Trip Svc to assign
                                                      ▼
                                 UPDATE trip      ────────────────────────────────
                                 status=MATCHED                  KAFKA
                                 driver_id=X                     ─────
                                      │
                                      │ publish (outbox)
                                      ▼
                                 TRIP_MATCHED ─────────────→  Notification Svc
                                                               push to rider:
                                                               "Driver on way"

poll GET /ride ──→ route ──────→ read trip +
  (every 3-5s)                   driver loc
                                 from Redis

DRIVER APP                       TRIP SVC                        KAFKA
──────────                       ────────                        ─────
POST /start ───→ route ────────→ UPDATE trip
                                 status=IN_PROGRESS
                                      │ publish (outbox)
                                      ▼
                                 TRIP_STARTED ──────────────→  Notification Svc
                                                               push: "Trip started"

POST /complete ─→ route ───────→ UPDATE trip
                                 status=COMPLETED
                                 completed_at=NOW
                                      │ publish (outbox)
                                      ▼
                                 TRIP_COMPLETED ────────────→  Notification Svc
                                                               push: "Trip complete"
                                                            →  Payment Svc
                                                               charge rider, pay driver
                                                            →  Analytics Svc
                                                               trip metrics
```

### Cancellation Flow

```
SCENARIO 1: Rider cancels while SEARCHING
──────────────────────────────────────────
Rider: POST /rides/{id}/cancel
Trip Service: UPDATE status=CANCELLED WHERE status='SEARCHING'
  → Kafka: TRIP_CANCELLED
  → Matching Service stops looking
  → No driver to notify (none assigned yet)

SCENARIO 2: Rider cancels after MATCHED (driver already assigned)
────────────────────────────────────────────────────────────────
Rider: POST /rides/{id}/cancel
Trip Service: UPDATE status=CANCELLED WHERE status='MATCHED'
  → Kafka: TRIP_CANCELLED
  → Notification Service: push to driver "Ride cancelled"
  → Driver status reverts to ONLINE (available for new rides)
  → Optional: cancellation fee charged to rider

SCENARIO 3: Driver declines / timeout (push model)
───────────────────────────────────────────────────
Matching Service assigned driver D1, but D1 didn't accept within 15 sec.
  → Trip stays SEARCHING (D1 was never committed to trip)
  → Matching Service picks next-best driver D2 and retries
  → If no driver accepts after N retries → CANCELLED + notify rider
```

### What Events Flow Through Kafka?

| Event | Producer | Consumers | Purpose |
|-------|----------|-----------|---------|
| `TRIP_MATCHED` | Trip Service | Notification Service | Notify rider ("driver on way") |
| `TRIP_STARTED` | Trip Service | Notification Service, Analytics | Notify, track |
| `TRIP_COMPLETED` | Trip Service | Notification, Payment, Analytics | Notify, charge, metrics |
| `TRIP_CANCELLED` | Trip Service | Notification, Analytics | Notify driver (if matched), metrics |
| `LOCATION_UPDATE` | Location Service | Analytics (batch) | Historical tracking, heatmaps |

Note: there is **no TRIP_CREATED event in Kafka**. Matching is triggered synchronously by Trip Service (HTTP call), not via Kafka. Matching is on the critical path — the rider is waiting — so we can't afford consumer lag.

> **Why Kafka for everything else?** If Trip Service calls Notification Service synchronously, a slow push notification (FCM taking 2 sec) blocks the trip state transition. With Kafka: Trip Service publishes the event and returns immediately. Notification, Payment, and Analytics consume at their own pace. If Notification is down, events queue up in Kafka and are processed when it recovers — no data loss, no blocking.

---

## 6. Matching in Depth

### Pull vs Push — Trade-offs

| | Pull (rider sees list; driver accepts) | Push (system assigns; notify driver) |
|--|----------------------------------------|--------------------------------------|
| **Flow** | Matching returns nearby drivers; request sent to one or more; first accept wins | Matching picks one driver; Trip Service assigns; driver notified (accept or decline/timeout) |
| **Rider experience** | Sees "Searching..." then driver appears when one accepts | Sees "Driver assigned" after a few seconds |
| **Double-assign** | Handled by atomic accept (only one UPDATE succeeds) | Handled by single assign (no race between drivers) |
| **Control** | Rider/driver choice | System can optimize (closest, rating, surge zone) |
| **Complexity** | Simpler (list + accept) | Need timeout and reassign if driver declines |
| **Real systems** | Some ride-hailing apps show nearby drivers and let first-to-accept win | **Uber (typical):** System picks one best driver (closest, rating, ETA); sends request with ~15 sec timeout. If driver declines or no response → system picks next driver and retries. Rider sees "Finding a driver…" |

### Geospatial: How We Find "Nearby Drivers"

**Option 1: Redis GEO**

- Redis has `GEOADD`, `GEORADIUS`, `GEODIST`.
- Add driver: `GEOADD drivers:location lng lat driver_id` (note: Redis uses lng,lat order).
- Find nearby: `GEORADIUS drivers:location lng lat 3 km WITHDIST COUNT 20` → returns up to 20 driver_ids within 3 km.
- Driver goes offline: remove from the GEO set or maintain a separate set `drivers:online` and intersect; or store status in hash and filter after GEORADIUS.
- **Pros**: Built-in, fast, no custom index. **Cons**: Redis must hold all driver locations; sharding by region if one Redis is not enough.

**Option 2: Geohash**

- Encode (lat, lng) into a string (e.g. "9q8yy"). Longer prefix = smaller area.
- Drivers stored by geohash prefix (e.g. key `geo:9q8yy` → set of driver_ids). Query: compute rider's geohash, look up same cell + neighboring cells (8 neighbors), get union of driver sets.
- **Pros**: Can shard by geohash prefix; works with any key-value store. **Cons**: Boundary issues (two points just across a cell edge); need to query 9 cells.

**Option 3: Grid / Quadtree**

- Divide map into grid cells (e.g. 1 km × 1 km). Each driver in one cell. Query: rider's cell + adjacent cells. Similar to geohash; implementation detail (tree vs grid).

```
Conceptual: Finding drivers near rider R

     [  cell A  ] [  cell B  ] [  cell C  ]
     [  D1, D2  ] [    R      ] [   D3     ]
     [  cell D  ] [  cell E   ] [  cell F  ]

Rider R is in cell B. Query cells A,B,C,D,E,F (and rest of 3×3). Return D1, D2, D3 (and any in other cells within radius).
```

**Our choice: Redis GEO.** Why?

| Criterion | Redis GEO | Geohash (custom) | Grid / Quadtree |
|-----------|-----------|-------------------|-----------------|
| Effort | Built-in (GEOADD, GEORADIUS) | Build index yourself | Build index yourself |
| Speed | In-memory; single-digit ms | Fast if in cache; still custom | Fast; more code |
| Sharding | Shard by region (one Redis per city/region) | Same | Same |
| Boundary issues | Handled by Redis internally | Must query 9 cells | Must query neighbors |

Redis GEO gives us "find nearby" **out of the box**: `GEOADD` to store, `GEORADIUS` to query. No custom indexing code. We already use Redis for driver locations (high write, cache), so adding GEO is natural. For scale: one Redis cluster per region (e.g. per city), so the dataset is bounded and "nearby" is always local.

> **In the interview**: "We use Redis GEO because it gives us geospatial queries (GEORADIUS) out of the box, it's in-memory so single-digit ms, and we already store driver locations in Redis. We shard by region so each Redis holds drivers for one city. If we needed a custom index (e.g. for more complex queries like 'drivers along a route'), we'd look at geohash or quadtree, but for 'nearest within X km' Redis GEO is the simplest and fastest choice."

### Avoiding Double-Assign (Two Drivers Accept Same Ride)

Two different problems, two different mechanisms:

| Problem | Mechanism |
|---------|-----------|
| Two **different** drivers accept same ride | Atomic conditional UPDATE (`WHERE status = 'SEARCHING'`) — only one wins |
| **Same** driver retries same request (network timeout, double-tap) | Idempotency key — return cached result, don't apply twice |

- **Atomic conditional UPDATE (prevents double-assign)**: Trip has status `SEARCHING`. Accept is an **UPDATE** that sets `driver_id` and `status=MATCHED` only if `status` is still `SEARCHING`. Only one driver's request can win (database row-level lock). The second driver's UPDATE finds 0 rows → rejected with 409. Idempotency key does NOT help here because each driver has a different key.
- **Idempotency key (prevents duplicate processing)**: Driver's request carries `Idempotency-Key`. If the **same** driver retries (e.g. timeout, double-tap), server recognizes the key, returns the cached result, and does not execute the UPDATE again.
- **Example SQL** (application uses this logic, possibly via ORM):

```sql
UPDATE trips
SET driver_id = $1, status = 'MATCHED', matched_at = NOW()
WHERE ride_id = $2 AND status = 'SEARCHING';
-- If 0 rows affected → another driver already accepted (return 409)
-- If 1 row affected → success (return 200)
```

---

## 7. Location Service in Depth

### Write Path (Driver Sends Location)

```
Driver App (every 4–10 sec)
   │
   ▼
POST /v1/drivers/me/location { lat, lng }
   │
   ▼
Location Service
   ├── Validate (driver is ONLINE or BUSY)
   ├── Redis: GEOADD drivers:location lng lat driver_id
   │   (or HSET driver:{id} lat lng updated_at; then maintain a GEO index separately)
   ├── Optional: Set TTL for "last seen" (e.g. 5 min) so offline drivers expire
   └── Optional: Async publish to Kafka for analytics (persist to time-series DB)
   │
   Response: 204 No Content
```

- **Volume**: 100K drivers × 0.2 updates/sec = 20K writes/sec. Redis can handle this; single Redis or Redis Cluster with sharding by region (e.g. region_id in key).

### Read Path (Rider Sees Driver Location)

| Approach | How | Pros | Cons |
|----------|-----|------|------|
| **Polling** | Rider app calls GET /rides/{id} every 3–5 sec; backend reads driver location from Redis and returns in response | Simple; stateless | More requests; slight delay |
| **WebSocket** | Rider opens WS to server; when driver location updates, Location Service (or a subscriber) pushes to rider's WS connection | Real-time; fewer HTTP requests | Connection management; scale WS servers |
| **Server-Sent Events** | One-way push from server to rider | Simpler than full WebSocket | Still need to fan-out location updates to the right rider |

- **Recommendation for interview**: Start with **polling** (GET /rides/{id}); mention WebSocket for lower latency and reduced load if needed.

### Throttling and Batching

- **Client throttle**: Driver app sends at most 1 update per 4–5 sec. Reduces load.
- **Server-side**: Reject or drop updates faster than 1/sec per driver (rate limit).
- **Optional**: Only store if position changed > 50 m (reduce writes for stationary drivers).

---

## 8. Trip Lifecycle (State Machine)

```
                    ┌─────────────┐
                    │  REQUESTED  │ (rider tapped "Request")
                    └──────┬──────┘
                           │
                           ▼
                    ┌─────────────┐
                    │  SEARCHING  │ (matching in progress)
                    └──────┬──────┘
                           │
         ┌─────────────────┼─────────────────┐
         │                 │                 │
    (driver accepts)  (timeout/cancel)  (rider cancels)
         │                 │                 │
         ▼                 ▼                 ▼
  ┌─────────────┐   ┌─────────────┐   ┌─────────────┐
  │   MATCHED   │   │  CANCELLED  │   │  CANCELLED  │
  └──────┬──────┘   └─────────────┘   └─────────────┘
         │
         │ (driver at pickup; rider in car)
         ▼
  ┌─────────────┐
  │ IN_PROGRESS │
  └──────┬──────┘
         │
         │ (driver ends trip)
         ▼
  ┌─────────────┐
  │  COMPLETED  │
  └─────────────┘
```

### Valid Transitions (Enforced by Trip Service)

| From | To | Trigger |
|------|-----|--------|
| REQUESTED | SEARCHING | Trip created; matching started |
| SEARCHING | MATCHED | Driver accept (atomic UPDATE) |
| SEARCHING | CANCELLED | Timeout or rider/driver cancel |
| MATCHED | IN_PROGRESS | Driver taps "Start trip" |
| MATCHED | CANCELLED | Rider/driver cancel before start |
| IN_PROGRESS | COMPLETED | Driver taps "Complete trip" |

- Every transition is a **write** to the trip row with a **guard** (e.g. UPDATE ... WHERE status = 'SEARCHING'). Invalid transitions (e.g. COMPLETED → MATCHED) affect 0 rows and return 409 or 400.

---

## 9. Data Model

### Users and Drivers

```
┌────────────────────────────────────────────────────┐
│ users                                              │
├──────────────────┬─────────────────────────────────┤
│ user_id          │ PK, UUID                         │
│ email            │                                  │
│ name             │                                  │
│ phone            │                                  │
│ created_at       │                                  │
└──────────────────┴─────────────────────────────────┘

┌────────────────────────────────────────────────────┐
│ drivers                                            │
├──────────────────┬─────────────────────────────────┤
│ driver_id        │ PK, UUID                         │
│ user_id          │ FK → users                       │
│ status           │ ONLINE | OFFLINE | BUSY          │
│ current_lat      │ (optional; else only in Redis)   │
│ current_lng      │                                  │
│ updated_at       │ last location update             │
└──────────────────┴─────────────────────────────────┘
```

- **Note**: Current location can live **only in Redis** (driver_id → lat, lng, ts). DB columns `current_lat/lng` then optional (for recovery or last-known).

### Trips

```
┌────────────────────────────────────────────────────────────────────┐
│ trips                                                              │
├──────────────────┬────────────────────────────────────────────────┤
│ ride_id          │ PK, UUID                                         │
│ rider_id         │ FK → users                                       │
│ driver_id        │ FK → drivers (NULL until MATCHED)                 │
│ status           │ REQUESTED | SEARCHING | MATCHED | IN_PROGRESS   │
│                  │ | COMPLETED | CANCELLED                          │
│ pickup_lat       │                                                  │
│ pickup_lng       │                                                  │
│ dropoff_lat      │                                                  │
│ dropoff_lng      │                                                  │
│ created_at       │                                                  │
│ matched_at       │ (when driver accepted)                           │
│ started_at       │ (when driver started trip)                       │
│ completed_at     │ (when driver completed)                          │
└──────────────────┴────────────────────────────────────────────────┘
```

### Location (Redis, Not DB — or DB for History)

- **Redis**: Key `drivers:location` — Redis GEO sorted set (lng, lat, member=driver_id). Or key per driver: `driver:{id}` → hash (lat, lng, updated_at).
- **Optional DB** (analytics): `driver_locations (driver_id, lat, lng, timestamp)` — append-only; written async (batch from Kafka). Not used for real-time "find nearby."

---

## 10. ETA and Notifications

### ETA Flow

```
Rider or driver requests ETA (or we push it with GET /rides/{id})
   │
   ▼
ETA / Routing Service
   ├── Input: (origin_lat, origin_lng), (dest_lat, dest_lng)
   ├── Call internal routing or Google Maps Directions API
   ├── Return duration_seconds (and optionally polyline)
   └── Cache by (origin_cell, dest_cell) to reduce API cost
   │
Response: eta_to_pickup_seconds, eta_to_dropoff_seconds
```

- **When to compute**: On every GET /rides/{id} (rider polling), or every 30 sec for active trip; cache for a short window (e.g. 1 min) for same (origin, dest).

### Notification Flow

```
Event (e.g. TripMatched) published to Kafka topic "trip-events"
   │
   ▼
Notification Service (consumer)
   ├── Read event: ride_id, driver_id, rider_id, type=MATCHED
   ├── Resolve device tokens (rider_id → FCM token; driver_id → FCM token)
   ├── Send push via FCM/APNs: "Driver John is on the way"
   └── If in-app WebSocket connected, push to rider/driver socket
```

- **Fan-out**: One event can trigger multiple notifications (rider push, driver push, in-app). Queue decouples Trip Service from notification delivery.

---

## 11. Failure Scenarios and Handling

| Scenario | What Breaks | Handling | Data Impact |
|----------|-------------|----------|-------------|
| **Driver goes offline while SEARCHING** | Matching sends request to a driver who disconnected | Push model: 15 sec timeout per driver; no response → pick next best driver. Filter by status=ONLINE + TTL on Redis (no location update in 60 sec → treat as offline). | Trip stays SEARCHING; no inconsistency |
| **Rider cancels during SEARCHING** | No driver assigned yet | UPDATE status=CANCELLED WHERE status='SEARCHING'. Matching stops looking. | Clean — no driver to notify |
| **Rider cancels after MATCHED** | Driver already assigned; needs notification | UPDATE status=CANCELLED WHERE status='MATCHED'. Publish TRIP_CANCELLED → Notification Service pushes to driver. Driver status reverts to ONLINE. Optional cancellation fee. | Driver freed; must update driver status |
| **Two drivers accept (race)** | Concurrent UPDATE on same trip row | Atomic conditional UPDATE (WHERE status='SEARCHING'): only one gets rows_affected=1; other gets 0 → 409. Different problem from idempotency (same driver retry). | Exactly one driver wins; DB row lock guarantees |
| **Location Service / Redis down** | Can't find nearby drivers; can't update location | Matching can't execute GEORADIUS → trip stays SEARCHING. Degrade: widen radius, retry, or show "Try again." Driver location stale until recovery. Redis Cluster with replicas reduces this risk. | Rider sees stale location; matching delayed |
| **Trip Service DB down** | Accept/start/complete blocked | Return 503; client retries with same idempotency key. Events queue up in Kafka (not lost). After recovery, process normally. | State transitions delayed but not lost |
| **Kafka down** | Events not published | Trip Service's DB write still succeeds (trip state is source of truth). Notifications and payment are delayed. Use **transactional outbox** (see Consistency section) to guarantee events are published after Kafka recovers. | Trip state consistent; side effects delayed |
| **Notification Service down** | Push notifications not delivered | Events queue in Kafka. When service recovers, it consumes backlog. Rider can still poll GET /rides/{id} to see status (doesn't depend on push). | UX degraded (no push), not broken |
| **Payment Service down** | Can't charge rider after trip completes | TRIP_COMPLETED event sits in Kafka. Payment Service processes when it recovers. Trip state is COMPLETED regardless. Eventual consistency. | Charge delayed; not lost |

---

## 12. Scale and Sharding

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                          │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐           │
│  │ Location (Redis) │   │ Trip DB (MySQL)  │   │ Matching Service│           │
│  │                  │   │                  │   │                  │           │
│  │ Shard by REGION  │   │ Shard by ride_id │   │ STATELESS       │           │
│  │ (one cluster per │   │ (hash) or        │   │ (just add more  │           │
│  │  city/region)    │   │  rider_id        │   │  instances)     │           │
│  │                  │   │                  │   │                  │           │
│  │ 100K drivers ×   │   │ 170 writes/sec   │   │ Each instance   │           │
│  │ 50 bytes = 5 MB  │   │ = single DB can  │   │ reads Redis +   │           │
│  │ per region       │   │ handle; shard    │   │ Trip DB          │           │
│  │                  │   │ when multi-region │   │                  │           │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘           │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐                                  │
│  │ Kafka            │   │ Notification     │                                  │
│  │                  │   │ Service          │                                  │
│  │ Partition by     │   │ Scale consumers  │                                  │
│  │ ride_id (ordered │   │ independently    │                                  │
│  │ events per trip) │   │ (Kafka consumer  │                                  │
│  │                  │   │  groups)         │                                  │
│  └─────────────────┘   └─────────────────┘                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Component | Shard/Scale Strategy | Why |
|-----------|---------------------|-----|
| **Redis (location)** | Shard by **region** (one cluster per city). Drivers in SF don't need to be in NYC's index. | "Find nearby" is always local to one region. Bounded dataset per shard (~5 MB). |
| **Trip DB** | Shard by **ride_id** (hash). Or by **rider_id** if we want one rider's trips on same shard. | Lookup is by primary key (ride_id); hash distributes evenly. 170 writes/sec → single DB is fine initially; shard when multi-region. |
| **Matching Service** | Stateless — horizontal scale (add instances behind LB). | No in-memory state. Each instance reads Redis + Trip DB. |
| **Kafka** | Partition by **ride_id**. All events for one trip go to the same partition → ordered. | Consumers process one trip's events in order (CREATED before MATCHED before COMPLETED). |
| **Notification Service** | Scale via Kafka consumer group (add consumers). | Each consumer handles a subset of partitions. Independent of Trip Service's scale. |
| **Trip Service** | Horizontal scale (stateless; DB is the state). | Each instance reads/writes the same DB (or shard). Idempotency ensures safety on retries. |

---

## 13. Consistency and Reliability

### Consistency Model

| Data | Consistency | Why | What If Wrong? |
|------|-------------|-----|----------------|
| Trip state (status, driver_id) | **Strong** (single DB row) | One driver per trip; no double-assign. Atomic UPDATE with guard. | Double-assign → two drivers going to same rider. Unacceptable. |
| Driver location | **Eventual** (seconds) | Acceptable for "where is my driver." High write volume (20K/sec) → optimize for availability. | Rider sees location a few seconds stale. Fine. |
| Notifications | **Eventual** (Kafka consumer lag) | Side effect; Trip Service shouldn't block on notification delivery. | Rider gets push a few seconds late; can still poll GET /rides/{id}. |
| Payment | **Eventual** (Kafka consumer lag) | Separate bounded context. Trip is COMPLETED regardless. | Charge delayed by seconds/minutes. Not lost — Kafka retries. |

### The Outbox Pattern: Reliable Event Publishing

**Problem:** Trip Service updates the DB (status=MATCHED) and publishes to Kafka. What if the DB write succeeds but Kafka publish fails? Trip is MATCHED in DB but no TRIP_MATCHED event → Notification never fires, rider never gets notified.

**Solution:** Transactional outbox — write the event to an outbox table in the **same DB transaction** as the trip update. A separate process reads the outbox and publishes to Kafka.

```
Trip Service receives accept request
   │
   ▼
BEGIN TRANSACTION;
  UPDATE trips SET status='MATCHED', driver_id=? WHERE ride_id=? AND status='SEARCHING';
  INSERT INTO outbox (event_type, payload, created_at)
    VALUES ('TRIP_MATCHED', '{"ride_id":"r_abc","driver_id":"d_xyz",...}', NOW());
COMMIT;
   │
   ▼ (both rows in same DB → atomic; both succeed or both fail)

Outbox Publisher (separate process / CDC via Debezium)
   │
   ├── Polls outbox table (or tails DB binlog via CDC)
   ├── Publishes event to Kafka topic "trip-events"
   └── Marks outbox row as published (or deletes it)
```

| | Without Outbox | With Outbox |
|---|----------------|-------------|
| **DB write + Kafka publish** | Two separate operations; one can fail | Single DB transaction (atomic) |
| **Failure mode** | DB succeeds, Kafka fails → lost event | Impossible — outbox row guarantees eventual publish |
| **Complexity** | Simpler code | Need outbox table + publisher process |
| **Latency** | Direct publish (~ms) | Publisher adds small delay (ms–seconds) |

> **In the interview**: "We use the transactional outbox pattern so that trip state updates and event publishing are in the same ACID transaction. A separate publisher (or CDC tool like Debezium) tails the outbox and pushes to Kafka. This guarantees that if the trip state changed, the event will eventually be published — even if Kafka was temporarily down."

### What If Kafka Is Down?

Events accumulate in the outbox table. The publisher retries. When Kafka recovers, backlog is drained. Downstream consumers (Notification, Payment) process events with a delay but don't lose any. Trip state in the DB is always the source of truth.

### What If a Consumer Fails?

Kafka retains events (configurable retention, e.g. 7 days). Consumer restarts and replays from its last committed offset. Consumers must be **idempotent** — processing the same event twice must not cause side effects (e.g. don't send the same push notification twice; dedup by ride_id + event_type).

---

## 14. Final Architecture (Putting It All Together)

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  Rider App   │ ① POST /rides/request                   │  Driver App  │                │
│  │              │ ⑪ poll GET /rides/{id}                   │              │                │
│  └──────┬───────┘                                          └──────┬───────┘                │
│         │                                                        │ ② POST /drivers/me/     │
│         │                                                        │    location (every 5s)   │
│         │                                                        │ ⑧ POST /rides/{id}/      │
│         │                                                        │    accept                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│                     Auth, rate limit, TLS, route by path                                   │
└─────────┬──────────────────────┬───────────────────────────┬──────────────────────────────┘
          │                      │                           │
   ┌──────▼───────┐    ┌────────▼────────┐        ┌─────────▼──────────┐
   │ Trip Service  │    │ Location Service│        │ Matching Service   │
   │               │    │                 │        │ (stateless)        │
   │ ③ INSERT trip │    │ ② GEOADD to    │        │                    │
   │ ⑨ UPDATE trip │    │   Redis         │        │ ⑥ GEORADIUS Redis  │
   │   (atomic)    │    │                 │        │ ⑦ pick best driver │
   │               │    │                 │        │                    │
   │ ④ sync call ─────────────────────────────────→│                    │
   │    Matching   │    │                 │   ⑥────│──→ Location Svc    │
   │               │    │                 │        │   (GEORADIUS)      │
   └───┬──────┬────┘    └────────┬────────┘        └────────────────────┘
       │      │                  │
       │      │ ⑩              │ ②
       │      │ publish          │ write
       ▼      ▼                  ▼
  ┌──────────────┐     ┌────────────────────┐
  │  Trip DB     │     │  Redis Cluster     │
  │  (MySQL/PG)  │     │  (per region)      │
  │              │     │                    │
  │  trips       │     │  GEO sorted set    │
  │  users       │     │  driver hashes     │
  │  outbox      │     │  idempotency keys  │
  └──────┬───────┘     └────────────────────┘
         │
         │ outbox publisher
         │ (CDC / polling)
         ▼
  ┌──────────────────────────────────────────────────────┐
  │                      KAFKA                            │
  │                                                       │
  │  topic: trip-events          topic: location-events  │
  │  (MATCHED, STARTED,          (batch, analytics only) │
  │   COMPLETED, CANCELLED)                              │
  └────┬──────────┬──────────┬───────────────────────────┘
       │          │          │
       ▼          ▼          ▼
┌────────────┐ ┌──────────┐ ┌────────────┐
│Notification│ │ Payment  │ │ Analytics  │
│ Service    │ │ Service  │ │ Service    │
│            │ │          │ │            │
│ FCM/APNs   │ │ charge   │ │ dashboards │
│ WebSocket  │ │ rider,   │ │ heatmaps   │
│ push       │ │ pay      │ │ metrics    │
│            │ │ driver   │ │            │
└────────────┘ └──────────┘ └────────────┘
```

### Step-by-Step Flow (Numbered)

| Step | Action | Service | Protocol | Data Store |
|------|--------|---------|----------|------------|
| ① | Rider taps "Request Ride" | Rider App → API GW → Trip Service | HTTP POST | |
| ② | Driver sends location (every 5 sec) | Driver App → API GW → Location Service | HTTP POST | Redis (GEOADD) |
| ③ | Create trip (status=SEARCHING) | Trip Service | DB write | Trip DB (INSERT) |
| ④ | Trip Service calls Matching Service | Trip Service → Matching Service | **Sync HTTP** (critical path) | |
| ⑤ | — | — | — | — |
| ⑥ | Find nearby available drivers | Matching Service → Location Service | Sync HTTP | Redis (GEORADIUS) |
| ⑦ | Pick best driver; Matching calls Trip Service to assign | Matching Service → Trip Service | Sync HTTP | |
| ⑧ | Driver notified and taps "Accept" (or auto-assigned via ⑦) | Driver App → API GW → Trip Service | HTTP POST | |
| ⑨ | Atomic UPDATE trip (SEARCHING→MATCHED) | Trip Service | DB write | Trip DB (UPDATE) |
| ⑩ | Publish TRIP_MATCHED event | Trip Service → outbox → Kafka | Async (outbox) | Kafka |
| ⑪ | Rider polls for status + driver location | Rider App → API GW → Trip Service | HTTP GET | Trip DB + Redis |
| ⑫ | Notification: "Driver on the way" | Kafka → Notification Service → FCM/APNs | Async | |
| ... | Driver starts trip (MATCHED→IN_PROGRESS) | Driver App → Trip Service → outbox → Kafka | HTTP + async | Trip DB + Kafka |
| ... | Driver completes trip (IN_PROGRESS→COMPLETED) | Driver App → Trip Service → outbox → Kafka | HTTP + async | Trip DB + Kafka |
| ... | Payment: charge rider | Kafka → Payment Service | Async | Payment DB |

---

## 15. Trade-off Summary

Every design decision has alternatives. Here's a consolidated view of all major choices:

| Decision | Our Choice | Alternative | Why Our Choice |
|----------|-----------|-------------|----------------|
| **Driver location store** | Redis (in-memory, GEO) | PostgreSQL + PostGIS | 20K writes/sec; Redis handles this trivially. DB would be a bottleneck at this write volume. |
| **Geospatial index** | Redis GEO (GEORADIUS) | Custom geohash / quadtree | Built-in, no custom code. Handles boundary issues internally. |
| **Trip state store** | MySQL / PostgreSQL | Redis / DynamoDB | Need strong consistency (atomic UPDATE for accept). RDBMS gives us ACID, row-level locks. |
| **Matching model** | Push (system assigns) | Pull (driver list, first-accept) | Better control (closest, rating, surge zone). Uber uses push. |
| **Rider sees location** | Polling (GET every 3–5s) | WebSocket / SSE | Simpler; stateless. Mention WS as enhancement for lower latency. |
| **Inter-service communication** | Kafka (async events) | Direct HTTP calls | Decouples services; Trip Service doesn't block on notifications or payment. Replay, audit trail. |
| **Event publishing** | Transactional outbox + CDC | Direct publish after DB commit | Guarantees no lost events. If Kafka is down, events accumulate in outbox. |
| **Idempotency store** | Separate table or Redis | Column in trips table | Request-level dedup; trips table shouldn't mix concerns. TTL-based cleanup. |
| **Sharding (location)** | By region (city) | By driver_id hash | "Nearby" queries are always within one region. Regional sharding = local queries. |
| **Sharding (trips)** | By ride_id (hash) | By rider_id / region | Even distribution. Primary lookup is by ride_id. |
| **ETA computation** | External (Google Maps) | Internal routing engine | Start with external; build internal when cost/latency justifies. |

---

## 16. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Better Approach |
|---------|---------------|-----------------|
| Storing driver locations in SQL DB | 20K writes/sec will overwhelm a relational DB; geo-queries are slow without specialized index | Use Redis GEO (in-memory, built-in GEORADIUS) |
| No idempotency on accept | Network timeout → driver retries → could double-process the accept | Idempotency key in request header; dedup before UPDATE |
| Synchronous notification from Trip Service | Trip state transition blocked by FCM/APNs latency (seconds) | Async via Kafka; Trip Service publishes event and returns |
| No atomic guard on trip state transitions | Two drivers accept same ride → double-assign | Conditional UPDATE with WHERE status='SEARCHING'; only one wins |
| Storing everything in one DB | Location writes (20K/sec) and trip writes (170/sec) have very different characteristics | Separate stores: Redis for location, RDBMS for trip state |
| Direct Kafka publish (no outbox) | DB write succeeds but Kafka publish fails → lost event → no notification, no payment | Transactional outbox: event in same DB transaction as state change |
| Polling PSP/external for ETA on every request | Expensive, rate-limited, slow | Cache ETA by (origin_cell, dest_cell); refresh periodically |
| No TTL on driver location | Driver app crashes → driver appears "online" forever → matching sends requests to ghost drivers | TTL in Redis (60 sec); no update → auto-removed from GEO set |
| Tight coupling between services | One service down → cascading failure | Kafka decouples producers from consumers; each scales independently |
| Ignoring cancellation edge cases | Rider cancels after driver is en route → driver not notified, wasted time | State machine enforces valid transitions; TRIP_CANCELLED event notifies driver |

---

## 17. Interview Talking Points

### "Walk me through the architecture"

> We have five core services: Trip Service (state machine for ride lifecycle), Location Service (ingests driver GPS into Redis GEO), Matching Service (finds nearby drivers via GEORADIUS and assigns), Notification Service (push notifications via Kafka events), and Payment Service (charges on trip completion). Sync HTTP calls where the caller needs an immediate answer (matching → location, rider → trip status). Async Kafka events for side effects (notifications, payment, analytics). Trip DB (MySQL) for strong consistency on trip state; Redis for high-throughput location writes and geo-queries.

### "How does matching work?"

> Rider's pickup coordinates go to Matching Service. It calls Location Service which runs GEORADIUS on Redis — "all drivers within 3 km." Filter by status=ONLINE. Pick the best driver (closest, highest rating). Call Trip Service to atomically assign: UPDATE trips SET driver_id=?, status='MATCHED' WHERE status='SEARCHING'. Only one driver can win (row-level lock). Publish TRIP_MATCHED to Kafka → Notification Service pushes to rider.

### "How do you handle consistency across services?"

> Trip state is strongly consistent (single DB row with atomic UPDATE). Everything else — notifications, payment, analytics — is eventually consistent via Kafka. We use the transactional outbox pattern: the trip state change and the event are written in the same DB transaction. A CDC process (Debezium) or poller publishes from outbox to Kafka. If Kafka is down, events accumulate in outbox; nothing is lost. Consumers are idempotent.

### "What if two drivers accept the same ride?"

> Atomic conditional UPDATE: `WHERE status='SEARCHING'` — only one driver's UPDATE finds that condition true. The second gets 0 rows affected → return 409. This is row-level locking, not application logic. Separately, idempotency keys prevent the same driver's retry from applying twice.

### "How do you scale location updates?"

> 100K drivers × 1 update every 5 sec = 20K writes/sec. Redis handles this easily (single instance does 100K+/sec). We use Redis GEO (GEOADD) — in-memory, no disk I/O. Shard by region (one Redis cluster per city). TTL for offline detection. Optionally batch-publish to Kafka for analytics (heatmaps, historical tracking), but real-time matching reads Redis directly.

### "Why Kafka and not direct HTTP calls between services?"

> If Trip Service called Notification Service directly (HTTP), a slow push notification (2 sec FCM latency) would block the trip state transition. The rider would wait longer to see "Driver on the way." With Kafka: Trip Service publishes the event in ~ms and returns. Notification processes asynchronously. If Notification is down, events queue in Kafka — no data loss, no blocking. Same for Payment: trip completion shouldn't fail because the payment system is slow.

### "Why Redis for location and not a database?"

> Two reasons: write volume (20K/sec would overwhelm MySQL) and geo-query (GEORADIUS is built-in and fast). A DB with PostGIS could work at lower scale, but Redis gives us sub-millisecond writes and queries. Trip state goes to MySQL because it needs strong consistency (ACID, row-level locks for atomic accept) and the volume is low (170 writes/sec).

### "How does the rider see live driver location?"

> Polling: rider app calls GET /rides/{id} every 3–5 sec. Backend reads driver's current location from Redis (by driver_id from the trip row) and returns lat, lng, ETA. For lower latency, we could use WebSocket — server pushes location updates when the driver moves — but polling is simpler to build, debug, and scale. Start with polling; upgrade to WS if UX requires it.

### "What happens when the trip completes?"

> Driver taps "Complete." Trip Service: UPDATE status=COMPLETED, write to outbox. Outbox publisher pushes TRIP_COMPLETED to Kafka. Three consumers act: Notification (push receipt to rider/driver), Payment Service (charge rider based on actual distance/time, credit driver), Analytics (trip metrics). All async, all idempotent. If any consumer fails, Kafka retries from last committed offset.
