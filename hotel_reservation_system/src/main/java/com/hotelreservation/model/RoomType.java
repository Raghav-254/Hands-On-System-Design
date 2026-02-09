package com.hotelreservation.model;

/**
 * Represents a type of room in a hotel (e.g., Standard, Deluxe, Suite).
 * Hotels have room types, not individual rooms, for reservation purposes.
 */
public class RoomType {
    private final String roomTypeId;
    private final String hotelId;
    private final String name;           // "Standard King", "Deluxe Suite"
    private final String description;
    private final int totalRooms;        // total rooms of this type
    private final double overbookingFactor; // e.g., 1.10 for 10% overbooking

    public RoomType(String roomTypeId, String hotelId, String name,
                    String description, int totalRooms, double overbookingFactor) {
        this.roomTypeId = roomTypeId;
        this.hotelId = hotelId;
        this.name = name;
        this.description = description;
        this.totalRooms = totalRooms;
        this.overbookingFactor = overbookingFactor;
    }

    public String getRoomTypeId() { return roomTypeId; }
    public String getHotelId() { return hotelId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public int getTotalRooms() { return totalRooms; }
    public double getOverbookingFactor() { return overbookingFactor; }

    /** Max reservations allowed = totalRooms * overbookingFactor */
    public int getMaxReservations() {
        return (int) Math.floor(totalRooms * overbookingFactor);
    }

    @Override
    public String toString() {
        return String.format("RoomType{id=%s, hotel=%s, name='%s', rooms=%d, maxBookable=%d}",
                roomTypeId, hotelId, name, totalRooms, getMaxReservations());
    }
}
