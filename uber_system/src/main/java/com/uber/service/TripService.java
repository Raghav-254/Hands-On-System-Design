package com.uber.service;

import com.uber.event.EventBus;
import com.uber.event.TripEvent;
import com.uber.model.Location;
import com.uber.model.Trip;
import com.uber.storage.IdempotencyStore;
import com.uber.storage.IdempotencyStore.CachedResponse;
import com.uber.storage.TripDB;
import java.time.Instant;

/**
 * Single source of truth for trip state.
 * Enforces valid state transitions (state machine) and idempotency.
 * Publishes trip events to Kafka (EventBus) on every transition.
 *
 * State machine:
 *   SEARCHING → MATCHED → IN_PROGRESS → COMPLETED
 *   SEARCHING → CANCELLED
 *   MATCHED   → CANCELLED
 */
public class TripService {

    private final TripDB tripDB;
    private final IdempotencyStore idempotencyStore;
    private final EventBus eventBus;
    private int rideCounter = 0;

    public TripService(TripDB tripDB, IdempotencyStore idempotencyStore, EventBus eventBus) {
        this.tripDB = tripDB;
        this.idempotencyStore = idempotencyStore;
        this.eventBus = eventBus;
    }

    /** Create a new trip (status = SEARCHING). Called by API Gateway on rider request. */
    public Trip createTrip(String riderId, Location pickup, Location dropoff) {
        String rideId = "ride_" + String.format("%03d", ++rideCounter);
        Trip trip = new Trip(rideId, riderId, pickup, dropoff);
        tripDB.insert(trip);
        System.out.println("  [TRIP] Created: " + trip);
        return trip;
    }

    /**
     * Accept a ride — atomic transition SEARCHING → MATCHED.
     * Implements idempotency: same key + same driver → return cached result.
     * Different driver but ride already matched → 409 Conflict.
     *
     * Returns HTTP-like status: 200 (matched), 409 (conflict), 404 (not found).
     */
    public int acceptRide(String rideId, String driverId, String idempotencyKey) {
        // 1. Idempotency check (same driver retrying)
        CachedResponse cached = idempotencyStore.lookup(idempotencyKey);
        if (cached != null) {
            System.out.println("  [IDEMPOTENT] Duplicate key '" + idempotencyKey +
                "' → returning cached " + cached.statusCode);
            return cached.statusCode;
        }

        Trip trip = tripDB.findById(rideId);
        if (trip == null) {
            idempotencyStore.save(idempotencyKey, 404, "Trip not found");
            return 404;
        }

        // 2. Atomic conditional UPDATE: WHERE status = 'SEARCHING'
        boolean success = tripDB.atomicTransition(rideId, Trip.Status.SEARCHING,
                                                   Trip.Status.MATCHED, driverId);

        if (success) {
            trip.setMatchedAt(Instant.now());
            idempotencyStore.save(idempotencyKey, 200, "Matched with " + driverId);
            System.out.println("  [TRIP] MATCHED: " + trip);

            // 3. Publish TRIP_MATCHED to Kafka (via outbox in production)
            eventBus.publish(new TripEvent(
                TripEvent.Type.TRIP_MATCHED, rideId, trip.getRiderId(), driverId));
            return 200;
        } else {
            // Another driver already accepted (or trip was cancelled)
            idempotencyStore.save(idempotencyKey, 409, "Already matched");
            System.out.println("  [TRIP] CONFLICT: ride " + rideId +
                " already " + trip.getStatus() + " — driver " + driverId + " rejected");
            return 409;
        }
    }

    /** Start trip — MATCHED → IN_PROGRESS. Driver at pickup, rider in car. */
    public boolean startTrip(String rideId) {
        Trip trip = tripDB.findById(rideId);
        if (trip == null) return false;

        boolean success = tripDB.atomicTransition(rideId, Trip.Status.MATCHED,
                                                   Trip.Status.IN_PROGRESS);
        if (success) {
            trip.setStartedAt(Instant.now());
            System.out.println("  [TRIP] STARTED: " + trip);
            eventBus.publish(new TripEvent(
                TripEvent.Type.TRIP_STARTED, rideId, trip.getRiderId(), trip.getDriverId()));
        } else {
            System.out.println("  [TRIP] Cannot start: ride " + rideId +
                " is " + trip.getStatus() + " (expected MATCHED)");
        }
        return success;
    }

    /** Complete trip — IN_PROGRESS → COMPLETED. Driver ends trip. */
    public boolean completeTrip(String rideId) {
        Trip trip = tripDB.findById(rideId);
        if (trip == null) return false;

        boolean success = tripDB.atomicTransition(rideId, Trip.Status.IN_PROGRESS,
                                                   Trip.Status.COMPLETED);
        if (success) {
            trip.setCompletedAt(Instant.now());
            System.out.println("  [TRIP] COMPLETED: " + trip);
            eventBus.publish(new TripEvent(
                TripEvent.Type.TRIP_COMPLETED, rideId, trip.getRiderId(), trip.getDriverId()));
        } else {
            System.out.println("  [TRIP] Cannot complete: ride " + rideId +
                " is " + trip.getStatus() + " (expected IN_PROGRESS)");
        }
        return success;
    }

    /** Cancel trip — SEARCHING or MATCHED → CANCELLED. */
    public boolean cancelTrip(String rideId) {
        Trip trip = tripDB.findById(rideId);
        if (trip == null) return false;

        // Try cancelling from SEARCHING first, then from MATCHED
        boolean success = tripDB.atomicTransition(rideId, Trip.Status.SEARCHING,
                                                   Trip.Status.CANCELLED);
        if (!success) {
            success = tripDB.atomicTransition(rideId, Trip.Status.MATCHED,
                                               Trip.Status.CANCELLED);
        }

        if (success) {
            System.out.println("  [TRIP] CANCELLED: " + trip);
            eventBus.publish(new TripEvent(
                TripEvent.Type.TRIP_CANCELLED, rideId, trip.getRiderId(), trip.getDriverId()));
        } else {
            System.out.println("  [TRIP] Cannot cancel: ride " + rideId +
                " is " + trip.getStatus() + " (expected SEARCHING or MATCHED)");
        }
        return success;
    }

    public Trip getTrip(String rideId) {
        return tripDB.findById(rideId);
    }
}
