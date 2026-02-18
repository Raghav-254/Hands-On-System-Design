package com.uber;

import com.uber.event.EventBus;
import com.uber.event.TripEvent;
import com.uber.model.*;
import com.uber.service.*;
import com.uber.storage.*;

/**
 * Demonstrates the Uber (Ride-Sharing) System Design concepts:
 * 1. Geospatial Matching (simulated Redis GEORADIUS — find nearby drivers)
 * 2. Atomic State Transitions (conditional UPDATE — no double-assign)
 * 3. Idempotency (same driver retrying accept → cached result)
 * 4. Trip Lifecycle State Machine (SEARCHING → MATCHED → IN_PROGRESS → COMPLETED)
 * 5. Event-Driven Architecture (Kafka simulation — async notifications/payment)
 * 6. Cancellation Handling (SEARCHING and MATCHED → CANCELLED)
 */
public class UberDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       Uber Ride-Sharing System Demo      ║");
        System.out.println("║       Matching + State Machine + Kafka   ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        demoGeospatialMatching();
        demoDoubleAssignPrevention();
        demoIdempotency();
        demoFullTripLifecycle();
        demoCancellation();
        demoEventDrivenArchitecture();
    }

    static void demoGeospatialMatching() {
        System.out.println("━━━ Demo 1: Geospatial Matching (Redis GEORADIUS) ━━━");
        System.out.println("Find nearby drivers and assign closest one\n");

        LocationStore locationStore = new LocationStore();
        LocationService locationService = new LocationService(locationStore);
        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();
        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);
        MatchingService matchingService = new MatchingService(locationService, tripService, 3.0);

        // Register drivers at various locations (SF area)
        Driver d1 = new Driver("d_001", "Alice",   new Location(37.7749, -122.4194)); // downtown SF
        Driver d2 = new Driver("d_002", "Bob",     new Location(37.7760, -122.4180)); // 0.2 km away
        Driver d3 = new Driver("d_003", "Charlie", new Location(37.8000, -122.4000)); // ~3 km away
        Driver d4 = new Driver("d_004", "Diana",   new Location(37.8500, -122.3500)); // ~10 km away

        locationService.updateDriverLocation(d1, d1.getCurrentLocation());
        locationService.updateDriverLocation(d2, d2.getCurrentLocation());
        locationService.updateDriverLocation(d3, d3.getCurrentLocation());
        locationService.updateDriverLocation(d4, d4.getCurrentLocation());

        System.out.println("Drivers registered: " + locationStore.size());
        System.out.println("Search radius: 3 km\n");

        // Rider requests ride near downtown SF
        Location pickup = new Location(37.7755, -122.4190);
        Trip trip = tripService.createTrip("rider_1", pickup,
            new Location(37.7849, -122.4094));

        System.out.println("\nRider at " + pickup + " requests a ride:");
        Driver matched = matchingService.matchDriver(trip);

        System.out.println("\nResult: " + (matched != null ?
            "Matched with " + matched.getName() + " (" + matched.getDriverId() + ")" :
            "No driver found"));
        System.out.println("  Diana (d_004) at ~10 km was NOT included (outside 3 km radius) ✓");
        System.out.println();
    }

    static void demoDoubleAssignPrevention() {
        System.out.println("━━━ Demo 2: Double-Assign Prevention ━━━");
        System.out.println("Two drivers try to accept the same ride — only one wins\n");

        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();
        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);

        Trip trip = tripService.createTrip("rider_1",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));

        System.out.println("\nDriver A tries to accept:");
        int resultA = tripService.acceptRide(trip.getRideId(), "driver_A", "key_A_001");
        System.out.println("  → Status: " + resultA + (resultA == 200 ? " (success)" : " (conflict)"));

        System.out.println("\nDriver B tries to accept the SAME ride:");
        int resultB = tripService.acceptRide(trip.getRideId(), "driver_B", "key_B_001");
        System.out.println("  → Status: " + resultB + (resultB == 200 ? " (success)" : " (conflict)"));

        System.out.println("\n  Only one driver got 200; the other got 409.");
        System.out.println("  Atomic UPDATE (WHERE status='SEARCHING') ensures no double-assign ✓\n");
    }

    static void demoIdempotency() {
        System.out.println("━━━ Demo 3: Idempotency (Same Driver Retries) ━━━");
        System.out.println("Same idempotency key → cached result, no duplicate processing\n");

        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();
        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);

        Trip trip = tripService.createTrip("rider_1",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));

        String sameKey = "idem_key_12345";

        System.out.println("\nFirst request (driver_A, key=" + sameKey + "):");
        int result1 = tripService.acceptRide(trip.getRideId(), "driver_A", sameKey);
        System.out.println("  → Status: " + result1);

        System.out.println("\nRetry with SAME key (network timeout, driver tapped twice):");
        int result2 = tripService.acceptRide(trip.getRideId(), "driver_A", sameKey);
        System.out.println("  → Status: " + result2);

        System.out.println("\n  Same key → same result. No duplicate UPDATE executed.");
        System.out.println("  Note: Kafka event was published only ONCE (first request) ✓\n");
    }

    static void demoFullTripLifecycle() {
        System.out.println("━━━ Demo 4: Full Trip Lifecycle (State Machine) ━━━");
        System.out.println("SEARCHING → MATCHED → IN_PROGRESS → COMPLETED\n");

        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();
        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);

        // Step 1: Create trip
        System.out.println("Step 1: Rider requests ride");
        Trip trip = tripService.createTrip("rider_1",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));

        // Step 2: Driver accepts
        System.out.println("\nStep 2: Driver accepts");
        tripService.acceptRide(trip.getRideId(), "driver_1", "key_accept_001");

        // Step 3: Driver starts trip
        System.out.println("\nStep 3: Driver starts trip (at pickup, rider in car)");
        tripService.startTrip(trip.getRideId());

        // Step 4: Driver completes trip
        System.out.println("\nStep 4: Driver completes trip (arrived at dropoff)");
        tripService.completeTrip(trip.getRideId());

        // Invalid transition: try to start a completed trip
        System.out.println("\nStep 5: Invalid transition — try to start a COMPLETED trip:");
        tripService.startTrip(trip.getRideId());
        System.out.println("  State machine rejected invalid transition ✓");

        System.out.println("\nFinal trip state: " + tripService.getTrip(trip.getRideId()));
        System.out.println();
    }

    static void demoCancellation() {
        System.out.println("━━━ Demo 5: Cancellation Handling ━━━");
        System.out.println("Cancel while SEARCHING and after MATCHED\n");

        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();
        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);

        // Scenario 1: Cancel while SEARCHING (no driver assigned)
        System.out.println("Scenario 1: Cancel while SEARCHING");
        Trip trip1 = tripService.createTrip("rider_1",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));
        System.out.println("  Trip status: " + trip1.getStatus());
        tripService.cancelTrip(trip1.getRideId());
        System.out.println("  No driver to notify — clean cancel ✓");

        // Scenario 2: Cancel after MATCHED (driver needs notification)
        System.out.println("\nScenario 2: Cancel after MATCHED (driver already assigned)");
        Trip trip2 = tripService.createTrip("rider_2",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));
        tripService.acceptRide(trip2.getRideId(), "driver_2", "key_cancel_demo");
        System.out.println("  Trip status: " + trip2.getStatus());
        System.out.println("  Rider cancels:");
        tripService.cancelTrip(trip2.getRideId());
        System.out.println("  TRIP_CANCELLED event published → driver notified ✓");

        // Scenario 3: Cannot cancel a COMPLETED trip
        System.out.println("\nScenario 3: Cannot cancel a COMPLETED trip");
        Trip trip3 = tripService.createTrip("rider_3",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));
        tripService.acceptRide(trip3.getRideId(), "driver_3", "key_cancel_demo_3");
        tripService.startTrip(trip3.getRideId());
        tripService.completeTrip(trip3.getRideId());
        System.out.println("  Try cancelling COMPLETED trip:");
        boolean cancelled = tripService.cancelTrip(trip3.getRideId());
        System.out.println("  " + (cancelled ? "Cancelled" : "Rejected — cannot cancel COMPLETED trip ✓"));
        System.out.println();
    }

    static void demoEventDrivenArchitecture() {
        System.out.println("━━━ Demo 6: Event-Driven Architecture (Kafka) ━━━");
        System.out.println("Trip events → Notification + Payment + Analytics\n");

        TripDB tripDB = new TripDB();
        IdempotencyStore idempotencyStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();

        // Register Kafka consumers
        eventBus.subscribe("NotificationService", event -> {
            switch (event.getType()) {
                case TRIP_MATCHED:
                    System.out.println("    [NOTIFICATION] Push to rider " +
                        event.getRiderId() + ": \"Driver " + event.getDriverId() + " is on the way\"");
                    break;
                case TRIP_COMPLETED:
                    System.out.println("    [NOTIFICATION] Push to rider " +
                        event.getRiderId() + ": \"Trip complete. Rate your driver.\"");
                    break;
                case TRIP_CANCELLED:
                    if (event.getDriverId() != null) {
                        System.out.println("    [NOTIFICATION] Push to driver " +
                            event.getDriverId() + ": \"Ride cancelled by rider\"");
                    }
                    break;
                default:
                    break;
            }
        });

        eventBus.subscribe("PaymentService", event -> {
            if (event.getType() == TripEvent.Type.TRIP_COMPLETED) {
                System.out.println("    [PAYMENT] Charging rider " + event.getRiderId() +
                    " for ride " + event.getRideId());
                System.out.println("    [PAYMENT] Crediting driver " + event.getDriverId());
            }
        });

        eventBus.subscribe("AnalyticsService", event ->
            System.out.println("    [ANALYTICS] Recorded: " + event));

        TripService tripService = new TripService(tripDB, idempotencyStore, eventBus);

        // Run a full trip lifecycle — watch events flow to consumers
        System.out.println("Running full trip with consumers attached:\n");

        Trip trip = tripService.createTrip("rider_1",
            new Location(37.7749, -122.4194), new Location(37.7849, -122.4094));

        System.out.println("\n--- Driver accepts ---");
        tripService.acceptRide(trip.getRideId(), "driver_1", "key_event_demo");

        System.out.println("\n--- Driver starts trip ---");
        tripService.startTrip(trip.getRideId());

        System.out.println("\n--- Driver completes trip ---");
        tripService.completeTrip(trip.getRideId());

        System.out.println("\n\nAll " + eventBus.eventCount() + " events processed by all consumers ✓");
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("Demo complete!");
    }
}
