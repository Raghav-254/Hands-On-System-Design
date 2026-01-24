package com.autocomplete.pipeline;

import com.autocomplete.trie.Trie;
import com.autocomplete.storage.TrieDB;
import java.util.*;

/**
 * TrieWorker - Builds/updates the Trie from aggregated data.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRIE UPDATE FLOW (From Figure 13-9, 13-13)                                  ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Aggregated Data → Workers → Trie DB                                        ║
 * ║                                   │                                          ║
 * ║                           Weekly Snapshot                                    ║
 * ║                                   │                                          ║
 * ║                                   ▼                                          ║
 * ║                             Trie Cache                                       ║
 * ║                                                                               ║
 * ║  WORKER RESPONSIBILITIES:                                                   ║
 * ║  ─────────────────────────                                                   ║
 * ║  1. Build new Trie from aggregated data                                     ║
 * ║  2. Compute top-k at each node                                              ║
 * ║  3. Serialize and store in Trie DB                                          ║
 * ║  4. Trigger cache refresh                                                   ║
 * ║                                                                               ║
 * ║  UPDATE STRATEGY (Figure 13-13):                                            ║
 * ║  ───────────────────────────────                                            ║
 * ║  - Rebuild weekly (or daily for high-traffic systems)                       ║
 * ║  - Create new trie, then atomically swap                                    ║
 * ║  - Old trie remains until new one is ready                                  ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TrieWorker {
    
    private final Aggregator aggregator;
    private final TrieDB trieDB;
    private final int topK;
    
    public TrieWorker(Aggregator aggregator, TrieDB trieDB, int topK) {
        this.aggregator = aggregator;
        this.trieDB = trieDB;
        this.topK = topK;
    }
    
    public TrieWorker(Aggregator aggregator, TrieDB trieDB) {
        this(aggregator, trieDB, 5);
    }
    
    /**
     * Build a new Trie from the current aggregated data.
     * 
     * In production:
     * - This runs as a weekly batch job
     * - Uses distributed workers (Hadoop/Spark)
     * - Can take hours for large datasets
     * 
     * @return The newly built Trie
     */
    public Trie buildTrie() {
        System.out.println("\n[TrieWorker] Building new Trie from aggregated data...");
        
        Map<String, Integer> data = aggregator.getAggregatedCounts();
        System.out.println(String.format("[TrieWorker] Processing %d unique queries", data.size()));
        
        Trie trie = new Trie(topK);
        
        // Insert all words with their frequencies
        for (Map.Entry<String, Integer> entry : data.entrySet()) {
            trie.insert(entry.getKey(), entry.getValue());
        }
        
        System.out.println("[TrieWorker] Trie built successfully!");
        return trie;
    }
    
    /**
     * Build trie and persist to DB.
     * This is the main job that runs weekly.
     */
    public void buildAndPersist() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("[TrieWorker] Starting weekly trie rebuild...");
        System.out.println("=".repeat(60));
        
        // Step 1: Build new trie
        Trie newTrie = buildTrie();
        
        // Step 2: Persist to DB
        System.out.println("[TrieWorker] Persisting trie to database...");
        trieDB.saveTrie(newTrie);
        
        // Step 3: Update version (triggers cache refresh)
        trieDB.incrementVersion();
        
        System.out.println("[TrieWorker] Trie rebuild complete!");
        System.out.println("=".repeat(60) + "\n");
    }
    
    /**
     * Incremental update: Update frequencies for specific words.
     * Used for hot updates between full rebuilds.
     * 
     * @param updates Map of word → new frequency
     */
    public void incrementalUpdate(Map<String, Integer> updates) {
        System.out.println(String.format("\n[TrieWorker] Incremental update: %d words", updates.size()));
        
        Trie currentTrie = trieDB.loadTrie();
        if (currentTrie == null) {
            System.out.println("[TrieWorker] No existing trie, building new one...");
            Trie newTrie = new Trie(topK);
            for (Map.Entry<String, Integer> entry : updates.entrySet()) {
                newTrie.insert(entry.getKey(), entry.getValue());
            }
            trieDB.saveTrie(newTrie);
            return;
        }
        
        // Update existing trie
        for (Map.Entry<String, Integer> entry : updates.entrySet()) {
            String word = entry.getKey();
            int newFreq = entry.getValue();
            
            if (!currentTrie.updateFrequency(word, newFreq)) {
                // Word doesn't exist, insert it
                currentTrie.insert(word, newFreq);
            }
        }
        
        trieDB.saveTrie(currentTrie);
        System.out.println("[TrieWorker] Incremental update complete!");
    }
    
    /**
     * Get statistics about the current trie.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("aggregatedQueries", aggregator.getAggregatedCounts().size());
        stats.put("topK", topK);
        stats.put("trieVersion", trieDB.getVersion());
        return stats;
    }
}

