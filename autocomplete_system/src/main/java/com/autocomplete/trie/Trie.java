package com.autocomplete.trie;

import com.autocomplete.trie.TrieNode.Suggestion;
import java.util.*;

/**
 * Trie - The core data structure for autocomplete.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRIE DATA STRUCTURE (From Figure 13-8)                                      ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║                              root                                            ║
 * ║                           /       \                                          ║
 * ║                          b         w                                         ║
 * ║                       /  |  \       \                                        ║
 * ║                     be   bu  ...    wi                                       ║
 * ║                    / \    \          \                                       ║
 * ║                 bee bes  buy        win                                      ║
 * ║                  |    \                                                      ║
 * ║                beer  best                                                    ║
 * ║                                                                               ║
 * ║  Each node caches top-k suggestions for that prefix!                        ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TIME COMPLEXITY                                                             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Operation              Without Cache      With Top-K Cache                  ║
 * ║  ─────────────────────  ───────────────    ──────────────────────────────── ║
 * ║  Search (get top k)     O(p + c)           O(p) where p = prefix length      ║
 * ║  Insert                 O(n)               O(n) where n = word length        ║
 * ║  Update frequency       O(n)               O(n * k) to update caches         ║
 * ║                                                                               ║
 * ║  p = prefix length, c = total characters under prefix, n = word length       ║
 * ║                                                                               ║
 * ║  With caching: Search is O(p) - just traverse to node and return cache!     ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class Trie {
    
    private final TrieNode root;
    private final int topK;
    
    public Trie() {
        this(5);  // Default: return top 5 suggestions
    }
    
    public Trie(int topK) {
        this.root = new TrieNode();
        this.topK = topK;
    }
    
    /**
     * Insert a word with its frequency.
     * Updates the top-k cache at each node along the path.
     * 
     * @param word The word to insert
     * @param frequency The search frequency
     */
    public void insert(String word, int frequency) {
        if (word == null || word.isEmpty()) return;
        
        word = normalize(word);
        TrieNode current = root;
        
        // Track all nodes along the path (to update their caches)
        List<TrieNode> path = new ArrayList<>();
        path.add(root);
        
        // Traverse/create path for the word
        for (char c : word.toCharArray()) {
            current = current.addChild(c);
            path.add(current);
        }
        
        // Mark end of word
        current.setEndOfWord(true, word);
        current.setFrequency(frequency);
        
        // Update top-k cache at each node along the path
        Suggestion suggestion = new Suggestion(word, frequency);
        for (TrieNode node : path) {
            node.updateTopKCache(suggestion, topK);
        }
    }
    
    /**
     * Update the frequency of an existing word.
     * This is called when analytics data shows increased search frequency.
     * 
     * @param word The word to update
     * @param newFrequency The new frequency
     * @return true if word exists and was updated
     */
    public boolean updateFrequency(String word, int newFrequency) {
        if (word == null || word.isEmpty()) return false;
        
        word = normalize(word);
        TrieNode current = root;
        List<TrieNode> path = new ArrayList<>();
        path.add(root);
        
        // Find the word
        for (char c : word.toCharArray()) {
            if (!current.hasChild(c)) {
                return false;  // Word doesn't exist
            }
            current = current.getChild(c);
            path.add(current);
        }
        
        if (!current.isEndOfWord()) {
            return false;  // Prefix exists but not as a complete word
        }
        
        // Update frequency
        current.setFrequency(newFrequency);
        
        // Update top-k cache at each node along the path
        Suggestion suggestion = new Suggestion(word, newFrequency);
        for (TrieNode node : path) {
            node.updateTopKCache(suggestion, topK);
        }
        
        return true;
    }
    
    /**
     * Get autocomplete suggestions for a prefix.
     * This is the main query operation - returns cached top-k suggestions.
     * 
     * TIME COMPLEXITY: O(p) where p = prefix length
     * - Traverse to the node: O(p)
     * - Return cached list: O(1)
     * 
     * @param prefix The search prefix
     * @return List of top-k suggestions sorted by frequency
     */
    public List<Suggestion> getAutocompleteSuggestions(String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return Collections.emptyList();
        }
        
        prefix = normalize(prefix);
        TrieNode node = findNode(prefix);
        
        if (node == null) {
            return Collections.emptyList();
        }
        
        // Return the cached top-k suggestions - O(1)!
        return node.getTopKCache();
    }
    
    /**
     * Find the node for a given prefix.
     * 
     * @param prefix The prefix to find
     * @return The node, or null if prefix doesn't exist
     */
    private TrieNode findNode(String prefix) {
        TrieNode current = root;
        
        for (char c : prefix.toCharArray()) {
            if (!current.hasChild(c)) {
                return null;
            }
            current = current.getChild(c);
        }
        
        return current;
    }
    
    /**
     * Get all words with a given prefix (for debugging/testing).
     * NOTE: In production, use getAutocompleteSuggestions() instead!
     */
    public List<Suggestion> getAllWordsWithPrefix(String prefix) {
        prefix = normalize(prefix);
        TrieNode node = findNode(prefix);
        
        if (node == null) {
            return Collections.emptyList();
        }
        
        List<Suggestion> results = new ArrayList<>();
        collectAllWords(node, results);
        results.sort(Comparator.naturalOrder());
        return results;
    }
    
    private void collectAllWords(TrieNode node, List<Suggestion> results) {
        if (node.isEndOfWord()) {
            results.add(new Suggestion(node.getWord(), node.getFrequency()));
        }
        
        for (TrieNode child : node.getChildren().values()) {
            collectAllWords(child, results);
        }
    }
    
    /**
     * Normalize input: lowercase, remove non-alphabetic characters.
     */
    private String normalize(String input) {
        return input.toLowerCase().replaceAll("[^a-z]", "");
    }
    
    /**
     * Check if a word exists in the trie.
     */
    public boolean contains(String word) {
        word = normalize(word);
        TrieNode node = findNode(word);
        return node != null && node.isEndOfWord();
    }
    
    /**
     * Get the frequency of a word.
     */
    public int getFrequency(String word) {
        word = normalize(word);
        TrieNode node = findNode(word);
        if (node != null && node.isEndOfWord()) {
            return node.getFrequency();
        }
        return 0;
    }
    
    /**
     * Get the root node (for serialization/debugging).
     */
    public TrieNode getRoot() {
        return root;
    }
    
    /**
     * Get the configured top-k value.
     */
    public int getTopK() {
        return topK;
    }
    
    /**
     * Print the trie structure (for debugging).
     */
    public void printTrie() {
        System.out.println("\n=== TRIE STRUCTURE ===");
        printNode(root, "", "root");
    }
    
    private void printNode(TrieNode node, String prefix, String edge) {
        StringBuilder sb = new StringBuilder();
        sb.append(prefix).append(edge);
        
        if (node.isEndOfWord()) {
            sb.append(" [").append(node.getWord()).append(": ").append(node.getFrequency()).append("]");
        }
        
        if (!node.getTopKCache().isEmpty()) {
            sb.append(" cache: ").append(node.getTopKCache());
        }
        
        System.out.println(sb);
        
        List<Character> sortedKeys = new ArrayList<>(node.getChildren().keySet());
        Collections.sort(sortedKeys);
        
        for (int i = 0; i < sortedKeys.size(); i++) {
            char c = sortedKeys.get(i);
            String newPrefix = prefix + (i == sortedKeys.size() - 1 ? "    " : "│   ");
            String newEdge = (i == sortedKeys.size() - 1 ? "└── " : "├── ") + c;
            printNode(node.getChildren().get(c), newPrefix, newEdge);
        }
    }
}

