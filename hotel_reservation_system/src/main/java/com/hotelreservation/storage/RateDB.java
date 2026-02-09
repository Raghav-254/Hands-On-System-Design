package com.hotelreservation.storage;

import java.util.*;

/**
 * Simulates the Rate database.
 * Stores dynamic pricing: room price varies by date.
 *
 * Key: "hotelId:roomTypeId:date" → price per night
 */
public class RateDB {
    private final Map<String, Double> rates = new LinkedHashMap<>();
    private final Map<String, Double> baseRates = new HashMap<>(); // fallback

    public void setRate(String hotelId, String roomTypeId, String date, double price) {
        String key = hotelId + ":" + roomTypeId + ":" + date;
        rates.put(key, price);
    }

    public void setBaseRate(String hotelId, String roomTypeId, double basePrice) {
        baseRates.put(hotelId + ":" + roomTypeId, basePrice);
    }

    /** Get price for a specific date (falls back to base rate) */
    public double getRate(String hotelId, String roomTypeId, String date) {
        String key = hotelId + ":" + roomTypeId + ":" + date;
        Double rate = rates.get(key);
        if (rate != null) return rate;
        // Fallback to base rate
        Double base = baseRates.get(hotelId + ":" + roomTypeId);
        return base != null ? base : 100.0; // default $100
    }

    /** Calculate total price for a date range */
    public double calculateTotalPrice(String hotelId, String roomTypeId,
                                       List<String> dates) {
        double total = 0;
        for (String date : dates) {
            total += getRate(hotelId, roomTypeId, date);
        }
        return total;
    }
}
