package com.proximity.model;

/**
 * Search request for nearby businesses.
 * 
 * API: GET /v1/search/nearby?latitude=37.7749&longitude=-122.4194&radius=5000&category=restaurant
 */
public class SearchRequest {
    private final double latitude;
    private final double longitude;
    private final int radiusMeters;      // Search radius in meters
    private final String category;        // Optional filter
    private final int limit;              // Max results (default 20)
    private final String sortBy;          // "distance" or "rating"

    // Common radius options (in meters)
    public static final int RADIUS_500M = 500;
    public static final int RADIUS_1KM = 1000;
    public static final int RADIUS_2KM = 2000;
    public static final int RADIUS_5KM = 5000;
    public static final int RADIUS_10KM = 10000;
    public static final int RADIUS_20KM = 20000;

    public SearchRequest(double latitude, double longitude, int radiusMeters) {
        this(latitude, longitude, radiusMeters, null, 20, "distance");
    }

    public SearchRequest(double latitude, double longitude, int radiusMeters, 
                         String category, int limit, String sortBy) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.radiusMeters = radiusMeters;
        this.category = category;
        this.limit = limit;
        this.sortBy = sortBy;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public int getRadiusMeters() { return radiusMeters; }
    public String getCategory() { return category; }
    public int getLimit() { return limit; }
    public String getSortBy() { return sortBy; }

    public GeoLocation getLocation() {
        return new GeoLocation(latitude, longitude);
    }

    /**
     * Get radius in kilometers for distance calculations.
     */
    public double getRadiusKm() {
        return radiusMeters / 1000.0;
    }

    @Override
    public String toString() {
        return String.format("SearchRequest{loc=(%.4f,%.4f), radius=%dm, category='%s', limit=%d}",
            latitude, longitude, radiusMeters, category, limit);
    }
}
