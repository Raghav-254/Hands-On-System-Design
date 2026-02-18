package com.bookmyshow.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates idempotency key storage (DB table in same DB as bookings).
 * Written atomically with booking in same transaction.
 */
public class IdempotencyStore {

    public static class CachedResponse {
        public final int statusCode;
        public final String body;

        public CachedResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }
    }

    private final Map<String, CachedResponse> store = new LinkedHashMap<>();

    public CachedResponse lookup(String key) {
        return store.get(key);
    }

    public void save(String key, int statusCode, String body) {
        store.put(key, new CachedResponse(statusCode, body));
    }
}
