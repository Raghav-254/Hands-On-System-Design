package com.urlshortener.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates Redis cache with LRU eviction.
 * Key: short_code, Value: long_url.
 *
 * In production: Redis Cluster with TTL and LRU eviction policy.
 */
public class CacheStore {

    private final int maxSize;
    private int hits = 0;
    private int misses = 0;

    // LinkedHashMap with accessOrder=true acts as LRU cache
    private final LinkedHashMap<String, String> cache;

    public CacheStore(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > maxSize;
            }
        };
    }

    /** GET short_code → long_url. Returns null on cache miss. */
    public String get(String shortCode) {
        String value = cache.get(shortCode);
        if (value != null) {
            hits++;
        } else {
            misses++;
        }
        return value;
    }

    /** SET short_code → long_url (on cache miss after DB lookup). */
    public void set(String shortCode, String longUrl) {
        cache.put(shortCode, longUrl);
    }

    /** DELETE short_code (on URL update or deletion — cache invalidation). */
    public void delete(String shortCode) {
        cache.remove(shortCode);
    }

    public int size() { return cache.size(); }
    public int getHits() { return hits; }
    public int getMisses() { return misses; }

    public double hitRate() {
        int total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total * 100;
    }

    public void resetStats() { hits = 0; misses = 0; }
}
