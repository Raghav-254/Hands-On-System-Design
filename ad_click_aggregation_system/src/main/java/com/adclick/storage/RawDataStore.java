package com.adclick.storage;

import com.adclick.model.AdClickEvent;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the raw data database (e.g., Cassandra).
 * Stores every raw click event for:
 * - Data recalculation (if aggregation had a bug)
 * - Debugging and auditing
 * - Backup source of truth
 */
public class RawDataStore {
    private final List<AdClickEvent> events = new ArrayList<>();

    public void store(AdClickEvent event) {
        events.add(event);
    }

    public void storeBatch(List<AdClickEvent> batch) {
        events.addAll(batch);
    }

    /** Retrieve events in a time range (used for recalculation) */
    public List<AdClickEvent> getEventsByTimeRange(long startMinute, long endMinute) {
        return events.stream()
                .filter(e -> e.getMinuteBucket() >= startMinute && e.getMinuteBucket() < endMinute)
                .collect(Collectors.toList());
    }

    /** Retrieve events for a specific ad */
    public List<AdClickEvent> getEventsByAdId(String adId) {
        return events.stream()
                .filter(e -> e.getAdId().equals(adId))
                .collect(Collectors.toList());
    }

    public int getTotalEvents() { return events.size(); }

    /** Estimate storage: ~0.1 KB per event */
    public String getStorageEstimate() {
        double sizeKB = events.size() * 0.1;
        if (sizeKB > 1024 * 1024) return String.format("%.1f TB", sizeKB / 1024 / 1024);
        if (sizeKB > 1024) return String.format("%.1f GB", sizeKB / 1024);
        return String.format("%.1f KB", sizeKB);
    }
}
