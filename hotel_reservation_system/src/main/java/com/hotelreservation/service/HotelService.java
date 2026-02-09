package com.hotelreservation.service;

import com.hotelreservation.model.Hotel;
import com.hotelreservation.model.RoomType;
import com.hotelreservation.storage.HotelDB;

import java.util.*;

/**
 * Hotel Service — handles hotel and room info.
 * Read-heavy: cached in Redis in production.
 */
public class HotelService {
    private final HotelDB hotelDB;
    // Simulates Redis cache
    private final Map<String, Hotel> cache = new HashMap<>();

    public HotelService(HotelDB hotelDB) {
        this.hotelDB = hotelDB;
    }

    public Hotel getHotel(String hotelId) {
        // Check cache first
        Hotel cached = cache.get(hotelId);
        if (cached != null) {
            System.out.println("  [Cache HIT] Hotel " + hotelId);
            return cached;
        }
        // Cache miss — read from DB
        Hotel hotel = hotelDB.getHotel(hotelId);
        if (hotel != null) {
            cache.put(hotelId, hotel);
            System.out.println("  [Cache MISS] Hotel " + hotelId + " loaded from DB");
        }
        return hotel;
    }

    public List<RoomType> getRoomTypes(String hotelId) {
        return hotelDB.getRoomTypesByHotel(hotelId);
    }

    public void invalidateCache(String hotelId) {
        cache.remove(hotelId);
        System.out.println("  [Cache INVALIDATED] Hotel " + hotelId);
    }
}
