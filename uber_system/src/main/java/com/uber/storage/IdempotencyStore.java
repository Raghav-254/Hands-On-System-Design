package com.uber.storage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Simulates idempotency key storage (separate DB table or Redis).
 * Key: client-supplied idempotency key → Value: cached response (status code + body).
 * In production: TTL-based cleanup (e.g. 24 hours).
 */
public class IdempotencyStore {

    public static class CachedResponse {
        public final int statusCode;
        public final String body;

        public CachedResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        @Override
        public String toString() {
            return String.format("CachedResponse[%d: %s]", statusCode, body);
        }
    }

    private final Map<String, CachedResponse> store = new LinkedHashMap<>();

    public CachedResponse lookup(String idempotencyKey) {
        return store.get(idempotencyKey);
    }

    public void save(String idempotencyKey, int statusCode, String body) {
        store.put(idempotencyKey, new CachedResponse(statusCode, body));
    }

    public int size() { return store.size(); }
}
