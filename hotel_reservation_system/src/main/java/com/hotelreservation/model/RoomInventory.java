package com.hotelreservation.model;

/**
 * Tracks room availability for a specific room type on a specific date.
 * This is the key table for concurrency control.
 *
 * Schema: (hotel_id, room_type_id, date) → (total_inventory, total_reserved, version)
 */
public class RoomInventory {
    private final String hotelId;
    private final String roomTypeId;
    private final String date;           // e.g., "2024-07-15"
    private final int totalInventory;    // total rooms of this type (with overbooking)
    private int totalReserved;           // how many are currently reserved
    private int version;                 // for optimistic locking

    public RoomInventory(String hotelId, String roomTypeId, String date,
                         int totalInventory, int totalReserved) {
        this.hotelId = hotelId;
        this.roomTypeId = roomTypeId;
        this.date = date;
        this.totalInventory = totalInventory;
        this.totalReserved = totalReserved;
        this.version = 0;
    }

    public String getHotelId() { return hotelId; }
    public String getRoomTypeId() { return roomTypeId; }
    public String getDate() { return date; }
    public int getTotalInventory() { return totalInventory; }
    public int getTotalReserved() { return totalReserved; }
    public int getVersion() { return version; }

    public int getAvailable() { return totalInventory - totalReserved; }
    public boolean isAvailable() { return getAvailable() > 0; }

    /**
     * Reserve a room (optimistic locking).
     * Returns true if successful, false if no availability.
     */
    public boolean reserve(int expectedVersion) {
        if (this.version != expectedVersion) {
            return false; // version mismatch — someone else modified
        }
        if (!isAvailable()) {
            return false; // no rooms left
        }
        this.totalReserved++;
        this.version++;
        return true;
    }

    /** Cancel a reservation — free up one room */
    public void cancelReservation() {
        if (totalReserved > 0) {
            totalReserved--;
            version++;
        }
    }

    public String getKey() {
        return hotelId + ":" + roomTypeId + ":" + date;
    }

    @Override
    public String toString() {
        return String.format("Inventory{hotel=%s, type=%s, date=%s, reserved=%d/%d, available=%d, v=%d}",
                hotelId, roomTypeId, date, totalReserved, totalInventory, getAvailable(), version);
    }
}
