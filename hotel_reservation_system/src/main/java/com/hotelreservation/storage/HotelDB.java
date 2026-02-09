package com.hotelreservation.storage;

import com.hotelreservation.model.Hotel;
import com.hotelreservation.model.RoomType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the Hotel database and Room Type database.
 * In production: MySQL/PostgreSQL with Redis cache in front.
 */
public class HotelDB {
    private final Map<String, Hotel> hotels = new LinkedHashMap<>();
    private final Map<String, RoomType> roomTypes = new LinkedHashMap<>();

    public void addHotel(Hotel hotel) {
        hotels.put(hotel.getHotelId(), hotel);
    }

    public void addRoomType(RoomType roomType) {
        roomTypes.put(roomType.getRoomTypeId(), roomType);
    }

    public Hotel getHotel(String hotelId) {
        return hotels.get(hotelId);
    }

    public List<RoomType> getRoomTypesByHotel(String hotelId) {
        return roomTypes.values().stream()
                .filter(rt -> rt.getHotelId().equals(hotelId))
                .collect(Collectors.toList());
    }

    public RoomType getRoomType(String roomTypeId) {
        return roomTypes.get(roomTypeId);
    }

    public Collection<Hotel> getAllHotels() {
        return hotels.values();
    }

    // Admin operations
    public void updateHotel(Hotel hotel) {
        hotels.put(hotel.getHotelId(), hotel);
    }

    public void removeHotel(String hotelId) {
        hotels.remove(hotelId);
        roomTypes.entrySet().removeIf(e -> e.getValue().getHotelId().equals(hotelId));
    }
}
