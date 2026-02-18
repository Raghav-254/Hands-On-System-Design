package com.bookmyshow.model;

import java.util.List;

public class Booking {
    public enum Status { CONFIRMED, CANCELLED }

    private final String bookingId;
    private final String holdId;
    private final String showId;
    private final String userId;
    private final List<String> seatIds;
    private final String paymentId;
    private final double totalAmount;
    private Status status;

    public Booking(String bookingId, String holdId, String showId, String userId,
                   List<String> seatIds, String paymentId, double totalAmount) {
        this.bookingId = bookingId;
        this.holdId = holdId;
        this.showId = showId;
        this.userId = userId;
        this.seatIds = seatIds;
        this.paymentId = paymentId;
        this.totalAmount = totalAmount;
        this.status = Status.CONFIRMED;
    }

    public String getBookingId() { return bookingId; }
    public String getHoldId() { return holdId; }
    public String getShowId() { return showId; }
    public String getUserId() { return userId; }
    public List<String> getSeatIds() { return seatIds; }
    public String getPaymentId() { return paymentId; }
    public double getTotalAmount() { return totalAmount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    @Override
    public String toString() {
        return String.format("Booking[%s hold=%s seats=%s status=%s $%.0f]",
            bookingId, holdId, seatIds, status, totalAmount);
    }
}
