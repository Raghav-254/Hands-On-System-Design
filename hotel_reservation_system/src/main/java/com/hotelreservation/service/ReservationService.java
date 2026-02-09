package com.hotelreservation.service;

import com.hotelreservation.model.Reservation;
import com.hotelreservation.model.RoomInventory;
import com.hotelreservation.storage.RateDB;
import com.hotelreservation.storage.ReservationDB;

import java.util.*;

/**
 * Reservation Service — core booking logic.
 *
 * Handles:
 * - Check availability
 * - Make reservation (with optimistic locking + idempotency)
 * - Cancel reservation
 * - Payment coordination
 */
public class ReservationService {
    private final ReservationDB reservationDB;
    private final RateDB rateDB;
    private int nextReservationId = 1;

    public ReservationService(ReservationDB reservationDB, RateDB rateDB) {
        this.reservationDB = reservationDB;
        this.rateDB = rateDB;
    }

    /** Check room availability for a date range */
    public boolean checkAvailability(String hotelId, String roomTypeId, List<String> dates) {
        for (String date : dates) {
            RoomInventory inv = reservationDB.getInventory(hotelId, roomTypeId, date);
            if (inv == null || !inv.isAvailable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Make a reservation with idempotency and optimistic locking.
     *
     * Flow:
     * 1. Check idempotency key (prevent double booking)
     * 2. Check availability for all dates
     * 3. Calculate price
     * 4. Reserve inventory with optimistic lock (for each date)
     * 5. Create reservation record
     * 6. Process payment
     * 7. Confirm reservation
     */
    public Reservation makeReservation(String hotelId, String roomTypeId, String userId,
                                        List<String> dates, String idempotencyKey) {
        System.out.println("\n--- Making reservation ---");
        System.out.println("  Hotel: " + hotelId + ", Room: " + roomTypeId +
                ", User: " + userId + ", Dates: " + dates);

        // Step 1: Idempotency check
        Reservation existing = reservationDB.getByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            System.out.println("  [IDEMPOTENT] Returning existing reservation: " + existing.getReservationId());
            return existing;
        }

        // Step 2: Check availability
        if (!checkAvailability(hotelId, roomTypeId, dates)) {
            System.out.println("  [REJECTED] No availability for requested dates");
            return null;
        }

        // Step 3: Calculate price
        double totalPrice = rateDB.calculateTotalPrice(hotelId, roomTypeId, dates);
        System.out.println("  [PRICE] Total: $" + String.format("%.2f", totalPrice));

        // Step 4: Reserve inventory with optimistic locking
        List<Integer> savedVersions = new ArrayList<>();
        boolean allReserved = true;

        for (String date : dates) {
            RoomInventory inv = reservationDB.getInventory(hotelId, roomTypeId, date);
            int currentVersion = inv.getVersion();
            boolean success = reservationDB.reserveWithOptimisticLock(
                    hotelId, roomTypeId, date, currentVersion);

            if (!success) {
                System.out.println("  [CONFLICT] Optimistic lock failed for date " + date +
                        " (version mismatch — another user booked simultaneously)");
                allReserved = false;
                break;
            }
            savedVersions.add(currentVersion);
        }

        if (!allReserved) {
            // Rollback already reserved dates
            for (int i = 0; i < savedVersions.size(); i++) {
                reservationDB.cancelInventory(hotelId, roomTypeId, dates.get(i));
            }
            System.out.println("  [ROLLBACK] Released " + savedVersions.size() + " dates");
            return null;
        }

        // Step 5: Create reservation
        String reservationId = "RES-" + (nextReservationId++);
        Reservation reservation = new Reservation(reservationId, hotelId, roomTypeId,
                userId, dates.get(0), dates.get(dates.size() - 1),
                idempotencyKey, totalPrice);
        reservationDB.saveReservation(reservation);
        System.out.println("  [CREATED] Reservation " + reservationId + " (status: PENDING)");

        // Step 6: Process payment (simulated)
        boolean paymentSuccess = processPayment(userId, totalPrice);

        if (paymentSuccess) {
            // Step 7: Confirm
            reservation.confirm();
            System.out.println("  [CONFIRMED] Reservation " + reservationId);
        } else {
            // Rollback everything
            reservation.reject();
            for (String date : dates) {
                reservationDB.cancelInventory(hotelId, roomTypeId, date);
            }
            System.out.println("  [REJECTED] Payment failed, inventory released");
        }

        return reservation;
    }

    /** Cancel a reservation */
    public boolean cancelReservation(String reservationId, List<String> dates) {
        Reservation reservation = reservationDB.getReservation(reservationId);
        if (reservation == null) {
            System.out.println("  [ERROR] Reservation not found: " + reservationId);
            return false;
        }

        reservation.cancel();
        for (String date : dates) {
            reservationDB.cancelInventory(reservation.getHotelId(),
                    reservation.getRoomTypeId(), date);
        }
        System.out.println("  [CANCELLED] Reservation " + reservationId + ", inventory released");
        // In production: trigger refund via Payment Service
        return true;
    }

    private boolean processPayment(String userId, double amount) {
        // Simulate payment processing
        System.out.println("  [PAYMENT] Processing $" + String.format("%.2f", amount) +
                " for user " + userId + " ... SUCCESS");
        return true; // always succeeds in demo
    }
}
