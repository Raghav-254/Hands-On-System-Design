package com.bookmyshow.storage;

import com.bookmyshow.model.Hold;
import com.bookmyshow.model.Booking;
import com.bookmyshow.model.ShowSeat;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the seat inventory database (MySQL/PostgreSQL).
 * Source of truth for seat state: AVAILABLE / HELD / CONFIRMED.
 *
 * Provides atomic operations:
 * - holdSeats: all-or-nothing hold (pessimistic lock via synchronized)
 * - confirmHold: HELD → CONFIRMED
 * - releaseHold: HELD → AVAILABLE
 */
public class SeatInventoryDB {

    private final Map<String, ShowSeat> showSeats = new LinkedHashMap<>();
    private final Map<String, Hold> holds = new LinkedHashMap<>();
    private final Map<String, Booking> bookings = new LinkedHashMap<>();
    private int holdCounter = 0;
    private int bookingCounter = 0;

    /** Initialize seat inventory for a show. */
    public void initShowSeat(String showId, String seatId) {
        String key = showId + ":" + seatId;
        showSeats.put(key, new ShowSeat(showId, seatId));
    }

    public ShowSeat getShowSeat(String showId, String seatId) {
        return showSeats.get(showId + ":" + seatId);
    }

    public List<ShowSeat> getSeatsForShow(String showId) {
        return showSeats.values().stream()
            .filter(s -> s.getShowId().equals(showId))
            .collect(Collectors.toList());
    }

    /**
     * Atomic all-or-nothing hold (simulates SELECT ... FOR UPDATE in a transaction).
     * If ALL seats are AVAILABLE → set to HELD. If ANY is not → fail entirely.
     * synchronized = simulates DB row-level lock (only one thread at a time).
     */
    public synchronized Hold holdSeats(String showId, List<String> seatIds,
                                        String userId, long holdDurationMs, double totalPrice) {
        // Check all seats are AVAILABLE
        for (String seatId : seatIds) {
            ShowSeat seat = getShowSeat(showId, seatId);
            if (seat == null || seat.getStatus() != ShowSeat.Status.AVAILABLE) {
                // If expired hold, release it first (lazy release)
                if (seat != null && seat.isExpired()) {
                    seat.release();
                    System.out.println("    [LAZY RELEASE] Seat " + seatId + " hold expired, released");
                    continue;
                }
                return null; // at least one seat not available → fail all
            }
        }

        // All available → hold all
        String holdId = "h_" + String.format("%03d", ++holdCounter);
        long expiresAt = System.currentTimeMillis() + holdDurationMs;

        for (String seatId : seatIds) {
            ShowSeat seat = getShowSeat(showId, seatId);
            seat.setStatus(ShowSeat.Status.HELD);
            seat.setHoldId(holdId);
            seat.setUserId(userId);
            seat.setExpiresAt(expiresAt);
            seat.incrementVersion();
        }

        Hold hold = new Hold(holdId, showId, userId, seatIds, expiresAt, totalPrice);
        holds.put(holdId, hold);
        return hold;
    }

    /**
     * Confirm a hold: HELD → CONFIRMED. Atomic.
     * Validates: hold exists, is ACTIVE, not expired, belongs to user.
     */
    public synchronized Booking confirmHold(String holdId, String userId, String paymentId) {
        Hold hold = holds.get(holdId);
        if (hold == null) return null;
        if (hold.getStatus() != Hold.Status.ACTIVE) return null;
        if (!hold.getUserId().equals(userId)) return null;

        // Check not expired
        if (hold.isExpired()) {
            releaseHold(holdId);
            return null;
        }

        // Transition all seats: HELD → CONFIRMED
        String bookingId = "b_" + String.format("%03d", ++bookingCounter);
        for (String seatId : hold.getSeatIds()) {
            ShowSeat seat = getShowSeat(hold.getShowId(), seatId);
            if (seat.getStatus() != ShowSeat.Status.HELD || !holdId.equals(seat.getHoldId())) {
                return null; // inconsistency guard
            }
            seat.setStatus(ShowSeat.Status.CONFIRMED);
            seat.setBookingId(bookingId);
            seat.incrementVersion();
        }

        hold.setStatus(Hold.Status.CONFIRMED);

        Booking booking = new Booking(bookingId, holdId, hold.getShowId(), userId,
            hold.getSeatIds(), paymentId, hold.getTotalPrice());
        bookings.put(bookingId, booking);
        return booking;
    }

    /** Release a hold: HELD → AVAILABLE. Called by Hold Manager on expiry or user cancel. */
    public synchronized void releaseHold(String holdId) {
        Hold hold = holds.get(holdId);
        if (hold == null || hold.getStatus() != Hold.Status.ACTIVE) return;

        for (String seatId : hold.getSeatIds()) {
            ShowSeat seat = getShowSeat(hold.getShowId(), seatId);
            if (seat != null && holdId.equals(seat.getHoldId())) {
                seat.release();
            }
        }
        hold.setStatus(Hold.Status.EXPIRED);
    }

    /** Find all active holds that have expired (for cron-based Hold Manager). */
    public List<Hold> findExpiredHolds() {
        return holds.values().stream()
            .filter(h -> h.getStatus() == Hold.Status.ACTIVE && h.isExpired())
            .collect(Collectors.toList());
    }

    public Hold getHold(String holdId) { return holds.get(holdId); }
    public Booking getBooking(String bookingId) { return bookings.get(bookingId); }
}
