package com.urlshortener.storage;

import com.urlshortener.model.UrlMapping;
import java.util.*;

/**
 * Simulates the URL mappings database (MySQL/PostgreSQL).
 *
 * Tables simulated:
 * - url_mappings: short_code (PK) → long_url, user_id, idempotency_key, ...
 * - id_ranges: single row tracking next_range_start (for Range Allocator)
 */
public class UrlDB {

    private final Map<String, UrlMapping> byShortCode = new LinkedHashMap<>();
    private final Map<String, String> byIdempotencyKey = new HashMap<>(); // idem_key → short_code

    // Range allocator state (simulates the id_ranges table)
    private long nextRangeStart = 1;
    private final long rangeSize;

    public UrlDB(long rangeSize) {
        this.rangeSize = rangeSize;
    }

    /** INSERT INTO url_mappings. Returns false if short_code already exists (UNIQUE constraint). */
    public boolean insert(UrlMapping mapping) {
        if (byShortCode.containsKey(mapping.getShortCode())) {
            return false;
        }
        byShortCode.put(mapping.getShortCode(), mapping);
        if (mapping.getIdempotencyKey() != null) {
            byIdempotencyKey.put(mapping.getIdempotencyKey(), mapping.getShortCode());
        }
        return true;
    }

    /** SELECT * FROM url_mappings WHERE short_code = ? */
    public UrlMapping getByShortCode(String shortCode) {
        return byShortCode.get(shortCode);
    }

    /** Idempotency lookup: returns existing mapping if same idempotency key was used before. */
    public UrlMapping getByIdempotencyKey(String idempotencyKey) {
        String code = byIdempotencyKey.get(idempotencyKey);
        return code != null ? byShortCode.get(code) : null;
    }

    /** Check if a custom alias is available. */
    public boolean isCodeAvailable(String shortCode) {
        return !byShortCode.containsKey(shortCode);
    }

    /** DELETE FROM url_mappings WHERE short_code = ? */
    public boolean delete(String shortCode) {
        UrlMapping removed = byShortCode.remove(shortCode);
        if (removed != null && removed.getIdempotencyKey() != null) {
            byIdempotencyKey.remove(removed.getIdempotencyKey());
        }
        return removed != null;
    }

    /**
     * Atomic range allocation: SELECT next_range_start FOR UPDATE; UPDATE next_range_start += range_size.
     * Returns [rangeStart, rangeEnd] (inclusive).
     */
    public synchronized long[] allocateRange() {
        long start = nextRangeStart;
        long end = start + rangeSize - 1;
        nextRangeStart = end + 1;
        return new long[]{start, end};
    }

    public int size() { return byShortCode.size(); }

    public Collection<UrlMapping> getAll() { return byShortCode.values(); }
}
