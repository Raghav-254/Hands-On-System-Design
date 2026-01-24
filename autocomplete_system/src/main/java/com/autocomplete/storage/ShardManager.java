package com.autocomplete.storage;

import com.autocomplete.cache.TrieCache;
import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;

/**
 * ShardManager - Manages sharding for the autocomplete system.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  SHARDING STRATEGY (From Figure 13-15)                                       ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  WHY SHARD?                                                                  ║
 * ║  ───────────                                                                 ║
 * ║  - Single server can't hold all trie data                                   ║
 * ║  - Need to distribute load across multiple servers                          ║
 * ║  - Improve availability (if one shard fails, others still work)            ║
 * ║                                                                               ║
 * ║  SHARDING APPROACHES:                                                        ║
 * ║  ─────────────────────                                                       ║
 * ║                                                                               ║
 * ║  1. SIMPLE: Shard by first character                                        ║
 * ║     ─────────────────────────────────                                        ║
 * ║     a-d → Shard 0                                                           ║
 * ║     e-h → Shard 1                                                           ║
 * ║     i-l → Shard 2                                                           ║
 * ║     ...                                                                      ║
 * ║                                                                               ║
 * ║     PROBLEM: Uneven distribution!                                           ║
 * ║     - 's' has way more words than 'x'                                       ║
 * ║     - Some shards overloaded, others underutilized                          ║
 * ║                                                                               ║
 * ║  2. SMART: Shard by historical data distribution (Figure 13-15)             ║
 * ║     ──────────────────────────────────────────────────────────              ║
 * ║     Analyze query frequency → create balanced shards                        ║
 * ║                                                                               ║
 * ║     Example:                                                                ║
 * ║     - 's' has 20% of queries → gets its own shard                          ║
 * ║     - 'u', 'v', 'w', 'x', 'y', 'z' combined = 20% → share one shard        ║
 * ║                                                                               ║
 * ║  ┌─────────────┐   ① What shard?    ┌─────────────────┐                     ║
 * ║  │ Web Servers │ ─────────────────► │ Shard Map       │                     ║
 * ║  └──────┬──────┘                    │ Manager         │                     ║
 * ║         │                           └─────────────────┘                     ║
 * ║         │ ② Retrieve data                                                   ║
 * ║         ▼                                                                    ║
 * ║  ┌─────────┬─────────┬─────────┐                                            ║
 * ║  │ Shard 1 │ Shard 2 │ Shard N │                                            ║
 * ║  └─────────┴─────────┴─────────┘                                            ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class ShardManager {
    
    // Shard map: prefix range → shard ID
    private final Map<String, Integer> shardMap;
    
    // Shards: shard ID → TrieCache
    private final Map<Integer, TrieCache> shards;
    
    // Number of shards
    private final int numShards;
    
    // Default: 26 shards (one per letter) - simple approach
    public ShardManager(int numShards) {
        this.numShards = numShards;
        this.shardMap = new HashMap<>();
        this.shards = new HashMap<>();
        
        initializeDefaultSharding();
    }
    
    /**
     * Initialize with smart sharding based on query distribution.
     * 
     * @param queryDistribution Map of first letter → percentage of queries
     */
    public ShardManager(Map<Character, Double> queryDistribution, int numShards) {
        this.numShards = numShards;
        this.shardMap = new HashMap<>();
        this.shards = new HashMap<>();
        
        initializeSmartSharding(queryDistribution);
    }
    
    /**
     * Simple sharding: Divide alphabet evenly.
     */
    private void initializeDefaultSharding() {
        System.out.println(String.format("\n[ShardManager] Initializing %d shards (simple sharding)...", numShards));
        
        int lettersPerShard = 26 / numShards;
        int currentShard = 0;
        
        for (char c = 'a'; c <= 'z'; c++) {
            shardMap.put(String.valueOf(c), currentShard);
            
            if ((c - 'a' + 1) % lettersPerShard == 0 && currentShard < numShards - 1) {
                currentShard++;
            }
        }
        
        printShardMapping();
    }
    
    /**
     * Smart sharding: Balance based on query distribution.
     * Ensures each shard handles roughly equal load.
     */
    private void initializeSmartSharding(Map<Character, Double> distribution) {
        System.out.println(String.format("\n[ShardManager] Initializing %d shards (smart sharding)...", numShards));
        
        double targetPerShard = 100.0 / numShards;
        int currentShard = 0;
        double currentLoad = 0;
        
        // Sort letters by frequency (ascending) for better packing
        List<Map.Entry<Character, Double>> sorted = new ArrayList<>(distribution.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        
        for (Map.Entry<Character, Double> entry : sorted) {
            char c = entry.getKey();
            double load = entry.getValue();
            
            // If this letter alone exceeds target, give it its own shard
            if (load >= targetPerShard && currentShard < numShards - 1) {
                shardMap.put(String.valueOf(c), currentShard);
                currentShard++;
                continue;
            }
            
            // Add to current shard
            shardMap.put(String.valueOf(c), currentShard);
            currentLoad += load;
            
            // Move to next shard if current is full
            if (currentLoad >= targetPerShard && currentShard < numShards - 1) {
                currentShard++;
                currentLoad = 0;
            }
        }
        
        printShardMapping();
    }
    
    /**
     * Register a shard.
     */
    public void registerShard(int shardId, TrieCache cache) {
        shards.put(shardId, cache);
        System.out.println(String.format("[ShardManager] Registered shard %d", shardId));
    }
    
    /**
     * Get the shard ID for a prefix.
     */
    public int getShardId(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 0;
        }
        
        String firstChar = String.valueOf(prefix.toLowerCase().charAt(0));
        return shardMap.getOrDefault(firstChar, 0);
    }
    
    /**
     * Get the cache for a prefix.
     */
    public TrieCache getCacheForPrefix(String prefix) {
        int shardId = getShardId(prefix);
        return shards.get(shardId);
    }
    
    /**
     * Get suggestions from the appropriate shard.
     */
    public List<Suggestion> getSuggestions(String prefix) {
        TrieCache cache = getCacheForPrefix(prefix);
        if (cache == null) {
            System.out.println(String.format("[ShardManager] No cache for shard %d", getShardId(prefix)));
            return Collections.emptyList();
        }
        return cache.getSuggestions(prefix);
    }
    
    /**
     * Get shard statistics.
     */
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("numShards", numShards);
        stats.put("registeredShards", shards.size());
        
        Map<Integer, List<String>> shardLetters = new HashMap<>();
        for (Map.Entry<String, Integer> entry : shardMap.entrySet()) {
            shardLetters.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                .add(entry.getKey());
        }
        stats.put("shardMapping", shardLetters);
        
        return stats;
    }
    
    /**
     * Print the shard mapping.
     */
    private void printShardMapping() {
        Map<Integer, List<String>> shardLetters = new HashMap<>();
        for (Map.Entry<String, Integer> entry : shardMap.entrySet()) {
            shardLetters.computeIfAbsent(entry.getValue(), k -> new ArrayList<>())
                .add(entry.getKey());
        }
        
        System.out.println("Shard mapping:");
        for (int i = 0; i < numShards; i++) {
            List<String> letters = shardLetters.getOrDefault(i, Collections.emptyList());
            Collections.sort(letters);
            System.out.println(String.format("  Shard %d: %s", i, String.join(", ", letters)));
        }
    }
    
    /**
     * Get example query distribution (for demo).
     */
    public static Map<Character, Double> getExampleDistribution() {
        // Approximate English letter frequency in searches
        Map<Character, Double> dist = new HashMap<>();
        dist.put('s', 12.0);  // 's' is very common
        dist.put('c', 8.0);
        dist.put('p', 7.0);
        dist.put('a', 6.0);
        dist.put('b', 6.0);
        dist.put('m', 5.0);
        dist.put('t', 5.0);
        dist.put('d', 5.0);
        dist.put('f', 4.0);
        dist.put('h', 4.0);
        dist.put('l', 4.0);
        dist.put('r', 4.0);
        dist.put('w', 4.0);
        dist.put('g', 3.0);
        dist.put('n', 3.0);
        dist.put('e', 2.0);
        dist.put('i', 2.0);
        dist.put('o', 2.0);
        dist.put('j', 1.5);
        dist.put('k', 1.5);
        dist.put('u', 1.5);
        dist.put('v', 1.0);
        dist.put('y', 1.0);
        dist.put('q', 0.5);
        dist.put('x', 0.5);
        dist.put('z', 0.5);
        return dist;
    }
}

