package com.googlemaps.service;

import com.googlemaps.model.GeocodingResult;
import com.googlemaps.storage.GeocodingDB;
import java.util.Optional;

/**
 * Geocoding Service - converts between addresses and coordinates.
 *
 * Two operations:
 * 1. Geocoding: "1600 Amphitheatre Parkway" → (37.4220, -122.0841)
 * 2. Reverse Geocoding: (37.4220, -122.0841) → "1600 Amphitheatre Parkway"
 *
 * Used by the Navigation Service to resolve origin/destination
 * when the user types an address instead of dropping a pin.
 */
public class GeocodingService {
    private final GeocodingDB geocodingDB;

    public GeocodingService(GeocodingDB geocodingDB) {
        this.geocodingDB = geocodingDB;
    }

    /** Convert address to coordinates */
    public Optional<GeocodingResult> geocode(String address) {
        System.out.printf("  [GeocodingService] Geocoding: \"%s\"%n", address);
        Optional<GeocodingResult> result = geocodingDB.geocode(address);
        result.ifPresentOrElse(
                r -> System.out.printf("  [GeocodingService] Found: %s%n", r),
                () -> System.out.printf("  [GeocodingService] Not found: \"%s\"%n", address)
        );
        return result;
    }

    /** Convert coordinates to nearest address */
    public Optional<GeocodingResult> reverseGeocode(double lat, double lng) {
        System.out.printf("  [GeocodingService] Reverse geocoding: (%.4f, %.4f)%n", lat, lng);
        return geocodingDB.reverseGeocode(lat, lng);
    }
}
