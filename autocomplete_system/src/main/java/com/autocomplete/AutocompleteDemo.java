package com.autocomplete;

import com.autocomplete.api.AutocompleteService;
import com.autocomplete.api.AutocompleteService.AutocompleteResponse;
import com.autocomplete.cache.TrieCache;
import com.autocomplete.pipeline.*;
import com.autocomplete.storage.*;
import com.autocomplete.trie.*;
import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;

/**
 * AutocompleteDemo - Demonstrates the complete Search Autocomplete System.
 * 
 * Based on Alex Xu's System Design Interview - Chapter 13
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  SYSTEM OVERVIEW                                                             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  REQUIREMENTS:                                                               ║
 * ║  • Prefix matching only (beginning of query)                                 ║
 * ║  • Return top 5 suggestions by popularity                                    ║
 * ║  • < 100ms latency                                                           ║
 * ║  • 10M DAU, highly available, scalable                                       ║
 * ║                                                                               ║
 * ║  COMPONENTS:                                                                 ║
 * ║  1. Trie - Core data structure with top-k caching                           ║
 * ║  2. AnalyticsLog - Collects raw search queries                              ║
 * ║  3. Aggregator - Aggregates query frequencies                               ║
 * ║  4. TrieWorker - Builds/updates trie from aggregated data                   ║
 * ║  5. TrieDB - Persistent storage (key-value: prefix → suggestions)           ║
 * ║  6. TrieCache - In-memory cache for fast lookups                            ║
 * ║  7. AutocompleteService - API layer                                         ║
 * ║  8. ShardManager - Distributes data across shards                           ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class AutocompleteDemo {
    
    public static void main(String[] args) {
        System.out.println("╔═══════════════════════════════════════════════════════════════╗");
        System.out.println("║       SEARCH AUTOCOMPLETE SYSTEM - HANDS-ON DEMO             ║");
        System.out.println("║       Based on Alex Xu's System Design Interview             ║");
        System.out.println("╚═══════════════════════════════════════════════════════════════╝");
        
        // Run all demos
        demoTrieDataStructure();
        demoDataPipeline();
        demoQueryFlow();
        demoSharding();
        
        System.out.println("\n" + "═".repeat(60));
        System.out.println("All demos completed successfully!");
        System.out.println("═".repeat(60));
    }
    
    /**
     * Demo 1: Trie Data Structure with Top-K Caching
     */
    public static void demoTrieDataStructure() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 1: TRIE DATA STRUCTURE WITH TOP-K CACHING");
        System.out.println("═".repeat(60));
        
        // Create trie (as shown in Figure 13-8)
        Trie trie = new Trie(5);  // Top 5 suggestions
        
        // Insert words with frequencies
        System.out.println("\nInserting words (from Figure 13-8):");
        trie.insert("best", 35);
        trie.insert("bet", 29);
        trie.insert("bee", 20);
        trie.insert("be", 15);
        trie.insert("buy", 14);
        trie.insert("beer", 10);
        trie.insert("win", 11);
        
        // Print trie structure
        trie.printTrie();
        
        // Query for suggestions
        System.out.println("\n--- AUTOCOMPLETE QUERIES ---");
        
        queryAndPrint(trie, "b");
        queryAndPrint(trie, "be");
        queryAndPrint(trie, "bee");
        queryAndPrint(trie, "bes");
        queryAndPrint(trie, "w");
        queryAndPrint(trie, "xyz");  // No match
        
        // Update frequency (Figure 13-13 shows this)
        System.out.println("\n--- FREQUENCY UPDATE ---");
        System.out.println("Updating 'beer' frequency: 10 → 30");
        trie.updateFrequency("beer", 30);
        
        queryAndPrint(trie, "be");  // beer should now be higher ranked
    }
    
    private static void queryAndPrint(Trie trie, String prefix) {
        List<Suggestion> suggestions = trie.getAutocompleteSuggestions(prefix);
        System.out.println(String.format("  '%s' → %s", prefix, suggestions));
    }
    
    /**
     * Demo 2: Data Collection Pipeline
     */
    public static void demoDataPipeline() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 2: DATA COLLECTION PIPELINE");
        System.out.println("═".repeat(60));
        
        // Create pipeline components
        AnalyticsLog analyticsLog = new AnalyticsLog();
        Aggregator aggregator = new Aggregator(analyticsLog);
        TrieDB trieDB = new TrieDB();
        TrieWorker worker = new TrieWorker(aggregator, trieDB, 5);
        
        // Simulate user searches
        System.out.println("\n--- SIMULATING USER SEARCHES ---");
        
        // User 1 searches
        analyticsLog.logQuery("best buy", "user1");
        analyticsLog.logQuery("best movies", "user1");
        analyticsLog.logQuery("best restaurants", "user1");
        
        // User 2 searches
        analyticsLog.logQuery("best buy", "user2");
        analyticsLog.logQuery("beer near me", "user2");
        analyticsLog.logQuery("bee movie", "user2");
        
        // User 3 searches (same queries - should increase frequency)
        analyticsLog.logQuery("best buy", "user3");
        analyticsLog.logQuery("best buy", "user3");  // Duplicate from same user - deduped!
        analyticsLog.logQuery("best movies", "user3");
        
        // Aggregate the logs
        System.out.println("\n--- AGGREGATING LOGS ---");
        aggregator.processLogs();
        aggregator.printSummary();
        
        // Build trie from aggregated data
        System.out.println("\n--- BUILDING TRIE FROM AGGREGATED DATA ---");
        worker.buildAndPersist();
        
        // Verify trie DB
        trieDB.printContents();
    }
    
    /**
     * Demo 3: Query Flow (User → API → Cache → DB)
     */
    public static void demoQueryFlow() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 3: QUERY FLOW (API → CACHE → DB)");
        System.out.println("═".repeat(60));
        
        // Set up the system
        AnalyticsLog analyticsLog = new AnalyticsLog();
        Aggregator aggregator = new Aggregator(analyticsLog);
        TrieDB trieDB = new TrieDB();
        TrieCache trieCache = new TrieCache(trieDB);
        
        // Pre-populate with data
        Trie trie = new Trie(5);
        trie.insert("facebook", 100);
        trie.insert("facebook login", 80);
        trie.insert("facebook marketplace", 60);
        trie.insert("fast food near me", 50);
        trie.insert("fantasy football", 45);
        trie.insert("fedex tracking", 40);
        trie.insert("firefox", 35);
        trie.insert("flights", 30);
        trieDB.saveTrie(trie);
        trieDB.incrementVersion();
        
        // Create API service
        AutocompleteService apiService = new AutocompleteService(trieCache, analyticsLog, "server-1");
        
        // Simulate API requests
        System.out.println("\n--- SIMULATING API REQUESTS ---");
        
        // First request: cache miss
        System.out.println("\n[Request 1: Cache MISS expected]");
        AutocompleteResponse resp1 = apiService.getSuggestions("fa", "user123");
        
        // Second request with same prefix: cache HIT
        System.out.println("\n[Request 2: Cache HIT expected]");
        AutocompleteResponse resp2 = apiService.getSuggestions("fa", "user456");
        
        // Different prefix
        System.out.println("\n[Request 3: Different prefix]");
        AutocompleteResponse resp3 = apiService.getSuggestions("fi", "user123");
        
        // Show cache stats
        System.out.println("\n--- CACHE STATISTICS ---");
        Map<String, Object> stats = apiService.getStats();
        stats.forEach((k, v) -> System.out.println(String.format("  %s: %s", k, v)));
    }
    
    /**
     * Demo 4: Sharding Strategy
     */
    public static void demoSharding() {
        System.out.println("\n" + "═".repeat(60));
        System.out.println("DEMO 4: SHARDING STRATEGY");
        System.out.println("═".repeat(60));
        
        // Simple sharding (even distribution)
        System.out.println("\n--- SIMPLE SHARDING (4 shards) ---");
        ShardManager simpleManager = new ShardManager(4);
        
        // Show which shard handles which queries
        String[] testPrefixes = {"apple", "banana", "cat", "dog", "elephant", "facebook", 
                                  "google", "hello", "instagram", "java", "kotlin", "linkedin"};
        
        System.out.println("\nQuery routing:");
        for (String prefix : testPrefixes) {
            int shardId = simpleManager.getShardId(prefix);
            System.out.println(String.format("  '%s' → Shard %d", prefix, shardId));
        }
        
        // Smart sharding (based on query distribution)
        System.out.println("\n--- SMART SHARDING (based on query distribution) ---");
        Map<Character, Double> distribution = ShardManager.getExampleDistribution();
        ShardManager smartManager = new ShardManager(distribution, 4);
        
        System.out.println("\nQuery routing with smart sharding:");
        for (String prefix : testPrefixes) {
            int shardId = smartManager.getShardId(prefix);
            System.out.println(String.format("  '%s' → Shard %d", prefix, shardId));
        }
        
        // Show benefit
        System.out.println("\n--- WHY SMART SHARDING? ---");
        System.out.println("Simple sharding: 's' and 'z' same load per shard");
        System.out.println("Smart sharding: 's' gets dedicated shard (12% of queries)");
        System.out.println("               'u','v','w','x','y','z' share a shard (combined ~5%)");
    }
}

