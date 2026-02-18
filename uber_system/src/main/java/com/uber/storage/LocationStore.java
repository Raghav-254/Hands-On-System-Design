package com.uber.storage;

import com.uber.model.Driver;
import com.uber.model.Location;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates Redis GEO — stores driver locations and supports "find nearby" (GEORADIUS).
 * In production: Redis GEOADD / GEORADIUS on a per-region cluster.
 */
public class LocationStore {

    private final Map<String, Driver> drivers = new LinkedHashMap<>();

    /** GEOADD equivalent: store or update a driver's location. */
    public void updateLocation(Driver driver, Location location) {
        driver.updateLocation(location);
        drivers.put(driver.getDriverId(), driver);
    }

    /** GEORADIUS equivalent: find drivers within radiusKm of the given point. */
    public List<Driver> findNearby(Location center, double radiusKm) {
        return drivers.values().stream()
            .filter(d -> d.getStatus() == Driver.Status.ONLINE)
            .filter(d -> d.getCurrentLocation().distanceKm(center) <= radiusKm)
            .sorted(Comparator.comparingDouble(
                d -> d.getCurrentLocation().distanceKm(center)))
            .collect(Collectors.toList());
    }

    /** Remove driver from GEO set (goes offline). */
    public void remove(String driverId) {
        drivers.remove(driverId);
    }

    public Driver getDriver(String driverId) {
        return drivers.get(driverId);
    }

    public int size() { return drivers.size(); }
}
