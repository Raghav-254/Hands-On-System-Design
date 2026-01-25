package com.googledrive.storage;

import com.googledrive.model.Block;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cloud Storage (S3/Blob) - Stores file blocks.
 * 
 * Key characteristics:
 * - Content-addressed: Block ID = hash of content
 * - Immutable: Blocks never change (new content = new block)
 * - Deduplicated: Same hash stored only once
 * - Replicated: 3+ copies per region for durability
 */
public class CloudStorage {
    // In production: S3/Azure Blob/GCS
    private final Map<String, byte[]> blocks = new ConcurrentHashMap<>();
    
    // Stats for demo
    private long totalBytesStored = 0;
    private long totalBytesDeduped = 0;
    
    /**
     * Store a block. If block already exists (same hash), it's a no-op (dedup).
     */
    public void storeBlock(Block block) {
        if (blocks.containsKey(block.getBlockHash())) {
            // Deduplication: block already exists
            totalBytesDeduped += block.getData().length;
            return;
        }
        
        blocks.put(block.getBlockHash(), block.getData());
        totalBytesStored += block.getData().length;
    }
    
    /**
     * Check if block exists (for deduplication check before upload).
     */
    public boolean blockExists(String blockHash) {
        return blocks.containsKey(blockHash);
    }
    
    /**
     * Retrieve a block by hash.
     */
    public byte[] getBlock(String blockHash) {
        byte[] data = blocks.get(blockHash);
        if (data == null) {
            throw new RuntimeException("Block not found: " + blockHash);
        }
        return data;
    }
    
    /**
     * Delete a block (called when no files reference it anymore).
     */
    public void deleteBlock(String blockHash) {
        byte[] removed = blocks.remove(blockHash);
        if (removed != null) {
            totalBytesStored -= removed.length;
        }
    }
    
    public int getBlockCount() {
        return blocks.size();
    }
    
    public long getTotalBytesStored() {
        return totalBytesStored;
    }
    
    public long getTotalBytesDeduped() {
        return totalBytesDeduped;
    }
    
    public void printStats() {
        System.out.println("\n=== Cloud Storage Stats ===");
        System.out.printf("Unique blocks: %d%n", blocks.size());
        System.out.printf("Bytes stored: %d%n", totalBytesStored);
        System.out.printf("Bytes saved (dedup): %d%n", totalBytesDeduped);
        if (totalBytesStored + totalBytesDeduped > 0) {
            double dedupRatio = (double) totalBytesDeduped / (totalBytesStored + totalBytesDeduped) * 100;
            System.out.printf("Deduplication ratio: %.1f%%%n", dedupRatio);
        }
    }
}

