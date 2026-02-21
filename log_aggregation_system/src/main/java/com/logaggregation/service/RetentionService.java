package com.logaggregation.service;

import com.logaggregation.model.LogEntry;
import com.logaggregation.storage.ColdStorage;
import com.logaggregation.storage.LogIndex;

import java.util.List;

/**
 * Manages index lifecycle: hot → warm → cold (S3) → delete.
 * Simulates Elasticsearch Index Lifecycle Management (ILM).
 */
public class RetentionService {
    private final LogIndex logIndex;
    private final ColdStorage coldStorage;

    public RetentionService(LogIndex logIndex, ColdStorage coldStorage) {
        this.logIndex = logIndex;
        this.coldStorage = coldStorage;
    }

    /**
     * Move an index from hot to warm tier (SSD → HDD, read-only).
     */
    public boolean moveToWarm(String indexName) {
        return logIndex.moveTier(indexName, LogIndex.Tier.WARM);
    }

    /**
     * Archive an index to cold storage (S3) and remove from Elasticsearch.
     */
    public boolean archiveToCold(String indexName) {
        List<LogEntry> entries = logIndex.archiveIndex(indexName);
        if (entries.isEmpty()) return false;

        coldStorage.archive(indexName, entries);
        System.out.printf("  [ILM] %s: archived %d docs to S3 (removed from ES)%n",
                indexName, entries.size());
        return true;
    }

    /**
     * Restore an index from cold storage back to Elasticsearch (for investigation).
     */
    public boolean restoreFromCold(String indexName) {
        if (!coldStorage.hasArchive(indexName)) return false;

        List<LogEntry> entries = coldStorage.restore(indexName);
        for (LogEntry e : entries) {
            logIndex.index(e);
        }
        System.out.printf("  [ILM] %s: restored %d docs from S3 to ES%n",
                indexName, entries.size());
        return true;
    }

    /**
     * Run lifecycle check on all indices (would be periodic in production).
     */
    public void runLifecycleCheck() {
        System.out.println("  [ILM] Running lifecycle check...");
        System.out.println("  Current indices:");
        logIndex.getIndexSummary().forEach((idx, info) ->
                System.out.printf("    %s: %s%n", idx, info));

        System.out.println("  Archived indices:");
        if (coldStorage.getArchivedIndices().isEmpty()) {
            System.out.println("    (none)");
        } else {
            coldStorage.getArchivedIndices().forEach(idx ->
                    System.out.printf("    %s (in S3)%n", idx));
        }
    }
}
