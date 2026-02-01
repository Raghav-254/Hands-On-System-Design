package com.proximity.model;

/**
 * Business entity representing a place/establishment.
 * 
 * In production DB schema:
 * CREATE TABLE business (
 *     business_id BIGINT PRIMARY KEY,
 *     name VARCHAR(255) NOT NULL,
 *     address VARCHAR(512),
 *     city VARCHAR(100),
 *     state VARCHAR(50),
 *     country VARCHAR(50),
 *     latitude DOUBLE NOT NULL,
 *     longitude DOUBLE NOT NULL,
 *     category VARCHAR(100),
 *     rating DECIMAL(2,1),
 *     review_count INT DEFAULT 0,
 *     is_open BOOLEAN DEFAULT TRUE,
 *     created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
 *     updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
 *     INDEX idx_category (category),
 *     INDEX idx_city (city)
 * );
 */
public class Business {
    private long businessId;
    private String name;
    private String address;
    private String city;
    private String state;
    private String country;
    private double latitude;
    private double longitude;
    private String category;  // restaurant, coffee, gas_station, etc.
    private double rating;
    private int reviewCount;
    private boolean isOpen;
    private long createdAt;
    private long updatedAt;

    public Business(long businessId, String name, double latitude, double longitude) {
        this.businessId = businessId;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.isOpen = true;
        this.createdAt = System.currentTimeMillis();
        this.updatedAt = this.createdAt;
    }

    // Builder pattern for optional fields
    public Business withAddress(String address, String city, String state, String country) {
        this.address = address;
        this.city = city;
        this.state = state;
        this.country = country;
        return this;
    }

    public Business withCategory(String category) {
        this.category = category;
        return this;
    }

    public Business withRating(double rating, int reviewCount) {
        this.rating = rating;
        this.reviewCount = reviewCount;
        return this;
    }

    // Getters
    public long getBusinessId() { return businessId; }
    public String getName() { return name; }
    public String getAddress() { return address; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getCountry() { return country; }
    public double getLatitude() { return latitude; }
    public double getLongitude() { return longitude; }
    public String getCategory() { return category; }
    public double getRating() { return rating; }
    public int getReviewCount() { return reviewCount; }
    public boolean isOpen() { return isOpen; }
    public long getCreatedAt() { return createdAt; }
    public long getUpdatedAt() { return updatedAt; }

    // Setters for updates
    public void setName(String name) { 
        this.name = name; 
        this.updatedAt = System.currentTimeMillis();
    }
    
    public void setLocation(double latitude, double longitude) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.updatedAt = System.currentTimeMillis();
    }

    public void setOpen(boolean isOpen) {
        this.isOpen = isOpen;
        this.updatedAt = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Business{id=%d, name='%s', loc=(%.4f,%.4f), category='%s', rating=%.1f}",
            businessId, name, latitude, longitude, category, rating);
    }
}
