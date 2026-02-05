package com.nearbyfriends.model;

public class Location {
    private final double latitude;
    private final double longitude;
    private final long timestamp;
    
    public Location(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.timestamp = System.currentTimeMillis();
    }
    
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public long getTimestamp() { return timestamp; }
    
    /**
     * Calculate distance between two locations using Haversine formula
     * Returns distance in miles
     */
    public double distanceTo(Location other) {
        final int EARTH_RADIUS = 3959; // miles
        
        double lat1Rad = Math.toRadians(this.latitude);
        double lat2Rad = Math.toRadians(other.latitude);
        double deltaLat = Math.toRadians(other.latitude - this.latitude);
        double deltaLon = Math.toRadians(other.longitude - this.longitude);
        
        double a = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                   Math.cos(lat1Rad) * Math.cos(lat2Rad) *
                   Math.sin(deltaLon / 2) * Math.sin(deltaLon / 2);
        
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        
        return EARTH_RADIUS * c;
    }
    
    @Override
    public String toString() {
        return String.format("(%.4f, %.4f)", latitude, longitude);
    }
}
