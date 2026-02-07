package com.adclick.service;

import com.adclick.aggregation.MapReduceEngine;
import com.adclick.aggregation.WatermarkHandler;
import com.adclick.model.AdClickEvent;
import com.adclick.model.AggregatedResult;
import com.adclick.storage.AggregationStore;
import com.adclick.storage.RawDataStore;

import java.util.*;

/**
 * The Data Aggregation Service.
 *
 * Consumes events from message queue (Kafka), runs MapReduce aggregation,
 * and writes results to both:
 * 1. Downstream message queue (for DB writer to consume)
 * 2. Raw data store (for recalculation)
 *
 * Supports exactly-once processing via atomic commit:
 * - Aggregation result + offset commit happen atomically
 */
public class AggregationService {
    private final MapReduceEngine engine;
    private final WatermarkHandler watermarkHandler;
    private final AggregationStore aggregationStore;
    private final RawDataStore rawDataStore;
    private final int topN;

    // Simulates committed offset for exactly-once semantics
    private long lastCommittedOffset = 0;

    public AggregationService(AggregationStore aggregationStore, RawDataStore rawDataStore, int topN) {
        this.engine = new MapReduceEngine();
        this.watermarkHandler = new WatermarkHandler(2); // 2-minute watermark delay
        this.aggregationStore = aggregationStore;
        this.rawDataStore = rawDataStore;
        this.topN = topN;
    }

    /**
     * Process a batch of events for a given minute window.
     * Simulates the full pipeline: ingest → classify → aggregate → store
     */
    public void processBatch(List<AdClickEvent> events, long minuteBucket) {
        System.out.println("\n--- Processing minute bucket: " + minuteBucket + " ---");

        // Step 1: Store raw events
        rawDataStore.storeBatch(events);
        System.out.println("  [Raw Store] Saved " + events.size() + " raw events");

        // Step 2: Classify events (on-time vs late)
        watermarkHandler.advanceWatermark(minuteBucket);
        List<AdClickEvent> onTimeEvents = new ArrayList<>();
        int lateCount = 0, droppedCount = 0;

        for (AdClickEvent event : events) {
            switch (watermarkHandler.classifyEvent(event)) {
                case ON_TIME -> onTimeEvents.add(event);
                case LATE_ACCEPTED -> { onTimeEvents.add(event); lateCount++; }
                case TOO_LATE_DROPPED -> droppedCount++;
            }
        }
        System.out.println("  [Watermark] On-time: " + (onTimeEvents.size() - lateCount) +
                ", Late-accepted: " + lateCount + ", Dropped: " + droppedCount);

        // Step 3: MapReduce aggregation
        List<AggregatedResult> topResults = engine.processWindow(onTimeEvents, minuteBucket, topN);

        // Step 4: Aggregate with filter (e.g., by country)
        List<AggregatedResult> filteredResults = engine.aggregateWithFilter(
                onTimeEvents, minuteBucket, "country");

        // Step 5: Atomic commit — store results + commit offset together
        atomicCommit(topResults, filteredResults, minuteBucket);
    }

    /**
     * Atomic commit: Ensures exactly-once semantics.
     * Either ALL of these succeed or NONE:
     * 1. Write aggregation results
     * 2. Write filtered results
     * 3. Update committed offset
     */
    private void atomicCommit(List<AggregatedResult> topResults,
                               List<AggregatedResult> filteredResults,
                               long minuteBucket) {
        // Simulate transaction
        for (AggregatedResult result : topResults) {
            aggregationStore.store(result);
        }
        aggregationStore.storeTopN(minuteBucket, topResults);

        for (AggregatedResult result : filteredResults) {
            aggregationStore.store(result);
        }

        lastCommittedOffset = minuteBucket;
        System.out.println("  [Atomic Commit] Results stored + offset committed for minute " + minuteBucket);
    }

    /**
     * Recalculation: Replay raw events to recompute aggregations.
     * Used when a bug is found in aggregation logic.
     */
    public void recalculate(long startMinute, long endMinute) {
        System.out.println("\n=== RECALCULATION: minutes " + startMinute + " to " + endMinute + " ===");

        List<AdClickEvent> rawEvents = rawDataStore.getEventsByTimeRange(startMinute, endMinute);
        System.out.println("  [Recalculation] Replaying " + rawEvents.size() + " raw events");

        // Group by minute bucket and re-aggregate
        Map<Long, List<AdClickEvent>> byMinute = new LinkedHashMap<>();
        for (AdClickEvent event : rawEvents) {
            byMinute.computeIfAbsent(event.getMinuteBucket(), k -> new ArrayList<>()).add(event);
        }

        for (Map.Entry<Long, List<AdClickEvent>> entry : byMinute.entrySet()) {
            List<AggregatedResult> results = engine.processWindow(entry.getValue(), entry.getKey(), topN);
            aggregationStore.storeTopN(entry.getKey(), results);
        }

        System.out.println("  [Recalculation] Complete!");
    }

    public long getLastCommittedOffset() { return lastCommittedOffset; }
}
