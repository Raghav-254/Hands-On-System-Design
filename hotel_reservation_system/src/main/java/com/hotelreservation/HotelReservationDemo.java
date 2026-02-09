package com.hotelreservation;

import com.hotelreservation.model.*;
import com.hotelreservation.service.*;
import com.hotelreservation.storage.*;

import java.util.*;

/**
 * Demo: Hotel Reservation System
 *
 * Demonstrates:
 * 1. Hotel and room browsing (with caching)
 * 2. Dynamic pricing (different price per date)
 * 3. Room reservation with optimistic locking
 * 4. Overbooking support (110% capacity)
 * 5. Idempotent reservation API
 * 6. Cancellation with inventory release
 * 7. Concurrent booking conflict handling
 * 8. Back-of-envelope estimation
 */
public class HotelReservationDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Hotel Reservation System Demo             ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // Initialize databases
        HotelDB hotelDB = new HotelDB();
        ReservationDB reservationDB = new ReservationDB();
        RateDB rateDB = new RateDB();

        // Initialize services
        HotelService hotelService = new HotelService(hotelDB);
        ReservationService reservationService = new ReservationService(reservationDB, rateDB);

        // Seed data
        seedData(hotelDB, reservationDB, rateDB);

        // ============================================
        // Demo 1: Hotel & Room Browsing (with Cache)
        // ============================================
        System.out.println("\n========== DEMO 1: Hotel & Room Browsing ==========");

        System.out.println("\n--- First access (cache miss) ---");
        Hotel hotel = hotelService.getHotel("H001");
        System.out.println("  " + hotel);

        System.out.println("\n--- Second access (cache hit) ---");
        hotelService.getHotel("H001");

        System.out.println("\n--- Room types ---");
        List<RoomType> roomTypes = hotelService.getRoomTypes("H001");
        for (RoomType rt : roomTypes) {
            System.out.println("  " + rt);
        }

        // ============================================
        // Demo 2: Dynamic Pricing
        // ============================================
        System.out.println("\n========== DEMO 2: Dynamic Pricing ==========");

        List<String> dates = List.of("2024-07-15", "2024-07-16", "2024-07-17");
        System.out.println("  Prices for Standard King (H001):");
        for (String date : dates) {
            double price = rateDB.getRate("H001", "RT001", date);
            System.out.println("    " + date + " → $" + String.format("%.2f", price));
        }
        double total = rateDB.calculateTotalPrice("H001", "RT001", dates);
        System.out.println("  Total for 3 nights: $" + String.format("%.2f", total));

        // ============================================
        // Demo 3: Make Reservation (Optimistic Locking)
        // ============================================
        System.out.println("\n========== DEMO 3: Room Reservation ==========");

        Reservation res1 = reservationService.makeReservation(
                "H001", "RT001", "user_alice", dates, "idem-key-001");

        System.out.println("\n--- Inventory after booking ---");
        for (String date : dates) {
            RoomInventory inv = reservationDB.getInventory("H001", "RT001", date);
            System.out.println("  " + inv);
        }

        // ============================================
        // Demo 4: Idempotent Reservation
        // ============================================
        System.out.println("\n========== DEMO 4: Idempotent API ==========");
        System.out.println("Retrying same reservation (network retry scenario)...");

        Reservation res1Retry = reservationService.makeReservation(
                "H001", "RT001", "user_alice", dates, "idem-key-001");
        System.out.println("  Same reservation returned: " +
                (res1.getReservationId().equals(res1Retry.getReservationId())));

        // ============================================
        // Demo 5: Overbooking
        // ============================================
        System.out.println("\n========== DEMO 5: Overbooking ==========");

        RoomType standardKing = hotelDB.getRoomType("RT001");
        System.out.println("  Total rooms: " + standardKing.getTotalRooms());
        System.out.println("  Overbooking factor: " + standardKing.getOverbookingFactor());
        System.out.println("  Max bookable: " + standardKing.getMaxReservations());
        System.out.println("  (10 rooms × 1.10 = 11 max reservations per night)");

        // Book more rooms to show overbooking
        for (int i = 2; i <= 11; i++) {
            Reservation r = reservationService.makeReservation(
                    "H001", "RT001", "user_" + i,
                    List.of("2024-07-15"), "idem-key-overbook-" + i);
        }

        System.out.println("\n--- Inventory after overbooking ---");
        RoomInventory inv = reservationDB.getInventory("H001", "RT001", "2024-07-15");
        System.out.println("  " + inv);

        // Try one more — should fail
        System.out.println("\n--- Attempt booking when full (should fail) ---");
        Reservation overLimit = reservationService.makeReservation(
                "H001", "RT001", "user_overflow",
                List.of("2024-07-15"), "idem-key-overflow");
        System.out.println("  Result: " + (overLimit == null ? "REJECTED (no availability)" : overLimit));

        // ============================================
        // Demo 6: Cancellation
        // ============================================
        System.out.println("\n========== DEMO 6: Cancellation ==========");

        System.out.println("Before cancel:");
        System.out.println("  " + reservationDB.getInventory("H001", "RT001", "2024-07-15"));

        reservationService.cancelReservation(res1.getReservationId(), List.of("2024-07-15"));

        System.out.println("After cancel:");
        System.out.println("  " + reservationDB.getInventory("H001", "RT001", "2024-07-15"));
        System.out.println("  Reservation status: " + res1.getStatus());

        // ============================================
        // Demo 7: Back-of-Envelope Estimation
        // ============================================
        System.out.println("\n========== DEMO 7: Back-of-Envelope Estimation ==========");
        System.out.println("┌──────────────────────────────────────────────────────┐");
        System.out.println("│  Hotels:              5,000                          │");
        System.out.println("│  Total rooms:         1,000,000                      │");
        System.out.println("│  Occupancy rate:      70%                            │");
        System.out.println("│  Avg stay duration:   3 days                         │");
        System.out.println("│  Daily reservations:  (1M × 0.7) / 3 = ~240,000     │");
        System.out.println("│  Reservations/sec:    240,000 / 86,400 = ~3 TPS      │");
        System.out.println("│                                                      │");
        System.out.println("│  → Low write QPS! This is NOT a high-throughput      │");
        System.out.println("│    system. The challenge is CONCURRENCY, not scale.  │");
        System.out.println("│    Multiple users booking the same room at the same  │");
        System.out.println("│    time during peak events (concerts, holidays).     │");
        System.out.println("└──────────────────────────────────────────────────────┘");

        System.out.println("\n✓ Demo complete!");
    }

    private static void seedData(HotelDB hotelDB, ReservationDB reservationDB, RateDB rateDB) {
        // Hotels
        hotelDB.addHotel(new Hotel("H001", "Grand Plaza", "123 Main St", "New York", 5));
        hotelDB.addHotel(new Hotel("H002", "Beach Resort", "456 Ocean Ave", "Miami", 4));

        // Room types (10 standard kings with 10% overbooking = 11 max)
        hotelDB.addRoomType(new RoomType("RT001", "H001", "Standard King",
                "King bed, city view", 10, 1.10));
        hotelDB.addRoomType(new RoomType("RT002", "H001", "Deluxe Suite",
                "Suite with living room", 5, 1.10));

        // Initialize inventory for 3 dates
        for (String date : List.of("2024-07-15", "2024-07-16", "2024-07-17")) {
            reservationDB.initInventory(new RoomInventory("H001", "RT001", date, 11, 0));
            reservationDB.initInventory(new RoomInventory("H001", "RT002", date, 5, 0));
        }

        // Dynamic pricing
        rateDB.setBaseRate("H001", "RT001", 150.00);
        rateDB.setRate("H001", "RT001", "2024-07-15", 200.00); // Friday premium
        rateDB.setRate("H001", "RT001", "2024-07-16", 220.00); // Saturday peak
        rateDB.setRate("H001", "RT001", "2024-07-17", 180.00); // Sunday
    }
}
