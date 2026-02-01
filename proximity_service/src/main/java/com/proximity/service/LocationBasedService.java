package com.proximity.service;

import com.proximity.cache.GeospatialCache;
import com.proximity.geo.Geohash;
import com.proximity.model.*;
import com.proximity.storage.BusinessDB;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Location-Based Service (LBS) - Handles nearby search queries.
 * 
 * ARCHITECTURE NOTES:
 * ===================
 * - STATELESS service (easy to scale horizontally)
 * - READ-ONLY (doesn't modify data)
 * - High QPS: 5000 queries/second
 * - Can add more instances behind load balancer
 * 
 * API ENDPOINT:
 * GET /v1/search/nearby?latitude=37.7749&longitude=-122.4194&radius=5000
 * 
 * SEARCH ALGORITHM:
 * 1. Convert (lat, lon, radius) to geohash prefix(es)
 * 2. Query cache/DB for businesses in those geohash cells
 * 3. Filter by exact distance (geohash gives approximate match)
 * 4. Apply category filter if specified
 * 5. Sort by distance or rating
 * 6. Return top N results
 * 
 * WHY TWO-STEP FILTERING?
 * =======================
 * Geohash cells are rectangular, but search radius is circular.
 * We first get all businesses in overlapping cells (fast, indexed),
 * then filter to exact distance (accurate but O(n)).
 * 
 *     +---+---+---+
 *     |   | X |   |    X = geohash cells that overlap with circle
 *     +---+---+---+    . = search radius circle
 *     | X |.X.|X  |    
 *     +---+---+---+    After geohash lookup, we filter to exact circle
 *     |   | X |   |
 *     +---+---+---+
 */
public class LocationBasedService {
    
    private final BusinessDB businessDB;
    private final GeospatialCache cache;
    
    // Rate limiting (simplified)
    private final Map<String, Long> rateLimitMap = new HashMap<>();
    private static final int RATE_LIMIT_PER_SECOND = 100;
    
    public LocationBasedService(BusinessDB businessDB, GeospatialCache cache) {
        this.businessDB = businessDB;
        this.cache = cache;
    }
    
    /**
     * Search for nearby businesses.
     * 
     * @param request Search parameters
     * @return Search results with distances
     */
    public SearchResult searchNearby(SearchRequest request) {
        System.out.println("\n[LBS] Searching: " + request);
        
        // Step 1: Determine optimal geohash precision and get search cells
        int precision = Geohash.getPrecisionForRadius(request.getRadiusMeters());
        List<String> searchGeohashes = Geohash.getSearchGeohashes(
            request.getLatitude(), 
            request.getLongitude(), 
            request.getRadiusMeters()
        );
        
        System.out.println("[LBS] Precision: " + precision + ", Geohashes: " + searchGeohashes);
        
        // Step 2: Get businesses from each geohash cell (cache first, then DB)
        List<Business> candidates = new ArrayList<>();
        List<String> cacheMisses = new ArrayList<>();
        
        for (String geohash : searchGeohashes) {
            List<Long> cachedIds = cache.getGeohashBusinessIds(geohash);
            if (cachedIds != null) {
                // Cache hit - get business details
                for (Long id : cachedIds) {
                    Business b = cache.getBusiness(id);
                    if (b == null) {
                        b = businessDB.getById(id);
                        if (b != null) cache.putBusiness(b);
                    }
                    if (b != null) candidates.add(b);
                }
            } else {
                cacheMisses.add(geohash);
            }
        }
        
        // Cache miss - query database
        if (!cacheMisses.isEmpty()) {
            List<Business> dbResults = businessDB.searchByGeohashes(
                cacheMisses, request.getCategory()
            );
            candidates.addAll(dbResults);
            
            // Populate cache
            Map<String, List<Long>> geohashToIds = new HashMap<>();
            for (Business b : dbResults) {
                String geohash = Geohash.encode(b.getLatitude(), b.getLongitude(), precision);
                geohashToIds.computeIfAbsent(geohash, k -> new ArrayList<>())
                    .add(b.getBusinessId());
                cache.putBusiness(b);
            }
            for (Map.Entry<String, List<Long>> entry : geohashToIds.entrySet()) {
                cache.putGeohashBusinessIds(entry.getKey(), entry.getValue());
            }
        }
        
        // Step 3: Filter by exact distance (geohash is approximate)
        GeoLocation searchPoint = request.getLocation();
        double radiusKm = request.getRadiusKm();
        
        List<SearchResult.BusinessDistance> results = candidates.stream()
            .map(b -> {
                GeoLocation bizLoc = new GeoLocation(b.getLatitude(), b.getLongitude());
                double distanceKm = searchPoint.distanceTo(bizLoc);
                return new SearchResult.BusinessDistance(b, distanceKm * 1000); // meters
            })
            .filter(bd -> bd.getDistanceMeters() <= request.getRadiusMeters())
            .collect(Collectors.toList());
        
        // Step 4: Apply category filter if not already applied
        if (request.getCategory() != null && !request.getCategory().isEmpty()) {
            results = results.stream()
                .filter(bd -> request.getCategory().equals(bd.getBusiness().getCategory()))
                .collect(Collectors.toList());
        }
        
        // Step 5: Sort by distance or rating
        if ("rating".equalsIgnoreCase(request.getSortBy())) {
            results.sort((a, b) -> Double.compare(
                b.getBusiness().getRating(), 
                a.getBusiness().getRating()
            ));
        } else {
            results.sort(Comparator.comparingDouble(SearchResult.BusinessDistance::getDistanceMeters));
        }
        
        // Step 6: Limit results
        int total = results.size();
        if (results.size() > request.getLimit()) {
            results = results.subList(0, request.getLimit());
        }
        
        System.out.println("[LBS] Found " + total + " businesses, returning " + results.size());
        
        return new SearchResult(total, results);
    }
    
    /**
     * Get business details by ID.
     * 
     * API: GET /v1/businesses/{id}
     */
    public Business getBusinessDetails(long businessId) {
        // Try cache first
        Business business = cache.getBusiness(businessId);
        if (business != null) {
            return business;
        }
        
        // Cache miss - query DB
        business = businessDB.getById(businessId);
        if (business != null) {
            cache.putBusiness(business);
        }
        
        return business;
    }
    
    /**
     * Simple rate limiting check.
     */
    public boolean isRateLimited(String clientId) {
        long now = System.currentTimeMillis() / 1000;
        String key = clientId + ":" + now;
        
        Long count = rateLimitMap.get(key);
        if (count == null) {
            rateLimitMap.put(key, 1L);
            // Clean old entries
            final long currentSecond = now;
            rateLimitMap.entrySet().removeIf(e -> {
                String[] parts = e.getKey().split(":");
                return Long.parseLong(parts[1]) < currentSecond - 1;
            });
            return false;
        }
        
        if (count >= RATE_LIMIT_PER_SECOND) {
            return true;
        }
        
        rateLimitMap.put(key, count + 1);
        return false;
    }
    
    /**
     * Get service statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("cacheStats", cache.getStats());
        stats.put("dbStats", businessDB.getStats());
        return stats;
    }
}
