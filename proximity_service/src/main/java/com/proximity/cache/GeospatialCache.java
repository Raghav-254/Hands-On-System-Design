package com.proximity.cache;

import com.proximity.model.Business;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geospatial cache for nearby search results.
 * 
 * CACHING STRATEGY:
 * =================
 * 
 * What to cache:
 * 1. Geohash -> Business IDs (most effective)
 *    - Key: "geo:6:9q8yyk" -> [id1, id2, id3, ...]
 *    - Cache entire geohash cells
 *    - Very high hit rate for popular areas
 * 
 * 2. Business details
 *    - Key: "biz:123" -> Business object
 *    - Cache individual business data
 * 
 * 3. Search results (less common)
 *    - Key: "search:37.77:-122.41:5000:restaurant"
 *    - Can be hard to cache due to many variations
 * 
 * WHY CACHE GEOHASH CELLS?
 * ========================
 * - Finite number of cells at each precision level
 * - At precision 6, Earth has ~40 million cells
 * - Active cells (with businesses) are much fewer
 * - Popular areas (Manhattan, SF downtown) get cached
 * - Very high cache hit rate possible
 * 
 * PRODUCTION: Redis with Sorted Sets
 * ===================================
 * - Redis GEOADD: Add locations with coordinates
 * - Redis GEORADIUS: Query by radius
 * - Built-in geospatial support!
 * 
 * Example:
 * GEOADD businesses -122.4194 37.7749 "biz:123"
 * GEORADIUS businesses -122.4194 37.7749 5 km WITHCOORD WITHDIST
 */
public class GeospatialCache {
    
    // Cache: geohash -> List of business IDs in that cell
    private final Map<String, CacheEntry<List<Long>>> geohashCache;
    
    // Cache: businessId -> Business object
    private final Map<Long, CacheEntry<Business>> businessCache;
    
    private final int maxGeohashEntries;
    private final int maxBusinessEntries;
    private final long ttlMillis;
    
    // Statistics
    private long geohashHits = 0;
    private long geohashMisses = 0;
    private long businessHits = 0;
    private long businessMisses = 0;
    
    /**
     * Cache entry with TTL.
     */
    private static class CacheEntry<T> {
        final T value;
        final long expiresAt;
        long lastAccessed;
        
        CacheEntry(T value, long ttlMillis) {
            this.value = value;
            this.expiresAt = System.currentTimeMillis() + ttlMillis;
            this.lastAccessed = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
        
        void touch() {
            this.lastAccessed = System.currentTimeMillis();
        }
    }
    
    public GeospatialCache() {
        this(10000, 50000, 300000); // 10k geohashes, 50k businesses, 5min TTL
    }
    
    public GeospatialCache(int maxGeohashEntries, int maxBusinessEntries, long ttlMillis) {
        this.geohashCache = new ConcurrentHashMap<>();
        this.businessCache = new ConcurrentHashMap<>();
        this.maxGeohashEntries = maxGeohashEntries;
        this.maxBusinessEntries = maxBusinessEntries;
        this.ttlMillis = ttlMillis;
    }
    
    /**
     * Get business IDs for a geohash cell.
     */
    public List<Long> getGeohashBusinessIds(String geohash) {
        CacheEntry<List<Long>> entry = geohashCache.get(geohash);
        if (entry != null && !entry.isExpired()) {
            entry.touch();
            geohashHits++;
            return entry.value;
        }
        geohashMisses++;
        return null;
    }
    
    /**
     * Cache business IDs for a geohash cell.
     */
    public void putGeohashBusinessIds(String geohash, List<Long> businessIds) {
        evictIfNeeded(geohashCache, maxGeohashEntries);
        geohashCache.put(geohash, new CacheEntry<>(new ArrayList<>(businessIds), ttlMillis));
    }
    
    /**
     * Get a business by ID.
     */
    public Business getBusiness(long businessId) {
        CacheEntry<Business> entry = businessCache.get(businessId);
        if (entry != null && !entry.isExpired()) {
            entry.touch();
            businessHits++;
            return entry.value;
        }
        businessMisses++;
        return null;
    }
    
    /**
     * Cache a business.
     */
    public void putBusiness(Business business) {
        evictIfNeeded(businessCache, maxBusinessEntries);
        businessCache.put(business.getBusinessId(), new CacheEntry<>(business, ttlMillis));
    }
    
    /**
     * Cache multiple businesses.
     */
    public void putBusinesses(List<Business> businesses) {
        for (Business b : businesses) {
            putBusiness(b);
        }
    }
    
    /**
     * Invalidate cache for a geohash (when business is added/updated/deleted).
     */
    public void invalidateGeohash(String geohash) {
        geohashCache.remove(geohash);
        System.out.println("[Cache] Invalidated geohash: " + geohash);
    }
    
    /**
     * Invalidate a business.
     */
    public void invalidateBusiness(long businessId) {
        businessCache.remove(businessId);
    }
    
    /**
     * Simple LRU-style eviction when cache is full.
     */
    private <K, V> void evictIfNeeded(Map<K, CacheEntry<V>> cache, int maxSize) {
        if (cache.size() >= maxSize) {
            // Remove expired entries first
            cache.entrySet().removeIf(e -> e.getValue().isExpired());
            
            // If still too full, remove oldest entries
            if (cache.size() >= maxSize * 0.9) {
                List<Map.Entry<K, CacheEntry<V>>> entries = new ArrayList<>(cache.entrySet());
                entries.sort(Comparator.comparingLong(e -> e.getValue().lastAccessed));
                
                int toRemove = (int) (cache.size() * 0.2); // Remove 20%
                for (int i = 0; i < toRemove && i < entries.size(); i++) {
                    cache.remove(entries.get(i).getKey());
                }
            }
        }
    }
    
    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("geohashCacheSize", geohashCache.size());
        stats.put("geohashHitRate", geohashHits + geohashMisses > 0 ? 
            (double) geohashHits / (geohashHits + geohashMisses) : 0);
        stats.put("businessCacheSize", businessCache.size());
        stats.put("businessHitRate", businessHits + businessMisses > 0 ? 
            (double) businessHits / (businessHits + businessMisses) : 0);
        return stats;
    }
}
