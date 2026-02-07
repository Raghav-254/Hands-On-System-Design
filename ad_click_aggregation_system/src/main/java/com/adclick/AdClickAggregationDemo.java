package com.adclick;

import com.adclick.model.AdClickEvent;
import com.adclick.model.AggregatedResult;
import com.adclick.service.AggregationService;
import com.adclick.service.QueryService;
import com.adclick.storage.AggregationStore;
import com.adclick.storage.RawDataStore;

import java.time.Instant;
import java.util.*;

/**
 * Demo: Ad Click Event Aggregation System
 *
 * Demonstrates:
 * 1. Event ingestion and raw data storage
 * 2. MapReduce aggregation (Map → Aggregate → Reduce)
 * 3. Filtered aggregation (by country, device)
 * 4. Top-N most clicked ads
 * 5. Watermark handling for late events
 * 6. Exactly-once processing (atomic commit)
 * 7. Data recalculation from raw events
 * 8. Query service (dashboard API)
 * 9. Back-of-envelope estimation
 */
public class AdClickAggregationDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║    Ad Click Event Aggregation System Demo       ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // Initialize stores and services
        RawDataStore rawDataStore = new RawDataStore();
        AggregationStore aggregationStore = new AggregationStore();
        AggregationService aggregationService = new AggregationService(aggregationStore, rawDataStore, 3);
        QueryService queryService = new QueryService(aggregationStore);

        // ============================================
        // Demo 1: Event Ingestion & Aggregation
        // ============================================
        System.out.println("\n========== DEMO 1: Event Ingestion & MapReduce Aggregation ==========");

        long minute1 = 1000;
        List<AdClickEvent> batch1 = generateEvents(minute1, new String[][]{
                {"ad1", "user1", "US", "mobile", "banner"},
                {"ad1", "user2", "US", "desktop", "video"},
                {"ad1", "user3", "UK", "mobile", "banner"},
                {"ad2", "user4", "US", "mobile", "native"},
                {"ad2", "user5", "IN", "tablet", "banner"},
                {"ad3", "user6", "US", "desktop", "video"},
                {"ad3", "user7", "UK", "mobile", "banner"},
                {"ad3", "user8", "IN", "mobile", "native"},
                {"ad3", "user9", "US", "desktop", "video"},
        });
        aggregationService.processBatch(batch1, minute1);

        long minute2 = 1001;
        List<AdClickEvent> batch2 = generateEvents(minute2, new String[][]{
                {"ad1", "user10", "US", "mobile", "banner"},
                {"ad2", "user11", "UK", "desktop", "video"},
                {"ad2", "user12", "US", "mobile", "banner"},
                {"ad2", "user13", "IN", "mobile", "native"},
                {"ad3", "user14", "US", "desktop", "video"},
                {"ad3", "user15", "UK", "mobile", "banner"},
        });
        aggregationService.processBatch(batch2, minute2);

        // ============================================
        // Demo 2: Query Service (Dashboard API)
        // ============================================
        System.out.println("\n========== DEMO 2: Query Service ==========");

        System.out.println("\n--- GET /ads/ad1/aggregated_count (minutes 1000-1002) ---");
        queryService.getAggregatedCount("ad1", minute1, minute2 + 1);

        System.out.println("\n--- GET /ads/ad2/aggregated_count?filter=country:US ---");
        queryService.getFilteredCount("ad2", minute1, minute2 + 1, "country:US");

        System.out.println("\n--- GET /ads/popular_ads (minute 1000, top 3) ---");
        queryService.getTopAds(minute1, 3);

        System.out.println("\n--- GET /ads/popular_ads (minute 1001, top 3) ---");
        queryService.getTopAds(minute2, 3);

        // ============================================
        // Demo 3: Late Event Handling (Watermark)
        // ============================================
        System.out.println("\n========== DEMO 3: Late Event Handling ==========");

        long minute3 = 1005;
        // Some events are from minute 1003 (late but within watermark)
        // and minute 998 (too late, will be dropped)
        List<AdClickEvent> batchWithLateEvents = new ArrayList<>();
        batchWithLateEvents.add(createEvent("ad1", minute3, "user20", "US", "mobile", "banner"));
        batchWithLateEvents.add(createEvent("ad2", minute3, "user21", "UK", "desktop", "video"));
        // Late event (within 2-min watermark tolerance)
        batchWithLateEvents.add(createEvent("ad1", minute3 - 1, "user22", "US", "mobile", "banner"));
        // Too late (beyond watermark)
        batchWithLateEvents.add(createEvent("ad3", minute3 - 10, "user23", "IN", "mobile", "native"));

        aggregationService.processBatch(batchWithLateEvents, minute3);

        // ============================================
        // Demo 4: Data Recalculation
        // ============================================
        System.out.println("\n========== DEMO 4: Data Recalculation ==========");
        System.out.println("Scenario: Bug found in aggregation logic, need to recompute minutes 1000-1002");
        aggregationService.recalculate(minute1, minute2 + 1);

        // ============================================
        // Demo 5: Back-of-Envelope Estimation
        // ============================================
        System.out.println("\n========== DEMO 5: Back-of-Envelope Estimation ==========");
        System.out.println("┌─────────────────────────────────────────────────┐");
        System.out.println("│ Scale Estimation                                │");
        System.out.println("├─────────────────────────────────────────────────┤");
        System.out.println("│ DAU:              1 billion                     │");
        System.out.println("│ Clicks/user/day:  1                             │");
        System.out.println("│ Daily events:     1 billion                     │");
        System.out.println("│ Avg QPS:          10,000 (1B / 100K sec)        │");
        System.out.println("│ Peak QPS:         50,000 (5x average)           │");
        System.out.println("│ Event size:       ~0.1 KB                       │");
        System.out.println("│ Daily storage:    100 GB                        │");
        System.out.println("│ Monthly storage:  ~3 TB                         │");
        System.out.println("├─────────────────────────────────────────────────┤");
        System.out.println("│ Demo raw events:  " + padRight(rawDataStore.getTotalEvents() + " events", 28) + " │");
        System.out.println("│ Demo storage:     " + padRight(rawDataStore.getStorageEstimate(), 28) + " │");
        System.out.println("└─────────────────────────────────────────────────┘");

        // ============================================
        // Demo 6: Exactly-Once Semantics
        // ============================================
        System.out.println("\n========== DEMO 6: Exactly-Once Processing ==========");
        System.out.println("┌──────────────────────────────────────────────────────────────┐");
        System.out.println("│ Exactly-Once = Atomic Commit of:                             │");
        System.out.println("│   1. Aggregation results → downstream Kafka topic            │");
        System.out.println("│   2. Consumer offset → state storage                         │");
        System.out.println("│   3. Either BOTH succeed or BOTH fail                        │");
        System.out.println("│                                                              │");
        System.out.println("│ Current committed offset: " + padRight(String.valueOf(aggregationService.getLastCommittedOffset()), 33) + "│");
        System.out.println("│                                                              │");
        System.out.println("│ If crash before commit → restart from last offset → no loss  │");
        System.out.println("│ If crash after commit → don't reprocess → no duplicates      │");
        System.out.println("└──────────────────────────────────────────────────────────────┘");

        System.out.println("\n✓ Demo complete!");
    }

    // ---- Helper methods ----

    private static List<AdClickEvent> generateEvents(long minuteBucket, String[][] data) {
        List<AdClickEvent> events = new ArrayList<>();
        for (String[] d : data) {
            events.add(createEvent(d[0], minuteBucket, d[1], d[2], d[3], d[4]));
        }
        return events;
    }

    private static AdClickEvent createEvent(String adId, long minuteBucket,
                                             String userId, String country,
                                             String device, String format) {
        Instant timestamp = Instant.ofEpochSecond(minuteBucket * 60 + new Random().nextInt(60));
        return new AdClickEvent(adId, timestamp, userId, "10.0.0.1", country, device, format);
    }

    private static String padRight(String s, int width) {
        return String.format("%-" + width + "s", s);
    }
}
