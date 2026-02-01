package com.proximity;

import com.proximity.cache.GeospatialCache;
import com.proximity.geo.Geohash;
import com.proximity.geo.QuadTree;
import com.proximity.model.*;
import com.proximity.service.*;
import com.proximity.storage.BusinessDB;
import java.util.*;

/**
 * Demonstration of the Proximity Service.
 * 
 * This demo simulates:
 * 1. Geohash encoding/decoding
 * 2. Business CRUD operations
 * 3. Nearby search functionality
 * 4. Caching behavior
 */
public class ProximityServiceDemo {
    
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════╗");
        System.out.println("║          PROXIMITY SERVICE DEMONSTRATION                  ║");
        System.out.println("║     (Based on Alex Xu Vol 2, Chapter 1)                  ║");
        System.out.println("╚═══════════════════════════════════════════════════════════╝\n");
        
        // Run all demos
        demonstrateGeohash();
        demonstrateQuadTree();
        demonstrateProximitySearch();
        demonstrateBusinessCRUD();
        demonstrateCaching();
    }
    
    /**
     * Demo 1: Geohash encoding and decoding
     */
    private static void demonstrateGeohash() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 1: GEOHASH ENCODING");
        System.out.println("═".repeat(60));
        
        // Famous locations
        Map<String, double[]> locations = new LinkedHashMap<>();
        locations.put("San Francisco", new double[]{37.7749, -122.4194});
        locations.put("New York City", new double[]{40.7128, -74.0060});
        locations.put("London", new double[]{51.5074, -0.1278});
        locations.put("Tokyo", new double[]{35.6762, 139.6503});
        
        for (Map.Entry<String, double[]> entry : locations.entrySet()) {
            String city = entry.getKey();
            double lat = entry.getValue()[0];
            double lon = entry.getValue()[1];
            
            System.out.printf("\n%s (%.4f, %.4f):%n", city, lat, lon);
            for (int precision = 4; precision <= 6; precision++) {
                String hash = Geohash.encode(lat, lon, precision);
                System.out.printf("  Precision %d: %s%n", precision, hash);
            }
        }
        
        // Show neighbors for SF
        System.out.println("\n--- Geohash Neighbors (for search) ---");
        String sfHash = Geohash.encode(37.7749, -122.4194, 6);
        List<String> neighbors = Geohash.getNeighborsAndSelf(sfHash);
        System.out.printf("Center geohash: %s%n", sfHash);
        System.out.printf("Neighbors: %s%n", neighbors);
        System.out.println("(We query all these cells to handle boundary cases!)");
    }
    
    /**
     * Demo 2: QuadTree spatial indexing
     */
    private static void demonstrateQuadTree() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 2: QUADTREE SPATIAL INDEXING");
        System.out.println("═".repeat(60));
        
        QuadTree tree = new QuadTree();
        
        // Add businesses in SF area (dense)
        System.out.println("\nAdding businesses in San Francisco area...");
        Random rand = new Random(42);
        for (int i = 0; i < 50; i++) {
            double lat = 37.7 + rand.nextDouble() * 0.1;  // SF area
            double lon = -122.5 + rand.nextDouble() * 0.15;
            tree.insert(new Business(i, "Business " + i, lat, lon));
        }
        
        // Add businesses in rural area (sparse)
        System.out.println("Adding businesses in rural area...");
        for (int i = 50; i < 60; i++) {
            double lat = 40.0 + rand.nextDouble() * 2.0;  // Large rural area
            double lon = -110.0 + rand.nextDouble() * 2.0;
            tree.insert(new Business(i, "Rural Business " + i, lat, lon));
        }
        
        System.out.println("\nQuadTree Statistics: " + tree.getStats());
        
        // Search in dense area
        System.out.println("\n--- Search in dense area (SF) ---");
        List<Business> sfResults = tree.search(37.75, -122.45, 5.0);  // 5km radius
        System.out.printf("Found %d businesses within 5km of SF downtown%n", sfResults.size());
        
        // Search in sparse area
        System.out.println("\n--- Search in sparse area (Rural) ---");
        List<Business> ruralResults = tree.search(41.0, -109.0, 50.0);  // 50km radius
        System.out.printf("Found %d businesses within 50km in rural area%n", ruralResults.size());
    }
    
    /**
     * Demo 3: Full proximity search flow
     */
    private static void demonstrateProximitySearch() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 3: PROXIMITY SEARCH FLOW");
        System.out.println("═".repeat(60));
        
        // Initialize system
        BusinessDB db = new BusinessDB();
        GeospatialCache cache = new GeospatialCache();
        LocationBasedService lbs = new LocationBasedService(db, cache);
        BusinessService bizService = new BusinessService(db, cache);
        
        // Add sample businesses in San Francisco
        System.out.println("\n--- Adding sample businesses ---");
        bizService.createBusiness("Blue Bottle Coffee", 37.7762, -122.4233,
            "66 Mint St", "San Francisco", "CA", "USA", "coffee");
        bizService.createBusiness("Tartine Bakery", 37.7614, -122.4241,
            "600 Guerrero St", "San Francisco", "CA", "USA", "bakery");
        bizService.createBusiness("Panda Express", 37.7851, -122.4089,
            "865 Market St", "San Francisco", "CA", "USA", "restaurant");
        bizService.createBusiness("Shell Gas Station", 37.7800, -122.4100,
            "100 Van Ness Ave", "San Francisco", "CA", "USA", "gas_station");
        bizService.createBusiness("Philz Coffee", 37.7642, -122.4339,
            "549 Castro St", "San Francisco", "CA", "USA", "coffee");
        bizService.createBusiness("In-N-Out Burger", 37.7870, -122.4089,
            "333 Jefferson St", "San Francisco", "CA", "USA", "restaurant");
        
        // Search: All businesses within 5km
        System.out.println("\n--- Search: All businesses within 5km ---");
        SearchRequest req1 = new SearchRequest(37.7749, -122.4194, 5000);
        SearchResult result1 = lbs.searchNearby(req1);
        System.out.println(result1);
        
        // Search: Only coffee shops within 2km
        System.out.println("\n--- Search: Coffee shops within 2km ---");
        SearchRequest req2 = new SearchRequest(37.7749, -122.4194, 2000, 
            "coffee", 10, "distance");
        SearchResult result2 = lbs.searchNearby(req2);
        System.out.println(result2);
        
        // Search: Restaurants sorted by rating
        System.out.println("\n--- Search: Restaurants sorted by rating ---");
        // First add ratings
        Business panda = db.getById(3);
        panda.withRating(4.2, 1500);
        Business inNOut = db.getById(6);
        inNOut.withRating(4.8, 3000);
        
        SearchRequest req3 = new SearchRequest(37.7749, -122.4194, 5000,
            "restaurant", 10, "rating");
        SearchResult result3 = lbs.searchNearby(req3);
        System.out.println(result3);
    }
    
    /**
     * Demo 4: Business CRUD operations
     */
    private static void demonstrateBusinessCRUD() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 4: BUSINESS CRUD OPERATIONS");
        System.out.println("═".repeat(60));
        
        BusinessDB db = new BusinessDB();
        GeospatialCache cache = new GeospatialCache();
        BusinessService bizService = new BusinessService(db, cache);
        ApiService api = new ApiService(
            new LocationBasedService(db, cache), 
            bizService
        );
        
        // CREATE
        System.out.println("\n--- CREATE Business ---");
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("name", "New Coffee Shop");
        createBody.put("latitude", 37.7800);
        createBody.put("longitude", -122.4200);
        createBody.put("category", "coffee");
        createBody.put("city", "San Francisco");
        
        ApiService.ApiResponse<Business> createResponse = api.createBusiness(createBody);
        System.out.println("Response: " + createResponse);
        
        long newBizId = createResponse.getData().getBusinessId();
        
        // READ
        System.out.println("\n--- READ Business ---");
        ApiService.ApiResponse<Business> readResponse = api.getBusinessById(newBizId);
        System.out.println("Response: " + readResponse);
        
        // UPDATE
        System.out.println("\n--- UPDATE Business ---");
        Map<String, Object> updateBody = new HashMap<>();
        updateBody.put("name", "Updated Coffee Shop Name");
        updateBody.put("latitude", 37.7810);
        updateBody.put("longitude", -122.4210);
        
        ApiService.ApiResponse<Business> updateResponse = api.updateBusiness(newBizId, updateBody);
        System.out.println("Response: " + updateResponse);
        
        // DELETE
        System.out.println("\n--- DELETE Business ---");
        ApiService.ApiResponse<Void> deleteResponse = api.deleteBusiness(newBizId);
        System.out.println("Response: " + deleteResponse);
        
        // Verify deletion
        ApiService.ApiResponse<Business> verifyResponse = api.getBusinessById(newBizId);
        System.out.println("After delete: " + verifyResponse);
    }
    
    /**
     * Demo 5: Caching behavior
     */
    private static void demonstrateCaching() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 5: CACHING BEHAVIOR");
        System.out.println("═".repeat(60));
        
        BusinessDB db = new BusinessDB();
        GeospatialCache cache = new GeospatialCache();
        LocationBasedService lbs = new LocationBasedService(db, cache);
        BusinessService bizService = new BusinessService(db, cache);
        
        // Add businesses
        for (int i = 1; i <= 10; i++) {
            bizService.createBusiness("Business " + i, 
                37.77 + (i * 0.001), 
                -122.42 + (i * 0.001),
                "", "San Francisco", "CA", "USA", "restaurant");
        }
        
        // First search - cache miss
        System.out.println("\n--- First Search (Cache Miss Expected) ---");
        SearchRequest request = new SearchRequest(37.7749, -122.4194, 5000);
        lbs.searchNearby(request);
        System.out.println("Cache stats after first search: " + cache.getStats());
        
        // Second search - cache hit
        System.out.println("\n--- Second Search (Cache Hit Expected) ---");
        lbs.searchNearby(request);
        System.out.println("Cache stats after second search: " + cache.getStats());
        
        // Third search - should be fast
        System.out.println("\n--- Third Search (Another Cache Hit) ---");
        long start = System.nanoTime();
        lbs.searchNearby(request);
        long duration = System.nanoTime() - start;
        System.out.printf("Search completed in %.2f ms%n", duration / 1_000_000.0);
        System.out.println("Cache stats: " + cache.getStats());
        
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO COMPLETE!");
        System.out.println("═".repeat(60));
    }
}
