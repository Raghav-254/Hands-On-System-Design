package com.bookmyshow.model;

import java.util.List;

public class Hold {
    public enum Status { ACTIVE, CONFIRMED, RELEASED, EXPIRED }

    private final String holdId;
    private final String showId;
    private final String userId;
    private final List<String> seatIds;
    private final long expiresAt;
    private final double totalPrice;
    private Status status;

    public Hold(String holdId, String showId, String userId, List<String> seatIds,
                long expiresAt, double totalPrice) {
        this.holdId = holdId;
        this.showId = showId;
        this.userId = userId;
        this.seatIds = seatIds;
        this.expiresAt = expiresAt;
        this.totalPrice = totalPrice;
        this.status = Status.ACTIVE;
    }

    public String getHoldId() { return holdId; }
    public String getShowId() { return showId; }
    public String getUserId() { return userId; }
    public List<String> getSeatIds() { return seatIds; }
    public long getExpiresAt() { return expiresAt; }
    public double getTotalPrice() { return totalPrice; }
    public Status getStatus() { return status; }

    public void setStatus(Status status) { this.status = status; }

    public boolean isExpired() {
        return status == Status.ACTIVE && System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        return String.format("Hold[%s show=%s user=%s seats=%s status=%s price=$%.0f]",
            holdId, showId, userId, seatIds, status, totalPrice);
    }
}
