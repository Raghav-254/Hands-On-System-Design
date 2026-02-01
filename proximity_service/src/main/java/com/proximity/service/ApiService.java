package com.proximity.service;

import com.proximity.model.*;
import java.util.*;

/**
 * API Service - REST API layer for the Proximity Service.
 * 
 * ENDPOINTS:
 * ==========
 * 
 * Search APIs (LBS):
 * - GET /v1/search/nearby
 *   Params: latitude, longitude, radius, category, limit
 *   
 * Business APIs (Business Service):
 * - GET    /v1/businesses/{id}
 * - POST   /v1/businesses
 * - PUT    /v1/businesses/{id}
 * - DELETE /v1/businesses/{id}
 * 
 * API GATEWAY RESPONSIBILITIES:
 * ============================
 * - Authentication (JWT tokens)
 * - Rate limiting
 * - Request validation
 * - Load balancing to LBS/Business Service instances
 * - SSL termination
 * - Request/Response logging
 * 
 * SCALING:
 * ========
 *                    ┌─────────────────┐
 *                    │  Load Balancer  │
 *                    └────────┬────────┘
 *                             │
 *              ┌──────────────┼──────────────┐
 *              ▼              ▼              ▼
 *         ┌─────────┐    ┌─────────┐    ┌─────────┐
 *         │  LBS 1  │    │  LBS 2  │    │  LBS 3  │
 *         └─────────┘    └─────────┘    └─────────┘
 *              │              │              │
 *              └──────────────┼──────────────┘
 *                             ▼
 *                    ┌─────────────────┐
 *                    │  Redis Cache    │
 *                    └────────┬────────┘
 *                             │
 *              ┌──────────────┼──────────────┐
 *              ▼              ▼              ▼
 *         ┌─────────┐    ┌─────────┐    ┌─────────┐
 *         │ Primary │    │ Replica │    │ Replica │
 *         │   DB    │    │   DB    │    │   DB    │
 *         └─────────┘    └─────────┘    └─────────┘
 */
public class ApiService {
    
    private final LocationBasedService lbs;
    private final BusinessService businessService;
    
    public ApiService(LocationBasedService lbs, BusinessService businessService) {
        this.lbs = lbs;
        this.businessService = businessService;
    }
    
    // ==================== SEARCH APIs ====================
    
    /**
     * GET /v1/search/nearby
     * 
     * Query params:
     * - latitude (required): User's latitude
     * - longitude (required): User's longitude
     * - radius (optional): Search radius in meters, default 5000
     * - category (optional): Filter by business category
     * - limit (optional): Max results, default 20
     * - sort_by (optional): "distance" or "rating", default "distance"
     * 
     * Response:
     * {
     *   "total": 25,
     *   "businesses": [
     *     {
     *       "business_id": "123",
     *       "name": "Coffee Shop",
     *       "address": "123 Main St",
     *       "distance": 150.5,
     *       "rating": 4.5,
     *       "category": "coffee"
     *     }
     *   ]
     * }
     */
    public ApiResponse<SearchResult> searchNearby(Map<String, String> params, String clientId) {
        try {
            // Rate limiting check
            if (lbs.isRateLimited(clientId)) {
                return ApiResponse.error(429, "Rate limit exceeded");
            }
            
            // Validate required params
            if (!params.containsKey("latitude") || !params.containsKey("longitude")) {
                return ApiResponse.error(400, "latitude and longitude are required");
            }
            
            double latitude = Double.parseDouble(params.get("latitude"));
            double longitude = Double.parseDouble(params.get("longitude"));
            int radius = params.containsKey("radius") ? 
                Integer.parseInt(params.get("radius")) : 5000;
            String category = params.get("category");
            int limit = params.containsKey("limit") ? 
                Integer.parseInt(params.get("limit")) : 20;
            String sortBy = params.getOrDefault("sort_by", "distance");
            
            // Validate values
            if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
                return ApiResponse.error(400, "Invalid coordinates");
            }
            if (radius <= 0 || radius > 50000) {
                return ApiResponse.error(400, "Radius must be between 1 and 50000 meters");
            }
            
            SearchRequest request = new SearchRequest(
                latitude, longitude, radius, category, limit, sortBy
            );
            
            SearchResult result = lbs.searchNearby(request);
            
            return ApiResponse.success(result);
            
        } catch (NumberFormatException e) {
            return ApiResponse.error(400, "Invalid number format: " + e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }
    
    // ==================== BUSINESS APIs ====================
    
    /**
     * GET /v1/businesses/{id}
     */
    public ApiResponse<Business> getBusinessById(long businessId) {
        try {
            Business business = businessService.getBusinessById(businessId);
            if (business == null) {
                return ApiResponse.error(404, "Business not found");
            }
            return ApiResponse.success(business);
        } catch (Exception e) {
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }
    
    /**
     * POST /v1/businesses
     */
    public ApiResponse<Business> createBusiness(Map<String, Object> body) {
        try {
            // Validate required fields
            if (!body.containsKey("name") || !body.containsKey("latitude") 
                || !body.containsKey("longitude")) {
                return ApiResponse.error(400, "name, latitude, longitude are required");
            }
            
            String name = (String) body.get("name");
            double latitude = ((Number) body.get("latitude")).doubleValue();
            double longitude = ((Number) body.get("longitude")).doubleValue();
            String address = (String) body.getOrDefault("address", "");
            String city = (String) body.getOrDefault("city", "");
            String state = (String) body.getOrDefault("state", "");
            String country = (String) body.getOrDefault("country", "");
            String category = (String) body.get("category");
            
            Business business = businessService.createBusiness(
                name, latitude, longitude, address, city, state, country, category
            );
            
            return ApiResponse.success(business, 201);
            
        } catch (Exception e) {
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }
    
    /**
     * PUT /v1/businesses/{id}
     */
    public ApiResponse<Business> updateBusiness(long businessId, Map<String, Object> body) {
        try {
            String name = (String) body.get("name");
            Double latitude = body.containsKey("latitude") ? 
                ((Number) body.get("latitude")).doubleValue() : null;
            Double longitude = body.containsKey("longitude") ? 
                ((Number) body.get("longitude")).doubleValue() : null;
            String category = (String) body.get("category");
            
            Business business = businessService.updateBusiness(
                businessId, name, latitude, longitude, category
            );
            
            return ApiResponse.success(business);
            
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }
    
    /**
     * DELETE /v1/businesses/{id}
     */
    public ApiResponse<Void> deleteBusiness(long businessId) {
        try {
            businessService.deleteBusiness(businessId);
            return ApiResponse.success(null, 204);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(404, e.getMessage());
        } catch (Exception e) {
            return ApiResponse.error(500, "Internal error: " + e.getMessage());
        }
    }
    
    // ==================== Response wrapper ====================
    
    public static class ApiResponse<T> {
        private final int statusCode;
        private final T data;
        private final String error;
        
        private ApiResponse(int statusCode, T data, String error) {
            this.statusCode = statusCode;
            this.data = data;
            this.error = error;
        }
        
        public static <T> ApiResponse<T> success(T data) {
            return new ApiResponse<>(200, data, null);
        }
        
        public static <T> ApiResponse<T> success(T data, int statusCode) {
            return new ApiResponse<>(statusCode, data, null);
        }
        
        public static <T> ApiResponse<T> error(int statusCode, String message) {
            return new ApiResponse<>(statusCode, null, message);
        }
        
        public int getStatusCode() { return statusCode; }
        public T getData() { return data; }
        public String getError() { return error; }
        public boolean isSuccess() { return error == null; }
        
        @Override
        public String toString() {
            if (isSuccess()) {
                return String.format("ApiResponse{status=%d, data=%s}", statusCode, data);
            } else {
                return String.format("ApiResponse{status=%d, error='%s'}", statusCode, error);
            }
        }
    }
}
