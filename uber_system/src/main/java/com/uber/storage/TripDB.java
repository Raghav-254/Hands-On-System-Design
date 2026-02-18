package com.uber.storage;

import com.uber.model.Trip;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates the Trip database (MySQL/PostgreSQL).
 * Provides atomic conditional updates for state transitions.
 */
public class TripDB {

    private final Map<String, Trip> trips = new LinkedHashMap<>();

    public void insert(Trip trip) {
        trips.put(trip.getRideId(), trip);
    }

    public Trip findById(String rideId) {
        return trips.get(rideId);
    }

    /**
     * Atomic conditional update — simulates:
     *   UPDATE trips SET status=newStatus, driver_id=driverId
     *   WHERE ride_id=rideId AND status=expectedStatus
     *
     * Returns true if 1 row affected (success), false if 0 rows (guard failed).
     * In a real DB, row-level lock ensures only one concurrent UPDATE wins.
     */
    public synchronized boolean atomicTransition(String rideId, Trip.Status expectedStatus,
                                                  Trip.Status newStatus, String driverId) {
        Trip trip = trips.get(rideId);
        if (trip == null) return false;
        if (trip.getStatus() != expectedStatus) return false;

        trip.setStatus(newStatus);
        if (driverId != null) {
            trip.setDriverId(driverId);
        }
        return true;
    }

    /**
     * Atomic conditional update (no driver assignment) — for start, complete, cancel.
     */
    public synchronized boolean atomicTransition(String rideId, Trip.Status expectedStatus,
                                                  Trip.Status newStatus) {
        return atomicTransition(rideId, expectedStatus, newStatus, null);
    }

    public int size() { return trips.size(); }
}
