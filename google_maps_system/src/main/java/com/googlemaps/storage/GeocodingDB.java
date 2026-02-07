package com.googlemaps.storage;

import com.googlemaps.model.GeocodingResult;
import java.util.*;

/**
 * Simulates the Geocoding Database.
 *
 * Stores mappings between addresses and coordinates.
 * In production: A specialized geospatial database with full-text search
 * for address matching and spatial indexing for reverse geocoding.
 */
public class GeocodingDB {
    private final Map<String, GeocodingResult> addressIndex = new HashMap<>();
    private final List<GeocodingResult> allLocations = new ArrayList<>();

    public GeocodingDB() {
        seed();
    }

    private void seed() {
        addEntry("1600 Amphitheatre Parkway, Mountain View, CA", 37.4220, -122.0841, "place_googleplex");
        addEntry("1 Hacker Way, Menlo Park, CA", 37.4845, -122.1477, "place_meta");
        addEntry("1 Apple Park Way, Cupertino, CA", 37.3349, -122.0090, "place_apple");
        addEntry("410 Terry Ave N, Seattle, WA", 47.6222, -122.3369, "place_amazon");
        addEntry("San Francisco, CA", 37.7749, -122.4194, "place_sf");
        addEntry("Los Angeles, CA", 34.0522, -118.2437, "place_la");
        addEntry("New York, NY", 40.7128, -74.0060, "place_nyc");
    }

    private void addEntry(String address, double lat, double lng, String placeId) {
        GeocodingResult result = new GeocodingResult(address, lat, lng, placeId);
        addressIndex.put(address.toLowerCase(), result);
        allLocations.add(result);
    }

    /** Geocode: Address → Coordinates */
    public Optional<GeocodingResult> geocode(String address) {
        return Optional.ofNullable(addressIndex.get(address.toLowerCase()));
    }

    /** Reverse Geocode: Coordinates → Nearest address */
    public Optional<GeocodingResult> reverseGeocode(double lat, double lng) {
        return allLocations.stream()
                .min(Comparator.comparingDouble(r ->
                        Math.pow(r.getLatitude() - lat, 2) + Math.pow(r.getLongitude() - lng, 2)));
    }
}
