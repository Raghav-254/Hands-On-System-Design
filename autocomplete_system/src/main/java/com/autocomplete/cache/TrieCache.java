package com.autocomplete.cache;

import com.autocomplete.storage.TrieDB;
import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;
import java.util.concurrent.*;

/**
 * TrieCache - In-memory cache for fast autocomplete lookups.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRIE CACHE (From Figure 13-9, 13-11)                                        ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  API Servers → Trie Cache → Trie DB                                         ║
 * ║                    │                                                          ║
 * ║              (Weekly Snapshot)                                               ║
 * ║                                                                               ║
 * ║  WHY CACHE?                                                                  ║
 * ║  ───────────                                                                 ║
 * ║  - < 100ms latency requirement (Trie DB might be 5-10ms)                    ║
 * ║  - High QPS: 10M DAU * 10 queries/day = 100M queries/day                    ║
 * ║  - Cache hit rate > 99% (popular prefixes repeat)                           ║
 * ║                                                                               ║
 * ║  CACHE STRATEGY:                                                            ║
 * ║  ────────────────                                                           ║
 * ║  1. Weekly snapshot from Trie DB                                            ║
 * ║  2. LRU eviction for less common prefixes                                   ║
 * ║  3. Popular prefixes (top 20%) always in cache                              ║
 * ║                                                                               ║
 * ║  CACHE KEY: prefix (e.g., "be", "bes", "best")                              ║
 * ║  CACHE VALUE: List<Suggestion> (top-k suggestions)                          ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TrieCache {
    
    private final TrieDB trieDB;
    
    // LRU cache: prefix → suggestions
    private final LinkedHashMap<String, CacheEntry> cache;
    private final int maxSize;
    
    // Metrics
    private long hits;
    private long misses;
    
    // Version tracking (for invalidation)
    private long cachedVersion;
    
    // Default cache size
    private static final int DEFAULT_MAX_SIZE = 100000;
    
    public TrieCache(TrieDB trieDB) {
        this(trieDB, DEFAULT_MAX_SIZE);
    }
    
    public TrieCache(TrieDB trieDB, int maxSize) {
        this.trieDB = trieDB;
        this.maxSize = maxSize;
        this.hits = 0;
        this.misses = 0;
        this.cachedVersion = -1;
        
        // LRU cache implementation
        this.cache = new LinkedHashMap<>(maxSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
                return size() > maxSize;
            }
        };
    }
    
    /**
     * Get suggestions for a prefix.
     * This is the main query method called by the API.
     * 
     * Flow:
     * 1. Check cache version (invalidate if stale)
     * 2. Look up in cache
     * 3. On miss: fetch from Trie DB, populate cache
     * 
     * @param prefix The search prefix
     * @return List of suggestions
     */
    public List<Suggestion> getSuggestions(String prefix) {
        prefix = normalize(prefix);
        
        if (prefix.isEmpty()) {
            return Collections.emptyList();
        }
        
        // Check if cache needs refresh
        checkVersion();
        
        // Try cache first
        CacheEntry entry = cache.get(prefix);
        if (entry != null) {
            hits++;
            return entry.getSuggestions();
        }
        
        // Cache miss - fetch from DB
        misses++;
        List<Suggestion> suggestions = trieDB.getSuggestionsForPrefix(prefix);
        
        // Populate cache
        if (!suggestions.isEmpty()) {
            cache.put(prefix, new CacheEntry(suggestions));
        }
        
        return suggestions;
    }
    
    /**
     * Check if cache version matches DB version.
     * If not, invalidate cache.
     */
    private void checkVersion() {
        long dbVersion = trieDB.getVersion();
        if (dbVersion != cachedVersion) {
            System.out.println(String.format(
                "[TrieCache] Version changed %d → %d, invalidating cache...", 
                cachedVersion, dbVersion));
            invalidate();
            cachedVersion = dbVersion;
        }
    }
    
    /**
     * Preload popular prefixes into cache.
     * Called after trie rebuild.
     * 
     * @param prefixes List of popular prefixes to preload
     */
    public void preload(List<String> prefixes) {
        System.out.println(String.format("[TrieCache] Preloading %d prefixes...", prefixes.size()));
        
        for (String prefix : prefixes) {
            prefix = normalize(prefix);
            List<Suggestion> suggestions = trieDB.getSuggestionsForPrefix(prefix);
            if (!suggestions.isEmpty()) {
                cache.put(prefix, new CacheEntry(suggestions));
            }
        }
        
        cachedVersion = trieDB.getVersion();
        System.out.println("[TrieCache] Preload complete!");
    }
    
    /**
     * Invalidate the entire cache.
     */
    public void invalidate() {
        cache.clear();
        hits = 0;
        misses = 0;
    }
    
    /**
     * Invalidate a specific prefix.
     */
    public void invalidate(String prefix) {
        cache.remove(normalize(prefix));
    }
    
    /**
     * Get cache statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("size", cache.size());
        stats.put("maxSize", maxSize);
        stats.put("hits", hits);
        stats.put("misses", misses);
        stats.put("hitRate", hits + misses > 0 ? 
            String.format("%.2f%%", 100.0 * hits / (hits + misses)) : "N/A");
        stats.put("cachedVersion", cachedVersion);
        return stats;
    }
    
    /**
     * Get cache hit rate.
     */
    public double getHitRate() {
        long total = hits + misses;
        return total > 0 ? (double) hits / total : 0.0;
    }
    
    /**
     * Normalize prefix for cache key.
     */
    private String normalize(String prefix) {
        return prefix.toLowerCase().replaceAll("[^a-z]", "");
    }
    
    /**
     * CacheEntry - Wrapper for cached suggestions.
     */
    private static class CacheEntry {
        private final List<Suggestion> suggestions;
        private final long timestamp;
        
        public CacheEntry(List<Suggestion> suggestions) {
            this.suggestions = new ArrayList<>(suggestions);
            this.timestamp = System.currentTimeMillis();
        }
        
        public List<Suggestion> getSuggestions() {
            return suggestions;
        }
        
        public long getTimestamp() {
            return timestamp;
        }
    }
}

