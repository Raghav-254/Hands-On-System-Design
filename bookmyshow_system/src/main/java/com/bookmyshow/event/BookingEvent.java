package com.bookmyshow.event;

import java.util.List;

/**
 * Event published to Kafka topic "booking-events" on state transitions.
 * Consumed by Notification Service, Analytics Service.
 */
public class BookingEvent {
    public enum Type {
        BOOKING_HELD, BOOKING_CONFIRMED, BOOKING_RELEASED, BOOKING_CANCELLED
    }

    private final Type type;
    private final String showId;
    private final String userId;
    private final String holdId;
    private final String bookingId;
    private final List<String> seatIds;

    public BookingEvent(Type type, String showId, String userId, String holdId,
                        String bookingId, List<String> seatIds) {
        this.type = type;
        this.showId = showId;
        this.userId = userId;
        this.holdId = holdId;
        this.bookingId = bookingId;
        this.seatIds = seatIds;
    }

    public Type getType() { return type; }
    public String getShowId() { return showId; }
    public String getUserId() { return userId; }
    public String getHoldId() { return holdId; }
    public String getBookingId() { return bookingId; }
    public List<String> getSeatIds() { return seatIds; }

    @Override
    public String toString() {
        return String.format("BookingEvent[%s show=%s user=%s hold=%s booking=%s seats=%s]",
            type, showId, userId, holdId,
            bookingId != null ? bookingId : "none", seatIds);
    }
}
