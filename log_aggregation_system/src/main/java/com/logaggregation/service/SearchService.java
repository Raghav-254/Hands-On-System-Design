package com.logaggregation.service;

import com.logaggregation.model.SearchQuery;
import com.logaggregation.model.SearchResult;
import com.logaggregation.storage.LogIndex;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps Elasticsearch with a Redis-like cache layer for frequent dashboard queries.
 */
public class SearchService {
    private final LogIndex logIndex;
    private final Map<String, CachedResult> queryCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 30_000; // 30 seconds
    private long cacheHits = 0;
    private long cacheMisses = 0;

    public SearchService(LogIndex logIndex) {
        this.logIndex = logIndex;
    }

    public SearchResult search(SearchQuery query) {
        String cacheKey = query.toString();

        CachedResult cached = queryCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            cacheHits++;
            return new SearchResult(cached.result.getLogs(), cached.result.getTotalHits(),
                    0, true);
        }

        cacheMisses++;
        SearchResult result = logIndex.search(query);

        queryCache.put(cacheKey, new CachedResult(result));
        return result;
    }

    public void invalidateCache() { queryCache.clear(); }
    public long getCacheHits() { return cacheHits; }
    public long getCacheMisses() { return cacheMisses; }

    private static class CachedResult {
        final SearchResult result;
        final long createdAt;

        CachedResult(SearchResult result) {
            this.result = result;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
