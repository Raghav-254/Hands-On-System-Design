package com.autocomplete.pipeline;

import com.autocomplete.pipeline.AnalyticsLog.LogEntry;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * Aggregator - Processes raw logs and aggregates query frequencies.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  AGGREGATION LAYER (From Figure 13-9)                                        ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  WHY AGGREGATE?                                                              ║
 * ║  - Raw logs: Billions of entries per day                                     ║
 * ║  - Aggregated: Millions of (query, count) pairs                             ║
 * ║  - Reduces data volume by 100x-1000x                                         ║
 * ║                                                                               ║
 * ║  AGGREGATION STRATEGIES:                                                     ║
 * ║  ─────────────────────────                                                   ║
 * ║  1. Time-based: Aggregate by week (balance freshness vs. stability)         ║
 * ║  2. Decay: Recent queries weighted more than old ones                        ║
 * ║  3. Deduplication: Same user, same query in short time = 1 count            ║
 * ║                                                                               ║
 * ║  Example:                                                                    ║
 * ║  Raw logs: ["best buy", "best buy", "best", "best buy", "beer"]             ║
 * ║  Aggregated: {"best buy": 3, "best": 1, "beer": 1}                          ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class Aggregator {
    
    private final AnalyticsLog analyticsLog;
    
    // Aggregated data: query → frequency
    private final ConcurrentHashMap<String, AggregatedData> aggregatedData;
    
    // Deduplication: user+query → last seen time (to avoid counting rapid retypes)
    private final ConcurrentHashMap<String, Instant> deduplicationMap;
    
    // Minimum time between counting same query from same user
    private static final Duration DEDUP_WINDOW = Duration.ofSeconds(60);
    
    // Time decay: queries older than this get reduced weight
    private static final Duration DECAY_WINDOW = Duration.ofDays(7);
    
    public Aggregator(AnalyticsLog analyticsLog) {
        this.analyticsLog = analyticsLog;
        this.aggregatedData = new ConcurrentHashMap<>();
        this.deduplicationMap = new ConcurrentHashMap<>();
    }
    
    /**
     * Process pending log entries and aggregate them.
     * 
     * In production: This runs continuously as a Spark/Flink job.
     * 
     * @return Number of entries processed
     */
    public int processLogs() {
        List<LogEntry> entries = analyticsLog.pollEntries(10000);
        
        if (entries.isEmpty()) {
            return 0;
        }
        
        System.out.println(String.format("\n[Aggregator] Processing %d log entries...", entries.size()));
        
        int counted = 0;
        for (LogEntry entry : entries) {
            if (shouldCount(entry)) {
                incrementCount(entry.getQuery());
                counted++;
            }
        }
        
        System.out.println(String.format("[Aggregator] Counted %d unique queries (after dedup)", counted));
        return entries.size();
    }
    
    /**
     * Check if this query should be counted (deduplication).
     */
    private boolean shouldCount(LogEntry entry) {
        String dedupKey = entry.getUserId() + ":" + entry.getQuery();
        Instant lastSeen = deduplicationMap.get(dedupKey);
        Instant now = entry.getTimestamp();
        
        if (lastSeen != null && Duration.between(lastSeen, now).compareTo(DEDUP_WINDOW) < 0) {
            // Same user, same query, within dedup window - skip
            return false;
        }
        
        deduplicationMap.put(dedupKey, now);
        return true;
    }
    
    /**
     * Increment the count for a query.
     */
    private void incrementCount(String query) {
        aggregatedData.compute(query, (k, v) -> {
            if (v == null) {
                return new AggregatedData(query, 1, Instant.now());
            }
            v.incrementCount();
            v.setLastUpdated(Instant.now());
            return v;
        });
    }
    
    /**
     * Get the current aggregated data.
     * This is consumed by Workers to update the Trie.
     * 
     * @return Map of query → frequency
     */
    public Map<String, Integer> getAggregatedCounts() {
        return aggregatedData.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().getCount()
            ));
    }
    
    /**
     * Get top N queries by frequency.
     */
    public List<Map.Entry<String, Integer>> getTopQueries(int n) {
        return aggregatedData.entrySet().stream()
            .sorted((a, b) -> Integer.compare(b.getValue().getCount(), a.getValue().getCount()))
            .limit(n)
            .map(e -> new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue().getCount()))
            .collect(Collectors.toList());
    }
    
    /**
     * Apply time decay to old queries.
     * Queries not searched recently get their frequency reduced.
     * 
     * In production: This runs as a periodic batch job.
     */
    public void applyTimeDecay() {
        Instant cutoff = Instant.now().minus(DECAY_WINDOW.toDays(), ChronoUnit.DAYS);
        
        System.out.println("\n[Aggregator] Applying time decay...");
        
        aggregatedData.forEach((query, data) -> {
            if (data.getLastUpdated().isBefore(cutoff)) {
                int oldCount = data.getCount();
                int newCount = Math.max(1, oldCount / 2);  // Decay by 50%
                data.setCount(newCount);
                
                if (oldCount != newCount) {
                    System.out.println(String.format("  Decayed '%s': %d → %d", query, oldCount, newCount));
                }
            }
        });
    }
    
    /**
     * Reset aggregated data (for testing).
     */
    public void reset() {
        aggregatedData.clear();
        deduplicationMap.clear();
    }
    
    /**
     * Print aggregated data summary.
     */
    public void printSummary() {
        System.out.println("\n=== AGGREGATED DATA ===");
        System.out.println(String.format("Total unique queries: %d", aggregatedData.size()));
        
        System.out.println("\nTop 10 queries:");
        getTopQueries(10).forEach(e -> 
            System.out.println(String.format("  %s: %d", e.getKey(), e.getValue()))
        );
    }
    
    /**
     * AggregatedData - Holds count and metadata for a query.
     */
    public static class AggregatedData {
        private final String query;
        private int count;
        private Instant lastUpdated;
        
        public AggregatedData(String query, int count, Instant lastUpdated) {
            this.query = query;
            this.count = count;
            this.lastUpdated = lastUpdated;
        }
        
        public String getQuery() {
            return query;
        }
        
        public int getCount() {
            return count;
        }
        
        public void setCount(int count) {
            this.count = count;
        }
        
        public void incrementCount() {
            this.count++;
        }
        
        public Instant getLastUpdated() {
            return lastUpdated;
        }
        
        public void setLastUpdated(Instant lastUpdated) {
            this.lastUpdated = lastUpdated;
        }
    }
}

