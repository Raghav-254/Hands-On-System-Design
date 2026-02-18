package com.bookmyshow.service;

import com.bookmyshow.event.BookingEvent;
import com.bookmyshow.event.EventBus;
import com.bookmyshow.model.Hold;
import com.bookmyshow.storage.SeatInventoryDB;
import java.util.List;

/**
 * Background process that releases expired holds.
 * In production: cron job running every 30-60 sec.
 * Processes in batches to avoid thundering herd.
 */
public class HoldManager {

    private final SeatInventoryDB seatDB;
    private final EventBus eventBus;

    public HoldManager(SeatInventoryDB seatDB, EventBus eventBus) {
        this.seatDB = seatDB;
        this.eventBus = eventBus;
    }

    /**
     * Scan for expired holds and release them.
     * Returns the number of holds released.
     */
    public int releaseExpiredHolds() {
        List<Hold> expired = seatDB.findExpiredHolds();
        if (expired.isEmpty()) {
            System.out.println("  [HOLD MANAGER] No expired holds found");
            return 0;
        }

        System.out.println("  [HOLD MANAGER] Found " + expired.size() + " expired hold(s)");
        int released = 0;
        for (Hold hold : expired) {
            seatDB.releaseHold(hold.getHoldId());
            released++;
            System.out.println("  [HOLD MANAGER] Released: " + hold.getHoldId() +
                " (seats " + hold.getSeatIds() + " → AVAILABLE)");
            eventBus.publish(new BookingEvent(BookingEvent.Type.BOOKING_RELEASED,
                hold.getShowId(), hold.getUserId(), hold.getHoldId(), null, hold.getSeatIds()));
        }
        return released;
    }
}
