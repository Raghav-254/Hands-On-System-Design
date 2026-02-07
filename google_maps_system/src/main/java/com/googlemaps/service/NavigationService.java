package com.googlemaps.service;

import com.googlemaps.model.GeocodingResult;
import com.googlemaps.model.Route;
import java.util.*;

/**
 * Navigation Service - the main orchestrator for route planning.
 *
 * Flow (Figure 17 from the book):
 * 1. User requests navigation (origin, destination, travel mode)
 * 2. Navigation Service calls Geocoding Service to resolve addresses
 * 3. Calls Route Planner Service which delegates to:
 *    a. Shortest Path Service → finds candidate routes using routing tiles
 *    b. ETA Service → calculates travel time with live traffic
 *    c. Ranker → ranks routes based on ETA, distance, tolls, user prefs
 *    d. Filter Service → removes routes that violate user constraints (avoid tolls, etc.)
 * 4. Returns top-k ranked routes to the user
 *
 * During active navigation:
 * - Adaptive ETA and Rerouting Service monitors traffic
 * - If conditions change → recalculates ETA and suggests alternative routes
 */
public class NavigationService {
    private final GeocodingService geocodingService;
    private final ShortestPathService shortestPathService;
    private final ETAService etaService;

    public NavigationService(GeocodingService geocodingService,
                             ShortestPathService shortestPathService,
                             ETAService etaService) {
        this.geocodingService = geocodingService;
        this.shortestPathService = shortestPathService;
        this.etaService = etaService;
    }

    /**
     * Plan a route from origin to destination.
     * Returns top-k ranked routes.
     */
    public List<Route> planRoute(String originAddress, String destinationAddress,
                                  String travelMode, boolean avoidTolls, boolean avoidHighways) {
        System.out.println("\n=== NAVIGATION SERVICE ===");
        System.out.printf("  From: %s%n  To: %s%n  Mode: %s%n", originAddress, destinationAddress, travelMode);

        // Step 1: Geocode addresses
        Optional<GeocodingResult> origin = geocodingService.geocode(originAddress);
        Optional<GeocodingResult> destination = geocodingService.geocode(destinationAddress);

        if (origin.isEmpty() || destination.isEmpty()) {
            System.out.println("  [NavigationService] Could not resolve addresses!");
            return Collections.emptyList();
        }

        // Step 2: Find shortest path (graph traversal on routing tiles)
        String originNode = resolveToNode(origin.get());
        String destNode = resolveToNode(destination.get());

        ShortestPathService.PathResult shortestPath = shortestPathService.findShortestPath(originNode, destNode);

        // Step 3: Calculate ETA with traffic
        ETAService.ETAResult eta = etaService.calculateETA(shortestPath.getPath());

        // Step 4: Build route object
        Route primaryRoute = new Route(
                "route_1",
                shortestPath.getPath(),
                shortestPath.getDistanceKm(),
                eta.getTrafficEtaMinutes(),
                travelMode,
                false, // tolls
                true   // highways
        );

        // Step 5: Rank and filter
        List<Route> routes = new ArrayList<>();
        routes.add(primaryRoute);

        // In production, we would compute multiple alternative routes
        // and rank them. For demo, we show the primary route.
        System.out.printf("%n  [NavigationService] Route found: %.1f km, ETA: %.0f min%n",
                primaryRoute.getDistanceKm(), primaryRoute.getEtaMinutes());

        return routes;
    }

    /** Resolve geocoding result to nearest road network node */
    private String resolveToNode(GeocodingResult result) {
        // Simplified: map known places to graph nodes
        String addr = result.getAddress().toLowerCase();
        if (addr.contains("mountain view")) return "mountain_view";
        if (addr.contains("cupertino")) return "cupertino";
        if (addr.contains("menlo park")) return "menlo_park";
        if (addr.contains("san francisco")) return "sf_downtown";
        if (addr.contains("seattle")) return "sf_downtown"; // fallback
        if (addr.contains("los angeles")) return "san_jose"; // fallback
        if (addr.contains("new york")) return "sf_downtown"; // fallback
        return "sf_downtown";
    }
}
