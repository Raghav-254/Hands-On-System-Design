package com.proximity.model;

import java.util.List;

/**
 * Search result containing nearby businesses.
 * 
 * API Response format:
 * {
 *   "total": 25,
 *   "businesses": [
 *     {
 *       "business_id": 123,
 *       "name": "Coffee Shop",
 *       "address": "123 Main St",
 *       "distance": 150.5,
 *       "rating": 4.5,
 *       "category": "coffee"
 *     },
 *     ...
 *   ]
 * }
 */
public class SearchResult {
    private final int total;
    private final List<BusinessDistance> businesses;

    public SearchResult(int total, List<BusinessDistance> businesses) {
        this.total = total;
        this.businesses = businesses;
    }

    public int getTotal() { return total; }
    public List<BusinessDistance> getBusinesses() { return businesses; }

    /**
     * Business with calculated distance from search point.
     */
    public static class BusinessDistance {
        private final Business business;
        private final double distanceMeters;

        public BusinessDistance(Business business, double distanceMeters) {
            this.business = business;
            this.distanceMeters = distanceMeters;
        }

        public Business getBusiness() { return business; }
        public double getDistanceMeters() { return distanceMeters; }

        public String getFormattedDistance() {
            if (distanceMeters < 1000) {
                return String.format("%.0f m", distanceMeters);
            } else {
                return String.format("%.1f km", distanceMeters / 1000);
            }
        }

        @Override
        public String toString() {
            return String.format("%s (%s away, rating: %.1f)", 
                business.getName(), getFormattedDistance(), business.getRating());
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found %d businesses:\n", total));
        for (int i = 0; i < businesses.size(); i++) {
            sb.append(String.format("  %d. %s\n", i + 1, businesses.get(i)));
        }
        return sb.toString();
    }
}
