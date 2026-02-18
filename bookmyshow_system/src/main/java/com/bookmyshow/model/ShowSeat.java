package com.bookmyshow.model;

/**
 * Represents one row in the show_seats table.
 * Primary key: (showId, seatId). Source of truth for seat availability.
 */
public class ShowSeat {
    public enum Status { AVAILABLE, HELD, CONFIRMED }

    private final String showId;
    private final String seatId;
    private Status status;
    private String holdId;
    private String userId;
    private long expiresAt;
    private String bookingId;
    private int version;

    public ShowSeat(String showId, String seatId) {
        this.showId = showId;
        this.seatId = seatId;
        this.status = Status.AVAILABLE;
        this.version = 1;
    }

    public String getShowId() { return showId; }
    public String getSeatId() { return seatId; }
    public Status getStatus() { return status; }
    public String getHoldId() { return holdId; }
    public String getUserId() { return userId; }
    public long getExpiresAt() { return expiresAt; }
    public String getBookingId() { return bookingId; }
    public int getVersion() { return version; }

    public void setStatus(Status status) { this.status = status; }
    public void setHoldId(String holdId) { this.holdId = holdId; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public void setBookingId(String bookingId) { this.bookingId = bookingId; }
    public void incrementVersion() { this.version++; }

    public boolean isExpired() {
        return status == Status.HELD && System.currentTimeMillis() > expiresAt;
    }

    public void release() {
        this.status = Status.AVAILABLE;
        this.holdId = null;
        this.userId = null;
        this.expiresAt = 0;
        this.version++;
    }

    @Override
    public String toString() {
        String extra = "";
        if (status == Status.HELD) extra = " hold=" + holdId + " user=" + userId;
        if (status == Status.CONFIRMED) extra = " booking=" + bookingId;
        return String.format("[%s %s%s]", seatId, status, extra);
    }
}
