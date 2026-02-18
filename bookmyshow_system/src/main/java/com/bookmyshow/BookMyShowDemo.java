package com.bookmyshow;

import com.bookmyshow.event.BookingEvent;
import com.bookmyshow.event.EventBus;
import com.bookmyshow.model.*;
import com.bookmyshow.service.*;
import com.bookmyshow.storage.*;

import java.util.*;

/**
 * Demonstrates the BookMyShow System Design concepts:
 * 1. Atomic Seat Hold (all-or-nothing; pessimistic lock prevents double booking)
 * 2. Double Booking Prevention (two users hold same seat — only one wins)
 * 3. Hold → Confirm Flow (hold, payment, confirm with idempotency)
 * 4. Hold Expiry (Hold Manager releases expired holds)
 * 5. Idempotency (same confirm retried → cached result, no double charge)
 * 6. Event-Driven Architecture (Kafka simulation — notifications, analytics)
 */
public class BookMyShowDemo {

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       BookMyShow System Demo             ║");
        System.out.println("║       Seat Booking + Hold + Kafka        ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        demoAtomicHold();
        demoDoubleBookingPrevention();
        demoAllOrNothingHold();
        demoHoldAndConfirm();
        demoHoldExpiry();
        demoIdempotency();
        demoEventDriven();
    }

    /** Setup: create a show with seats A1-A5. */
    static SeatInventoryDB setupShow(String showId) {
        SeatInventoryDB db = new SeatInventoryDB();
        for (int i = 1; i <= 5; i++) {
            db.initShowSeat(showId, "A" + i);
        }
        return db;
    }

    static void printSeats(SeatInventoryDB db, String showId) {
        System.out.print("  Seats: ");
        for (ShowSeat s : db.getSeatsForShow(showId)) {
            System.out.print(s + " ");
        }
        System.out.println();
    }

    static void demoAtomicHold() {
        System.out.println("━━━ Demo 1: Atomic Seat Hold ━━━");
        System.out.println("Hold seats A1, A2 for user_1. Verify they become HELD.\n");

        SeatInventoryDB db = setupShow("s_100");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        printSeats(db, "s_100");

        Hold hold = bookingService.holdSeats("s_100", List.of("A1", "A2"), "user_1");

        System.out.println("\nAfter hold:");
        printSeats(db, "s_100");
        System.out.println("  A1 and A2 are HELD; A3-A5 still AVAILABLE ✓\n");
    }

    static void demoDoubleBookingPrevention() {
        System.out.println("━━━ Demo 2: Double Booking Prevention ━━━");
        System.out.println("Two users try to hold seat A1 — only one wins.\n");

        SeatInventoryDB db = setupShow("s_200");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        System.out.println("User_1 holds A1:");
        Hold hold1 = bookingService.holdSeats("s_200", List.of("A1"), "user_1");
        System.out.println("  Result: " + (hold1 != null ? "SUCCESS" : "FAILED"));

        System.out.println("\nUser_2 tries to hold same seat A1:");
        Hold hold2 = bookingService.holdSeats("s_200", List.of("A1"), "user_2");
        System.out.println("  Result: " + (hold2 != null ? "SUCCESS" : "FAILED"));

        System.out.println("\n  Only user_1 got the hold. Atomic lock prevents double booking ✓\n");
    }

    static void demoAllOrNothingHold() {
        System.out.println("━━━ Demo 3: All-or-Nothing Hold ━━━");
        System.out.println("User needs A1+A2+A3 together. A2 is already held → entire hold fails.\n");

        SeatInventoryDB db = setupShow("s_300");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        // User_1 holds A2 first
        System.out.println("User_1 holds A2:");
        bookingService.holdSeats("s_300", List.of("A2"), "user_1");

        // User_2 tries to hold A1+A2+A3 (A2 is taken)
        System.out.println("\nUser_2 tries to hold A1, A2, A3 together:");
        Hold hold = bookingService.holdSeats("s_300", List.of("A1", "A2", "A3"), "user_2");
        System.out.println("  Result: " + (hold != null ? "SUCCESS" : "FAILED — entire hold rejected"));

        printSeats(db, "s_300");
        System.out.println("  A1 is still AVAILABLE (not partially held). All-or-nothing ✓\n");
    }

    static void demoHoldAndConfirm() {
        System.out.println("━━━ Demo 4: Full Hold → Payment → Confirm Flow ━━━");
        System.out.println("AVAILABLE → HELD → (payment) → CONFIRMED\n");

        SeatInventoryDB db = setupShow("s_400");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        // Step 1: Hold
        System.out.println("Step 1: Hold seats A1, A2, A3");
        Hold hold = bookingService.holdSeats("s_400", List.of("A1", "A2", "A3"), "user_1");
        printSeats(db, "s_400");

        // Step 2: Confirm (includes payment)
        System.out.println("\nStep 2: Confirm booking (payment + HELD → CONFIRMED)");
        int result = bookingService.confirmBooking(hold.getHoldId(), "user_1", "idem_key_001");
        System.out.println("  HTTP status: " + result);
        printSeats(db, "s_400");

        // Step 3: Try invalid action — hold a confirmed seat
        System.out.println("\nStep 3: Another user tries to hold confirmed seat A1:");
        Hold hold2 = bookingService.holdSeats("s_400", List.of("A1"), "user_2");
        System.out.println("  Result: " + (hold2 != null ? "SUCCESS" : "FAILED — seat is CONFIRMED ✓"));
        System.out.println();
    }

    static void demoHoldExpiry() throws InterruptedException {
        System.out.println("━━━ Demo 5: Hold Expiry (Hold Manager) ━━━");
        System.out.println("Hold with 1 sec TTL → expires → Hold Manager releases seats.\n");

        SeatInventoryDB db = setupShow("s_500");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        // Very short hold: 1 second (for demo purposes)
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 1_000);
        HoldManager holdManager = new HoldManager(db, eventBus);

        System.out.println("Hold seats A1, A2 (TTL = 1 sec):");
        Hold hold = bookingService.holdSeats("s_500", List.of("A1", "A2"), "user_1");
        printSeats(db, "s_500");

        System.out.println("\nWaiting 1.5 sec for hold to expire...");
        Thread.sleep(1500);

        System.out.println("\nHold Manager runs (simulates cron):");
        int released = holdManager.releaseExpiredHolds();
        System.out.println("  Released " + released + " hold(s)");
        printSeats(db, "s_500");
        System.out.println("  Seats A1, A2 are AVAILABLE again ✓");

        // User_2 can now hold the released seats
        BookingService svc2 = new BookingService(db, idempStore, eventBus, 600_000);
        System.out.println("\nUser_2 can now hold the released seats:");
        Hold hold2 = svc2.holdSeats("s_500", List.of("A1", "A2"), "user_2");
        System.out.println("  Result: " + (hold2 != null ? "SUCCESS ✓" : "FAILED"));
        System.out.println();
    }

    static void demoIdempotency() {
        System.out.println("━━━ Demo 6: Idempotency (Confirm Retry) ━━━");
        System.out.println("Same Idempotency-Key → cached result, no double charge.\n");

        SeatInventoryDB db = setupShow("s_600");
        EventBus eventBus = new EventBus();
        IdempotencyStore idempStore = new IdempotencyStore();
        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        Hold hold = bookingService.holdSeats("s_600", List.of("A1", "A2"), "user_1");
        String sameKey = "idem_confirm_xyz";

        System.out.println("\nFirst confirm (key=" + sameKey + "):");
        int result1 = bookingService.confirmBooking(hold.getHoldId(), "user_1", sameKey);
        System.out.println("  → Status: " + result1);

        System.out.println("\nRetry with SAME key (network timeout, user double-clicked):");
        int result2 = bookingService.confirmBooking(hold.getHoldId(), "user_1", sameKey);
        System.out.println("  → Status: " + result2);

        System.out.println("\n  Same key → same result. No duplicate charge. Kafka event only once ✓\n");
    }

    static void demoEventDriven() {
        System.out.println("━━━ Demo 7: Event-Driven Architecture (Kafka) ━━━");
        System.out.println("Booking events → Notification + Analytics consumers.\n");

        SeatInventoryDB db = setupShow("s_700");
        IdempotencyStore idempStore = new IdempotencyStore();
        EventBus eventBus = new EventBus();

        // Register consumers
        eventBus.subscribe("NotificationService", event -> {
            switch (event.getType()) {
                case BOOKING_HELD:
                    System.out.println("    [NOTIFICATION] Push to " + event.getUserId() +
                        ": \"Seats " + event.getSeatIds() + " held. Pay within 10 min.\"");
                    break;
                case BOOKING_CONFIRMED:
                    System.out.println("    [NOTIFICATION] Email to " + event.getUserId() +
                        ": \"Booking " + event.getBookingId() + " confirmed! Seats: " +
                        event.getSeatIds() + "\"");
                    break;
                case BOOKING_RELEASED:
                    System.out.println("    [NOTIFICATION] Push to " + event.getUserId() +
                        ": \"Your hold expired. Seats released.\"");
                    break;
                default:
                    break;
            }
        });

        eventBus.subscribe("AnalyticsService", event ->
            System.out.println("    [ANALYTICS] Recorded: " + event.getType() +
                " show=" + event.getShowId() + " seats=" + event.getSeatIds()));

        BookingService bookingService = new BookingService(db, idempStore, eventBus, 600_000);

        System.out.println("Full flow with consumers attached:\n");

        System.out.println("--- User holds seats ---");
        Hold hold = bookingService.holdSeats("s_700", List.of("A1", "A2", "A3"), "user_1");

        System.out.println("\n--- User confirms booking ---");
        bookingService.confirmBooking(hold.getHoldId(), "user_1", "idem_event_demo");

        System.out.println("\n\nAll " + eventBus.eventCount() + " events processed by all consumers ✓");
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("Demo complete!");
    }
}
