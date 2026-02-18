package com.urlshortener.service;

import com.urlshortener.event.EventBus;
import com.urlshortener.model.ClickEvent;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.storage.CacheStore;
import com.urlshortener.storage.UrlDB;

/**
 * Core service: create short URL (write path) and redirect (read path).
 * Uses cache-first for reads; publishes click events to Kafka (EventBus) async.
 */
public class UrlShortenerService {

    private final UrlDB db;
    private final CacheStore cache;
    private final KeyGeneratorService keyGen;
    private final EventBus eventBus;

    public UrlShortenerService(UrlDB db, CacheStore cache,
                                KeyGeneratorService keyGen, EventBus eventBus) {
        this.db = db;
        this.cache = cache;
        this.keyGen = keyGen;
        this.eventBus = eventBus;
    }

    /**
     * Create short URL (write path).
     * Returns: UrlMapping on success, null on conflict (custom alias taken).
     */
    public UrlMapping createShortUrl(String longUrl, String customAlias,
                                      String userId, String idempotencyKey, long expiresAt) {
        // 1. Idempotency check
        if (idempotencyKey != null) {
            UrlMapping existing = db.getByIdempotencyKey(idempotencyKey);
            if (existing != null) {
                System.out.println("  [IDEMPOTENT] Key '" + idempotencyKey +
                    "' → returning existing: " + existing.getShortCode());
                return existing;
            }
        }

        // 2. Generate or validate short code
        String shortCode;
        if (customAlias != null && !customAlias.isEmpty()) {
            if (!db.isCodeAvailable(customAlias)) {
                System.out.println("  [CREATE] Custom alias '" + customAlias + "' already taken → 409 Conflict");
                return null;
            }
            shortCode = customAlias;
            System.out.println("  [CREATE] Using custom alias: " + shortCode);
        } else {
            shortCode = keyGen.generateCode();
            System.out.println("  [CREATE] Generated code: " + shortCode +
                " (strategy=" + keyGen.getStrategy() + ")");
        }

        // 3. Insert into DB
        UrlMapping mapping = new UrlMapping(shortCode, longUrl, userId, idempotencyKey, expiresAt);
        boolean inserted = db.insert(mapping);
        if (!inserted) {
            System.out.println("  [CREATE] FAILED: code " + shortCode + " already exists in DB");
            return null;
        }

        System.out.println("  [CREATE] SUCCESS: " + mapping);
        return mapping;
    }

    /**
     * Redirect (read path): cache-first, then DB.
     * Returns: long URL on success, null if not found or expired.
     * Publishes click event to Kafka (async, fire-and-forget).
     */
    public String redirect(String shortCode, String country, String device, String referrer) {
        // 1. Cache lookup
        String longUrl = cache.get(shortCode);
        if (longUrl != null) {
            System.out.println("  [REDIRECT] Cache HIT: " + shortCode + " → " + longUrl);
        } else {
            // 2. Cache miss → DB lookup
            UrlMapping mapping = db.getByShortCode(shortCode);
            if (mapping == null) {
                System.out.println("  [REDIRECT] Not found: " + shortCode + " → 404");
                return null;
            }

            // 3. Check expiry
            if (mapping.isExpired()) {
                System.out.println("  [REDIRECT] Expired: " + shortCode + " → 410 Gone");
                cache.delete(shortCode);
                return null;
            }

            longUrl = mapping.getLongUrl();
            System.out.println("  [REDIRECT] Cache MISS → DB: " + shortCode + " → " + longUrl);

            // 4. Populate cache
            cache.set(shortCode, longUrl);
            System.out.println("  [REDIRECT] Cache SET: " + shortCode);
        }

        // 5. Publish click event (async, fire-and-forget)
        ClickEvent event = new ClickEvent(shortCode, country, device, referrer);
        eventBus.publish(event);

        return longUrl;
    }

    /** Delete a short URL (invalidate cache + remove from DB). */
    public boolean delete(String shortCode) {
        cache.delete(shortCode);
        boolean deleted = db.delete(shortCode);
        if (deleted) {
            System.out.println("  [DELETE] Removed: " + shortCode + " (cache + DB)");
        } else {
            System.out.println("  [DELETE] Not found: " + shortCode);
        }
        return deleted;
    }

    public CacheStore getCache() { return cache; }
}
