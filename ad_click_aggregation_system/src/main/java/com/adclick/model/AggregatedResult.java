package com.adclick.model;

/**
 * Represents an aggregated click count for an ad in a time window.
 * Stored in the aggregation database.
 */
public class AggregatedResult {
    private final String adId;
    private final long minuteBucket;    // minute timestamp
    private long count;
    private final String filterId;      // null for unfiltered, e.g., "country:US" for filtered

    public AggregatedResult(String adId, long minuteBucket, long count, String filterId) {
        this.adId = adId;
        this.minuteBucket = minuteBucket;
        this.count = count;
        this.filterId = filterId;
    }

    public AggregatedResult(String adId, long minuteBucket, long count) {
        this(adId, minuteBucket, count, null);
    }

    public String getAdId() { return adId; }
    public long getMinuteBucket() { return minuteBucket; }
    public long getCount() { return count; }
    public String getFilterId() { return filterId; }

    public void incrementCount(long delta) {
        this.count += delta;
    }

    @Override
    public String toString() {
        String filter = filterId != null ? ", filter=" + filterId : "";
        return String.format("Aggregated{ad=%s, minute=%d, count=%d%s}",
                adId, minuteBucket, count, filter);
    }
}
