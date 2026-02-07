# Google Maps System

Based on Alex Xu's System Design Interview Volume 2 - Chapter 3

## Overview

A simplified implementation of Google Maps focusing on three core features:
1. **Map Rendering** - Serving pre-computed map tiles via CDN
2. **Navigation Service** - Route planning with shortest path, ranking, and ETA
3. **Location Service** - User location tracking with batch updates via Kafka

## Key Concepts Demonstrated

- **Map Tile System**: Pre-computed tiles at 21 zoom levels served via CDN
- **Routing Tiles**: Road network graph partitioned into geographic tiles for efficient shortest-path computation
- **Geocoding**: Address ↔ coordinate conversion
- **Adaptive ETA**: Real-time rerouting based on live traffic data
- **Location Batching**: Client batches location updates every 15 seconds to reduce network calls
- **Kafka Event Streaming**: Location data consumed by multiple downstream services

## Architecture

```
Mobile User → CDN (map tiles)
            → Load Balancer → Navigation Service → Geocoding DB
                                                  → Route Planner → Shortest Path (Routing Tiles)
                                                                   → Ranker → Filter
                                                                   → ETA Service → Traffic DB
                             → Location Service → User Location DB → Kafka
                                                                      → Traffic Update Service
                                                                      → Routing Tile Processing
                                                                      → ML Personalization
                                                                      → Analytics
```

## Running the Demo

```bash
# Option 1: Using Maven
mvn compile exec:java

# Option 2: Direct compilation
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `GoogleMapsDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Location, Route, MapTile, RoutingTile, etc.) |
| `service/` | Core services (NavigationService, MapTileService, LocationService, etc.) |
| `storage/` | Database simulations (GeocodingDB, RoutingTileStore, TrafficDB, etc.) |
| `cache/` | CDN and caching layer simulation |
