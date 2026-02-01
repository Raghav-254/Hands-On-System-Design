package com.proximity.storage;

import com.proximity.model.Business;
import com.proximity.geo.Geohash;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Business database with geospatial indexing.
 * 
 * PRODUCTION IMPLEMENTATION:
 * ==========================
 * 
 * Option 1: MySQL with Geohash Index
 * ----------------------------------
 * CREATE TABLE business (
 *     business_id BIGINT PRIMARY KEY,
 *     name VARCHAR(255),
 *     latitude DOUBLE,
 *     longitude DOUBLE,
 *     geohash_4 CHAR(4),  -- City level
 *     geohash_5 CHAR(5),  -- Neighborhood
 *     geohash_6 CHAR(6),  -- Street level
 *     category VARCHAR(100),
 *     ...
 *     INDEX idx_geohash_4 (geohash_4),
 *     INDEX idx_geohash_5 (geohash_5),
 *     INDEX idx_geohash_6 (geohash_6),
 *     INDEX idx_category_geohash (category, geohash_6)
 * );
 * 
 * Query example:
 * SELECT * FROM business 
 * WHERE geohash_6 IN ('9q8yy0', '9q8yy1', ...) 
 * AND category = 'restaurant';
 * 
 * Option 2: PostgreSQL with PostGIS
 * ----------------------------------
 * CREATE TABLE business (
 *     business_id BIGINT PRIMARY KEY,
 *     name VARCHAR(255),
 *     location GEOGRAPHY(POINT, 4326),
 *     ...
 * );
 * CREATE INDEX idx_location ON business USING GIST(location);
 * 
 * Query: SELECT * FROM business 
 *        WHERE ST_DWithin(location, ST_MakePoint(-122.4194, 37.7749)::geography, 5000);
 * 
 * Option 3: Elasticsearch
 * -----------------------
 * - geo_point field type
 * - geo_distance query for radius search
 * - Great for full-text + geo combined search
 * 
 * READ/WRITE RATIO:
 * =================
 * This is a READ-HEAVY system!
 * - Reads: 5000 QPS (search queries)
 * - Writes: ~100/day (business updates) - negligible
 * 
 * We can use read replicas aggressively.
 */
public class BusinessDB {
    
    // Primary storage: businessId -> Business
    private final Map<Long, Business> businesses = new ConcurrentHashMap<>();
    
    // Geohash index: geohash -> Set<businessId>
    // We maintain indices at multiple precision levels
    private final Map<String, Set<Long>> geohashIndex4 = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> geohashIndex5 = new ConcurrentHashMap<>();
    private final Map<String, Set<Long>> geohashIndex6 = new ConcurrentHashMap<>();
    
    // Category index: category -> Set<businessId>
    private final Map<String, Set<Long>> categoryIndex = new ConcurrentHashMap<>();
    
    /**
     * Add a new business.
     */
    public void addBusiness(Business business) {
        businesses.put(business.getBusinessId(), business);
        
        // Update geohash indices
        updateGeohashIndices(business, true);
        
        // Update category index
        if (business.getCategory() != null) {
            categoryIndex.computeIfAbsent(business.getCategory(), 
                k -> ConcurrentHashMap.newKeySet())
                .add(business.getBusinessId());
        }
        
        System.out.println("[BusinessDB] Added: " + business.getName() + 
            " at geohash=" + Geohash.encode(business.getLatitude(), business.getLongitude(), 6));
    }
    
    /**
     * Update a business (location change requires re-indexing).
     */
    public void updateBusiness(Business business) {
        Business existing = businesses.get(business.getBusinessId());
        if (existing != null) {
            // Remove from old geohash indices
            updateGeohashIndices(existing, false);
        }
        
        // Add with new location
        businesses.put(business.getBusinessId(), business);
        updateGeohashIndices(business, true);
        
        System.out.println("[BusinessDB] Updated: " + business.getName());
    }
    
    /**
     * Delete a business.
     */
    public void deleteBusiness(long businessId) {
        Business business = businesses.remove(businessId);
        if (business != null) {
            updateGeohashIndices(business, false);
            if (business.getCategory() != null) {
                Set<Long> categorySet = categoryIndex.get(business.getCategory());
                if (categorySet != null) {
                    categorySet.remove(businessId);
                }
            }
            System.out.println("[BusinessDB] Deleted: " + business.getName());
        }
    }
    
    /**
     * Get business by ID.
     */
    public Business getById(long businessId) {
        return businesses.get(businessId);
    }
    
    /**
     * Search businesses by geohash prefixes.
     * This is the core search method used by LBS.
     * 
     * SQL equivalent:
     * SELECT * FROM business WHERE geohash_6 IN (?, ?, ...) AND category = ?
     */
    public List<Business> searchByGeohashes(List<String> geohashes, String category) {
        Set<Long> candidateIds = new HashSet<>();
        
        // Determine which index to use based on geohash length
        int precision = geohashes.isEmpty() ? 6 : geohashes.get(0).length();
        Map<String, Set<Long>> index = switch (precision) {
            case 4 -> geohashIndex4;
            case 5 -> geohashIndex5;
            default -> geohashIndex6;
        };
        
        // Collect all business IDs from matching geohashes
        for (String geohash : geohashes) {
            Set<Long> ids = index.get(geohash);
            if (ids != null) {
                candidateIds.addAll(ids);
            }
        }
        
        // Apply category filter if specified
        if (category != null && !category.isEmpty()) {
            Set<Long> categoryIds = categoryIndex.get(category);
            if (categoryIds != null) {
                candidateIds.retainAll(categoryIds);
            } else {
                candidateIds.clear();
            }
        }
        
        // Fetch actual business objects
        return candidateIds.stream()
            .map(businesses::get)
            .filter(Objects::nonNull)
            .collect(Collectors.toList());
    }
    
    /**
     * Update geohash indices for a business.
     */
    private void updateGeohashIndices(Business business, boolean add) {
        String hash4 = Geohash.encode(business.getLatitude(), business.getLongitude(), 4);
        String hash5 = Geohash.encode(business.getLatitude(), business.getLongitude(), 5);
        String hash6 = Geohash.encode(business.getLatitude(), business.getLongitude(), 6);
        
        if (add) {
            geohashIndex4.computeIfAbsent(hash4, k -> ConcurrentHashMap.newKeySet())
                .add(business.getBusinessId());
            geohashIndex5.computeIfAbsent(hash5, k -> ConcurrentHashMap.newKeySet())
                .add(business.getBusinessId());
            geohashIndex6.computeIfAbsent(hash6, k -> ConcurrentHashMap.newKeySet())
                .add(business.getBusinessId());
        } else {
            removeFromIndex(geohashIndex4, hash4, business.getBusinessId());
            removeFromIndex(geohashIndex5, hash5, business.getBusinessId());
            removeFromIndex(geohashIndex6, hash6, business.getBusinessId());
        }
    }
    
    private void removeFromIndex(Map<String, Set<Long>> index, String key, long id) {
        Set<Long> set = index.get(key);
        if (set != null) {
            set.remove(id);
            if (set.isEmpty()) {
                index.remove(key);
            }
        }
    }
    
    /**
     * Get statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalBusinesses", businesses.size());
        stats.put("geohashCells_4", geohashIndex4.size());
        stats.put("geohashCells_5", geohashIndex5.size());
        stats.put("geohashCells_6", geohashIndex6.size());
        stats.put("categories", categoryIndex.keySet());
        return stats;
    }
}
