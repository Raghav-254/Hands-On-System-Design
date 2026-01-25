package com.googledrive.service;

import com.googledrive.model.Block;
import com.googledrive.storage.CloudStorage;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Block Server - The heart of Google Drive's file sync.
 * 
 * Responsibilities:
 * 1. Split files into fixed-size blocks
 * 2. Compress blocks (reduce bandwidth)
 * 3. Encrypt blocks (security)
 * 4. Deduplicate (content-addressed storage)
 * 
 * Key Insight: Content-addressed storage means block ID = hash of content.
 * Same content = same hash = stored only once, even across users!
 */
public class BlockServer {
    private final CloudStorage cloudStorage;
    private final byte[] encryptionKey; // In production: per-user keys from KMS
    
    public BlockServer(CloudStorage cloudStorage) {
        this.cloudStorage = cloudStorage;
        // Demo encryption key - in production, use KMS (Key Management Service)
        this.encryptionKey = "0123456789ABCDEF".getBytes(); // 16 bytes for AES-128
    }
    
    /**
     * Upload a file by splitting into blocks.
     * Returns list of block hashes (file metadata references these).
     */
    public List<Block> uploadFile(byte[] fileData) {
        System.out.println("\n=== Block Server: Processing Upload ===");
        System.out.printf("Original file size: %d bytes%n", fileData.length);
        
        // Step 1: Split into blocks
        List<byte[]> chunks = splitIntoBlocks(fileData);
        System.out.printf("Split into %d blocks%n", chunks.size());
        
        List<Block> blocks = new ArrayList<>();
        int totalCompressed = 0;
        int skippedBlocks = 0;
        
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            
            // Step 2: Compress (before encryption for better ratio)
            byte[] compressed = compress(chunk);
            
            // Step 3: Encrypt
            byte[] encrypted = encrypt(compressed);
            
            // Create block with hash
            Block block = new Block(encrypted, i);
            blocks.add(block);
            
            // Step 4: Check for deduplication
            if (cloudStorage.blockExists(block.getBlockHash())) {
                skippedBlocks++;
                System.out.printf("  Block %d: DEDUP (already exists)%n", i);
            } else {
                cloudStorage.storeBlock(block);
                System.out.printf("  Block %d: Uploaded (compressed: %d → %d bytes)%n", 
                    i, chunk.length, encrypted.length);
            }
            
            totalCompressed += encrypted.length;
        }
        
        double ratio = (1.0 - (double)totalCompressed / fileData.length) * 100;
        System.out.printf("Total: %d bytes → %d bytes (%.1f%% savings)%n", 
            fileData.length, totalCompressed, ratio);
        
        if (skippedBlocks > 0) {
            System.out.printf("Deduplication: %d blocks already existed%n", skippedBlocks);
        }
        
        return blocks;
    }
    
    /**
     * Download and reassemble file from blocks.
     */
    public byte[] downloadFile(List<String> blockHashes) {
        System.out.println("\n=== Block Server: Processing Download ===");
        
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        
        for (int i = 0; i < blockHashes.size(); i++) {
            String hash = blockHashes.get(i);
            
            // Fetch encrypted block
            byte[] encrypted = cloudStorage.getBlock(hash);
            
            // Decrypt
            byte[] compressed = decrypt(encrypted);
            
            // Decompress
            byte[] data = decompress(compressed);
            
            try {
                output.write(data);
            } catch (Exception e) {
                throw new RuntimeException("Failed to write block", e);
            }
            
            System.out.printf("  Block %d: Downloaded (%d bytes)%n", i, data.length);
        }
        
        System.out.printf("Reassembled file: %d bytes%n", output.size());
        return output.toByteArray();
    }
    
    /**
     * Check which blocks already exist (for delta sync).
     * Returns hashes that DON'T exist (need to be uploaded).
     */
    public List<String> findMissingBlocks(List<String> blockHashes) {
        List<String> missing = new ArrayList<>();
        for (String hash : blockHashes) {
            if (!cloudStorage.blockExists(hash)) {
                missing.add(hash);
            }
        }
        return missing;
    }
    
    // === Private helper methods ===
    
    private List<byte[]> splitIntoBlocks(byte[] data) {
        List<byte[]> blocks = new ArrayList<>();
        int offset = 0;
        
        while (offset < data.length) {
            int length = Math.min(Block.BLOCK_SIZE, data.length - offset);
            byte[] block = Arrays.copyOfRange(data, offset, offset + length);
            blocks.add(block);
            offset += length;
        }
        
        return blocks;
    }
    
    private byte[] compress(byte[] data) {
        try {
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            deflater.setInput(data);
            deflater.finish();
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[1024];
            
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            
            deflater.end();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Compression failed", e);
        }
    }
    
    private byte[] decompress(byte[] data) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(data);
            
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream(data.length);
            byte[] buffer = new byte[1024];
            
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }
            
            inflater.end();
            return outputStream.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Decompression failed", e);
        }
    }
    
    private byte[] encrypt(byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }
    
    private byte[] decrypt(byte[] data) {
        try {
            SecretKeySpec keySpec = new SecretKeySpec(encryptionKey, "AES");
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
            return cipher.doFinal(data);
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }
}

