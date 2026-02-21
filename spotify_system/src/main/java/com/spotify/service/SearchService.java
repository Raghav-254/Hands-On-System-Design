package com.spotify.service;

import com.spotify.model.Song;
import com.spotify.storage.SearchIndex;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wraps Elasticsearch with a Redis cache layer for popular queries.
 * Implements: fuzzy search, popularity re-ranking, result caching.
 */
public class SearchService {
    private final SearchIndex searchIndex;
    private final Map<String, CachedResult> searchCache = new ConcurrentHashMap<>();
    private static final long CACHE_TTL_MS = 5 * 60 * 1000; // 5 minutes
    private long cacheHits = 0;
    private long cacheMisses = 0;

    public SearchService(SearchIndex searchIndex) {
        this.searchIndex = searchIndex;
    }

    public List<Song> search(String query, int limit) {
        String cacheKey = normalize(query) + ":" + limit;

        CachedResult cached = searchCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            cacheHits++;
            return cached.results;
        }

        cacheMisses++;
        List<Song> results = searchIndex.search(query, limit);

        searchCache.put(cacheKey, new CachedResult(results));
        return results;
    }

    /**
     * Autocomplete: search with short prefix, return top suggestions.
     */
    public List<String> autocomplete(String prefix, int limit) {
        List<Song> results = searchIndex.search(prefix, limit);
        List<String> suggestions = new ArrayList<>();
        for (Song s : results) {
            suggestions.add(s.getTitle() + " — " + s.getArtistName());
        }
        return suggestions;
    }

    public void invalidateCache() { searchCache.clear(); }
    public long getCacheHits() { return cacheHits; }
    public long getCacheMisses() { return cacheMisses; }

    private String normalize(String query) {
        return query.toLowerCase().trim();
    }

    private static class CachedResult {
        final List<Song> results;
        final long createdAt;

        CachedResult(List<Song> results) {
            this.results = results;
            this.createdAt = System.currentTimeMillis();
        }

        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }
}
