package com.autocomplete.trie;

import java.util.*;

/**
 * TrieNode - A node in the Trie with cached top-k suggestions.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRIE NODE STRUCTURE (From Figure 13-8, 13-10)                               ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Each node contains:                                                         ║
 * ║  1. children: Map<Character, TrieNode> - child nodes                         ║
 * ║  2. isEndOfWord: boolean - marks complete words                              ║
 * ║  3. frequency: int - search frequency (only for word-ending nodes)           ║
 * ║  4. topK: List<Suggestion> - CACHED top-k suggestions from this prefix       ║
 * ║                                                                               ║
 * ║  Example: Node for prefix "be"                                               ║
 * ║  ┌──────────────────────────────────────────────────────────────────────┐    ║
 * ║  │  prefix: "be"                                                        │    ║
 * ║  │  topK: [("best", 35), ("bet", 29), ("bee", 20), ("beer", 10)]       │    ║
 * ║  │  children: {'e' → node, 's' → node, 't' → node}                     │    ║
 * ║  └──────────────────────────────────────────────────────────────────────┘    ║
 * ║                                                                               ║
 * ║  KEY OPTIMIZATION: Top-k is cached at each node!                            ║
 * ║  - Without cache: Traverse all children to find top-k = O(n)                ║
 * ║  - With cache: Just return cached list = O(1)                               ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TrieNode {
    
    private final Map<Character, TrieNode> children;
    private boolean isEndOfWord;
    private int frequency;  // Only meaningful if isEndOfWord = true
    private String word;    // The complete word (only if isEndOfWord = true)
    
    // CRITICAL: Cached top-k suggestions from this prefix
    // This is the key optimization from Figure 13-10
    private List<Suggestion> topKCache;
    
    private static final int DEFAULT_K = 5;
    
    public TrieNode() {
        this.children = new HashMap<>();
        this.isEndOfWord = false;
        this.frequency = 0;
        this.word = null;
        this.topKCache = new ArrayList<>();
    }
    
    // ===== Child Management =====
    
    public boolean hasChild(char c) {
        return children.containsKey(c);
    }
    
    public TrieNode getChild(char c) {
        return children.get(c);
    }
    
    public TrieNode addChild(char c) {
        if (!children.containsKey(c)) {
            children.put(c, new TrieNode());
        }
        return children.get(c);
    }
    
    public Map<Character, TrieNode> getChildren() {
        return children;
    }
    
    // ===== Word Termination =====
    
    public boolean isEndOfWord() {
        return isEndOfWord;
    }
    
    public void setEndOfWord(boolean endOfWord, String word) {
        this.isEndOfWord = endOfWord;
        this.word = word;
    }
    
    public String getWord() {
        return word;
    }
    
    // ===== Frequency Management =====
    
    public int getFrequency() {
        return frequency;
    }
    
    public void setFrequency(int frequency) {
        this.frequency = frequency;
    }
    
    public void incrementFrequency() {
        this.frequency++;
    }
    
    public void incrementFrequency(int delta) {
        this.frequency += delta;
    }
    
    // ===== Top-K Cache Management =====
    
    /**
     * Get cached top-k suggestions.
     * This is O(1) - just return the pre-computed list!
     */
    public List<Suggestion> getTopKCache() {
        return Collections.unmodifiableList(topKCache);
    }
    
    /**
     * Update the top-k cache with a new suggestion.
     * Called during trie updates (from workers processing aggregated data).
     * 
     * @param suggestion The suggestion to potentially add
     * @param k Maximum number of suggestions to keep
     */
    public void updateTopKCache(Suggestion suggestion, int k) {
        // Check if this word already exists in cache
        boolean found = false;
        for (int i = 0; i < topKCache.size(); i++) {
            if (topKCache.get(i).getWord().equals(suggestion.getWord())) {
                // Update existing entry
                topKCache.set(i, suggestion);
                found = true;
                break;
            }
        }
        
        if (!found) {
            topKCache.add(suggestion);
        }
        
        // Sort by frequency (descending)
        topKCache.sort((a, b) -> Integer.compare(b.getFrequency(), a.getFrequency()));
        
        // Keep only top k
        if (topKCache.size() > k) {
            topKCache = new ArrayList<>(topKCache.subList(0, k));
        }
    }
    
    /**
     * Set the entire top-k cache (used during trie loading from DB).
     */
    public void setTopKCache(List<Suggestion> cache) {
        this.topKCache = new ArrayList<>(cache);
    }
    
    /**
     * Suggestion - A word with its frequency score.
     */
    public static class Suggestion implements Comparable<Suggestion> {
        private final String word;
        private final int frequency;
        
        public Suggestion(String word, int frequency) {
            this.word = word;
            this.frequency = frequency;
        }
        
        public String getWord() {
            return word;
        }
        
        public int getFrequency() {
            return frequency;
        }
        
        @Override
        public int compareTo(Suggestion other) {
            // Higher frequency first
            return Integer.compare(other.frequency, this.frequency);
        }
        
        @Override
        public String toString() {
            return String.format("%s: %d", word, frequency);
        }
        
        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            Suggestion that = (Suggestion) obj;
            return word.equals(that.word);
        }
        
        @Override
        public int hashCode() {
            return word.hashCode();
        }
    }
}

