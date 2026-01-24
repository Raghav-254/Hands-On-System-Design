package com.autocomplete.storage;

import com.autocomplete.trie.Trie;
import com.autocomplete.trie.TrieNode;
import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TrieDB - Persistent storage for the Trie.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRIE DATABASE (From Figure 13-9, 13-10)                                     ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  STORAGE OPTIONS:                                                            ║
 * ║  ─────────────────                                                           ║
 * ║  1. Document Store: Store serialized trie as JSON/binary                    ║
 * ║  2. Key-Value Store: Each prefix → top-k suggestions (Figure 13-10)         ║
 * ║  3. Relational: Table with (prefix, word, frequency, rank)                  ║
 * ║                                                                               ║
 * ║  KEY-VALUE APPROACH (Figure 13-10):                                          ║
 * ║  ┌─────────────────────────────────────────────────────────────────────────┐ ║
 * ║  │  Key         Value                                                      │ ║
 * ║  │  ─────────   ─────────────────────────────────────────────────────────  │ ║
 * ║  │  "b"         [(be, 15), (bee, 20), (beer, 10), (best, 35)]             │ ║
 * ║  │  "be"        [(be, 15), (bee, 20), (beer, 10), (best, 35)]             │ ║
 * ║  │  "bee"       [(bee, 20), (beer, 10)]                                    │ ║
 * ║  │  "beer"      [(beer, 10)]                                               │ ║
 * ║  │  ...                                                                    │ ║
 * ║  └─────────────────────────────────────────────────────────────────────────┘ ║
 * ║                                                                               ║
 * ║  BENEFITS OF K-V APPROACH:                                                   ║
 * ║  - Direct lookup: Get suggestions for prefix in O(1)                        ║
 * ║  - Easy sharding: Shard by prefix first character                          ║
 * ║  - Cache-friendly: Each key is independently cacheable                      ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TrieDB {
    
    // In production: This would be Redis, DynamoDB, or Cassandra
    // Here we store prefix → top-k suggestions (Figure 13-10)
    private final Map<String, List<Suggestion>> prefixStore;
    
    // Store the full trie for incremental updates
    private Trie currentTrie;
    
    // Version number (for cache invalidation)
    private final AtomicLong version;
    
    // For tracking storage metrics
    private int totalPrefixes;
    private int totalWords;
    
    public TrieDB() {
        this.prefixStore = new HashMap<>();
        this.version = new AtomicLong(0);
        this.totalPrefixes = 0;
        this.totalWords = 0;
    }
    
    /**
     * Save a trie to the database.
     * Converts the trie to key-value format (Figure 13-10).
     */
    public void saveTrie(Trie trie) {
        System.out.println("\n[TrieDB] Saving trie to database...");
        
        this.currentTrie = trie;
        prefixStore.clear();
        
        // Traverse trie and store each prefix's top-k
        traverseAndStore(trie.getRoot(), "");
        
        totalPrefixes = prefixStore.size();
        System.out.println(String.format("[TrieDB] Stored %d prefixes", totalPrefixes));
    }
    
    private void traverseAndStore(TrieNode node, String prefix) {
        // Store top-k for this prefix (if not empty)
        List<Suggestion> topK = node.getTopKCache();
        if (!topK.isEmpty()) {
            prefixStore.put(prefix, new ArrayList<>(topK));
        }
        
        // Count words
        if (node.isEndOfWord()) {
            totalWords++;
        }
        
        // Recurse to children
        for (Map.Entry<Character, TrieNode> entry : node.getChildren().entrySet()) {
            traverseAndStore(entry.getValue(), prefix + entry.getKey());
        }
    }
    
    /**
     * Load the trie from database.
     */
    public Trie loadTrie() {
        return currentTrie;
    }
    
    /**
     * Get suggestions for a prefix directly from the key-value store.
     * This is the fast path used by the cache.
     * 
     * @param prefix The search prefix
     * @return List of suggestions, or empty if not found
     */
    public List<Suggestion> getSuggestionsForPrefix(String prefix) {
        prefix = prefix.toLowerCase().replaceAll("[^a-z]", "");
        return prefixStore.getOrDefault(prefix, Collections.emptyList());
    }
    
    /**
     * Get all prefixes (for debugging/testing).
     */
    public Set<String> getAllPrefixes() {
        return new HashSet<>(prefixStore.keySet());
    }
    
    /**
     * Get the current version.
     * Used for cache invalidation.
     */
    public long getVersion() {
        return version.get();
    }
    
    /**
     * Increment version (triggers cache refresh).
     */
    public long incrementVersion() {
        return version.incrementAndGet();
    }
    
    /**
     * Get storage statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalPrefixes", totalPrefixes);
        stats.put("totalWords", totalWords);
        stats.put("version", version.get());
        stats.put("storeSizeBytes", estimateStorageSize());
        return stats;
    }
    
    /**
     * Estimate storage size (for capacity planning).
     */
    private long estimateStorageSize() {
        long size = 0;
        for (Map.Entry<String, List<Suggestion>> entry : prefixStore.entrySet()) {
            // Key size
            size += entry.getKey().length() * 2;  // chars are 2 bytes
            // Value size: each suggestion ~50 bytes average
            size += entry.getValue().size() * 50;
        }
        return size;
    }
    
    /**
     * Print database contents (for debugging).
     */
    public void printContents() {
        System.out.println("\n=== TRIE DB CONTENTS ===");
        System.out.println(String.format("Version: %d", version.get()));
        System.out.println(String.format("Total prefixes: %d", totalPrefixes));
        System.out.println(String.format("Total words: %d", totalWords));
        
        System.out.println("\nSample prefixes:");
        prefixStore.entrySet().stream()
            .limit(10)
            .forEach(e -> System.out.println(String.format("  '%s' → %s", e.getKey(), e.getValue())));
    }
}

