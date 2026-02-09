package com.hotelreservation.model;

import java.time.Instant;

/**
 * Represents a hotel room reservation.
 */
public class Reservation {
    public enum Status {
        PENDING,     // Created, awaiting payment
        CONFIRMED,   // Paid and confirmed
        CANCELLED,   // Cancelled by user
        REJECTED     // Failed (no availability or payment failed)
    }

    private final String reservationId;
    private final String hotelId;
    private final String roomTypeId;
    private final String userId;
    private final String checkInDate;
    private final String checkOutDate;
    private final String idempotencyKey;  // prevents double booking
    private Status status;
    private double totalPrice;
    private final Instant createdAt;

    public Reservation(String reservationId, String hotelId, String roomTypeId,
                       String userId, String checkInDate, String checkOutDate,
                       String idempotencyKey, double totalPrice) {
        this.reservationId = reservationId;
        this.hotelId = hotelId;
        this.roomTypeId = roomTypeId;
        this.userId = userId;
        this.checkInDate = checkInDate;
        this.checkOutDate = checkOutDate;
        this.idempotencyKey = idempotencyKey;
        this.status = Status.PENDING;
        this.totalPrice = totalPrice;
        this.createdAt = Instant.now();
    }

    public String getReservationId() { return reservationId; }
    public String getHotelId() { return hotelId; }
    public String getRoomTypeId() { return roomTypeId; }
    public String getUserId() { return userId; }
    public String getCheckInDate() { return checkInDate; }
    public String getCheckOutDate() { return checkOutDate; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public Status getStatus() { return status; }
    public double getTotalPrice() { return totalPrice; }
    public Instant getCreatedAt() { return createdAt; }

    public void confirm() { this.status = Status.CONFIRMED; }
    public void cancel() { this.status = Status.CANCELLED; }
    public void reject() { this.status = Status.REJECTED; }

    @Override
    public String toString() {
        return String.format("Reservation{id=%s, hotel=%s, type=%s, user=%s, %s→%s, status=%s, $%.2f}",
                reservationId, hotelId, roomTypeId, userId,
                checkInDate, checkOutDate, status, totalPrice);
    }
}
