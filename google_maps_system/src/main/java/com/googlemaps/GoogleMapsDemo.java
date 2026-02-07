package com.googlemaps;

import com.googlemaps.cache.MapTileCDN;
import com.googlemaps.model.*;
import com.googlemaps.service.*;
import com.googlemaps.storage.*;

import java.util.*;

/**
 * Google Maps System - Demo
 * Based on Alex Xu's System Design Interview Volume 2, Chapter 3
 *
 * Demonstrates three core features:
 * 1. Map Rendering (map tile serving via CDN)
 * 2. Navigation Service (route planning + ETA with traffic)
 * 3. Location Service (batch location updates + Kafka streaming)
 */
public class GoogleMapsDemo {

    // Storage layer
    private static GeocodingDB geocodingDB;
    private static RoutingTileStore routingTileStore;
    private static TrafficDB trafficDB;
    private static UserLocationDB userLocationDB;

    // Cache / CDN
    private static MapTileCDN mapTileCDN;

    // Services
    private static GeocodingService geocodingService;
    private static MapTileService mapTileService;
    private static ShortestPathService shortestPathService;
    private static ETAService etaService;
    private static NavigationService navigationService;
    private static LocationService locationService;

    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║           GOOGLE MAPS SYSTEM DESIGN DEMO                 ║");
        System.out.println("║    Based on Alex Xu Volume 2, Chapter 3                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝");

        initializeSystem();

        demoMapRendering();
        demoGeocodingService();
        demoNavigationWithETA();
        demoLocationBatchUpdates();
        demoKafkaDownstreamConsumers();
        demoAdaptiveRerouting();
        demoScaleEstimation();

        System.out.println("\n═══════════════════════════════════════════════════════════");
        System.out.println("Demo complete! See INTERVIEW_CHEATSHEET.md for full design.");
        System.out.println("═══════════════════════════════════════════════════════════");
    }

    private static void initializeSystem() {
        System.out.println("\n--- Initializing System Components ---");

        // Storage
        geocodingDB = new GeocodingDB();
        routingTileStore = new RoutingTileStore();
        trafficDB = new TrafficDB();
        userLocationDB = new UserLocationDB();

        // CDN
        mapTileCDN = new MapTileCDN();

        // Services
        geocodingService = new GeocodingService(geocodingDB);
        mapTileService = new MapTileService(mapTileCDN);
        shortestPathService = new ShortestPathService(routingTileStore);
        etaService = new ETAService(routingTileStore, trafficDB);
        navigationService = new NavigationService(geocodingService, shortestPathService, etaService);
        locationService = new LocationService(userLocationDB);

        System.out.println("  ✓ GeocodingDB initialized (7 locations)");
        System.out.println("  ✓ RoutingTileStore initialized (3 tiles)");
        System.out.println("  ✓ TrafficDB initialized (9 road segments)");
        System.out.println("  ✓ MapTileCDN initialized");
        System.out.println("  ✓ All services ready");
    }

    /**
     * Demo 1: Map Tile Rendering
     * Shows how map tiles are pre-computed, stored in S3, and served via CDN.
     */
    private static void demoMapRendering() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 1: MAP TILE RENDERING                         ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nFlow: Client → Map Tile Service → CDN → Client");
        System.out.println("(Client downloads tiles directly from CDN, NOT through our servers)");

        // User opens map centered on San Francisco
        double lat = 37.7749;
        double lng = -122.4194;
        int zoom = 14; // City-level detail

        System.out.printf("%nStep 1: User opens map at (%.4f, %.4f) zoom=%d%n", lat, lng, zoom);

        // Map Tile Service constructs tile URLs
        List<String> tileUrls = mapTileService.getTileUrlsForViewport(lat, lng, zoom);
        System.out.printf("Step 2: Map Tile Service returns %d tile URLs%n", tileUrls.size());
        for (int i = 0; i < Math.min(3, tileUrls.size()); i++) {
            System.out.printf("  %s%n", tileUrls.get(i));
        }
        System.out.printf("  ... and %d more tiles%n", tileUrls.size() - 3);

        // Simulate client fetching tiles from CDN
        System.out.println("Step 3: Client fetches tiles from CDN");
        MapTile tile = mapTileService.fetchTile(zoom, 2621, 6334);
        System.out.printf("  Fetched: %s (cache MISS → pulled from S3)%n", tile);

        // Fetch same tile again → CDN cache hit
        mapTileService.fetchTile(zoom, 2621, 6334);
        System.out.printf("  Fetched same tile again: cache HIT!%n");
        System.out.printf("  CDN hit rate: %.0f%% (%d hits, %d misses)%n",
                mapTileCDN.getHitRate(), mapTileCDN.getCacheHits(), mapTileCDN.getCacheMisses());

        // Zoom level calculation
        System.out.println("\nMap Tile Math:");
        System.out.println("  Zoom 0:  1 tile (whole world)");
        System.out.println("  Zoom 10: 1,048,576 tiles (city-level)");
        System.out.println("  Zoom 14: 268 million tiles");
        System.out.println("  Zoom 21: 4.4 TRILLION tiles (building-level)");
        System.out.println("  Total tiles across all zoom levels: ~4.4 trillion");
        System.out.println("  → Pre-computed and stored in Object Storage (S3)");
        System.out.println("  → CDN serves from 200+ edge locations worldwide");
    }

    /**
     * Demo 2: Geocoding Service
     * Shows address ↔ coordinate conversion.
     */
    private static void demoGeocodingService() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 2: GEOCODING SERVICE                          ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        // Forward geocoding
        System.out.println("\nForward Geocoding (address → coordinates):");
        geocodingService.geocode("1600 Amphitheatre Parkway, Mountain View, CA");
        geocodingService.geocode("1 Apple Park Way, Cupertino, CA");

        // Reverse geocoding
        System.out.println("\nReverse Geocoding (coordinates → address):");
        geocodingService.reverseGeocode(37.4845, -122.1477);
    }

    /**
     * Demo 3: Navigation with ETA
     * Shows the full route planning pipeline.
     */
    private static void demoNavigationWithETA() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 3: NAVIGATION SERVICE + ETA                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nFlow: User → Navigation Service → Geocoding → Route Planner");
        System.out.println("       → Shortest Path → ETA (with traffic) → Ranker → Response");

        // Plan a route: San Francisco → Cupertino
        List<Route> routes = navigationService.planRoute(
                "San Francisco, CA",
                "1 Apple Park Way, Cupertino, CA",
                "driving",
                false,  // don't avoid tolls
                false   // don't avoid highways
        );

        System.out.println("\n  RESULT:");
        for (Route route : routes) {
            System.out.printf("  %s%n", route);
        }
    }

    /**
     * Demo 4: Location Batch Updates
     * Shows how the client batches GPS updates every 15 seconds.
     */
    private static void demoLocationBatchUpdates() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 4: LOCATION BATCH UPDATES                     ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nClient batches GPS locations every 15 seconds:");
        System.out.println("  loc_1, loc_2, ..., loc_15 → BATCH → Send to Location Service");
        System.out.println("  (Reduces network calls from 1/sec to 1/15sec = 15x reduction!)");

        // Simulate a user driving and sending batched locations
        List<Location> batch1 = new ArrayList<>();
        double startLat = 37.7749;
        double startLng = -122.4194;
        for (int i = 0; i < 15; i++) {
            batch1.add(new Location(startLat + i * 0.0001, startLng + i * 0.0002));
        }

        System.out.println("\n  User 'alice' driving... sending batch:");
        locationService.receiveBatch("alice", batch1);

        // Second batch (15 seconds later)
        List<Location> batch2 = new ArrayList<>();
        for (int i = 15; i < 30; i++) {
            batch2.add(new Location(startLat + i * 0.0001, startLng + i * 0.0002));
        }

        System.out.println("\n  15 seconds later... sending next batch:");
        locationService.receiveBatch("alice", batch2);

        System.out.printf("%n  Total location points in DB: %d%n", userLocationDB.getTotalLocationPoints());
    }

    /**
     * Demo 5: Kafka Downstream Consumers
     * Shows how location data flows to multiple services via Kafka.
     */
    private static void demoKafkaDownstreamConsumers() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 5: KAFKA DOWNSTREAM CONSUMERS                 ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nLocation data published to Kafka is consumed by:");
        System.out.println("  ┌── Traffic Update Service → updates Traffic DB (live traffic)");
        System.out.println("  ├── Routing Tile Processing → updates routing tiles (road changes)");
        System.out.println("  ├── ML Personalization → learns user patterns (frequent routes)");
        System.out.println("  └── Analytics Service → aggregated statistics");

        int totalMessages = locationService.getKafkaStream().size();
        System.out.printf("%n  Kafka stream has %d messages ready for consumption%n", totalMessages);

        // Simulate Traffic Update Service consuming from Kafka
        System.out.println("\n  [TrafficUpdateService] Consuming Kafka messages...");
        System.out.println("  [TrafficUpdateService] Detected slowdown on sf_downtown→sf_mission");

        trafficDB.updateTraffic("sf_downtown", "sf_mission", 2.0, "heavy");
        System.out.println("  [TrafficUpdateService] Updated Traffic DB: sf_downtown→sf_mission = HEAVY (2.0x)");
    }

    /**
     * Demo 6: Adaptive ETA and Rerouting
     * Shows how the system detects traffic changes and suggests reroutes.
     */
    private static void demoAdaptiveRerouting() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 6: ADAPTIVE ETA & REROUTING                   ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\nDuring active navigation, the system monitors traffic...");

        // User is navigating on route: sf_downtown → daly_city → san_mateo → mountain_view
        List<String> currentRoute = List.of("sf_downtown", "daly_city", "san_mateo", "mountain_view");
        System.out.printf("  Current route: %s%n", currentRoute);

        // Calculate initial ETA
        ETAService.ETAResult initialEta = etaService.calculateETA(currentRoute);
        System.out.printf("  Initial ETA: %.0f min%n", initialEta.getTrafficEtaMinutes());

        // Simulate traffic getting worse
        System.out.println("\n  [Traffic Change] Accident on daly_city → san_mateo!");
        trafficDB.updateTraffic("daly_city", "san_mateo", 4.0, "standstill");

        // Check if rerouting is needed
        boolean shouldReroute = etaService.shouldReroute(currentRoute, initialEta.getTrafficEtaMinutes());

        if (shouldReroute) {
            System.out.println("  → Recalculating route via highway...");
            List<String> alternativeRoute = List.of("sf_downtown", "mountain_view");
            ETAService.ETAResult altEta = etaService.calculateETA(alternativeRoute);
            System.out.printf("  Alternative route: %s, ETA: %.0f min%n",
                    alternativeRoute, altEta.getTrafficEtaMinutes());
            System.out.println("  → Push reroute suggestion to user's phone");
        }
    }

    /**
     * Demo 7: Scale Estimation
     * Back-of-the-envelope calculations for the system.
     */
    private static void demoScaleEstimation() {
        System.out.println("\n╔══════════════════════════════════════════════════════╗");
        System.out.println("║  DEMO 7: SCALE ESTIMATION                           ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");

        System.out.println("\n1 billion DAU, key numbers:");
        System.out.println("  ┌────────────────────────────────────────────────┐");
        System.out.println("  │ Navigation QPS:                                │");
        System.out.println("  │   ~5 requests/user/week ÷ 7 days ÷ 86,400s   │");
        System.out.println("  │   = ~800 QPS (bursty, peak = ~5,000)          │");
        System.out.println("  │                                                │");
        System.out.println("  │ Location Updates:                              │");
        System.out.println("  │   ~35M navigating users (5% of DAU)           │");
        System.out.println("  │   Batch every 15 seconds                       │");
        System.out.println("  │   = 35M / 15 = ~2.3M batches/sec             │");
        System.out.println("  │                                                │");
        System.out.println("  │ Map Tile Requests:                             │");
        System.out.println("  │   ~200M users viewing maps daily              │");
        System.out.println("  │   ~9 tiles per viewport, ~5 viewports/session │");
        System.out.println("  │   = 200M × 45 tiles / 86,400 = ~100K QPS     │");
        System.out.println("  │   (Mostly served by CDN, origin QPS << 100K)  │");
        System.out.println("  │                                                │");
        System.out.println("  │ Storage:                                       │");
        System.out.println("  │   Map tiles: ~100 PB (all zoom levels)        │");
        System.out.println("  │   Routing tiles: ~10 TB (graph data)          │");
        System.out.println("  │   Geocoding DB: ~2 TB                          │");
        System.out.println("  │   Location history: ~100 TB/day               │");
        System.out.println("  └────────────────────────────────────────────────┘");
    }
}
