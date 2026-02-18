package com.uber.service;

import com.uber.model.Driver;
import com.uber.model.Location;
import com.uber.storage.LocationStore;
import java.util.List;

/**
 * Receives driver location updates and serves "find nearby" queries.
 *
 * Write path: Driver App → POST /drivers/me/location → GEOADD to Redis
 * Read path:  Matching Service → "find drivers within X km" → GEORADIUS
 */
public class LocationService {

    private final LocationStore store;

    public LocationService(LocationStore store) {
        this.store = store;
    }

    /** Ingest location update from driver (simulates GEOADD). */
    public void updateDriverLocation(Driver driver, Location location) {
        store.updateLocation(driver, location);
    }

    /**
     * Find available drivers within radiusKm of center (simulates GEORADIUS).
     * Returns drivers sorted by distance (closest first).
     */
    public List<Driver> findNearbyDrivers(Location center, double radiusKm) {
        return store.findNearby(center, radiusKm);
    }

    /** Remove driver from index (went offline or TTL expired). */
    public void removeDriver(String driverId) {
        store.remove(driverId);
    }
}
