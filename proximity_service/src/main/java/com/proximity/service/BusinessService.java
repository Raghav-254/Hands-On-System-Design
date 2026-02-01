package com.proximity.service;

import com.proximity.cache.GeospatialCache;
import com.proximity.geo.Geohash;
import com.proximity.model.Business;
import com.proximity.storage.BusinessDB;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Business Service - Handles business CRUD operations.
 * 
 * ARCHITECTURE NOTES:
 * ===================
 * - Write operations (low volume: ~100/day)
 * - Eventual consistency is acceptable (per requirements)
 * - Cache invalidation on updates
 * 
 * API ENDPOINTS:
 * - POST   /v1/businesses          - Create business
 * - GET    /v1/businesses/{id}     - Get business details
 * - PUT    /v1/businesses/{id}     - Update business
 * - DELETE /v1/businesses/{id}     - Delete business
 * 
 * EVENTUAL CONSISTENCY:
 * ====================
 * When a business is updated:
 * 1. Update primary database
 * 2. Invalidate cache
 * 3. (Optional) Publish event for async processing
 * 
 * Users might see stale data briefly, which is acceptable per requirements.
 * 
 * WRITE FLOW:
 *     Business Owner
 *          │
 *          ▼
 *    ┌─────────────┐
 *    │ API Gateway │
 *    └─────────────┘
 *          │
 *          ▼
 *   ┌──────────────┐      ┌─────────┐
 *   │ Business     │─────>│ Primary │
 *   │ Service      │      │   DB    │
 *   └──────────────┘      └─────────┘
 *          │                   │
 *          ▼                   ▼
 *   ┌──────────────┐      ┌─────────┐
 *   │ Cache        │      │ Read    │
 *   │ Invalidation │      │ Replicas│
 *   └──────────────┘      └─────────┘
 */
public class BusinessService {
    
    private final BusinessDB businessDB;
    private final GeospatialCache cache;
    private final AtomicLong idGenerator = new AtomicLong(1);
    
    public BusinessService(BusinessDB businessDB, GeospatialCache cache) {
        this.businessDB = businessDB;
        this.cache = cache;
    }
    
    /**
     * Create a new business.
     * 
     * API: POST /v1/businesses
     * Body: { "name": "...", "latitude": ..., "longitude": ..., ... }
     */
    public Business createBusiness(String name, double latitude, double longitude,
                                    String address, String city, String state, 
                                    String country, String category) {
        long businessId = idGenerator.getAndIncrement();
        
        Business business = new Business(businessId, name, latitude, longitude)
            .withAddress(address, city, state, country)
            .withCategory(category);
        
        // Save to database
        businessDB.addBusiness(business);
        
        // Cache the new business
        cache.putBusiness(business);
        
        // Invalidate geohash cell cache (new business in that cell)
        invalidateGeohashCache(latitude, longitude);
        
        System.out.println("[BusinessService] Created business: " + business);
        
        return business;
    }
    
    /**
     * Update an existing business.
     * 
     * API: PUT /v1/businesses/{id}
     */
    public Business updateBusiness(long businessId, String name, 
                                    Double latitude, Double longitude,
                                    String category) {
        Business existing = businessDB.getById(businessId);
        if (existing == null) {
            throw new IllegalArgumentException("Business not found: " + businessId);
        }
        
        // Track old location for cache invalidation
        double oldLat = existing.getLatitude();
        double oldLon = existing.getLongitude();
        boolean locationChanged = false;
        
        // Apply updates
        if (name != null) {
            existing.setName(name);
        }
        if (latitude != null && longitude != null) {
            existing.setLocation(latitude, longitude);
            locationChanged = true;
        }
        if (category != null) {
            // Would need setter, simplified here
        }
        
        // Update database
        businessDB.updateBusiness(existing);
        
        // Invalidate caches
        cache.invalidateBusiness(businessId);
        if (locationChanged) {
            invalidateGeohashCache(oldLat, oldLon);  // Old location
            invalidateGeohashCache(latitude, longitude);  // New location
        }
        
        System.out.println("[BusinessService] Updated business: " + existing);
        
        return existing;
    }
    
    /**
     * Delete a business.
     * 
     * API: DELETE /v1/businesses/{id}
     */
    public void deleteBusiness(long businessId) {
        Business business = businessDB.getById(businessId);
        if (business == null) {
            throw new IllegalArgumentException("Business not found: " + businessId);
        }
        
        // Delete from database
        businessDB.deleteBusiness(businessId);
        
        // Invalidate caches
        cache.invalidateBusiness(businessId);
        invalidateGeohashCache(business.getLatitude(), business.getLongitude());
        
        System.out.println("[BusinessService] Deleted business: " + businessId);
    }
    
    /**
     * Get business by ID.
     * 
     * API: GET /v1/businesses/{id}
     */
    public Business getBusinessById(long businessId) {
        // Try cache first
        Business business = cache.getBusiness(businessId);
        if (business != null) {
            return business;
        }
        
        // Cache miss
        business = businessDB.getById(businessId);
        if (business != null) {
            cache.putBusiness(business);
        }
        
        return business;
    }
    
    /**
     * Invalidate geohash cache for a location.
     * We invalidate at all precision levels.
     */
    private void invalidateGeohashCache(double latitude, double longitude) {
        for (int precision = 4; precision <= 6; precision++) {
            String geohash = Geohash.encode(latitude, longitude, precision);
            cache.invalidateGeohash(geohash);
        }
    }
    
    /**
     * Bulk import businesses (for initial data load).
     */
    public int bulkImport(List<Business> businesses) {
        int count = 0;
        for (Business business : businesses) {
            businessDB.addBusiness(business);
            count++;
        }
        System.out.println("[BusinessService] Imported " + count + " businesses");
        return count;
    }
}
