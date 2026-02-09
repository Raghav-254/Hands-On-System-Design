package com.hotelreservation.model;

/**
 * Represents a hotel in the chain.
 */
public class Hotel {
    private final String hotelId;
    private final String name;
    private final String address;
    private final String city;
    private final int starRating;

    public Hotel(String hotelId, String name, String address, String city, int starRating) {
        this.hotelId = hotelId;
        this.name = name;
        this.address = address;
        this.city = city;
        this.starRating = starRating;
    }

    public String getHotelId() { return hotelId; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getCity() { return city; }
    public int getStarRating() { return starRating; }

    @Override
    public String toString() {
        return String.format("Hotel{id=%s, name='%s', city=%s, stars=%d}", hotelId, name, city, starRating);
    }
}
