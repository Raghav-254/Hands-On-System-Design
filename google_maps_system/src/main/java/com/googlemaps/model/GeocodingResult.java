package com.googlemaps.model;

/**
 * Represents the result of a geocoding or reverse geocoding operation.
 *
 * Geocoding: Address → (lat, lng)
 *   "1600 Amphitheatre Parkway" → (37.4220, -122.0841)
 *
 * Reverse Geocoding: (lat, lng) → Address
 *   (37.4220, -122.0841) → "1600 Amphitheatre Parkway, Mountain View, CA"
 */
public class GeocodingResult {
    private final String address;
    private final double latitude;
    private final double longitude;
    private final String placeId;

    public GeocodingResult(String address, double latitude, double longitude, String placeId) {
        this.address = address;
        this.latitude = latitude;
        this.longitude = longitude;
        this.placeId = placeId;
    }

    public String getAddress() { return address; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getPlaceId() { return placeId; }

    @Override
    public String toString() {
        return String.format("\"%s\" ↔ (%.4f, %.4f) [%s]", address, latitude, longitude, placeId);
    }
}
