package com.chatapp.storage;

import java.util.*;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;

/**
 * In-memory implementation of KVStore using ConcurrentSkipListMap.
 * 
 * ConcurrentSkipListMap provides:
 * - Thread-safe operations
 * - Sorted keys (important for range queries on message IDs)
 * - O(log n) operations
 * 
 * In production, you'd use Cassandra/HBase which provide similar guarantees
 * with persistence and distribution.
 */
public class InMemoryKVStore<K extends Comparable<K>, V> implements KVStore<K, V> {
    
    private final ConcurrentNavigableMap<K, V> store;
    private final String name;
    
    public InMemoryKVStore(String name) {
        this.store = new ConcurrentSkipListMap<>();
        this.name = name;
    }
    
    @Override
    public void put(K key, V value) {
        store.put(key, value);
        System.out.printf("[KVStore-%s] PUT: %s%n", name, key);
    }
    
    @Override
    public Optional<V> get(K key) {
        return Optional.ofNullable(store.get(key));
    }
    
    @Override
    public void delete(K key) {
        store.remove(key);
        System.out.printf("[KVStore-%s] DELETE: %s%n", name, key);
    }
    
    @Override
    public boolean exists(K key) {
        return store.containsKey(key);
    }
    
    @Override
    public List<V> getRange(K startKey, K endKey) {
        return new ArrayList<>(store.subMap(startKey, true, endKey, false).values());
    }
    
    @Override
    public List<V> getByPrefix(String prefix) {
        // For string keys, filter by prefix
        return store.entrySet().stream()
            .filter(e -> e.getKey().toString().startsWith(prefix))
            .map(Map.Entry::getValue)
            .collect(Collectors.toList());
    }
    
    /**
     * Get all entries (for debugging).
     */
    public Map<K, V> getAll() {
        return new TreeMap<>(store);
    }
    
    /**
     * Clear all data (for testing).
     */
    public void clear() {
        store.clear();
    }
    
    public int size() {
        return store.size();
    }
}

