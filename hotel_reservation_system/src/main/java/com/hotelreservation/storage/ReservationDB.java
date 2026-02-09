package com.hotelreservation.storage;

import com.hotelreservation.model.Reservation;
import com.hotelreservation.model.RoomInventory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the Reservation database and Room Inventory table.
 * In production: MySQL/PostgreSQL with ACID transactions.
 *
 * Key tables:
 * - reservation: stores all reservations
 * - room_inventory: tracks availability per (hotel, room_type, date)
 */
public class ReservationDB {
    private final Map<String, Reservation> reservations = new LinkedHashMap<>();
    private final Map<String, RoomInventory> inventory = new LinkedHashMap<>();

    // Idempotency tracking: idempotency_key → reservation_id
    private final Map<String, String> idempotencyKeys = new HashMap<>();

    public void initInventory(RoomInventory inv) {
        inventory.put(inv.getKey(), inv);
    }

    public RoomInventory getInventory(String hotelId, String roomTypeId, String date) {
        String key = hotelId + ":" + roomTypeId + ":" + date;
        return inventory.get(key);
    }

    /**
     * Reserve with optimistic locking.
     * Returns true if reservation succeeded, false if version conflict or no availability.
     */
    public boolean reserveWithOptimisticLock(String hotelId, String roomTypeId,
                                              String date, int expectedVersion) {
        RoomInventory inv = getInventory(hotelId, roomTypeId, date);
        if (inv == null) return false;
        return inv.reserve(expectedVersion);
    }

    public void saveReservation(Reservation reservation) {
        reservations.put(reservation.getReservationId(), reservation);
        if (reservation.getIdempotencyKey() != null) {
            idempotencyKeys.put(reservation.getIdempotencyKey(), reservation.getReservationId());
        }
    }

    /** Check if this idempotency key was already used */
    public Reservation getByIdempotencyKey(String idempotencyKey) {
        String resId = idempotencyKeys.get(idempotencyKey);
        return resId != null ? reservations.get(resId) : null;
    }

    public Reservation getReservation(String reservationId) {
        return reservations.get(reservationId);
    }

    public List<Reservation> getReservationsByUser(String userId) {
        return reservations.values().stream()
                .filter(r -> r.getUserId().equals(userId))
                .collect(Collectors.toList());
    }

    public void cancelInventory(String hotelId, String roomTypeId, String date) {
        RoomInventory inv = getInventory(hotelId, roomTypeId, date);
        if (inv != null) {
            inv.cancelReservation();
        }
    }

    public Collection<RoomInventory> getAllInventory() {
        return inventory.values();
    }
}
