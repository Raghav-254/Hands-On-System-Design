package com.bookmyshow.service;

import com.bookmyshow.event.BookingEvent;
import com.bookmyshow.event.EventBus;
import com.bookmyshow.model.*;
import com.bookmyshow.storage.IdempotencyStore;
import com.bookmyshow.storage.IdempotencyStore.CachedResponse;
import com.bookmyshow.storage.SeatInventoryDB;
import java.util.List;

/**
 * Orchestrates the hold → payment → confirm flow.
 * Handles idempotency for confirm requests.
 * Publishes booking events to Kafka (EventBus).
 */
public class BookingService {

    private final SeatInventoryDB seatDB;
    private final IdempotencyStore idempotencyStore;
    private final EventBus eventBus;
    private final long holdDurationMs;

    public BookingService(SeatInventoryDB seatDB, IdempotencyStore idempotencyStore,
                          EventBus eventBus, long holdDurationMs) {
        this.seatDB = seatDB;
        this.idempotencyStore = idempotencyStore;
        this.eventBus = eventBus;
        this.holdDurationMs = holdDurationMs;
    }

    /**
     * Hold seats — all-or-nothing.
     * If any seat is not AVAILABLE, the entire hold fails.
     */
    public Hold holdSeats(String showId, List<String> seatIds, String userId) {
        double totalPrice = 0;
        for (String seatId : seatIds) {
            ShowSeat ss = seatDB.getShowSeat(showId, seatId);
            if (ss == null) {
                System.out.println("  [HOLD] Seat " + seatId + " does not exist");
                return null;
            }
            // Sum up prices (simplified; in production, look up from seats/shows table)
            totalPrice += 500;
        }

        Hold hold = seatDB.holdSeats(showId, seatIds, userId, holdDurationMs, totalPrice);

        if (hold == null) {
            System.out.println("  [HOLD] FAILED: one or more seats not available for show " + showId);
            return null;
        }

        System.out.println("  [HOLD] SUCCESS: " + hold);
        eventBus.publish(new BookingEvent(BookingEvent.Type.BOOKING_HELD,
            showId, userId, hold.getHoldId(), null, seatIds));
        return hold;
    }

    /**
     * Confirm booking — idempotency check → validate hold → payment → confirm.
     * Returns HTTP-like status: 200 (confirmed), 402 (payment failed),
     * 409 (conflict), 410 (hold expired).
     */
    public int confirmBooking(String holdId, String userId, String idempotencyKey) {
        // 1. Idempotency check
        CachedResponse cached = idempotencyStore.lookup(idempotencyKey);
        if (cached != null) {
            System.out.println("  [IDEMPOTENT] Duplicate key '" + idempotencyKey +
                "' → returning cached " + cached.statusCode + " (" + cached.body + ")");
            return cached.statusCode;
        }

        // 2. Validate hold
        Hold hold = seatDB.getHold(holdId);
        if (hold == null) {
            idempotencyStore.save(idempotencyKey, 404, "Hold not found");
            System.out.println("  [CONFIRM] Hold " + holdId + " not found");
            return 404;
        }
        if (!hold.getUserId().equals(userId)) {
            idempotencyStore.save(idempotencyKey, 403, "Not your hold");
            System.out.println("  [CONFIRM] Hold " + holdId + " does not belong to user " + userId);
            return 403;
        }
        if (hold.isExpired()) {
            seatDB.releaseHold(holdId);
            idempotencyStore.save(idempotencyKey, 410, "Hold expired");
            System.out.println("  [CONFIRM] Hold " + holdId + " has EXPIRED → seats released");
            eventBus.publish(new BookingEvent(BookingEvent.Type.BOOKING_RELEASED,
                hold.getShowId(), userId, holdId, null, hold.getSeatIds()));
            return 410;
        }

        // 3. Payment (simulated — always succeeds)
        String paymentId = "pay_" + System.currentTimeMillis();
        System.out.println("  [PAYMENT] Charging user " + userId +
            " $" + String.format("%.0f", hold.getTotalPrice()) + " ... SUCCESS (id=" + paymentId + ")");

        // 4. Confirm: HELD → CONFIRMED (atomic in same transaction as idempotency save)
        Booking booking = seatDB.confirmHold(holdId, userId, paymentId);

        if (booking == null) {
            // Hold was released between validation and confirm (race)
            idempotencyStore.save(idempotencyKey, 409, "Hold no longer valid");
            System.out.println("  [CONFIRM] CONFLICT: hold " + holdId + " no longer valid");
            return 409;
        }

        idempotencyStore.save(idempotencyKey, 200, booking.getBookingId());
        System.out.println("  [CONFIRM] SUCCESS: " + booking);

        eventBus.publish(new BookingEvent(BookingEvent.Type.BOOKING_CONFIRMED,
            hold.getShowId(), userId, holdId, booking.getBookingId(), hold.getSeatIds()));
        return 200;
    }

    /** Release hold manually (user cancels). */
    public void releaseHold(String holdId) {
        Hold hold = seatDB.getHold(holdId);
        if (hold == null || hold.getStatus() != Hold.Status.ACTIVE) {
            System.out.println("  [RELEASE] Hold " + holdId + " not found or not active");
            return;
        }
        seatDB.releaseHold(holdId);
        System.out.println("  [RELEASE] Hold " + holdId + " released → seats back to AVAILABLE");
        eventBus.publish(new BookingEvent(BookingEvent.Type.BOOKING_RELEASED,
            hold.getShowId(), hold.getUserId(), holdId, null, hold.getSeatIds()));
    }
}
