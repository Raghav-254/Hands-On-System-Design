# Google Maps System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview Volume 2 - Chapter 3

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Navigation Service** | Orchestrates route planning | Stateless | Calls Geocoding, Route Planner, ETA |
| **Map Tile Service** | Constructs tile URLs for client | Stateless | Client downloads from CDN directly |
| **Location Service** | Receives user GPS updates | Stateless | Batch writes to DB + publishes to Kafka |
| **Geocoding DB** | Address ↔ coordinate mapping | Redis / Elasticsearch | Read-heavy, rarely updated |
| **Routing Tiles** | Road network graph data | Object Storage (S3) | Partitioned geographic road graphs |
| **Traffic DB** | Real-time traffic conditions | Time-series DB | Updated by Kafka consumers |
| **User Location DB** | User GPS history | Write-heavy DB | Append-only, high write throughput |
| **CDN** | Map tile delivery | Edge cache | Serves pre-computed images globally |
| **Kafka** | Location event streaming | Disk (retention) | Feeds traffic, analytics, ML services |

---

## The Story: Building Google Maps

Let me walk you through how we'd build a navigation and map rendering system step by step.

---

## 1. What Are We Building? (Requirements)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. User location update (track user position during navigation)            ║
║  2. Navigation service (route from A to B with directions)                  ║
║  3. ETA service (estimated time of arrival with live traffic)               ║
║  4. Map rendering (display maps on mobile devices)                          ║
║  5. Support different travel modes (driving, walking, cycling, transit)      ║
║                                                                               ║
║  OUT OF SCOPE:                                                              ║
║  • Business search / points of interest                                     ║
║  • Photos and reviews                                                       ║
║  • Multi-stop directions (simplification)                                   ║
║  • Street View                                                              ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  • Accuracy: Users must NOT be given wrong directions                       ║
║  • Smooth Rendering: Map scrolling/zooming must be seamless                 ║
║  • Low Data & Battery: Minimize mobile data and power usage                 ║
║  • Low Latency: Route computation < 1 second                               ║
║  • High Availability: 99.99% uptime                                         ║
║  • Scalability: Support 1 billion DAU                                       ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Users:                                                                     ║
║  • 1 billion DAU                                                            ║
║  • ~35M users actively navigating (5% of DAU at any time)                  ║
║                                                                               ║
║  Navigation Requests:                                                       ║
║  • ~5 navigation requests per user per week                                ║
║  • QPS: 1B × 5 / 7 / 86,400 ≈ ~800 QPS (peak: ~5,000 QPS)               ║
║  • Not as high as you'd think! Navigation is infrequent                    ║
║                                                                               ║
║  Location Updates (write-heavy!):                                           ║
║  • 35M navigating users send batches every 15 seconds                      ║
║  • Batch QPS: 35M / 15 ≈ 2.3M batches/sec                                 ║
║  • Each batch = ~15 GPS points (1 per second)                              ║
║                                                                               ║
║  Map Tile Requests:                                                         ║
║  • ~200M users viewing maps daily                                           ║
║  • ~9 tiles per viewport × ~5 viewports/session                            ║
║  • QPS: 200M × 45 / 86,400 ≈ ~100K QPS                                    ║
║  • Mostly served by CDN → origin server sees << 100K QPS                   ║
║                                                                               ║
║  Storage:                                                                   ║
║  • Map tiles: ~100 PB across all zoom levels (Object Storage)              ║
║  • Road data (routing tiles): ~10 TB of raw graph data                     ║
║  • Geocoding DB: ~2 TB                                                      ║
║  • Location history: ~100 TB/day (massive write volume)                    ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. How Do Users Interact? (API Design)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  NAVIGATION APIs                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /v1/nav/directions?origin=...&destination=...&mode=driving             ║
║  ──────────────────────────────────────────────────────────────               ║
║  Use: Get route directions from A to B                                      ║
║  Params:                                                                     ║
║    • origin: address or lat,lng (required)                                  ║
║    • destination: address or lat,lng (required)                             ║
║    • mode: driving | walking | cycling | transit                            ║
║    • avoid: tolls | highways (optional)                                     ║
║  Response: {                                                                 ║
║    "routes": [                                                               ║
║      {                                                                       ║
║        "route_id": "route_1",                                               ║
║        "distance_km": 55.0,                                                 ║
║        "eta_minutes": 42,                                                    ║
║        "steps": [...],                                                       ║
║        "polyline": "encoded_polyline_string",                               ║
║        "has_tolls": false                                                    ║
║      }                                                                       ║
║    ]                                                                         ║
║  }                                                                           ║
║                                                                               ║
║  GET /v1/nav/eta?route_id=...                                                ║
║  ──────────────────────────────────────────────────────────────               ║
║  Use: Get updated ETA for active navigation (with live traffic)             ║
║  Response: { "eta_minutes": 38, "traffic": "moderate" }                     ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  GEOCODING APIs                                                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /v1/geocode?address=1600+Amphitheatre+Parkway                          ║
║  Use: Address → (lat, lng) conversion                                       ║
║  Response: { "lat": 37.4220, "lng": -122.0841, "place_id": "..." }         ║
║                                                                               ║
║  GET /v1/geocode/reverse?lat=37.4220&lng=-122.0841                          ║
║  Use: (lat, lng) → Address conversion                                       ║
║  Response: { "address": "1600 Amphitheatre Parkway, Mountain View, CA" }    ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  MAP TILE APIs                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /v1/tiles?lat=...&lng=...&zoom=14                                      ║
║  Use: Get tile URLs for the current viewport                                ║
║  Response: {                                                                 ║
║    "tiles": [                                                                ║
║      "https://cdn.maps.com/tiles/14/2621/6334.png",                         ║
║      "https://cdn.maps.com/tiles/14/2621/6335.png",                         ║
║      ...                                                                     ║
║    ]                                                                         ║
║  }                                                                           ║
║  Note: Client downloads tiles directly from CDN, NOT through our servers    ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  LOCATION UPDATE APIs                                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  POST /v1/location/batch                                                     ║
║  Use: Send batched GPS location updates (every 15 seconds)                  ║
║  Request: {                                                                  ║
║    "user_id": "alice",                                                       ║
║    "locations": [                                                            ║
║      { "lat": 37.7749, "lng": -122.4194, "ts": 1700000001 },               ║
║      { "lat": 37.7750, "lng": -122.4192, "ts": 1700000002 },               ║
║      ...                                                                     ║
║    ]                                                                         ║
║  }                                                                           ║
║  Note: Client records GPS every 1 second, batches 15 points, sends once    ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  WEBSOCKET (Server → Client, during active navigation)                       ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  wss://maps.example.com/navigation?token=jwt...                              ║
║                                                                               ║
║  Server → Client (when conditions change):                                   ║
║  {                                                                           ║
║    "type": "reroute",                                                        ║
║    "reason": "traffic_change",                                               ║
║    "new_eta_minutes": 38,                                                    ║
║    "new_route": { "polyline": "...", "steps": [...] }                       ║
║  }                                                                           ║
║                                                                               ║
║  Why WebSocket (not SSE or push notifications)?                              ║
║  • Bidirectional: Supports features like last-mile delivery                 ║
║  • Low overhead: Lightweight persistent connection                          ║
║  • Push notifications: Payload too small (4KB iOS limit)                    ║
║  • Long polling: Higher server overhead than WebSocket                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 3. The Big Picture (High-Level Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                     GOOGLE MAPS HIGH-LEVEL DESIGN                            ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║                          📱 Mobile User                                      ║
║                               │                                              ║
║               ┌───────────────┼───────────────┐                             ║
║               │               │               │                             ║
║          Map Tiles     Navigation &      Location                           ║
║          (rendering)   Geocoding         Updates                            ║
║               │               │               │                             ║
║               ▼               ▼               ▼                             ║
║                                                                               ║
║   ┌────────────────────┐                                                     ║
║   │   CDN              │   ┌─────────────┐  ┌──────────────┐                ║
║   │   (edge cache)     │   │    Load     │  │    Load      │                ║
║   │                    │   │  Balancer   │  │  Balancer    │                ║
║   │         ▲          │   └──────┬──────┘  └──────┬───────┘                ║
║   │  preload│          │          │                │                         ║
║   │         │          │   ┌──────┴──────┐         │                         ║
║   │ ┌───────┴────────┐│   │             │         │                         ║
║   │ │Static Map      ││   ▼             ▼         ▼                         ║
║   │ │Images          ││  ┌──────────┐ ┌────────┐ ┌──────────────┐          ║
║   │ │(Object Store/  ││  │Navigation│ │Geocod- │ │  Location    │          ║
║   │ │ S3)            ││  │ Service  │ │ing Svc │ │  Service     │          ║
║   │ └────────────────┘│  └────┬─────┘ └───┬────┘ └──────┬───────┘          ║
║   └────────────────────┘      │            │            │                   ║
║                                │            ▼           ├──────┐            ║
║                                ▼       ┌──────────┐       │      │            ║
║                           ┌──────────┐ │Geocoding │       ▼      ▼            ║
║                           │  Route   │ │   DB     │ ┌─────────┐┌───────┐     ║
║                           │ Planner  │ └──────────┘ │User Loc ││ Kafka │     ║
║                           └────┬─────┘              │   DB    │└───┬───┘     ║
║                           ┌────┼────────┐           └─────────┘    │          ║
║                           │    │        │                          │          ║
║                           ▼    ▼        ▼                ┌────────┼────────┐ ║
║                        ┌────┐┌────┐┌─────┐               │        │        │ ║
║                        │Shor││Rank││ ETA │               ▼        ▼        ▼ ║
║                        │test││ er ││Serv.│         ┌────────┐┌───────┐┌─────┐║
║                        │Path││    ││     │         │Traffic ││Routing││Anal-│║
║                        └──┬─┘└──┬─┘└──┬──┘         │Update ││ Tile  ││ytics│║
║                           │     │     │            │Service ││Process││     │║
║                           ▼     ▼     ▼            └───┬────┘└───┬───┘└─────┘║
║                        ┌─────┐  │  ┌────────┐          │        │            ║
║                        │Rout-│  ▼  │Traffic │          ▼        ▼            ║
║                        │ ing │Filter│  DB    │    ┌────────┐┌────────┐       ║
║                        │Tiles│Svc  │        │    │Traffic ││Routing │       ║
║                        │(S3) │     └────────┘    │  DB    ││ Tiles  │       ║
║                        └─────┘(avoid tolls,      └────────┘│ (S3)   │       ║
║                                highways)                    └────────┘       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

**Three main traffic flows:**
1. **Map Rendering:** Client → CDN (pre-computed tile images, served from edge cache)
2. **Navigation:** Client → Load Balancer → Navigation Service → Route Planner → Response
3. **Location Updates:** Client → Load Balancer → Location Service → DB + Kafka → Downstream

---

## 4. Deep Dive: Map Tile Rendering

### How Map Tiles Work

The world is divided into square tiles at different zoom levels:

```
Zoom Level    Grid Size       Total Tiles        Detail Level
───────────────────────────────────────────────────────────
  0           1 × 1           1                  Whole world
  1           2 × 2           4                  Continents
  5           32 × 32         1,024              Countries
  10          1024 × 1024     ~1 million         Cities
  14          16384 × 16384   ~268 million       Streets
  21          2M × 2M         ~4.4 trillion      Buildings
───────────────────────────────────────────────────────────
Formula: tiles at zoom N = 4^N
```

### Map Rendering Flow

```
FLOW A: Populating CDN (Offline / Background)
──────────────────────────────────────────────

① Raw road data received from various sources (TBs of data)
        │
        ▼
② Map images are PRE-COMPUTED offline (batch processing)
   (Not on-the-fly! Static PNG images generated in advance)
        │
        ▼
③ Stored in Object Storage (S3) — ~100 PB across all zoom levels
        │
        ▼
④ CDN pulls images from S3 and caches at 200+ edge locations worldwide
   (Pre-loaded for popular areas, pulled on-demand for the rest)


FLOW B: Map Rendering (Runtime / User-Facing)
──────────────────────────────────────────────

① User opens map / scrolls / zooms
        │
        ▼
② Client sends viewport info (lat, lng, zoom) to Map Tile Service
        │
        ▼
③ Map Tile Service constructs tile URLs for that viewport
   Returns: ["cdn.maps.com/tiles/14/2621/6334.png", ...]
        │
        ▼
④ Client downloads tiles DIRECTLY from CDN (NOT through our servers)
   ~9 tiles per viewport (3×3 grid)

KEY: Flow A happens rarely (when road data changes).
     Flow B happens millions of times per day (every user interaction).
```

**Key Insights:**
- Our Map Tile Service only tells the client WHICH tile URLs to fetch. It does NOT serve the images itself.
- The CDN does all the heavy lifting of serving the actual images to the client.
- Since tiles are static and rarely change, CDN cache hit rate is 99%+. This means S3 (the origin storage) is almost never hit — the CDN absorbs nearly all traffic.

### Why Pre-Computed Tiles?

| Approach | Pros | Cons |
|----------|------|------|
| **Pre-computed (chosen)** | CDN-friendly, fast, consistent | Storage cost, update delay |
| **On-the-fly rendering** | Always fresh | CPU-intensive, high latency, can't cache |

**Decision:** Maps change infrequently (new roads are rare). Pre-computation wins because tiles are mostly static and perfectly suited for CDN caching.

---

## 5. Deep Dive: Navigation Service

### The Complete Navigation Flow

When a user requests directions from "San Francisco" to "Cupertino":

```
① User: "Navigate to 1 Apple Park Way, Cupertino"
        │
        ▼
② Navigation Service (orchestrator)
        │
        ├──→ ③ Geocoding Service
        │        "1 Apple Park Way" → (37.3349, -122.0090)
        │        Uses Geocoding DB (address ↔ lat/lng mapping)
        │
        ├──→ ④ Route Planner Service
        │        │
        │        ├──→ ⑤ Shortest Path Service
        │        │        Loads routing tiles from S3
        │        │        Runs Dijkstra's algorithm on road graph
        │        │        Returns candidate paths
        │        │
        │        ├──→ ⑥ ETA Service
        │        │        Queries Traffic DB for live conditions
        │        │        Calculates time for each path segment
        │        │        Adjusts for traffic multipliers
        │        │
        │        └──→ ⑦ Ranker + Filter
        │                 Ranks routes by: ETA, distance, user preferences
        │                 Filters out routes violating constraints (avoid tolls, highways)
        │
        ▼
⑧ Return top-k routes to user
```

### Routing Tiles vs Map Tiles (Important Distinction!)

```
┌─────────────────────────────────┬─────────────────────────────────┐
│         MAP TILES               │        ROUTING TILES            │
├─────────────────────────────────┼─────────────────────────────────┤
│ Purpose: Visual rendering       │ Purpose: Path computation       │
│ Content: PNG/JPEG images        │ Content: Graph data (nodes+edges)│
│ Storage: Object Storage + CDN   │ Storage: Object Storage (S3)    │
│ Served to: Mobile client        │ Served to: Shortest Path Service│
│ Size: ~100KB per tile           │ Size: ~few MB per tile          │
│ Total: ~100 PB                  │ Total: ~10 TB                   │
│ Updates: Rarely (new roads)     │ Updates: More often (traffic)   │
└─────────────────────────────────┴─────────────────────────────────┘
```

### Shortest Path: How It Works at Scale

The road network is a massive graph (billions of nodes + edges). Running Dijkstra's on the entire graph is impractical. Solution: **Routing Tiles**.

1. **Partition the road network** into geographic tiles
2. **Each tile** contains a subgraph: nodes (intersections) + edges (road segments)
3. **Shortest Path Service** loads only tiles near the route
4. **Tiles connected via boundary nodes** (shared nodes at tile edges)
5. **Hierarchical routing**: Different tile detail levels for local vs long-distance
6. Tiles stored in S3, loaded on-demand into memory

**How Tiles Connect (Boundary Nodes):**

Tiles don't exist in isolation. When the road network is partitioned, roads that cross tile boundaries create **boundary nodes** — these nodes appear in BOTH adjacent tiles. This is how Shortest Path Service traverses across tiles:

```
 ┌──────────── Tile A ──────────────┐ ┌──────────── Tile B ──────────────┐
 │                                   │ │                                   │
 │  sf_downtown ──→ sf_mission      │ │      oakland_dt ──→ berkeley     │
 │      │                           │ │         ▲                         │
 │      │                           │ │         │                         │
 │      └──→ oakland_bridge ────────┼─┼── oakland_bridge                 │
 │           (BOUNDARY NODE)        │ │    (SAME NODE in both tiles)      │
 │                                   │ │                                   │
 └───────────────────────────────────┘ └───────────────────────────────────┘

When Dijkstra reaches "oakland_bridge" in Tile A:
  → Load Tile B (which also contains "oakland_bridge")
  → Continue pathfinding into Tile B's graph
```

**How Hierarchical Routing Works:**

The road network is stored at multiple levels of detail:

```
 Level 0 (Detailed):  ALL roads — local streets, residential, alleys
 Level 1 (Medium):    Major roads — arterials, collectors
 Level 2 (Coarse):    Highways only — interstates, freeways

Short route (within a city — SF downtown to SF Mission):
  → Use Level 0 tiles only (all local roads needed)

Long route (cross-city — SF to Los Angeles):
  → Level 0 at START (local roads to reach the highway on-ramp)
  → Level 2 in MIDDLE (highway tiles for the long stretch — I-5, US-101)
  → Level 0 at END (local roads from highway off-ramp to destination)
```

```
Example: San Francisco → Los Angeles (600 km)

  ┌──── Level 0 ────┐  ┌──────── Level 2 (Highway) ────────┐  ┌── Level 0 ──┐
  │                  │  │                                     │  │              │
  │ sf_downtown      │  │                                     │  │   la_offramp │
  │   → sf_soma      │  │  US-101 on-ramp ────────────────── │  │   → la_dt    │
  │   → US-101       │  │  ──→ San Jose ──→ Salinas ──→     │  │   → dest     │
  │     on-ramp ─────┼──┼──                        LA off ──┼──┼──             │
  │ (detailed local  │  │  (only highway nodes/edges,        │  │ (detailed    │
  │  roads needed)   │  │   skips all local roads = FAST)    │  │  local roads)│
  └──────────────────┘  └─────────────────────────────────────┘  └──────────────┘
  
  Without hierarchy: Load 1000s of Level 0 tiles (slow, too much data)
  With hierarchy:    Load ~5 Level 0 tiles + ~10 Level 2 tiles (fast!)
```

The **routing algorithm decides** which level to use based on the distance between origin and destination. If they are far apart, it "zooms out" to coarser tiles for the middle portion — just like how a human would think: *"take local roads to the highway, drive the highway, then local roads to the destination."*

### ETA Service: Traffic-Aware Estimation

```
ETA = Σ (segment_distance / speed_limit) × traffic_multiplier

Traffic Multiplier:
  1.0 = clear (normal speed)
  1.5 = moderate (67% of normal speed)
  2.0 = heavy (50% of normal speed)
  4.0 = standstill (25% of normal speed)
  
Example:
  sf_downtown → mountain_view (Highway 101)
  Distance: 55 km, Speed limit: 100 km/h
  Base ETA: 55/100 × 60 = 33 min
  With traffic (1.9x): 33 × 1.9 = 63 min
```

### Adaptive ETA and Rerouting (During Navigation)

**The Problem:** Traffic changes on a road segment. How do we find which of the millions of actively navigating users are affected?

**Naive Approach (O(n × m)):**
- Store each user's route as a list of routing tiles:
  `user_1: [s_1, s_2, s_3, ..., s_k]`
- When traffic changes on tile `s_2` → scan every user's route to check if `s_2` is in it
- With n users and average route length m → O(n × m). Too slow at scale!

**Optimized Approach (using routing tile hierarchy):**
```
For each navigating user, store the current tile AND its parent tiles:

user_1: s_1, super(s_1), super(super(s_1))

              ┌────────────────────────┐
              │ Routing tile           │ ← Contains only origin
              │   ┌──┐                 │
              │   │🟣│ Origin          │
              │   └──┘                 │
              └─────────┬──────────────┘
                        │
              ┌─────────▼──────────────┐
              │ Level 1 Routing tile   │ ← super(s_1)
              │   ┌──┐                 │
              │   │🟣│                 │
              │   ├──┼──┐              │
              │   │  │  │              │
              │   └──┴──┘              │
              └─────────┬──────────────┘
                        │
              ┌─────────▼──────────────┐
              │ Level 2 Routing tile   │ ← super(super(s_1))
              │   ┌──┬──┬──┐          │
              │   │🟣│  │  │          │
              │   ├──┼──┼──┤          │
              │   │  │  │  │          │
              │   ├──┼──┼──┤          │
              │   │  │🟢│  │ Dest     │
              │   └──┴──┴──┘          │
              └────────────────────────┘

When traffic changes on a routing tile:
  → Check if the affected tile matches the user's LAST (coarsest) routing tile
  → If NO match → user is NOT affected (skip immediately!)
  → If YES → drill down to finer levels to confirm

This filters out most users instantly at the coarsest level,
instead of scanning every tile in every user's route.
```

**Rerouting Flow:**
1. Traffic change detected on routing tile `s_2`
2. Use hierarchy to quickly find affected users
3. Recalculate ETA for affected users' routes
4. If a better alternative route exists → push reroute via WebSocket
5. When traffic clears → recalculate again, notify if old route is faster

---

## 6. Deep Dive: Location Service

### Client-Side Batching (Key Optimization)

The client does NOT send every GPS update individually. Instead:

```
┌────────────────────────────────────────────────────────────────┐
│                          Client                                │
│                                                                │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐        │
│  │   Batch 3    │  │   Batch 2    │  │   Batch 1    │        │
│  │              │  │              │  │              │        │
│  │ loc_45  ...  │  │ loc_30  ...  │  │ loc_15  ...  │        │
│  │ loc_32      │  │ loc_17      │  │ loc_2       │        │
│  │ loc_31      │  │ loc_16      │  │ loc_1       │        │
│  │              │  │              │  │              │        │
│  │◄── 15s ────▶│  │◄── 15s ────▶│  │◄── 15s ────▶│        │
│  └──────────────┘  └──────────────┘  └──────────────┘        │
│                                                                │
│  GPS records: 1 per second                                    │
│  Batch size: 15 GPS points per batch                          │
│  Send frequency: 1 batch every 15 seconds                    │
│  Network reduction: 15x fewer HTTP calls!                     │
└────────────────────────────────────────────────────────────────┘
```

**Why batching?**
- Reduces network calls from 1/sec to 1/15sec
- Saves battery (fewer radio wake-ups)
- Saves data (single HTTP overhead for 15 points)
- No accuracy loss (all points eventually sent)

### Location Data Pipeline (via Kafka)

```
📱 Mobile User
      │
      │ POST /v1/location/batch (every 15s)
      ▼
┌──────────────┐
│   Location   │
│   Service    │
└──────┬───────┘
       │
       ├──────────────────┐
       │ (parallel)       │
       ▼                  ▼
┌──────────────┐   ┌─────────────┐
│ User Loc DB  │   │    Kafka    │
│ (Cassandra)  │   └──────┬──────┘
└──────────────┘          │
                 ┌────────┼────────┬────────────┐
                 │        │        │            │
                 ▼        ▼        ▼            ▼
            ┌────────┐┌────────┐┌────────┐┌──────────┐
            │Traffic ││Routing ││  ML    ││Analytics │
            │Update  ││ Tile   ││Person- ││ Service  │
            │Service ││Process ││alize   ││          │
            └───┬────┘└───┬────┘└───┬────┘└────┬─────┘
                │         │         │          │
                ▼         ▼         ▼          ▼
            ┌────────┐┌────────┐┌────────┐┌──────────┐
            │Traffic ││Routing ││Person- ││Analytics │
            │  DB    ││ Tiles  ││alize   ││   DB     │
            │        ││ (S3)   ││  DB    ││          │
            └────────┘└────────┘└────────┘└──────────┘
```

**Why User Location DB?**
- Acts as raw source of truth for GPS history ("where was user X at time T?")
- Useful for debugging, auditing, and regulatory compliance
- If Kafka consumers need historical replay, raw data is always available
- That said, it's **secondary** — the primary path is Kafka → downstream consumers
- Could be optional: some designs skip it entirely and let Kafka + Analytics DB serve as the record

**Downstream Kafka Consumers:**

| Consumer | Purpose | Output |
|----------|---------|--------|
| **Traffic Update Service** | Aggregates user speeds per road segment → live traffic | Traffic DB |
| **Routing Tile Processing** | Detects new roads, closed roads → updates routing tiles | Routing Tiles (S3) |
| **ML Personalization** | Learns user patterns (frequent routes, preferred times) | Personalization DB |
| **Analytics** | Aggregated stats (popular routes, peak hours) | Analytics DB |

---

## 7. How We Store Data (Database Design)

### Database Selection & Tradeoffs

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATABASE DESIGN                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐   ║
║  │  Geocoding DB                                                         │   ║
║  │  Purpose: Address ↔ (lat, lng) mapping                               │   ║
║  │  Choice: Redis + Elasticsearch                                        │   ║
║  │  Why: Read-heavy, full-text search for fuzzy address matching        │   ║
║  │  Size: ~2 TB                                                         │   ║
║  │  Schema:                                                              │   ║
║  │    address (text) → lat (double), lng (double), place_id (string)    │   ║
║  │  Write pattern: Rarely updated (new addresses are infrequent)        │   ║
║  │  Read pattern: Very high QPS for navigation lookups                  │   ║
║  └───────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐   ║
║  │  Routing Tiles (Object Storage / S3)                                  │   ║
║  │  Purpose: Road network graph (nodes + edges)                         │   ║
║  │  Choice: Object Storage (S3)                                          │   ║
║  │  Why: Large binary blobs, cost-efficient, no query engine needed     │   ║
║  │  Size: ~10 TB total                                                   │   ║
║  │  Format: Protocol Buffers or Avro (compact binary, fast deserialize) │   ║
║  │    NOT JSON (too verbose, slow to parse for graph data)              │   ║
║  │  Structure per tile:                                                  │   ║
║  │    tile_id → { nodes: [{id, lat, lng}], edges: [{from, to, dist, speed}] } ║
║  │  Write pattern: Infrequently updated by Routing Tile Processing      │   ║
║  │  Read pattern: Loaded on every navigation request by Shortest Path   │   ║
║  │    Service (frequently read, cached in memory with LRU eviction)     │   ║
║  └───────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐   ║
║  │  Traffic DB                                                           │   ║
║  │  Purpose: Real-time & historical traffic conditions per road segment │   ║
║  │  Choice: Time-series DB (InfluxDB / TimescaleDB)                     │   ║
║  │  Why: Time-based queries, aggregation, automatic downsampling        │   ║
║  │                                                                       │   ║
║  │  Schema:                                                              │   ║
║  │    road_segment_id, timestamp → speed_kmh, traffic_multiplier        │   ║
║  │                                                                       │   ║
║  │  How it works:                                                        │   ║
║  │  • road_segment_id = a specific stretch of road (e.g., "US101_exit5  │   ║
║  │    _to_exit6"). Each road is divided into segments of ~1-2 km.       │   ║
║  │  • Traffic Update Service aggregates GPS speeds from ALL users        │   ║
║  │    driving on that segment (via Kafka) → computes average speed.     │   ║
║  │  • traffic_multiplier = speed_limit / actual_avg_speed               │   ║
║  │    e.g., speed_limit=100, avg_speed=50 → multiplier = 2.0 (heavy)   │   ║
║  │                                                                       │   ║
║  │  Example rows:                                                        │   ║
║  │    ("US101_exit5_to_exit6", 10:00AM) → 45 km/h, 2.2x (heavy)       │   ║
║  │    ("US101_exit5_to_exit6", 10:05AM) → 60 km/h, 1.7x (moderate)    │   ║
║  │    ("I280_cupertino_to_sv",  10:00AM) → 95 km/h, 1.1x (clear)      │   ║
║  │                                                                       │   ║
║  │  ETA Service queries: "Give me current traffic_multiplier for all    │   ║
║  │  segments on this route" → multiplies base travel time accordingly.  │   ║
║  │                                                                       │   ║
║  │  Write pattern: High (aggregated from user locations every few min)  │   ║
║  │  Read pattern: By ETA Service for current conditions                 │   ║
║  └───────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐   ║
║  │  User Location DB                                                     │   ║
║  │  Purpose: Store GPS history from user batches                        │   ║
║  │  Choice: Cassandra                                                    │   ║
║  │  Why: Extremely write-heavy (2.3M batches/sec), append-only          │   ║
║  │                                                                       │   ║
║  │  Schema (Cassandra):                                                  │   ║
║  │    Partition Key: user_id                                             │   ║
║  │    Clustering Key: timestamp (DESC — most recent first)              │   ║
║  │    Columns: lat, lng, driving_mode, speed, heading                   │   ║
║  │                                                                       │   ║
║  │  Why user_id as partition key?                                        │   ║
║  │  • All location data for one user lives on the same partition        │   ║
║  │  • Enables efficient range queries: "user X's locations from 2-3 PM" │   ║
║  │  • Even distribution across nodes (user_id is unique)                │   ║
║  │  • Avoids hotspots (no single partition gets disproportionate writes) │   ║
║  │                                                                       │   ║
║  │  Why timestamp as clustering key?                                     │   ║
║  │  • Data stored sorted by time within each partition                  │   ║
║  │  • Efficient range scans ("last 10 minutes" = single sequential read)│   ║
║  │  • DESC order: Most recent data accessed first (most useful)         │   ║
║  │                                                                       │   ║
║  │  Example rows:                                                        │   ║
║  │    (alice, 10:00:01) → 37.7749, -122.4194, driving, 45km/h, N       │   ║
║  │    (alice, 10:00:02) → 37.7750, -122.4192, driving, 47km/h, N       │   ║
║  │    (bob,   10:00:01) → 40.7128, -74.0060,  walking, 5km/h,  E      │   ║
║  │                                                                       │   ║
║  │  Write pattern: 2.3M batches/sec (massive!)                          │   ║
║  │  Read pattern: Rarely read directly (downstream via Kafka)           │   ║
║  └───────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
║  ┌───────────────────────────────────────────────────────────────────────┐   ║
║  │  Map Tiles (Object Storage / S3)                                      │   ║
║  │  Purpose: Pre-computed map images (PNG) for rendering                │   ║
║  │  Choice: Object Storage (S3) + CDN                                    │   ║
║  │  Why: Static binary files, perfect for CDN caching                   │   ║
║  │  Size: ~100 PB across all zoom levels                                │   ║
║  │  URL pattern: /tiles/{zoom}/{x}/{y}.png                              │   ║
║  │  Write pattern: Batch updates when road data changes                 │   ║
║  │  Read pattern: Served by CDN (our servers rarely hit)                │   ║
║  └───────────────────────────────────────────────────────────────────────┘   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Database Choice Tradeoffs

| Database | Chosen For | Alternative | Why Not Alternative |
|----------|-----------|-------------|---------------------|
| **Redis + ES** | Geocoding | PostgreSQL + PostGIS | Need fuzzy text search + speed |
| **S3** | Routing Tiles | Database | Graph blobs, not relational data |
| **TimescaleDB** | Traffic | Redis | Need historical queries + aggregation |
| **Cassandra** | User Locations | PostgreSQL | Write volume too high for relational |
| **S3 + CDN** | Map Tiles | On-the-fly rendering | Pre-computed = fast + cacheable |

---

## 8. How We Scale (Scaling Each Component)

### Navigation Service (Stateless)

- Horizontally scale behind Load Balancer
- Peak: ~5,000 QPS → 50 servers (100 QPS each)
- Route computation is CPU-heavy → optimize with caching popular routes
- Cache recently computed routes in Redis (TTL: 5 minutes)

### Map Tile Serving (CDN-Powered)

- **CDN handles 99%+ of requests** (tiles are static)
- 200+ edge locations globally
- Cache hit rate: >99% (tiles rarely change)
- Origin servers only handle cache misses
- Map tile updates: Deploy new tiles to S3 → CDN pulls on next miss

### Location Service (Write-Heavy)

**Challenge:** 2.3M batches/sec is massive

**Solution:**
- Horizontally scale Location Service instances
- Kafka absorbs burst writes (buffering)
- User Location DB: Cassandra with 100+ nodes
  - Partition key: `user_id` (even distribution)
  - Replication factor: 3
  - Each node handles ~23K writes/sec

### Routing Tiles (Object Storage)

- Stored in S3 → virtually unlimited storage
- Shortest Path Service caches frequently-used tiles in memory
- LRU cache: Keep popular area tiles (SF, NYC, LA) always warm
- Cold tiles loaded on-demand (~50ms from S3)

### Traffic DB (Time-Series)

- Shard by geographic region (road_segment_id hash)
- Automatic downsampling: 
  - Last 1 hour: 1-second granularity
  - Last 24 hours: 1-minute granularity
  - Last 7 days: 5-minute granularity
- Reduces storage while keeping recent data precise

### Kafka (Event Streaming)

- Multiple topic partitions (partition by `user_id`)
- Independent consumer groups for each downstream service
- Each consumer scales independently
- Retention: 7 days (replayable for debugging)

---

## 9. What Can Go Wrong? (Failure Handling)

### 1. CDN Failure (Map Tiles Unavailable)

**Impact:** Users can't see the map
**Solution:**
- Multi-CDN strategy (primary + fallback CDN provider)
- Client caches recently viewed tiles locally (offline maps)
- Mobile app stores ~1GB of tiles for frequently visited areas

### 2. Navigation Service Failure

**Impact:** Users can't get directions
**Solution:**
- Multiple instances behind Load Balancer
- Health checks detect failures in 3 seconds
- Circuit breaker prevents cascading failures
- Client retries with exponential backoff
- Fallback: Return cached route if same origin/destination requested recently

### 3. Kafka Failure (Location Pipeline Stalled)

**Impact:** Traffic data becomes stale, no new routing updates
**Solution:**
- Kafka replication factor = 3 (survive 2 broker failures)
- Multi-datacenter Kafka replication
- Location Service buffers locally if Kafka is down
- Traffic DB still serves last-known conditions (degraded but functional)

### 4. Routing Tile Storage (S3) Failure

**Impact:** Can't compute new routes
**Solution:**
- S3 has 99.999999999% durability (11 nines)
- Shortest Path Service has in-memory cache of popular tiles
- Cross-region S3 replication for disaster recovery
- Pre-warm cache with tiles for major cities

### 5. Traffic DB Failure

**Impact:** ETAs based on speed limits only (no live traffic)
**Solution:**
- Master-replica setup with auto-failover
- Graceful degradation: ETA Service returns base ETA (without traffic multiplier)
- Users see "Traffic data unavailable" indicator

### 6. Client Network Issues

**Impact:** Can't fetch tiles, can't send location updates
**Solution:**
- Offline map support (pre-downloaded tiles)
- Client buffers location batches and retries when connected
- Pre-cached routes for recently computed journeys

---

## 10. Why These Choices? (Key Design Decisions)

### Decision #1: Pre-Computed Map Tiles vs On-the-Fly Rendering

**Problem:** Serving map images to 1B users

**Why Pre-Computed Wins:**
- Tiles change infrequently → perfect for CDN caching
- Zero server-side computation for rendering
- Consistent quality across all users
- CDN handles 99%+ traffic → minimal origin load

**Trade-off:** Storage cost (~100 PB) is high but cheaper than real-time rendering at scale

### Decision #2: Routing Tiles in Object Storage (S3) vs Database

**Problem:** Storing terabytes of road network graph data

**Why S3 Wins:**
- Graph data is large binary blobs (not relational)
- Read pattern: Load entire tile into memory (no partial queries)
- Cost: ~$0.023/GB/month vs ~$0.10+/GB for database
- No query engine needed (just GET by tile_id)

### Decision #3: Client-Side Batching (15-Second Batches)

**Problem:** 35M users sending GPS every second = 35M writes/sec

**Why Batching Wins:**
- 15x reduction in HTTP calls (1/sec → 1/15sec)
- Saves battery (fewer radio wake-ups on mobile)
- Saves data usage
- No accuracy loss (all points eventually sent)
- Server handles 2.3M batches/sec instead of 35M/sec

### Decision #4: Kafka for Location Data Pipeline

**Problem:** Location data needs to reach 4+ downstream services

**Why Kafka (Not Direct Database Writes):**
- Decouples Location Service from downstream consumers
- Each consumer processes independently and at its own speed
- Replay capability (reprocess data if consumer had a bug)
- Buffer for burst traffic (absorbs spikes)
- Adding new consumers doesn't impact Location Service

**Why Kafka and Not Redis Pub/Sub?**
- Unlike Nearby Friends (where location is ephemeral and fire-and-forget), here every GPS point must be reliably processed for accurate traffic data — Kafka persists messages and lets consumers resume after failures, while Redis Pub/Sub drops messages if a consumer is down.

### Decision #5: Separate Navigation + Location Services (Not Combined)

**Problem:** Should navigation and location tracking be one service?

**Why Separate:**
- Very different traffic patterns: Navigation = low QPS, Location = ultra-high write
- Independent scaling: Scale location processing without affecting navigation
- Failure isolation: Location pipeline failure shouldn't break navigation
- Team independence: Different teams can own each service

---

## 11. Interview Pro Tips

### Opening Statement
"Google Maps has three core components: map rendering via pre-computed tiles served through CDN, a navigation service that uses graph-based routing tiles with live traffic ETA, and a location service that uses client-side batching and Kafka for streaming GPS data to downstream services. The key challenges are handling 1B DAU, massive location write volume, and providing accurate ETAs with real-time traffic data."

### Key Talking Points
1. **Map Tiles:** Pre-computed, static images served via CDN (NOT rendered on-the-fly)
2. **Routing Tiles ≠ Map Tiles:** Graph data for path computation vs images for rendering
3. **Client-Side Batching:** 15-second GPS batches reduce write load by 15x
4. **Kafka Pipeline:** Decouples location data from 4+ downstream consumers
5. **ETA with Traffic:** Base time × traffic multiplier per road segment
6. **Adaptive Rerouting:** Monitor traffic during navigation, suggest alternatives

### Common Follow-ups

**Q: How do you handle traffic changes during navigation?**
A: Adaptive ETA Service periodically checks traffic on user's route. If ETA increases by >5 min, recompute alternative routes and push suggestion to the user's phone.

**Q: Does Google Maps use WebSocket?**
A: Yes! Two communication channels are used: (1) HTTP batch requests for client → server location updates (every 15s), and (2) WebSocket for server → client pushes during active navigation (reroute suggestions, updated ETAs, traffic alerts). WebSocket is chosen over SSE because it supports bidirectional communication needed for features like last-mile delivery.

**Q: How do routing tiles get updated when a new road is built?**
A: Routing Tile Processing Service (Kafka consumer) detects road changes from aggregated location data. Updated tiles are written to S3. Shortest Path Service picks up new tiles on next cache miss.

**Q: What about offline navigation?**
A: Client pre-downloads map tiles and routing tiles for the route area. Navigation runs locally using device GPS. When back online, syncs location batches and fetches fresh traffic data.

**Q: How do you handle accuracy of ETA?**
A: Combine multiple signals: speed limits from routing tiles, real-time traffic from Traffic DB, historical patterns from ML model, and current road conditions. ML model trained on billions of historical trips.

---

## 12. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║               GOOGLE MAPS COMPLETE ARCHITECTURE                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  📱 Mobile User                                                              ║
║      │                                                                        ║
║      ├──① Map View ──────────────────────────────────────────┐               ║
║      │   (scroll, zoom, pan)                                  │               ║
║      │                                                        ▼               ║
║      │                                                  ┌──────────┐         ║
║      │               ④ Download tiles directly ────────▶│   CDN    │         ║
║      │                                                  └────┬─────┘         ║
║      │                                                       │               ║
║      │                                            ① Preload  │               ║
║      │                                                       ▼               ║
║      │                                              ┌──────────────┐         ║
║      │                                              │ Map Images   │         ║
║      │                                              │ (S3)         │         ║
║      │                                              └──────────────┘         ║
║      │                                                                        ║
║      ├──② Navigate ──────────┐                                               ║
║      │   "SF → Cupertino"    │                                               ║
║      │                       ▼                                               ║
║      │              ┌─────────────────┐                                      ║
║      │              │  Load Balancer  │                                      ║
║      │              └────────┬────────┘                                      ║
║      │                       │                                               ║
║      │                       ▼                                               ║
║      │              ┌─────────────────┐      ┌──────────────┐               ║
║      │              │  Navigation     │─────▶│ Geocoding    │               ║
║      │              │  Service        │      │ Service      │               ║
║      │              └────────┬────────┘      └──────┬───────┘               ║
║      │                       │                      │                        ║
║      │                       ▼                      ▼                        ║
║      │              ┌─────────────────┐      ┌──────────────┐               ║
║      │              │  Route Planner  │      │ Geocoding DB │               ║
║      │              └────────┬────────┘      └──────────────┘               ║
║      │                       │                                               ║
║      │          ┌────────────┼────────────┐                                  ║
║      │          │            │            │                                  ║
║      │          ▼            ▼            ▼                                  ║
║      │    ┌──────────┐ ┌──────────┐ ┌──────────┐                           ║
║      │    │ Shortest │ │  Ranker  │ │   ETA    │                           ║
║      │    │  Path    │ │          │ │ Service  │                           ║
║      │    └────┬─────┘ └────┬─────┘ └────┬─────┘                           ║
║      │         │            │            │                                  ║
║      │         ▼            ▼            ▼                                  ║
║      │    ┌──────────┐ ┌──────────┐ ┌──────────┐                           ║
║      │    │ Routing  │ │  Filter  │ │ Traffic  │                           ║
║      │    │ Tiles    │ │ Service  │ │   DB     │                           ║
║      │    │ (S3)     │ │(no tolls)│ │          │                           ║
║      │    └──────────┘ └──────────┘ └──────────┘                           ║
║      │                                                                        ║
║      │                                                                        ║
║      └──③ GPS Updates ──────┐                                                ║
║         (batch every 15s)    │                                               ║
║                              ▼                                               ║
║                     ┌─────────────────┐                                      ║
║                     │  Location       │                                      ║
║                     │  Service        │                                      ║
║                     └────────┬────────┘                                      ║
║                              │                                               ║
║                    ┌─────────┴─────────┐                                    ║
║                    │                   │                                    ║
║                    ▼                   ▼                                    ║
║            ┌──────────────┐    ┌─────────────┐                              ║
║            │ User Loc DB  │    │    Kafka    │                              ║
║            │ (Cassandra)  │    └──────┬──────┘                              ║
║            └──────────────┘           │                                      ║
║                              ┌────────┼────────┬────────┐                   ║
║                              │        │        │        │                   ║
║                              ▼        ▼        ▼        ▼                   ║
║                         ┌────────┐┌────────┐┌──────┐┌────────┐             ║
║                         │Traffic ││Routing ││  ML  ││Analyt- │             ║
║                         │Update  ││ Tile   ││      ││  ics   │             ║
║                         │Service ││Process ││      ││        │             ║
║                         └───┬────┘└───┬────┘└──┬───┘└───┬────┘             ║
║                             │         │        │        │                   ║
║                             ▼         ▼        ▼        ▼                   ║
║                        ┌────────┐┌────────┐┌──────┐┌────────┐              ║
║                        │Traffic ││Routing ││Pers- ││Analyt- │              ║
║                        │  DB    ││ Tiles  ││onal  ││ics DB  │              ║
║                        │        ││ (S3)   ││  DB  ││        │              ║
║                        └────────┘└────────┘└──────┘└────────┘              ║
║                                                                               ║
║─────────────────────────────────────────────────────────────────────────────║
║                                                                               ║
║  KEY FLOWS:                                                                  ║
║  ① Map View: Client → Map Tile Service → CDN URLs → Client downloads       ║
║  ② Navigate: Client → Navigation Service → Geocoding → Route Planner       ║
║              → Shortest Path + ETA + Ranker → Top-k routes returned        ║
║  ③ Location: Client batches GPS → Location Service → DB + Kafka            ║
║              → Traffic / Routing / ML / Analytics (parallel consumers)      ║
║  ④ Adaptive: During navigation, ETA Service monitors traffic → reroute     ║
║                                                                               ║
║  CRITICAL DESIGN DECISIONS:                                                  ║
║  • Pre-computed map tiles + CDN (NOT on-the-fly rendering)                  ║
║  • Routing tiles ≠ Map tiles (graph data vs images)                         ║
║  • Client-side 15s batching (15x reduction in network calls)               ║
║  • Kafka decouples location data from 4 downstream services                 ║
║  • Separate Navigation + Location services (different scale patterns)       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

**Good luck with your interview!** 🚀
