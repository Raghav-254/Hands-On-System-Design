package com.chatapp.storage;

import java.util.List;
import java.util.Optional;

/**
 * Key-Value Store interface.
 * 
 * In production, this would be backed by:
 * - Cassandra / HBase for message storage (wide-column stores good for time-series data)
 * - Redis for caching and presence data
 * 
 * The KV store is used for:
 * 1. Storing messages (indexed by user_id for 1:1, channel_id for group)
 * 2. Storing user session data
 * 3. Caching frequently accessed data
 */
public interface KVStore<K, V> {
    
    /**
     * Store a value with the given key.
     */
    void put(K key, V value);
    
    /**
     * Retrieve a value by key.
     */
    Optional<V> get(K key);
    
    /**
     * Delete a value by key.
     */
    void delete(K key);
    
    /**
     * Check if key exists.
     */
    boolean exists(K key);
    
    /**
     * Get all values for a range of keys (useful for fetching message history).
     * The range is inclusive of start and exclusive of end.
     */
    List<V> getRange(K startKey, K endKey);
    
    /**
     * Get all values matching a prefix (useful for fetching all messages in a channel).
     */
    List<V> getByPrefix(String prefix);
}

