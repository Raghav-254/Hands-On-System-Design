package com.uber.service;

import com.uber.model.Driver;
import com.uber.model.Location;
import com.uber.model.Trip;
import java.util.List;

/**
 * Finds nearby available drivers and assigns the best one to a trip.
 * Called synchronously by Trip Service (critical path — rider is waiting).
 *
 * Push model: system picks the best driver (closest), calls Trip Service to assign.
 * Stateless — scales horizontally behind a load balancer.
 */
public class MatchingService {

    private final LocationService locationService;
    private final TripService tripService;
    private final double searchRadiusKm;

    public MatchingService(LocationService locationService, TripService tripService,
                           double searchRadiusKm) {
        this.locationService = locationService;
        this.tripService = tripService;
        this.searchRadiusKm = searchRadiusKm;
    }

    /**
     * Find a driver for the given trip. Push model:
     * 1. GEORADIUS for nearby ONLINE drivers
     * 2. Pick closest (best) driver
     * 3. Call Trip Service to assign (atomic UPDATE)
     *
     * Returns the assigned driver, or null if no driver found.
     */
    public Driver matchDriver(Trip trip) {
        Location pickup = trip.getPickup();

        List<Driver> nearby = locationService.findNearbyDrivers(pickup, searchRadiusKm);

        if (nearby.isEmpty()) {
            System.out.println("  [MATCH] No available drivers within " +
                searchRadiusKm + " km of " + pickup);
            return null;
        }

        System.out.println("  [MATCH] Found " + nearby.size() + " driver(s) near " + pickup + ":");
        for (Driver d : nearby) {
            double dist = d.getCurrentLocation().distanceKm(pickup);
            System.out.printf("    %s — %.2f km away%n", d, dist);
        }

        // Pick the closest driver (push model — system decides)
        Driver bestDriver = nearby.get(0);
        double dist = bestDriver.getCurrentLocation().distanceKm(pickup);
        System.out.printf("  [MATCH] Selected: %s (%.2f km away)%n",
            bestDriver.getDriverId(), dist);

        // Assign via Trip Service (atomic UPDATE)
        String idempotencyKey = "match_" + trip.getRideId() + "_" + bestDriver.getDriverId();
        int result = tripService.acceptRide(trip.getRideId(),
            bestDriver.getDriverId(), idempotencyKey);

        if (result == 200) {
            bestDriver.setStatus(Driver.Status.BUSY);
            return bestDriver;
        }

        System.out.println("  [MATCH] Assignment failed (status " + result + ")");
        return null;
    }
}
