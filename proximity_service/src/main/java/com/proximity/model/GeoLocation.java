package com.proximity.model;

/**
 * Represents a geographic location with latitude and longitude.
 * 
 * Key concepts:
 * - Latitude: -90 to +90 (North-South)
 * - Longitude: -180 to +180 (East-West)
 * - Earth radius: ~6,371 km
 */
public class GeoLocation {
    private final double latitude;
    private final double longitude;

    // Earth's radius in kilometers
    public static final double EARTH_RADIUS_KM = 6371.0;

    public GeoLocation(double latitude, double longitude) {
        if (latitude < -90 || latitude > 90) {
            throw new IllegalArgumentException("Latitude must be between -90 and 90");
        }
        if (longitude < -180 || longitude > 180) {
            throw new IllegalArgumentException("Longitude must be between -180 and 180");
        }
        this.latitude = latitude;
        this.longitude = longitude;
    }

    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }

    /**
     * Calculate distance to another location using Haversine formula.
     * This is the standard formula for calculating distance on a sphere.
     * 
     * @param other The other location
     * @return Distance in kilometers
     */
    public double distanceTo(GeoLocation other) {
        double lat1Rad = Math.toRadians(this.latitude);
        double lat2Rad = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);

        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS_KM * c;
    }

    /**
     * Calculate distance in meters (more precise for short distances).
     */
    public double distanceToMeters(GeoLocation other) {
        return distanceTo(other) * 1000;
    }

    @Override
    public String toString() {
        return String.format("(%.6f, %.6f)", latitude, longitude);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GeoLocation that = (GeoLocation) o;
        return Double.compare(that.latitude, latitude) == 0 &&
               Double.compare(that.longitude, longitude) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(latitude, longitude);
    }
}
