package com.logaggregation.model;

import java.util.List;

public class SearchResult {
    private final List<LogEntry> logs;
    private final long totalHits;
    private final long tookMs;
    private final boolean fromCache;

    public SearchResult(List<LogEntry> logs, long totalHits, long tookMs, boolean fromCache) {
        this.logs = logs;
        this.totalHits = totalHits;
        this.tookMs = tookMs;
        this.fromCache = fromCache;
    }

    public List<LogEntry> getLogs() { return logs; }
    public long getTotalHits() { return totalHits; }
    public long getTookMs() { return tookMs; }
    public boolean isFromCache() { return fromCache; }

    @Override
    public String toString() {
        return String.format("SearchResult[%d hits, %d returned, %dms, %s]",
                totalHits, logs.size(), tookMs, fromCache ? "CACHE HIT" : "ES query");
    }
}
