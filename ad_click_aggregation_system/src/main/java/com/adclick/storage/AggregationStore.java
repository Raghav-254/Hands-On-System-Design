package com.adclick.storage;

import com.adclick.model.AggregatedResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the aggregation database (e.g., Cassandra).
 * Stores pre-computed aggregation results for fast queries.
 * Query Service reads from this store.
 */
public class AggregationStore {
    // Key: "adId:minuteBucket" or "adId:minuteBucket:filterId"
    private final Map<String, AggregatedResult> results = new LinkedHashMap<>();

    // Top-N results per window
    private final Map<Long, List<AggregatedResult>> topNByMinute = new LinkedHashMap<>();

    public void store(AggregatedResult result) {
        String key = buildKey(result);
        results.merge(key, result, (existing, newResult) -> {
            existing.incrementCount(newResult.getCount());
            return existing;
        });
    }

    public void storeTopN(long minuteBucket, List<AggregatedResult> topN) {
        topNByMinute.put(minuteBucket, new ArrayList<>(topN));
    }

    /** Query: Get click count for ad_id in last M minutes */
    public long getAggregatedCount(String adId, long startMinute, long endMinute) {
        return results.values().stream()
                .filter(r -> r.getAdId().equals(adId))
                .filter(r -> r.getMinuteBucket() >= startMinute && r.getMinuteBucket() < endMinute)
                .filter(r -> r.getFilterId() == null) // unfiltered only
                .mapToLong(AggregatedResult::getCount)
                .sum();
    }

    /** Query: Get click count with filter */
    public long getFilteredCount(String adId, long startMinute, long endMinute, String filterId) {
        return results.values().stream()
                .filter(r -> r.getAdId().equals(adId))
                .filter(r -> r.getMinuteBucket() >= startMinute && r.getMinuteBucket() < endMinute)
                .filter(r -> filterId.equals(r.getFilterId()))
                .mapToLong(AggregatedResult::getCount)
                .sum();
    }

    /** Query: Get top N most clicked ads in a minute */
    public List<AggregatedResult> getTopN(long minuteBucket, int n) {
        List<AggregatedResult> topList = topNByMinute.getOrDefault(minuteBucket, Collections.emptyList());
        return topList.stream().limit(n).collect(Collectors.toList());
    }

    /** Get all stored results (for debugging) */
    public Collection<AggregatedResult> getAllResults() {
        return results.values();
    }

    private String buildKey(AggregatedResult result) {
        String key = result.getAdId() + ":" + result.getMinuteBucket();
        if (result.getFilterId() != null) {
            key += ":" + result.getFilterId();
        }
        return key;
    }
}
