package com.proximity.geo;

import com.proximity.model.Business;
import com.proximity.model.GeoLocation;
import java.util.*;

/**
 * QuadTree implementation for geospatial indexing.
 * 
 * WHAT IS A QUADTREE?
 * ===================
 * A tree where each internal node has exactly 4 children, representing
 * NE, NW, SE, SW quadrants of a 2D space.
 * 
 * KEY DIFFERENCE FROM GEOHASH:
 * ============================
 * | Aspect        | Geohash              | QuadTree               |
 * |---------------|----------------------|------------------------|
 * | Structure     | String-based grid    | Tree structure         |
 * | Memory        | In database (hashes) | In memory (tree)       |
 * | Update        | O(1) per business    | O(log n) rebuild       |
 * | Query         | Prefix search        | Tree traversal         |
 * | Best for      | Database indexing    | In-memory processing   |
 * | Density aware | No (fixed grid)      | Yes (adaptive)         |
 * 
 * HOW IT WORKS:
 * 1. Start with a bounding box covering the entire world
 * 2. When a node exceeds capacity, split into 4 children
 * 3. Keep splitting until each leaf has <= MAX_CAPACITY businesses
 * 4. Dense areas have deeper trees (more precision)
 * 
 * EXAMPLE:
 * - Manhattan (dense) might be split to depth 10+
 * - Rural Wyoming might stay at depth 3
 */
public class QuadTree {
    
    private static final int MAX_CAPACITY = 100;  // Max businesses per leaf
    private static final int MAX_DEPTH = 21;       // Maximum tree depth
    
    private final QuadTreeNode root;
    
    /**
     * Bounding box for a quadrant.
     */
    public static class BoundingBox {
        final double minLat, maxLat, minLon, maxLon;
        
        public BoundingBox(double minLat, double maxLat, double minLon, double maxLon) {
            this.minLat = minLat;
            this.maxLat = maxLat;
            this.minLon = minLon;
            this.maxLon = maxLon;
        }
        
        public boolean contains(double lat, double lon) {
            return lat >= minLat && lat <= maxLat && 
                   lon >= minLon && lon <= maxLon;
        }
        
        public boolean intersects(double lat, double lon, double radiusKm) {
            // Simplified check: see if the search circle could overlap this box
            GeoLocation center = new GeoLocation(lat, lon);
            GeoLocation boxCenter = new GeoLocation(
                (minLat + maxLat) / 2, 
                (minLon + maxLon) / 2
            );
            
            // Calculate diagonal of box (rough approximation)
            double boxDiagonalKm = new GeoLocation(minLat, minLon)
                .distanceTo(new GeoLocation(maxLat, maxLon));
            
            // If distance to center is less than radius + half diagonal, might intersect
            return center.distanceTo(boxCenter) <= radiusKm + boxDiagonalKm / 2;
        }
        
        // Create 4 child quadrants
        public BoundingBox[] subdivide() {
            double midLat = (minLat + maxLat) / 2;
            double midLon = (minLon + maxLon) / 2;
            
            return new BoundingBox[] {
                new BoundingBox(midLat, maxLat, minLon, midLon),  // NW
                new BoundingBox(midLat, maxLat, midLon, maxLon),  // NE
                new BoundingBox(minLat, midLat, minLon, midLon),  // SW
                new BoundingBox(minLat, midLat, midLon, maxLon)   // SE
            };
        }
        
        @Override
        public String toString() {
            return String.format("[lat(%.2f,%.2f) lon(%.2f,%.2f)]", 
                minLat, maxLat, minLon, maxLon);
        }
    }
    
    /**
     * QuadTree node - either leaf (holds businesses) or internal (has children).
     */
    public static class QuadTreeNode {
        final BoundingBox bounds;
        final int depth;
        
        // Leaf node data
        List<Business> businesses;
        
        // Internal node data
        QuadTreeNode[] children;  // NW, NE, SW, SE
        
        public QuadTreeNode(BoundingBox bounds, int depth) {
            this.bounds = bounds;
            this.depth = depth;
            this.businesses = new ArrayList<>();
            this.children = null;
        }
        
        public boolean isLeaf() {
            return children == null;
        }
        
        public int getBusinessCount() {
            if (isLeaf()) {
                return businesses.size();
            }
            int count = 0;
            for (QuadTreeNode child : children) {
                if (child != null) {
                    count += child.getBusinessCount();
                }
            }
            return count;
        }
    }
    
    /**
     * Create a QuadTree covering the entire world.
     */
    public QuadTree() {
        BoundingBox world = new BoundingBox(-90, 90, -180, 180);
        this.root = new QuadTreeNode(world, 0);
    }
    
    /**
     * Insert a business into the quadtree.
     */
    public void insert(Business business) {
        insert(root, business);
    }
    
    private void insert(QuadTreeNode node, Business business) {
        if (!node.bounds.contains(business.getLatitude(), business.getLongitude())) {
            return;  // Business outside this node's bounds
        }
        
        if (node.isLeaf()) {
            node.businesses.add(business);
            
            // Split if over capacity and not at max depth
            if (node.businesses.size() > MAX_CAPACITY && node.depth < MAX_DEPTH) {
                split(node);
            }
        } else {
            // Insert into appropriate child
            for (QuadTreeNode child : node.children) {
                if (child != null && 
                    child.bounds.contains(business.getLatitude(), business.getLongitude())) {
                    insert(child, business);
                    return;
                }
            }
        }
    }
    
    /**
     * Split a leaf node into 4 children.
     */
    private void split(QuadTreeNode node) {
        BoundingBox[] childBounds = node.bounds.subdivide();
        node.children = new QuadTreeNode[4];
        
        for (int i = 0; i < 4; i++) {
            node.children[i] = new QuadTreeNode(childBounds[i], node.depth + 1);
        }
        
        // Redistribute businesses to children
        for (Business business : node.businesses) {
            for (QuadTreeNode child : node.children) {
                if (child.bounds.contains(business.getLatitude(), business.getLongitude())) {
                    child.businesses.add(business);
                    break;
                }
            }
        }
        
        // Clear businesses from this node (now internal)
        node.businesses = null;
    }
    
    /**
     * Search for businesses within radius of a point.
     */
    public List<Business> search(double latitude, double longitude, double radiusKm) {
        List<Business> results = new ArrayList<>();
        GeoLocation searchPoint = new GeoLocation(latitude, longitude);
        search(root, searchPoint, radiusKm, results);
        return results;
    }
    
    private void search(QuadTreeNode node, GeoLocation searchPoint, 
                        double radiusKm, List<Business> results) {
        if (node == null) return;
        
        // Check if this node's bounds could contain results
        if (!node.bounds.intersects(
                searchPoint.getLatitude(), searchPoint.getLongitude(), radiusKm)) {
            return;  // Prune this branch
        }
        
        if (node.isLeaf()) {
            // Check each business in this leaf
            for (Business business : node.businesses) {
                GeoLocation businessLoc = new GeoLocation(
                    business.getLatitude(), business.getLongitude()
                );
                if (searchPoint.distanceTo(businessLoc) <= radiusKm) {
                    results.add(business);
                }
            }
        } else {
            // Recurse into children
            for (QuadTreeNode child : node.children) {
                search(child, searchPoint, radiusKm, results);
            }
        }
    }
    
    /**
     * Get statistics about the tree.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        int[] leafDepths = new int[MAX_DEPTH + 1];
        int[] businessCounts = {0};
        int[] leafCount = {0};
        
        collectStats(root, leafDepths, businessCounts, leafCount);
        
        stats.put("totalBusinesses", businessCounts[0]);
        stats.put("leafNodes", leafCount[0]);
        stats.put("depthDistribution", leafDepths);
        
        return stats;
    }
    
    private void collectStats(QuadTreeNode node, int[] leafDepths, 
                              int[] businessCounts, int[] leafCount) {
        if (node == null) return;
        
        if (node.isLeaf()) {
            leafDepths[node.depth]++;
            businessCounts[0] += node.businesses.size();
            leafCount[0]++;
        } else {
            for (QuadTreeNode child : node.children) {
                collectStats(child, leafDepths, businessCounts, leafCount);
            }
        }
    }
    
    /**
     * Demonstration of QuadTree.
     */
    public static void main(String[] args) {
        QuadTree tree = new QuadTree();
        
        // Add some businesses in San Francisco area
        tree.insert(new Business(1, "Coffee Shop", 37.7749, -122.4194));
        tree.insert(new Business(2, "Restaurant", 37.7751, -122.4180));
        tree.insert(new Business(3, "Bookstore", 37.7760, -122.4200));
        tree.insert(new Business(4, "Gas Station", 37.7800, -122.4100));
        tree.insert(new Business(5, "Supermarket", 37.7700, -122.4300));
        
        System.out.println("=== QuadTree Demo ===");
        System.out.println("Tree stats: " + tree.getStats());
        
        // Search for businesses within 1km of a point
        List<Business> nearby = tree.search(37.7749, -122.4194, 1.0);
        System.out.println("\nBusinesses within 1km of (37.7749, -122.4194):");
        for (Business b : nearby) {
            System.out.println("  - " + b);
        }
    }
}
