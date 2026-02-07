package com.googlemaps.model;

import java.util.List;

/**
 * Represents a navigation route from origin to destination.
 *
 * The Route Planner Service generates multiple candidate routes,
 * ranks them, and returns the top-k routes to the user.
 */
public class Route {
    private final String routeId;
    private final List<String> waypoints; // Ordered list of nodes
    private final double distanceKm;
    private final double etaMinutes;
    private final String travelMode; // driving, walking, cycling, transit
    private final boolean hasTolls;
    private final boolean hasHighways;

    public Route(String routeId, List<String> waypoints, double distanceKm,
                 double etaMinutes, String travelMode, boolean hasTolls, boolean hasHighways) {
        this.routeId = routeId;
        this.waypoints = waypoints;
        this.distanceKm = distanceKm;
        this.etaMinutes = etaMinutes;
        this.travelMode = travelMode;
        this.hasTolls = hasTolls;
        this.hasHighways = hasHighways;
    }

    public String getRouteId() { return routeId; }
    public List<String> getWaypoints() { return waypoints; }
    public double getDistanceKm() { return distanceKm; }
    public double getEtaMinutes() { return etaMinutes; }
    public String getTravelMode() { return travelMode; }
    public boolean hasTolls() { return hasTolls; }
    public boolean hasHighways() { return hasHighways; }

    @Override
    public String toString() {
        return String.format("Route[%s] %.1fkm, ETA: %.0f min (%s)%s%s",
                routeId, distanceKm, etaMinutes, travelMode,
                hasTolls ? " [tolls]" : "", hasHighways ? " [highway]" : "");
    }
}
