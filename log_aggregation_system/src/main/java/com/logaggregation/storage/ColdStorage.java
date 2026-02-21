package com.logaggregation.storage;

import com.logaggregation.model.LogEntry;

import java.util.*;

/**
 * Simulates S3 cold storage for archived log indices.
 * Once archived, logs are not directly searchable — must be restored to ES first.
 */
public class ColdStorage {
    private final Map<String, List<LogEntry>> archivedIndices = new LinkedHashMap<>();

    public void archive(String indexName, List<LogEntry> entries) {
        archivedIndices.put(indexName, new ArrayList<>(entries));
    }

    public List<LogEntry> restore(String indexName) {
        return archivedIndices.getOrDefault(indexName, Collections.emptyList());
    }

    public boolean hasArchive(String indexName) {
        return archivedIndices.containsKey(indexName);
    }

    public Set<String> getArchivedIndices() {
        return archivedIndices.keySet();
    }

    public long getTotalArchivedDocs() {
        return archivedIndices.values().stream().mapToLong(List::size).sum();
    }

    @Override
    public String toString() {
        return String.format("ColdStorage[%d indices, %d total docs]",
                archivedIndices.size(), getTotalArchivedDocs());
    }
}
