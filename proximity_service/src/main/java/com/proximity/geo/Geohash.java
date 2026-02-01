package com.proximity.geo;

import com.proximity.model.GeoLocation;
import java.util.*;

/**
 * Geohash implementation for geospatial indexing.
 * 
 * WHAT IS GEOHASH?
 * ================
 * Geohash is a geocoding system that encodes latitude/longitude into a short string.
 * It recursively divides the world into smaller and smaller grids.
 * 
 * HOW IT WORKS:
 * 1. Start with the whole world: lat [-90, 90], lon [-180, 180]
 * 2. Divide into 32 cells (5 bits: 2 for lat, 3 for lon alternating)
 * 3. Each level adds more precision
 * 
 * GEOHASH PRECISION TABLE:
 * ========================
 * | Length | Cell Width  | Cell Height | Use Case              |
 * |--------|-------------|-------------|-----------------------|
 * | 4      | ~39.1 km    | ~19.5 km    | City-level            |
 * | 5      | ~4.9 km     | ~4.9 km     | Neighborhood          |
 * | 6      | ~1.2 km     | ~0.61 km    | Street-level          |
 * | 7      | ~153 m      | ~153 m      | Building-level        |
 * | 8      | ~38 m       | ~19 m       | Precise location      |
 * 
 * KEY PROPERTY: Geohashes with common prefix are geographically close!
 * Example: "9q8yy" and "9q8yz" are adjacent cells.
 * 
 * EDGE CASE (Boundary Problem):
 * Two locations very close to each other might have completely different
 * geohashes if they're on opposite sides of a grid boundary.
 * Solution: Always search neighboring cells too!
 */
public class Geohash {
    
    // Base32 encoding characters (Geohash standard)
    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";
    private static final Map<Character, Integer> BASE32_DECODE = new HashMap<>();
    
    static {
        for (int i = 0; i < BASE32.length(); i++) {
            BASE32_DECODE.put(BASE32.charAt(i), i);
        }
    }

    // Direction offsets for finding neighbors
    private static final int[][] NEIGHBOR_OFFSETS = {
        {-1, -1}, {-1, 0}, {-1, 1},  // Top row
        {0, -1},           {0, 1},    // Middle row (excluding center)
        {1, -1},  {1, 0},  {1, 1}     // Bottom row
    };

    /**
     * Encode latitude/longitude to geohash string.
     * 
     * @param latitude  Latitude (-90 to 90)
     * @param longitude Longitude (-180 to 180)
     * @param precision Length of geohash (4-8 typical)
     * @return Geohash string
     */
    public static String encode(double latitude, double longitude, int precision) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        
        StringBuilder geohash = new StringBuilder();
        boolean isLon = true;  // Alternate between lon and lat bits
        int bit = 0;
        int charIndex = 0;
        
        while (geohash.length() < precision) {
            double mid;
            if (isLon) {
                mid = (lonRange[0] + lonRange[1]) / 2;
                if (longitude >= mid) {
                    charIndex = (charIndex << 1) | 1;
                    lonRange[0] = mid;
                } else {
                    charIndex = charIndex << 1;
                    lonRange[1] = mid;
                }
            } else {
                mid = (latRange[0] + latRange[1]) / 2;
                if (latitude >= mid) {
                    charIndex = (charIndex << 1) | 1;
                    latRange[0] = mid;
                } else {
                    charIndex = charIndex << 1;
                    latRange[1] = mid;
                }
            }
            
            isLon = !isLon;
            bit++;
            
            if (bit == 5) {
                geohash.append(BASE32.charAt(charIndex));
                bit = 0;
                charIndex = 0;
            }
        }
        
        return geohash.toString();
    }

    /**
     * Decode geohash to bounding box (lat/lon ranges).
     * 
     * @param geohash The geohash string
     * @return double[4]: {minLat, maxLat, minLon, maxLon}
     */
    public static double[] decode(String geohash) {
        double[] latRange = {-90.0, 90.0};
        double[] lonRange = {-180.0, 180.0};
        boolean isLon = true;
        
        for (char c : geohash.toCharArray()) {
            int charIndex = BASE32_DECODE.get(c);
            
            for (int i = 4; i >= 0; i--) {
                int bit = (charIndex >> i) & 1;
                
                if (isLon) {
                    double mid = (lonRange[0] + lonRange[1]) / 2;
                    if (bit == 1) {
                        lonRange[0] = mid;
                    } else {
                        lonRange[1] = mid;
                    }
                } else {
                    double mid = (latRange[0] + latRange[1]) / 2;
                    if (bit == 1) {
                        latRange[0] = mid;
                    } else {
                        latRange[1] = mid;
                    }
                }
                isLon = !isLon;
            }
        }
        
        return new double[]{latRange[0], latRange[1], lonRange[0], lonRange[1]};
    }

    /**
     * Get center point of a geohash cell.
     */
    public static GeoLocation decodeToCenter(String geohash) {
        double[] bounds = decode(geohash);
        double centerLat = (bounds[0] + bounds[1]) / 2;
        double centerLon = (bounds[2] + bounds[3]) / 2;
        return new GeoLocation(centerLat, centerLon);
    }

    /**
     * Get all 8 neighboring geohashes + the center geohash.
     * This is CRITICAL for handling boundary problems!
     * 
     * Example: For geohash "9q8yy", returns 9 geohashes:
     * - "9q8yy" (center)
     * - 8 adjacent cells
     */
    public static List<String> getNeighborsAndSelf(String geohash) {
        List<String> neighbors = new ArrayList<>();
        neighbors.add(geohash);  // Include self
        
        // Get center of current geohash
        GeoLocation center = decodeToCenter(geohash);
        double[] bounds = decode(geohash);
        
        // Calculate cell size
        double cellHeight = bounds[1] - bounds[0];
        double cellWidth = bounds[3] - bounds[2];
        
        // Generate neighbors by offsetting center
        for (int[] offset : NEIGHBOR_OFFSETS) {
            double newLat = center.getLatitude() + (offset[0] * cellHeight);
            double newLon = center.getLongitude() + (offset[1] * cellWidth);
            
            // Handle edge cases at poles and date line
            if (newLat >= -90 && newLat <= 90 && newLon >= -180 && newLon <= 180) {
                String neighbor = encode(newLat, newLon, geohash.length());
                if (!neighbors.contains(neighbor)) {
                    neighbors.add(neighbor);
                }
            }
        }
        
        return neighbors;
    }

    /**
     * Get optimal geohash precision for a given search radius.
     * 
     * We want cells slightly larger than the radius to minimize
     * the number of cells we need to search.
     */
    public static int getPrecisionForRadius(int radiusMeters) {
        // Approximate cell sizes at different precisions
        if (radiusMeters >= 20000) return 4;      // ~39km cells
        if (radiusMeters >= 5000) return 5;       // ~5km cells
        if (radiusMeters >= 1000) return 6;       // ~1.2km cells
        if (radiusMeters >= 100) return 7;        // ~150m cells
        return 8;                                  // ~38m cells
    }

    /**
     * Get all geohash prefixes that might contain results for a search.
     * This handles the search radius by including multiple cells.
     * 
     * @param latitude  Center latitude
     * @param longitude Center longitude
     * @param radiusMeters Search radius
     * @return List of geohash prefixes to query
     */
    public static List<String> getSearchGeohashes(double latitude, double longitude, 
                                                   int radiusMeters) {
        int precision = getPrecisionForRadius(radiusMeters);
        String centerHash = encode(latitude, longitude, precision);
        
        // For larger radii, we might need to expand further
        // But neighbors + self typically covers most cases
        return getNeighborsAndSelf(centerHash);
    }

    /**
     * Check if a geohash is within a prefix.
     * Used for efficient database queries.
     */
    public static boolean hasPrefix(String geohash, String prefix) {
        return geohash.startsWith(prefix);
    }

    /**
     * Demonstration of geohash encoding.
     */
    public static void main(String[] args) {
        // San Francisco coordinates
        double lat = 37.7749;
        double lon = -122.4194;
        
        System.out.println("=== Geohash Demo ===");
        System.out.println("Location: San Francisco (" + lat + ", " + lon + ")");
        System.out.println();
        
        for (int precision = 4; precision <= 8; precision++) {
            String hash = encode(lat, lon, precision);
            double[] bounds = decode(hash);
            System.out.printf("Precision %d: %s%n", precision, hash);
            System.out.printf("  Bounds: lat[%.4f, %.4f] lon[%.4f, %.4f]%n",
                bounds[0], bounds[1], bounds[2], bounds[3]);
        }
        
        System.out.println();
        System.out.println("=== Neighbors for precision 6 ===");
        String hash6 = encode(lat, lon, 6);
        List<String> neighbors = getNeighborsAndSelf(hash6);
        System.out.println("Center: " + hash6);
        System.out.println("Neighbors: " + neighbors);
    }
}
