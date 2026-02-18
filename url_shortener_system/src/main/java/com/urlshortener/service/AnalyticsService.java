package com.urlshortener.service;

import com.urlshortener.model.ClickEvent;
import java.util.*;

/**
 * Consumes click events from Kafka and aggregates analytics.
 * In production: writes to ClickHouse / BigQuery for fast aggregation.
 */
public class AnalyticsService {

    private final Map<String, Integer> clickCounts = new LinkedHashMap<>();
    private final Map<String, Map<String, Integer>> countryBreakdown = new HashMap<>();
    private final Map<String, Map<String, Integer>> deviceBreakdown = new HashMap<>();
    private int totalEventsProcessed = 0;

    /** Process a click event (called by Kafka consumer). */
    public void processEvent(ClickEvent event) {
        String code = event.getShortCode();
        clickCounts.merge(code, 1, Integer::sum);

        countryBreakdown
            .computeIfAbsent(code, k -> new LinkedHashMap<>())
            .merge(event.getCountry(), 1, Integer::sum);

        deviceBreakdown
            .computeIfAbsent(code, k -> new LinkedHashMap<>())
            .merge(event.getDevice(), 1, Integer::sum);

        totalEventsProcessed++;
    }

    /** GET /v1/analytics/:shortCode */
    public void printAnalytics(String shortCode) {
        int total = clickCounts.getOrDefault(shortCode, 0);
        System.out.println("  Analytics for '" + shortCode + "':");
        System.out.println("    Total clicks: " + total);

        Map<String, Integer> countries = countryBreakdown.get(shortCode);
        if (countries != null) {
            System.out.println("    By country: " + countries);
        }

        Map<String, Integer> devices = deviceBreakdown.get(shortCode);
        if (devices != null) {
            System.out.println("    By device:  " + devices);
        }
    }

    public int getTotalEventsProcessed() { return totalEventsProcessed; }
    public int getClickCount(String shortCode) { return clickCounts.getOrDefault(shortCode, 0); }
}
