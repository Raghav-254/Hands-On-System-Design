package com.spotify.storage;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates a CDN edge cache with LRU eviction.
 * In production, CDN edge servers cache audio files close to users.
 * Popular songs (top 1%) stay cached; long-tail songs are fetched from S3 origin.
 */
public class CDNCache {
    private final int maxSize;
    private final Map<String, CacheEntry> cache;
    private final LinkedList<String> accessOrder;
    private long hits = 0;
    private long misses = 0;

    public CDNCache(int maxSize) {
        this.maxSize = maxSize;
        this.cache = new ConcurrentHashMap<>();
        this.accessOrder = new LinkedList<>();
    }

    public synchronized String fetch(String s3Path) {
        CacheEntry entry = cache.get(s3Path);
        if (entry != null) {
            hits++;
            accessOrder.remove(s3Path);
            accessOrder.addFirst(s3Path);
            return "[CDN HIT] " + s3Path + " (served from edge, ~10ms)";
        }

        misses++;
        String result = fetchFromOrigin(s3Path);
        put(s3Path, result);
        return "[CDN MISS] " + s3Path + " (fetched from S3 origin, ~100ms, now cached)";
    }

    private void put(String key, String value) {
        if (cache.size() >= maxSize) {
            String evicted = accessOrder.removeLast();
            cache.remove(evicted);
        }
        cache.put(key, new CacheEntry(value));
        accessOrder.addFirst(key);
    }

    private String fetchFromOrigin(String s3Path) {
        return "audio_bytes_from_s3:" + s3Path;
    }

    /**
     * Pre-warm cache with popular songs (called during deployment or trending events).
     */
    public synchronized void preWarm(List<String> s3Paths) {
        for (String path : s3Paths) {
            if (!cache.containsKey(path)) {
                put(path, fetchFromOrigin(path));
            }
        }
    }

    public boolean isCached(String s3Path) { return cache.containsKey(s3Path); }
    public long getHits() { return hits; }
    public long getMisses() { return misses; }
    public int getCachedCount() { return cache.size(); }

    public double getHitRate() {
        long total = hits + misses;
        return total == 0 ? 0 : (double) hits / total * 100;
    }

    @Override
    public String toString() {
        return String.format("CDN[cached=%d/%d, hits=%d, misses=%d, hit_rate=%.1f%%]",
                cache.size(), maxSize, hits, misses, getHitRate());
    }

    private static class CacheEntry {
        final String data;
        CacheEntry(String data) { this.data = data; }
    }
}
