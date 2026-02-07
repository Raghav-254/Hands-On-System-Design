package com.googlemaps.model;

/**
 * Represents a geographic location with latitude and longitude.
 */
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

    @Override
    public String toString() {
        return String.format("(%.4f, %.4f)", latitude, longitude);
    }
}
