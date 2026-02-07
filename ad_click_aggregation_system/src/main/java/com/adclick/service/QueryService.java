package com.adclick.service;

import com.adclick.model.AggregatedResult;
import com.adclick.storage.AggregationStore;

import java.util.List;

/**
 * Query Service — reads from the Aggregation Database.
 * Serves the dashboard/API for ad click analytics.
 *
 * API endpoints:
 *   GET /ads/{ad_id}/aggregated_count?from=&to=
 *   GET /ads/popular_ads?window_size=M&top=N
 *   GET /ads/{ad_id}/aggregated_count?filter=country:US
 */
public class QueryService {
    private final AggregationStore store;

    public QueryService(AggregationStore store) {
        this.store = store;
    }

    /** GET /ads/{ad_id}/aggregated_count — clicks in last M minutes */
    public long getAggregatedCount(String adId, long startMinute, long endMinute) {
        long count = store.getAggregatedCount(adId, startMinute, endMinute);
        System.out.println(String.format("  [Query] ad=%s, range=[%d,%d) → %d clicks",
                adId, startMinute, endMinute, count));
        return count;
    }

    /** GET /ads/{ad_id}/aggregated_count?filter=country:US — filtered clicks */
    public long getFilteredCount(String adId, long startMinute, long endMinute, String filterId) {
        long count = store.getFilteredCount(adId, startMinute, endMinute, filterId);
        System.out.println(String.format("  [Query] ad=%s, filter=%s, range=[%d,%d) → %d clicks",
                adId, filterId, startMinute, endMinute, count));
        return count;
    }

    /** GET /ads/popular_ads — top N most clicked ads in a minute */
    public List<AggregatedResult> getTopAds(long minuteBucket, int n) {
        List<AggregatedResult> topAds = store.getTopN(minuteBucket, n);
        System.out.println("  [Query] Top " + n + " ads for minute " + minuteBucket + ":");
        for (int i = 0; i < topAds.size(); i++) {
            System.out.println("    #" + (i + 1) + ": " + topAds.get(i));
        }
        return topAds;
    }
}
