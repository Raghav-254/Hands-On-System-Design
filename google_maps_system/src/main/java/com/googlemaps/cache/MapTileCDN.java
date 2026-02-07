package com.googlemaps.cache;

import com.googlemaps.model.MapTile;
import java.util.*;

/**
 * Simulates the CDN layer for map tile delivery.
 *
 * Map tiles are pre-computed images served to mobile clients.
 * Flow:
 * 1. Precomputed map images are generated offline and stored in Object Storage (S3)
 * 2. CDN pulls from Object Storage and caches at edge locations worldwide
 * 3. Mobile client requests tiles by zoom/x/y → CDN serves from nearest edge
 *
 * Key design decisions:
 * - Tiles are STATIC (only change when road data changes) → perfect for CDN
 * - Client requests tile URLs from Map Tile Service, then downloads from CDN directly
 * - Aggressive caching: tiles rarely change, can be cached for days
 */
public class MapTileCDN {
    private final Map<String, MapTile> cache = new HashMap<>();
    private int cacheHits = 0;
    private int cacheMisses = 0;

    /**
     * Get a map tile. Simulates CDN cache lookup.
     * In production, the client fetches directly from CDN by URL.
     */
    public MapTile getTile(int zoom, int x, int y) {
        String key = String.format("%d/%d/%d", zoom, x, y);
        MapTile cached = cache.get(key);
        if (cached != null) {
            cacheHits++;
            return cached;
        }
        // CDN cache miss → pull from origin (Object Storage)
        cacheMisses++;
        MapTile tile = new MapTile(zoom, x, y);
        cache.put(key, tile);
        return tile;
    }

    /**
     * Get tile URLs for a given viewport and zoom level.
     * This is what the Map Tile Service returns to the client.
     */
    public List<String> getTileUrls(int zoom, int startX, int startY, int endX, int endY) {
        List<String> urls = new ArrayList<>();
        for (int x = startX; x <= endX; x++) {
            for (int y = startY; y <= endY; y++) {
                urls.add(String.format("https://cdn.maps.example.com/tiles/%d/%d/%d.png", zoom, x, y));
            }
        }
        return urls;
    }

    public int getCacheHits() { return cacheHits; }
    public int getCacheMisses() { return cacheMisses; }
    public double getHitRate() {
        int total = cacheHits + cacheMisses;
        return total == 0 ? 0 : (double) cacheHits / total * 100;
    }
}
