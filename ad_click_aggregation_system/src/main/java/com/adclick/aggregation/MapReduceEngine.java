package com.adclick.aggregation;

import com.adclick.model.AdClickEvent;
import com.adclick.model.AggregatedResult;

import java.util.*;
import java.util.stream.Collectors;

/**
 * MapReduce-style aggregation engine.
 *
 * Pipeline:
 *   Input Events → Map (group by ad_id) → Aggregate (count per ad) → Reduce (top N)
 *
 * This is the core processing logic of the Data Aggregation Service.
 */
public class MapReduceEngine {

    /**
     * MAP phase: Group events by ad_id within a minute bucket.
     * Each mapper node handles a subset of events.
     */
    public Map<String, List<AdClickEvent>> map(List<AdClickEvent> events) {
        return events.stream()
                .collect(Collectors.groupingBy(AdClickEvent::getAdId));
    }

    /**
     * AGGREGATE phase: Count clicks per ad_id in a minute bucket.
     */
    public List<AggregatedResult> aggregate(Map<String, List<AdClickEvent>> mapped, long minuteBucket) {
        List<AggregatedResult> results = new ArrayList<>();
        for (Map.Entry<String, List<AdClickEvent>> entry : mapped.entrySet()) {
            results.add(new AggregatedResult(entry.getKey(), minuteBucket, entry.getValue().size()));
        }
        return results;
    }

    /**
     * AGGREGATE with filter: Count clicks per ad_id grouped by a filter dimension.
     * e.g., filter by "country" → separate counts for US, UK, IN, etc.
     */
    public List<AggregatedResult> aggregateWithFilter(List<AdClickEvent> events,
                                                       long minuteBucket,
                                                       String filterDimension) {
        List<AggregatedResult> results = new ArrayList<>();

        // Group by (ad_id, filter_value)
        Map<String, Map<String, List<AdClickEvent>>> grouped = events.stream()
                .collect(Collectors.groupingBy(
                        AdClickEvent::getAdId,
                        Collectors.groupingBy(e -> e.getTags().getOrDefault(filterDimension, "unknown"))
                ));

        for (Map.Entry<String, Map<String, List<AdClickEvent>>> adEntry : grouped.entrySet()) {
            for (Map.Entry<String, List<AdClickEvent>> filterEntry : adEntry.getValue().entrySet()) {
                String filterId = filterDimension + ":" + filterEntry.getKey();
                results.add(new AggregatedResult(
                        adEntry.getKey(), minuteBucket,
                        filterEntry.getValue().size(), filterId));
            }
        }
        return results;
    }

    /**
     * REDUCE phase: Find top N most clicked ads from aggregated results.
     * Each node computes local top-N, then results are merged globally.
     */
    public List<AggregatedResult> reduceTopN(List<AggregatedResult> aggregated, int n) {
        return aggregated.stream()
                .filter(r -> r.getFilterId() == null) // only unfiltered results
                .sorted((a, b) -> Long.compare(b.getCount(), a.getCount()))
                .limit(n)
                .collect(Collectors.toList());
    }

    /**
     * Full pipeline: Map → Aggregate → Reduce (Top N)
     */
    public List<AggregatedResult> processWindow(List<AdClickEvent> events, long minuteBucket, int topN) {
        System.out.println("  [MapReduce] Processing " + events.size() + " events for minute " + minuteBucket);

        // Map phase
        Map<String, List<AdClickEvent>> mapped = map(events);
        System.out.println("  [Map] Grouped into " + mapped.size() + " ad_ids");

        // Aggregate phase
        List<AggregatedResult> aggregated = aggregate(mapped, minuteBucket);
        System.out.println("  [Aggregate] Counted clicks per ad_id");

        // Reduce phase (top N)
        List<AggregatedResult> topResults = reduceTopN(aggregated, topN);
        System.out.println("  [Reduce] Top " + topN + " ads selected");

        return topResults;
    }
}
