package com.logaggregation.storage;

import com.logaggregation.model.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Simulates Elasticsearch with time-based indices and inverted index for full-text search.
 * Each day gets its own index (e.g., "logs-2025-02-15").
 * Supports: full-text search, field filters, time-range queries.
 */
public class LogIndex {

    public enum Tier { HOT, WARM, COLD }

    private final Map<String, List<LogEntry>> indices = new ConcurrentHashMap<>();
    private final Map<String, Tier> indexTiers = new ConcurrentHashMap<>();
    private long totalIndexed = 0;

    public synchronized void index(LogEntry entry) {
        String indexName = "logs-" + entry.getDateKey();
        indices.computeIfAbsent(indexName, k -> {
            indexTiers.put(k, Tier.HOT);
            return Collections.synchronizedList(new ArrayList<>());
        }).add(entry);
        totalIndexed++;
    }

    public void bulkIndex(List<LogEntry> entries) {
        for (LogEntry entry : entries) {
            index(entry);
        }
    }

    /**
     * Search with full-text matching on message + field filters + time range.
     * Only searches HOT and WARM indices (COLD = archived to S3).
     */
    public SearchResult search(SearchQuery query) {
        long start = System.currentTimeMillis();

        List<String> targetIndices = getTargetIndices(query.getFrom(), query.getTo());

        Stream<LogEntry> stream = targetIndices.stream()
                .filter(idx -> {
                    Tier tier = indexTiers.getOrDefault(idx, Tier.HOT);
                    return tier == Tier.HOT || tier == Tier.WARM;
                })
                .map(idx -> indices.getOrDefault(idx, Collections.emptyList()))
                .flatMap(Collection::stream);

        if (query.getService() != null) {
            stream = stream.filter(e -> e.getService().equals(query.getService()));
        }
        if (query.getLevel() != null) {
            stream = stream.filter(e -> e.getLevel().isAtLeast(query.getLevel()));
        }
        if (query.getFrom() != null) {
            stream = stream.filter(e -> !e.getTimestamp().isBefore(query.getFrom()));
        }
        if (query.getTo() != null) {
            stream = stream.filter(e -> !e.getTimestamp().isAfter(query.getTo()));
        }
        if (query.getText() != null && !query.getText().isEmpty()) {
            String[] terms = query.getText().toLowerCase().split("\\s+");
            stream = stream.filter(e -> {
                String msg = e.getMessage().toLowerCase();
                for (String term : terms) {
                    if (!msg.contains(term)) return false;
                }
                return true;
            });
        }

        List<LogEntry> allMatches = stream
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .collect(Collectors.toList());

        long totalHits = allMatches.size();
        List<LogEntry> limited = allMatches.stream()
                .limit(query.getLimit())
                .collect(Collectors.toList());

        long tookMs = System.currentTimeMillis() - start;
        return new SearchResult(limited, totalHits, tookMs, false);
    }

    private List<String> getTargetIndices(Instant from, Instant to) {
        if (from == null && to == null) {
            return new ArrayList<>(indices.keySet());
        }
        return indices.keySet().stream()
                .filter(idx -> {
                    String dateStr = idx.replace("logs-", "");
                    if (from != null && dateStr.compareTo(from.toString().substring(0, 10)) < 0) return false;
                    if (to != null && dateStr.compareTo(to.toString().substring(0, 10)) > 0) return false;
                    return true;
                })
                .collect(Collectors.toList());
    }

    /**
     * Move an index to a different storage tier.
     */
    public boolean moveTier(String indexName, Tier newTier) {
        if (!indices.containsKey(indexName)) return false;
        Tier oldTier = indexTiers.get(indexName);
        indexTiers.put(indexName, newTier);
        System.out.printf("  [ILM] %s: %s → %s%n", indexName, oldTier, newTier);
        return true;
    }

    /**
     * Archive an index to cold storage (returns the entries, removes from searchable).
     */
    public List<LogEntry> archiveIndex(String indexName) {
        List<LogEntry> archived = indices.remove(indexName);
        indexTiers.remove(indexName);
        return archived != null ? archived : Collections.emptyList();
    }

    public Set<String> getIndexNames() { return new TreeSet<>(indices.keySet()); }
    public Tier getIndexTier(String indexName) { return indexTiers.get(indexName); }
    public int getIndexSize(String indexName) {
        List<LogEntry> entries = indices.get(indexName);
        return entries != null ? entries.size() : 0;
    }
    public long getTotalIndexed() { return totalIndexed; }

    public Map<String, String> getIndexSummary() {
        Map<String, String> summary = new TreeMap<>();
        for (String idx : indices.keySet()) {
            Tier tier = indexTiers.getOrDefault(idx, Tier.HOT);
            int size = indices.get(idx).size();
            summary.put(idx, String.format("%d docs [%s]", size, tier));
        }
        return summary;
    }
}
