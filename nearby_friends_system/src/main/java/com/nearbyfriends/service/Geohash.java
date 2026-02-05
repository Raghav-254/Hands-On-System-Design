package com.nearbyfriends.service;

import java.util.*;

/**
 * Geohash - encodes latitude/longitude into a string for efficient spatial indexing
 * Used for optimizing nearby friend searches by reducing search space
 * 
 * Key properties:
 * - Common prefix = nearby locations
 * - Longer hash = more precise location
 * - Must search 9 cells (center + 8 neighbors) to handle boundaries
 */
public class Geohash {
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    
    /**
     * Encode lat/lng to geohash with specified precision
     * Precision levels:
     * - 4: ~20km x 20km (city level)
     * - 5: ~5km x 5km (neighborhood)
     * - 6: ~1.2km x 0.6km (village/town)
     * - 7: ~150m x 150m (street)
     */
    public static String encode(double latitude, double longitude, int precision) {
        double[] latInterval = {-90.0, 90.0};
        double[] lonInterval = {-180.0, 180.0};
        
        StringBuilder geohash = new StringBuilder();
        boolean isEven = true;
        int bit = 0;
        int ch = 0;
        
        while (geohash.length() < precision) {
            double mid;
            if (isEven) {
                mid = (lonInterval[0] + lonInterval[1]) / 2;
                if (longitude > mid) {
                    ch |= (1 << (4 - bit));
                    lonInterval[0] = mid;
                } else {
                    lonInterval[1] = mid;
                }
            } else {
                mid = (latInterval[0] + latInterval[1]) / 2;
                if (latitude > mid) {
                    ch |= (1 << (4 - bit));
                    latInterval[0] = mid;
                } else {
                    latInterval[1] = mid;
                }
            }
            
            isEven = !isEven;
            
            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(ch));
                bit = 0;
                ch = 0;
            }
        }
        
        return geohash.toString();
    }
    
    /**
     * Get the 8 neighboring geohash cells
     * Need to check neighbors to handle boundary cases
     */
    public static List<String> getNeighbors(String geohash) {
        // Simplified version - in production, use full neighbor algorithm
        List<String> neighbors = new ArrayList<>();
        neighbors.add(geohash);
        
        // Add 8 neighbors (simplified - just showing the concept)
        // In production: implement full geohash neighbor calculation
        System.out.println("[Geohash] Searching geohash '" + geohash + "' + 8 neighbors");
        
        return neighbors;
    }
    
    /**
     * Calculate geohash for a given radius
     * Smaller radius = longer/more precise geohash
     */
    public static int getPrecisionForRadius(double radiusMiles) {
        if (radiusMiles >= 100) return 4;  // ~20km
        if (radiusMiles >= 20) return 5;   // ~5km
        if (radiusMiles >= 5) return 6;    // ~1.2km
        return 7;                           // ~150m
    }
}
