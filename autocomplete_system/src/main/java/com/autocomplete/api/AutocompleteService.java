package com.autocomplete.api;

import com.autocomplete.cache.TrieCache;
import com.autocomplete.pipeline.*;
import com.autocomplete.storage.TrieDB;
import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;
import java.util.stream.Collectors;

/**
 * AutocompleteService - The main API service for autocomplete.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  QUERY FLOW (From Figure 13-11)                                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  ┌────────┐     ┌──────────────┐     ┌────────────┐     ┌──────────────┐     ║
 * ║  │  User  │ ──► │Load Balancer │ ──► │ API Server │ ──► │ Trie Cache   │     ║
 * ║  │        │  ①  │              │  ②  │            │  ③  │              │     ║
 * ║  └────────┘     └──────────────┘     └────────────┘     └──────┬───────┘     ║
 * ║                                                                 │            ║
 * ║                                                            ④ (miss)         ║
 * ║                                                                 ▼            ║
 * ║                                                          ┌──────────────┐    ║
 * ║                                                          │   Trie DB    │    ║
 * ║                                                          └──────────────┘    ║
 * ║                                                                               ║
 * ║  ① User types "be" in search box                                            ║
 * ║  ② Load balancer routes to API server                                       ║
 * ║  ③ API server queries Trie Cache                                            ║
 * ║  ④ On cache miss, fetch from Trie DB and populate cache                     ║
 * ║                                                                               ║
 * ║  LATENCY REQUIREMENT: < 100ms                                               ║
 * ║  - Cache hit: ~1ms                                                          ║
 * ║  - Cache miss (DB lookup): ~10ms                                            ║
 * ║  - Network overhead: ~5-10ms                                                ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class AutocompleteService {
    
    private final TrieCache trieCache;
    private final AnalyticsLog analyticsLog;
    private final String serverId;
    
    // Rate limiting (per user)
    private final Map<String, Long> lastQueryTime;
    private static final long MIN_QUERY_INTERVAL_MS = 50;  // Max 20 queries/sec per user
    
    // Query metrics
    private long totalQueries;
    private long totalLatencyMs;
    
    public AutocompleteService(TrieCache trieCache, AnalyticsLog analyticsLog, String serverId) {
        this.trieCache = trieCache;
        this.analyticsLog = analyticsLog;
        this.serverId = serverId;
        this.lastQueryTime = new HashMap<>();
        this.totalQueries = 0;
        this.totalLatencyMs = 0;
    }
    
    /**
     * Get autocomplete suggestions for a prefix.
     * This is the main API endpoint.
     * 
     * GET /v1/autocomplete?q={prefix}
     * 
     * @param prefix The search prefix
     * @param userId The user ID (for logging and rate limiting)
     * @return AutocompleteResponse with suggestions
     */
    public AutocompleteResponse getSuggestions(String prefix, String userId) {
        long startTime = System.currentTimeMillis();
        
        System.out.println(String.format("\n[API] GET /v1/autocomplete?q=%s (user: %s, server: %s)", 
            prefix, userId, serverId));
        
        // Rate limiting
        if (isRateLimited(userId)) {
            System.out.println("[API] Rate limited!");
            return new AutocompleteResponse(prefix, Collections.emptyList(), 0, true);
        }
        
        // Get suggestions from cache
        List<Suggestion> suggestions = trieCache.getSuggestions(prefix);
        
        // Log the query (for analytics)
        if (prefix.length() >= 2) {  // Only log meaningful prefixes
            analyticsLog.logQuery(prefix, userId);
        }
        
        long latency = System.currentTimeMillis() - startTime;
        totalQueries++;
        totalLatencyMs += latency;
        
        // Convert to response format
        List<String> words = suggestions.stream()
            .map(Suggestion::getWord)
            .collect(Collectors.toList());
        
        System.out.println(String.format("[API] Returned %d suggestions in %dms: %s", 
            words.size(), latency, words));
        
        return new AutocompleteResponse(prefix, words, latency, false);
    }
    
    /**
     * Simplified version without user tracking.
     */
    public AutocompleteResponse getSuggestions(String prefix) {
        return getSuggestions(prefix, "anonymous");
    }
    
    /**
     * Check if user is rate limited.
     */
    private boolean isRateLimited(String userId) {
        long now = System.currentTimeMillis();
        Long lastTime = lastQueryTime.get(userId);
        
        if (lastTime != null && (now - lastTime) < MIN_QUERY_INTERVAL_MS) {
            return true;
        }
        
        lastQueryTime.put(userId, now);
        return false;
    }
    
    /**
     * Get service statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("serverId", serverId);
        stats.put("totalQueries", totalQueries);
        stats.put("avgLatencyMs", totalQueries > 0 ? totalLatencyMs / totalQueries : 0);
        stats.putAll(trieCache.getStats());
        return stats;
    }
    
    /**
     * Health check endpoint.
     */
    public boolean isHealthy() {
        return trieCache.getStats().get("size") != null;
    }
    
    /**
     * AutocompleteResponse - The API response.
     */
    public static class AutocompleteResponse {
        private final String query;
        private final List<String> suggestions;
        private final long latencyMs;
        private final boolean rateLimited;
        
        public AutocompleteResponse(String query, List<String> suggestions, 
                                    long latencyMs, boolean rateLimited) {
            this.query = query;
            this.suggestions = suggestions;
            this.latencyMs = latencyMs;
            this.rateLimited = rateLimited;
        }
        
        public String getQuery() {
            return query;
        }
        
        public List<String> getSuggestions() {
            return suggestions;
        }
        
        public long getLatencyMs() {
            return latencyMs;
        }
        
        public boolean isRateLimited() {
            return rateLimited;
        }
        
        @Override
        public String toString() {
            return String.format("AutocompleteResponse{query='%s', suggestions=%s, latency=%dms}", 
                query, suggestions, latencyMs);
        }
    }
}

