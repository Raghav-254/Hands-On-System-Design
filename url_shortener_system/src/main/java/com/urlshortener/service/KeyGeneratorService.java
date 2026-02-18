package com.urlshortener.service;

import com.urlshortener.storage.UrlDB;
import java.security.SecureRandom;

/**
 * Three key generation strategies as discussed in the cheatsheet:
 * A) Random + collision check
 * B) DB sequence (auto-increment)
 * C) Range-based allocation (our choice)
 */
public class KeyGeneratorService {

    public enum Strategy { RANDOM, SEQUENCE, RANGE }

    private static final String ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 7;
    private static final SecureRandom random = new SecureRandom();

    private final Strategy strategy;
    private final UrlDB db;
    private final String instanceName;

    // Sequence state (Strategy B)
    private long sequenceCounter = 0;

    // Range state (Strategy C)
    private long rangeStart;
    private long rangeEnd;
    private long rangeCurrent;
    private boolean rangeInitialized = false;

    public KeyGeneratorService(Strategy strategy, UrlDB db, String instanceName) {
        this.strategy = strategy;
        this.db = db;
        this.instanceName = instanceName;
    }

    public String generateCode() {
        switch (strategy) {
            case RANDOM:   return generateRandom();
            case SEQUENCE: return generateSequence();
            case RANGE:    return generateRange();
            default: throw new IllegalStateException("Unknown strategy: " + strategy);
        }
    }

    /**
     * Strategy A: Random + collision check.
     * Generate random 7-char base62 string. Check DB. If exists, retry.
     */
    private String generateRandom() {
        int attempts = 0;
        while (true) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
            }
            String code = sb.toString();
            attempts++;
            if (db.isCodeAvailable(code)) {
                if (attempts > 1) {
                    System.out.println("    [RANDOM] Collision(s) detected, took " + attempts + " attempts");
                }
                return code;
            }
        }
    }

    /**
     * Strategy B: Single DB sequence.
     * Increment counter, encode to base62. No collision, but single writer bottleneck.
     */
    private String generateSequence() {
        sequenceCounter++;
        return Base62.encode(sequenceCounter);
    }

    /**
     * Strategy C: Range-based allocation (our choice).
     * Get a block of IDs from Range Allocator. Increment local counter. No collision, no bottleneck.
     */
    private String generateRange() {
        if (!rangeInitialized || rangeCurrent > rangeEnd) {
            allocateNewRange();
        }
        long id = rangeCurrent++;
        return Base62.encode(id);
    }

    private void allocateNewRange() {
        long[] range = db.allocateRange();
        this.rangeStart = range[0];
        this.rangeEnd = range[1];
        this.rangeCurrent = rangeStart;
        this.rangeInitialized = true;
        System.out.println("    [" + instanceName + "] Allocated range [" + rangeStart + ", " + rangeEnd + "]");
    }

    public Strategy getStrategy() { return strategy; }
    public String getInstanceName() { return instanceName; }
}
