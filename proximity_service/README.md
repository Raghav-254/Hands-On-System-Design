# Proximity Service

A location-based service (LBS) implementation demonstrating nearby search functionality, based on **Chapter 1 of "System Design Interview Volume 2" by Alex Xu**.

## Overview

This project implements a proximity service similar to Yelp or Google Maps' "nearby" feature, allowing users to search for businesses based on their current location.

### Key Features
- **Nearby Search**: Find businesses within a given radius
- **Geospatial Indexing**: Efficient search using Geohash encoding
- **QuadTree**: Alternative spatial indexing for in-memory processing
- **Caching**: Redis-style caching for high-performance lookups
- **Business CRUD**: Create, read, update, delete businesses

## Architecture

```
Client → Load Balancer → LBS (Read) / Business Service (Write) → Cache → Database
```

### Core Components
- **Location-Based Service (LBS)**: Stateless service handling search queries
- **Business Service**: Handles business CRUD operations
- **Geohash**: Encodes lat/lon into strings for efficient indexing
- **QuadTree**: Tree-based spatial indexing (alternative approach)
- **GeospatialCache**: In-memory cache for geohash cells and business data

## Project Structure

```
proximity_service/
├── pom.xml
├── README.md
├── INTERVIEW_CHEATSHEET.md          # Interview preparation guide
└── src/main/java/com/proximity/
    ├── ProximityServiceDemo.java     # Main demo
    ├── model/
    │   ├── Business.java             # Business entity
    │   ├── GeoLocation.java          # Location with distance calculation
    │   ├── SearchRequest.java        # Search parameters
    │   └── SearchResult.java         # Search response
    ├── geo/
    │   ├── Geohash.java              # Geohash encoding/decoding
    │   └── QuadTree.java             # QuadTree implementation
    ├── storage/
    │   └── BusinessDB.java           # Database with geohash index
    ├── cache/
    │   └── GeospatialCache.java      # Geospatial caching
    └── service/
        ├── LocationBasedService.java # Search service (read-heavy)
        ├── BusinessService.java      # CRUD service
        └── ApiService.java           # REST API layer
```

## Key Concepts

### Geohash
- Encodes 2D coordinates (lat, lon) into a 1D string
- Common prefix = nearby locations
- Precision levels: 4 (city) to 8 (building)
- Must search 9 cells (center + 8 neighbors) to handle boundaries

### QuadTree
- Tree where each node has 4 children (NW, NE, SW, SE)
- Adaptive: dense areas have deeper trees
- Good for in-memory spatial indexing

### Read-Heavy Design
- Search QPS: 5,000
- Write QPS: ~100/day
- Heavily cached, read replicas

## Running the Demo

```bash
cd proximity_service
mvn compile exec:java -Dexec.mainClass="com.proximity.ProximityServiceDemo"
```

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/v1/search/nearby` | Search nearby businesses |
| GET | `/v1/businesses/{id}` | Get business details |
| POST | `/v1/businesses` | Create business |
| PUT | `/v1/businesses/{id}` | Update business |
| DELETE | `/v1/businesses/{id}` | Delete business |

## Scale

- 100M DAU
- 5 searches/user/day
- 5,000 search QPS
- ~200M businesses worldwide

## References

- System Design Interview Volume 2 (Alex Xu) - Chapter 1
- [Geohash Wikipedia](https://en.wikipedia.org/wiki/Geohash)
