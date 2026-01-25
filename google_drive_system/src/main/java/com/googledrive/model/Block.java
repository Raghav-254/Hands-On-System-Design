package com.googledrive.model;

import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Represents a block (chunk) of a file.
 * Files are split into fixed-size blocks for:
 * - Delta sync (only upload changed blocks)
 * - Deduplication (same content = same hash = store once)
 * - Resume (restart from last successful block)
 */
public class Block {
    private final String blockHash;     // SHA-256 hash (content-addressed)
    private final byte[] data;          // Compressed + encrypted data
    private final int order;            // Position in file (0-indexed)
    private final int originalSize;     // Size before compression
    
    public static final int BLOCK_SIZE = 4 * 1024 * 1024; // 4MB blocks
    
    public Block(byte[] data, int order) {
        this.data = data;
        this.order = order;
        this.originalSize = data.length;
        this.blockHash = computeHash(data);
    }
    
    public Block(String blockHash, byte[] data, int order) {
        this.blockHash = blockHash;
        this.data = data;
        this.order = order;
        this.originalSize = data.length;
    }
    
    private String computeHash(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute hash", e);
        }
    }
    
    public String getBlockHash() { return blockHash; }
    public byte[] getData() { return data; }
    public int getOrder() { return order; }
    public int getOriginalSize() { return originalSize; }
    public int getCompressedSize() { return data.length; }
    
    @Override
    public String toString() {
        return String.format("Block[hash=%s..., order=%d, size=%d]", 
            blockHash.substring(0, 8), order, data.length);
    }
}

