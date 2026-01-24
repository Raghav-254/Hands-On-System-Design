package com.videostreaming.cache;

import com.videostreaming.model.VideoMetadata;
import java.util.*;

/**
 * Metadata Cache - caches hot video metadata for fast reads.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  METADATA CACHE (Redis)                                                      ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Cache frequently accessed video metadata                                 ║
 * ║  • Reduce load on Metadata DB                                               ║
 * ║  • Sub-millisecond reads for popular videos                                 ║
 * ║                                                                               ║
 * ║  CACHE STRATEGY:                                                            ║
 * ║  ─────────────────                                                           ║
 * ║  • Cache-aside pattern: Check cache → if miss, load from DB → cache it     ║
 * ║  • TTL: 1 hour for metadata                                                 ║
 * ║  • LRU eviction when cache is full                                          ║
 * ║                                                                               ║
 * ║  WHAT TO CACHE:                                                             ║
 * ║  ─────────────────                                                           ║
 * ║  • Video metadata (title, description, thumbnail URL)                       ║
 * ║  • Available resolutions and CDN URLs                                       ║
 * ║  • View count (with periodic sync to DB)                                    ║
 * ║                                                                               ║
 * ║  CACHE KEY PATTERNS:                                                        ║
 * ║  ─────────────────────                                                       ║
 * ║  video:metadata:{videoId}     → VideoMetadata JSON                          ║
 * ║  video:viewcount:{videoId}    → Counter (for high-volume updates)           ║
 * ║  trending:daily               → Sorted set of trending videos               ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class MetadataCache {
    
    // Simulates Redis: videoId → metadata
    private final Map<String, VideoMetadata> cache = new LinkedHashMap<>(1000, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, VideoMetadata> eldest) {
            return size() > MAX_SIZE;
        }
    };
    
    // View count buffer: accumulate counts before flushing to DB
    private final Map<String, Long> viewCountBuffer = new HashMap<>();
    
    private static final int MAX_SIZE = 10000;  // Max cached videos
    private static final long TTL_MS = 3600 * 1000;  // 1 hour TTL
    
    // Track cache entry times for TTL
    private final Map<String, Long> entryTimes = new HashMap<>();
    
    /**
     * Get metadata from cache.
     */
    public VideoMetadata get(String videoId) {
        // Check TTL
        Long entryTime = entryTimes.get(videoId);
        if (entryTime != null && System.currentTimeMillis() - entryTime > TTL_MS) {
            // Expired, remove and return null
            cache.remove(videoId);
            entryTimes.remove(videoId);
            return null;
        }
        
        VideoMetadata metadata = cache.get(videoId);
        if (metadata != null) {
            System.out.println(String.format("  [MetadataCache] Cache HIT for video %s", videoId));
        } else {
            System.out.println(String.format("  [MetadataCache] Cache MISS for video %s", videoId));
        }
        return metadata;
    }
    
    /**
     * Put metadata in cache.
     */
    public void put(String videoId, VideoMetadata metadata) {
        cache.put(videoId, metadata);
        entryTimes.put(videoId, System.currentTimeMillis());
        System.out.println(String.format("  [MetadataCache] Cached metadata for video %s", videoId));
    }
    
    /**
     * Invalidate cache entry.
     */
    public void invalidate(String videoId) {
        cache.remove(videoId);
        entryTimes.remove(videoId);
        System.out.println(String.format("  [MetadataCache] Invalidated cache for video %s", videoId));
    }
    
    /**
     * Increment view count in buffer.
     * Views are batched and periodically flushed to DB.
     */
    public void incrementViewCount(String videoId) {
        viewCountBuffer.merge(videoId, 1L, Long::sum);
    }
    
    /**
     * Get buffered view counts and clear buffer.
     * Called periodically to flush to DB.
     */
    public Map<String, Long> flushViewCounts() {
        Map<String, Long> counts = new HashMap<>(viewCountBuffer);
        viewCountBuffer.clear();
        return counts;
    }
    
    /**
     * Check if video is cached.
     */
    public boolean contains(String videoId) {
        return cache.containsKey(videoId);
    }
    
    /**
     * Get cache stats.
     */
    public String getStats() {
        return String.format("Cache size: %d/%d, View buffer: %d", 
            cache.size(), MAX_SIZE, viewCountBuffer.size());
    }
}

