# Proximity Service - Interview Cheatsheet
## (Based on Alex Xu Volume 2, Chapter 1)

---

## 1. Requirements Summary

### Functional Requirements
| # | Requirement | Details |
|---|-------------|---------|
| 1 | **Nearby Search** | Return businesses based on user location (lat/lng) and radius |
| 2 | **Business CRUD** | Add/update/delete businesses (doesn't need real-time reflection) |
| 3 | **Business Details** | View detailed information about a business |

### Non-Functional Requirements
| # | Requirement | Target | Notes |
|---|-------------|--------|-------|
| 1 | **Low Latency** | < 100ms | Users expect instant results |
| 2 | **Data Privacy** | GDPR/CCPA compliant | Location data is sensitive |
| 3 | **High Availability** | 99.99% | Handle peak hours in dense areas |
| 4 | **Scalability** | 5000+ QPS | Support traffic spikes |

### Scale Estimation
| Metric | Value | Calculation |
|--------|-------|-------------|
| DAU | 100 million | Given |
| Searches/user/day | 5 | Given |
| **Search QPS** | 5,000 | 100M × 5 / 10⁵ |
| Peak QPS | 10,000-15,000 | ~2-3x average |
| Total businesses | ~200 million | Worldwide estimate |
| Write QPS | ~100/day | Business updates (negligible) |

**KEY INSIGHT**: This is a **READ-HEAVY** system! Writes are negligible.

---

## 2. API Design

### Search APIs (Location-Based Service)

```
GET /v1/search/nearby
```

| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| latitude | double | Yes | - | User's latitude |
| longitude | double | Yes | - | User's longitude |
| radius | int | No | 5000 | Search radius in meters (1-50000) |
| category | string | No | - | Filter: restaurant, coffee, gas_station |
| limit | int | No | 20 | Max results to return |
| sort_by | string | No | distance | "distance" or "rating" |

**Response:**
```json
{
  "total": 25,
  "businesses": [
    {
      "business_id": "123",
      "name": "Coffee Shop",
      "address": "123 Main St",
      "latitude": 37.7762,
      "longitude": -122.4233,
      "distance": 150.5,
      "rating": 4.5,
      "category": "coffee"
    }
  ]
}
```

### Business APIs (Business Service)

| Method | Endpoint | Description | Service |
|--------|----------|-------------|---------|
| GET | `/v1/businesses/{id}` | Get business details | Business Service |
| POST | `/v1/businesses` | Create new business | Business Service |
| PUT | `/v1/businesses/{id}` | Update business | Business Service |
| DELETE | `/v1/businesses/{id}` | Delete business | Business Service |

---

## 3. High-Level Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              PROXIMITY SERVICE                               │
└─────────────────────────────────────────────────────────────────────────────┘

                              ┌──────────────┐
                              │    Client    │
                              │ (Mobile/Web) │
                              └──────┬───────┘
                                     │
                                     ▼
                            ┌─────────────────┐
                            │  Load Balancer  │
                            └────────┬────────┘
                                     │
                                     ▼
                            ┌─────────────────┐
                            │   API Gateway   │  ← Auth, Rate Limiting, Logging
                            │  (API Server)   │
                            └────────┬────────┘
                                     │
                                     │ Route by path:
                                     │ /v1/search/* → LBS
                                     │ /v1/businesses/* → Business Service
                                     │
                    ┌────────────────┴────────────────┐
                    │                                 │
                    ▼                                 ▼
           ┌────────────────┐               ┌────────────────┐
           │ Location-Based │               │   Business     │
           │ Service (LBS)  │               │   Service      │
           │  [READ-HEAVY]  │               │  [WRITE-LIGHT] │
           └───────┬────────┘               └───────┬────────┘
                   │                                │
                   │         ┌─────────┐           │
                   └────────>│  Redis  │<──────────┘
                             │  Cache  │
                             └────┬────┘
                                  │
                    ┌─────────────┴─────────────┐
                    │                           │
                    ▼                           ▼
           ┌─────────────────┐        ┌─────────────────┐
           │  Business DB    │        │  Geospatial     │
           │  (Primary)      │        │  Index DB       │
           └───────┬─────────┘        └─────────────────┘
                   │
           ┌───────┴───────┐
           ▼               ▼
    ┌────────────┐  ┌────────────┐
    │  Replica   │  │  Replica   │
    └────────────┘  └────────────┘

KEY FLOWS:
1. Search: Client → LB → API Gateway → LBS → Cache/DB → Filter → Response
2. Get Details: Client → LB → API Gateway → LBS → Cache/DB → Response
3. Create/Update: Client → LB → API Gateway → Business Service → DB → Invalidate Cache
```

### API Gateway Responsibilities

| Responsibility | Description |
|----------------|-------------|
| **Authentication** | Validate JWT tokens, API keys |
| **Rate Limiting** | Throttle requests per client (e.g., 100 req/sec) |
| **Request Routing** | Route `/v1/search/*` → LBS, `/v1/businesses/*` → Business Service |
| **SSL Termination** | Handle HTTPS, forward HTTP internally |
| **Request Validation** | Validate required params, reject malformed requests |
| **Logging/Metrics** | Log all requests, collect latency metrics |
| **Response Caching** | Cache common responses (optional) |

**Tech Stack Options:** AWS API Gateway, Kong, NGINX, Envoy, Spring Cloud Gateway

---

## 4. Geospatial Indexing (CORE CONCEPT)

### The Problem
Given a point (lat, lon) and radius, how do we efficiently find all nearby businesses?

**Naive approach**: Check every business → O(n) per query → **Too slow!**

### Solution Options

| Option | Description | Best For |
|--------|-------------|----------|
| **Geohash** | Encodes location as string, prefix = proximity | Database indexing |
| **QuadTree** | Tree with 4 children per node | In-memory processing |
| **Google S2** | Sphere-based cells, more accurate | Large-scale systems |
| **R-Tree** | Rectangle-based indexing | PostGIS, spatial DBs |

---

## 5. Geospatial Indexing Explained

**Problem:** We have TWO columns (latitude, longitude) and need to find nearby points.

```sql
-- Naive approach: Range query on two columns
SELECT * FROM business 
WHERE latitude BETWEEN 37.7 AND 37.8 
  AND longitude BETWEEN -122.5 AND -122.4;
```

**Why this is slow:**
- Database can use index on ONE column efficiently
- With two columns, it finds all matching latitudes, then scans for longitude
- Or uses composite index, but range queries on second column are inefficient

**Solution:** Convert 2D problem → 1D problem!
- Geohash encodes (lat, lon) into a **single string**
- Now we can use a simple B-Tree index on one column
- Nearby locations have similar strings (common prefix)

---

## 6. Geohash Deep Dive

### What is Geohash?
Encodes (lat, lon) into a short string where **common prefix = nearby**.

```
San Francisco (37.7749, -122.4194):
  Precision 4: 9q8y      (~39km × 19km cell)
  Precision 5: 9q8yy     (~5km × 5km cell)
  Precision 6: 9q8yyk    (~1.2km × 0.6km cell)
```

### How Geohash is Created

Geohash recursively divides the world into smaller grids using binary subdivision:

```
World divided by first few bits:
┌─────────────┬─────────────┐
│      0      │      1      │   ← First bit: West vs East hemisphere
│  (Western)  │  (Eastern)  │
├──────┬──────┼──────┬──────┤
│  00  │  01  │  10  │  11  │   ← After 2 bits: 4 quadrants
└──────┴──────┴──────┴──────┘

Each bit subdivides further → more bits = smaller cells

San Francisco (Western hemisphere) → starts with "9"
Tokyo (Eastern hemisphere) → starts with "x"
```

- Alternates between longitude and latitude bits
- Every 5 bits → 1 Base32 character (0-9, b-z except a,i,l,o)
- **Key insight:** Each additional character = 32x more precise grid!

### Precision Table
| Length | Cell Width | Cell Height | Use Case |
|--------|------------|-------------|----------|
| 4 | ~39.1 km | ~19.5 km | City-level |
| 5 | ~4.9 km | ~4.9 km | Neighborhood |
| **6** | **~1.2 km** | **~0.61 km** | **Street-level (common)** |
| 7 | ~153 m | ~153 m | Building |
| 8 | ~38 m | ~19 m | Precise |

### Search Algorithm

```
STEP 1: Choose precision based on radius
        - radius >= 5km → precision 5
        - radius >= 1km → precision 6
        
STEP 2: Get center geohash
        - encode(37.7749, -122.4194, 6) = "9q8yyk"
        
STEP 3: Get neighboring geohashes (CRITICAL!)
        - Must include 8 neighbors + center = 9 cells
        - Handles boundary problem
        
STEP 4: Query DB for all 9 cells
        - SELECT * FROM business WHERE geohash_6 IN (?, ?, ...)
        
STEP 5: Filter by exact distance
        - Geohash cells are rectangular, search is circular
        - Calculate haversine distance for each candidate
```

### Boundary Problem (IMPORTANT!)

```
    +---+---+
    | A | B |    Points X and Y are very close but in different cells!
    +--X+Y--+    If we only search cell A, we miss Y.
    | C | D |
    +---+---+    SOLUTION: Always search center + 8 neighbors!
```

### Database Schema with Geohash

```sql
CREATE TABLE business (
    business_id BIGINT PRIMARY KEY,  -- Clustered index (table sorted by this)
    name VARCHAR(255),
    latitude DOUBLE,
    longitude DOUBLE,
    geohash_4 CHAR(4),      -- Secondary index for large radius
    geohash_5 CHAR(5),      -- Secondary index for medium radius
    geohash_6 CHAR(6),      -- Secondary index for small radius
    category VARCHAR(100),
    rating DECIMAL(2,1),
    ...
    INDEX idx_geohash_4 (geohash_4),   -- Secondary index
    INDEX idx_geohash_5 (geohash_5),   -- Secondary index
    INDEX idx_geohash_6 (geohash_6),   -- Secondary index
    INDEX idx_category_geohash (category, geohash_6)  -- Composite secondary index
);
```

**How Indexes Work (Visual):**

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  CLUSTERED INDEX (Table sorted by PRIMARY KEY - business_id)                │
│  Row │ business_id │ name           │ geohash_4 │ geohash_6 │ category      │
│  ────┼─────────────┼────────────────┼───────────┼───────────┼───────────────│
│  1   │ 101         │ Coffee Shop    │ 9q8y      │ 9q8yyk    │ coffee        │
│  2   │ 102         │ Pizza Place    │ dr5r      │ dr5ruk    │ restaurant    │
│  3   │ 103         │ Gas Station    │ 9q8y      │ 9q8yzm    │ gas_station   │
└─────────────────────────────────────────────────────────────────────────────┘
         ▲                                    ▲
         │                                    │
┌────────┴─────────────────┐    ┌─────────────┴────────────────────────────┐
│  SECONDARY INDEX:        │    │  COMPOSITE SECONDARY INDEX:              │
│  idx_geohash_6           │    │  idx_category_geohash (category, geohash)│
│                          │    │                                          │
│  9q8yyk → PK 101         │    │  coffee + 9q8yyk → PK 101                │
│  9q8yzm → PK 103         │    │  gas_station + 9q8yzm → PK 103           │
│  dr5ruk → PK 102         │    │  restaurant + dr5ruk → PK 102            │
└──────────────────────────┘    └──────────────────────────────────────────┘

Query: WHERE geohash_6 = '9q8yyk'
  → Use idx_geohash_6 → Find PK 101 → Lookup clustered index

Query: WHERE category = 'restaurant' AND geohash_6 IN (...)
  → Use idx_category_geohash → Jump to 'restaurant' section → Very efficient!
```

> For details on clustered vs secondary indexes, composite indexes, and column ordering, see [Database Fundamentals - Indexing](../database_fundamentals/02_DATABASE_LOGIC.md)

---

## 7. QuadTree Deep Dive

### What is a QuadTree?
A tree where each internal node has **exactly 4 children** (NW, NE, SW, SE).

```
                    ┌───────────────────┐
                    │     WORLD         │
                    │   (root node)     │
                    └─────────┬─────────┘
              ┌───────┬───────┴───────┬───────┐
              ▼       ▼               ▼       ▼
           ┌─────┐ ┌─────┐         ┌─────┐ ┌─────┐
           │ NW  │ │ NE  │         │ SW  │ │ SE  │
           └──┬──┘ └─────┘         └─────┘ └──┬──┘
              │                               │
        (splits further                 (splits further
         if too many                     if too many
         businesses)                     businesses)
```

### QuadTree vs Geohash

| Aspect | Geohash | QuadTree |
|--------|---------|----------|
| **Structure** | String encoding | Tree structure |
| **Storage** | Database column | In-memory |
| **Density-aware** | No (fixed grid) | Yes (adaptive depth) |
| **Updates** | O(1) | O(log n) |
| **Query** | Prefix search | Tree traversal |
| **Best for** | DB indexing, simple | Dense/sparse areas |

### QuadTree Algorithm

```
INSERT(node, business):
    if business not in node.bounds:
        return
    if node is leaf:
        add business to node
        if node.count > MAX_CAPACITY and depth < MAX_DEPTH:
            split(node)  // Create 4 children
    else:
        for each child in node.children:
            INSERT(child, business)

SEARCH(node, center, radius):
    if node.bounds doesn't intersect search circle:
        return []  // Prune!
    if node is leaf:
        return filter(node.businesses, within radius)
    else:
        return merge(SEARCH(child) for each child)
```

**Key benefit**: Dense areas (Manhattan) split more, sparse areas (rural) stay shallow.

---

## 8. Caching Strategy

### What to Cache

| Cache Key | Value | TTL | Notes |
|-----------|-------|-----|-------|
| `geo:6:9q8yyk` | [biz_id1, biz_id2, ...] | 5 min | Business IDs in geohash cell |
| `biz:123` | Business object | 1 hour | Business details |

### Why Cache Geohash Cells?
- Finite number of active cells
- Popular areas (SF downtown) get cached
- Very high hit rate (90%+)

### Redis as Geohash Cache

Simple key-value storage with geohash as key:

```
┌─────────────────────────────────────────────────────────────────┐
│  Redis                                                          │
│                                                                  │
│  ┌───────────────────────────────────────┐                      │
│  │  Geohash Cache (geohash → biz IDs)    │                      │
│  │                                       │                      │
│  │  "geo:6:9q8yyk" → [101, 105, 108]     │                      │
│  │  "geo:6:9q8yym" → [102, 106]          │                      │
│  │  "geo:6:dr5ruk" → [103, 107]          │                      │
│  └───────────────────────────────────────┘                      │
│                                                                  │
│  ┌───────────────────────────────────────┐                      │
│  │  Business Cache (biz ID → details)    │                      │
│  │                                       │                      │
│  │  "biz:101" → {name, lat, lon, ...}    │                      │
│  │  "biz:102" → {name, lat, lon, ...}    │                      │
│  └───────────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────────┘

Query flow:
1. Compute geohash for user location → "9q8yyk"
2. GET "geo:6:9q8yyk" → [101, 105, 108]
3. GET "biz:101", "biz:105", "biz:108" → Full business details
```

### Cache Invalidation

**What is "invalidate"?** Simply **DELETE the cache key** so next read fetches fresh data from DB.

```
On Business Create/Update/Delete:

1. Update primary database
2. DELETE "geo:6:9q8yyk" from Redis     ← Invalidate geohash cell
3. DELETE "biz:101" from Redis          ← Invalidate business details
```

**Cache Repopulation Strategy:**

Since real-time updates are NOT required (per requirements), we have two options:

| Strategy | How it Works | When to Use |
|----------|--------------|-------------|
| **Lazy (on-demand)** | Cache miss → fetch from DB | Simple, but first request is slow |
| **Nightly Batch Job** | Scheduled job rebuilds cache overnight | Better for production, consistent performance |

```
Nightly Batch Repopulation:
┌─────────────────────────────────────────────────────────────┐
│  Cron Job (runs at 2 AM daily)                              │
│  1. Query all businesses from DB                            │
│  2. Group by geohash cells                                  │
│  3. Bulk write to Redis: SET "geo:6:9q8yyk" → [101, 105...] │
│  4. Set TTL = 25 hours (survives until next rebuild)        │
└─────────────────────────────────────────────────────────────┘
```

### Caching Strategies (When to Use Which)

```
┌────────────────────┬─────────────────────────────────────┬─────────────────────┐
│ Strategy           │ How it Works                        │ Best For            │
├────────────────────┼─────────────────────────────────────┼─────────────────────┤
│ Cache-Aside        │ App writes to DB → deletes cache    │ Read-heavy,         │
│ (what we use)      │ App reads: cache miss → fetch DB    │ infrequent writes   │
│                    │                                     │ ✅ Proximity Service │
├────────────────────┼─────────────────────────────────────┼─────────────────────┤
│ Write-Through      │ App writes to cache AND DB together │ Strong consistency  │
│                    │ (sync, slower writes)               │ needed              │
├────────────────────┼─────────────────────────────────────┼─────────────────────┤
│ Write-Back         │ App writes to cache only            │ Write-heavy,        │
│ (Write-Behind)     │ Cache async writes to DB later      │ eventual consistency│
│                    │ (risk: data loss if cache crashes)  │ ok                  │
└────────────────────┴─────────────────────────────────────┴─────────────────────┘
```

**Why Cache-Aside for Proximity Service?**
- Writes are rare (~100/day) → no need for write optimization
- Simple to implement
- Eventual consistency is acceptable (business updates don't need real-time)

---

## 9. Database Design

### Why MySQL?

| Factor | MySQL Suitability |
|--------|-------------------|
| **Read-heavy** (5000 QPS reads, ~100 writes/day) | ✅ MySQL with read replicas handles this well |
| **Structured data** (business info) | ✅ Relational model fits perfectly |
| **Simple queries** (geohash IN clause) | ✅ No complex JOINs needed |
| **ACID transactions** | ✅ Needed for business updates |

### Database Schema (Single Table with Geohash Columns)

For simplicity, we use **one denormalized table** with geohash columns (same as shown in Geohash Deep Dive section):

```sql
CREATE TABLE business (
    business_id BIGINT PRIMARY KEY,
    name VARCHAR(255),
    address VARCHAR(512),
    city VARCHAR(100),
    latitude DOUBLE,
    longitude DOUBLE,
    geohash_4 CHAR(4),      -- Computed from lat/lon, for large radius (~40km)
    geohash_5 CHAR(5),      -- For medium radius (~5km)  
    geohash_6 CHAR(6),      -- For small radius (~1km)
    category VARCHAR(100),
    rating DECIMAL(2,1),
    review_count INT,
    created_at TIMESTAMP,
    updated_at TIMESTAMP,
    
    INDEX idx_geohash_6 (geohash_6),
    INDEX idx_category_geohash (category, geohash_6)
);
```

**Why single table (denormalized)?**
- No JOINs needed → faster queries
- Simpler to explain in interviews
- Geohash columns are computed from lat/lon when business is created/updated
- Trade-off: Slightly more storage, but worth it for read performance

### Distance Calculation: Haversine Formula

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  WHY HAVERSINE? (Not Euclidean or Manhattan)                                │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Euclidean (straight line):  √((x₂-x₁)² + (y₂-y₁)²)                        │
│    ❌ Assumes flat surface - WRONG for Earth!                              │
│                                                                             │
│  Manhattan (grid distance):  |x₂-x₁| + |y₂-y₁|                             │
│    ❌ Also assumes flat surface - WRONG for Earth!                         │
│                                                                             │
│  Haversine (great-circle distance):                                        │
│    ✅ Accounts for Earth's curvature                                       │
│    ✅ Calculates shortest path on a sphere                                 │
│                                                                             │
│  Formula:                                                                   │
│    a = sin²(Δlat/2) + cos(lat₁) × cos(lat₂) × sin²(Δlon/2)                │
│    distance = 2 × R × arctan2(√a, √(1-a))                                  │
│    where R = Earth's radius (6,371 km)                                     │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```


---

## 10. Scaling Strategies

### Read Scaling (Primary Need)

```
                    ┌──────────────┐
                    │ Load Balancer│
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌─────────┐        ┌─────────┐        ┌─────────┐
   │  LBS 1  │        │  LBS 2  │        │  LBS 3  │
   └────┬────┘        └────┬────┘        └────┬────┘
        │                  │                  │
        └──────────────────┼──────────────────┘
                           │
                    ┌──────┴───────┐
                    │ Redis Cluster│
                    └──────┬───────┘
                           │
        ┌──────────────────┼──────────────────┐
        ▼                  ▼                  ▼
   ┌─────────┐        ┌─────────┐        ┌─────────┐
   │ Primary │        │ Replica │        │ Replica │
   └─────────┘        └─────────┘        └─────────┘
```

### Database Sharding (If Needed)

**Shard by Region/Country:**
- US businesses → Shard 1
- Europe businesses → Shard 2
- Asia businesses → Shard 3

**Why region-based?**
- Users typically search locally
- No cross-shard queries needed
- Natural data locality

**Sharding is done by application code (MySQL doesn't support sharding natively):**
```
┌─────────────────────────────────────────────────────────────────┐
│  LBS Application Code                                           │
│                                                                  │
│  1. Get user's location → (37.77, -122.41)                      │
│  2. Determine region → "US"                                      │
│  3. Route to correct shard → Shard 1 (US)                       │
│  4. Query: SELECT * FROM business WHERE geohash_6 IN (...)      │
└─────────────────────────────────────────────────────────────────┘
```

**Note:** MySQL has no built-in sharding. Application must:
- Maintain shard configuration (region → DB connection)
- Route queries to correct shard
- Handle cross-shard queries if needed (not needed here - users search locally)

### LBS Scaling

1. **Stateless** - No session state, easy to add instances
2. **Horizontal scaling** - Add more servers behind load balancer
3. **Regional deployment** - Deploy LBS close to users (CDN-like)

---

## 11. Complete Search Flow

```
┌────────┐     ┌────────────┐     ┌─────────────────┐     ┌───────────┐
│ Client │────>│ Load       │────>│ Location-Based  │────>│ Redis     │
│        │     │ Balancer   │     │ Service (LBS)   │     │ Cache     │
└────────┘     └────────────┘     └────────┬────────┘     └─────┬─────┘
                                           │                    │
                                           │ Cache Miss         │ Cache Hit
                                           ▼                    │
                                  ┌─────────────────┐           │
                                  │   Business DB   │           │
                                  │   (with index)  │           │
                                  └────────┬────────┘           │
                                           │                    │
                                           ▼                    ▼
                                  ┌─────────────────────────────────┐
                                  │  Filter by exact distance       │
                                  │  (Haversine formula)            │
                                  └────────────────┬────────────────┘
                                                   │
                                                   ▼
                                  ┌─────────────────────────────────┐
                                  │  Sort by distance/rating        │
                                  │  Apply limit                    │
                                  └────────────────┬────────────────┘
                                                   │
                                                   ▼
                                            ┌──────────┐
                                            │ Response │
                                            └──────────┘
```

**Step-by-step:**

1. **Client** sends: `GET /v1/search/nearby?lat=37.77&lon=-122.41&radius=5000`
2. **LBS** determines geohash precision (6 for 5km)
3. **LBS** encodes location → `9q8yyk`
4. **LBS** gets 9 cells (center + 8 neighbors)
5. **LBS** checks Redis for each cell
6. **Cache miss** → Query Business DB with geohash IN clause
7. **LBS** filters results by exact distance (haversine)
8. **LBS** sorts by distance or rating
9. **LBS** returns top N results
10. **LBS** populates cache for future requests

---

## 12. Data Privacy & Compliance

### GDPR/CCPA Considerations

| Concern | Mitigation |
|---------|------------|
| Location tracking | Don't store search history by default |
| User consent | Get explicit consent for location access |
| Data retention | Auto-delete logs after X days |
| Right to deletion | Allow users to delete their data |
| Anonymization | Use hashed IDs, not real identifiers |

### Best Practices

1. **Minimize data collection** - Only collect what's needed
2. **Encrypt at rest** - Location data is sensitive
3. **Encrypt in transit** - HTTPS everywhere
4. **Audit logging** - Track who accessed what
5. **Regional compliance** - Different rules for EU, California, etc.

---

## 13. Failure Scenarios & Mitigations

| Failure | Impact | Mitigation |
|---------|--------|------------|
| LBS server down | Some search requests fail | Multiple LBS instances + health checks |
| Redis cache down | Higher DB load, slower responses | Fallback to DB, cache warm-up |
| Primary DB down | Writes fail, reads degraded | Failover to replica, promote to primary |
| Network partition | Region isolated | Multi-region deployment |
| Hot spot (viral location) | Single geohash overwhelmed | Rate limiting, cache aggressively |

---

## 14. Quick Q&A

### Q: Why use Geohash instead of just lat/lon range query?
**A:** Range queries on two columns (lat AND lon) don't use indexes efficiently. Geohash converts 2D problem to 1D, allowing simple prefix-based indexing.

### Q: Why search 9 cells instead of just 1?
**A:** Boundary problem! Two points very close together might be in different geohash cells. Searching neighbors ensures we don't miss nearby businesses at cell boundaries.

### Q: Why filter by distance after geohash query?
**A:** Geohash cells are rectangular, but search radius is circular. Geohash gives approximate candidates (fast, indexed), then we filter to exact distance (accurate).

### Q: Can we cache search results directly?
**A:** Difficult due to many variations (lat, lon, radius, category, limit). Better to cache geohash cells, which are finite and reusable.

### Q: How to handle a viral location (everyone searching same spot)?
**A:** 
1. Cache aggressively (those geohash cells will be hot)
2. Rate limiting per client
3. Possibly pre-compute popular areas

### Q: Geohash vs QuadTree - when to use which?
**A:** 
- **Geohash**: Database indexing, simpler implementation, works with standard DBs
- **QuadTree**: In-memory processing, density-aware (efficient for uneven distribution)

### Q: How to handle businesses that move (food trucks)?
**A:** 
1. Allow frequent location updates
2. Invalidate old and new geohash cells on update
3. Consider shorter cache TTL for mobile businesses

### Q: What about elevation (multi-floor buildings)?
**A:** For most use cases, 2D is sufficient. For 3D (airports, malls), could add floor as additional filter after geo search.

### Q: How is this different from Google Maps?
**A:** Google Maps also includes:
- Routing/directions
- Real-time traffic
- Street view
- Place details/reviews
- Much larger scale (billions of queries/day)

---

## 15. Visual Architecture Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         PROXIMITY SERVICE ARCHITECTURE                       │
└─────────────────────────────────────────────────────────────────────────────┘

┌────────────────────────┐
│      COMPONENTS        │
├────────────────────────┤
│                        │
│  ┌──────────────────┐  │     ┌─────────────────────────────────────────────┐
│  │  Load Balancer   │  │     │ SERVICES                                    │
│  └────────┬─────────┘  │     │                                             │
│           │            │     │  API Gateway:                               │
│  ┌────────┴─────────┐  │     │  - Auth, Rate Limiting, Logging             │
│  │   API Gateway    │  │     │  - Route /search/* → LBS                    │
│  └────────┬─────────┘  │     │  - Route /businesses/* → Business Service   │
│           │            │     │                                             │
│  ┌────────┴────────┐   │     │  Location-Based Service (LBS):              │
│  ▼                 ▼   │     │  - Stateless, read-only                     │
│ ┌───────┐    ┌──────┐  │     │  - Handles 5000 QPS                         │
│ │  LBS  │    │ Biz  │  │     │  - Geohash-based search                     │
│ │ (×N)  │    │ Svc  │  │     │  - Distance filtering                       │
│ └───┬───┘    └──┬───┘  │     │                                             │
│     │           │      │     │  Business Service:                          │
│     └─────┬─────┘      │     │  - CRUD operations                          │
│           │            │     │  - Low QPS (~100/day)                       │
│     ┌─────┴─────┐      │     │  - Cache invalidation                       │
│     │   Redis   │      │     │                                             │
│     │   Cache   │      │     └─────────────────────────────────────────────┘
│     └─────┬─────┘      │
│           │            │     ┌─────────────────────────────────────────────┐
│     ┌─────┴─────┐      │     │ STORAGE                                     │
│     │  Primary  │      │     │                                             │
│     ┌─────┴─────┐      │     │  Redis Cache:                               │
│     │  Primary  │      │     │  - Geohash cell → business IDs              │
│     │    DB     │      │     │  - Business ID → details                    │
│     └─────┬─────┘      │     │  - 5 min TTL                                │
│           │            │     │                                             │
│     ┌─────┴─────┐      │     │  Business DB (MySQL/PostgreSQL):            │
│     │  Replicas │      │     │  - Geohash indexed columns                  │
│     │   (×2)    │      │     │  - Read replicas for scaling                │
│     └───────────┘      │     │                                             │
│                        │     └─────────────────────────────────────────────┘
└────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ KEY FLOWS                                                                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│ 1. SEARCH NEARBY:                                                           │
│    Client → LB → API GW → LBS → Cache/DB → Filter → Sort → Return           │
│                                                                             │
│ 2. GET BUSINESS DETAILS:                                                    │
│    Client → LB → API GW → LBS → Cache → (miss: DB) → Return                 │
│                                                                             │
│ 3. CREATE/UPDATE BUSINESS:                                                  │
│    Client → LB → API GW → Business Service → DB → Invalidate Cache          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────┐
│ KEY DESIGN DECISIONS                                                         │
├─────────────────────────────────────────────────────────────────────────────┤
│ • Geohash for indexing (converts 2D → 1D, enables prefix search)            │
│ • Search 9 cells (center + 8 neighbors) to handle boundary problem          │
│ • Two-step filtering: geohash (approximate) → distance (exact)              │
│ • Cache geohash cells (finite, high hit rate in popular areas)              │
│ • Separate LBS (read) and Business Service (write) for independent scaling  │
│ • API Gateway for auth, rate limiting, routing                              │
│ • Read replicas for 5000 QPS                                                │
│ • Eventual consistency acceptable for business updates                       │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 16. Interview Tips

1. **Start with requirements** - Clarify search vs CRUD, real-time needs
2. **Identify read-heavy nature** - Key insight that drives architecture
3. **Explain geohash clearly** - Core concept, precision levels, boundary problem
4. **Discuss alternatives** - QuadTree, PostGIS, show breadth of knowledge
5. **Cover caching** - Geohash cells are perfect cache candidates
6. **Address scaling** - Stateless LBS, read replicas, regional sharding
7. **Mention data privacy** - GDPR/CCPA awareness shows maturity

---

## References
- System Design Interview Volume 2 (Alex Xu) - Chapter 1
- [Geohash Wikipedia](https://en.wikipedia.org/wiki/Geohash)
- [PostGIS Documentation](https://postgis.net/documentation/)
- [Redis Geospatial](https://redis.io/docs/data-types/geospatial/)
