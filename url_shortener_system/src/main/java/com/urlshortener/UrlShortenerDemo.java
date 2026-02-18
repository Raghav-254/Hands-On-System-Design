package com.urlshortener;

import com.urlshortener.event.EventBus;
import com.urlshortener.model.UrlMapping;
import com.urlshortener.service.*;
import com.urlshortener.storage.*;

/**
 * Demonstrates the URL Shortener System Design concepts:
 * 1. Key Generation Strategies (Random, Sequence, Range-Based)
 * 2. Range-Based Allocation (multiple instances, no collision)
 * 3. Cache-First Redirect (cache hit vs. cache miss → DB → populate cache)
 * 4. LRU Cache Eviction (small cache, popular URLs survive)
 * 5. Custom Alias (user picks short code; conflict → 409)
 * 6. Idempotency (same create request retried → same result)
 * 7. Link Expiry (expired link → 410 Gone)
 * 8. Analytics via Kafka (click events consumed by Analytics Service)
 */
public class UrlShortenerDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       URL Shortener System Demo          ║");
        System.out.println("║       Cache + KeyGen + Analytics         ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        demoKeyGenStrategies();
        demoRangeAllocation();
        demoCacheFirstRedirect();
        demoLruEviction();
        demoCustomAlias();
        demoIdempotency();
        demoLinkExpiry();
        demoAnalytics();
    }

    // ─── Demo 1: Key Generation Strategies ─────────────────────────────

    static void demoKeyGenStrategies() {
        System.out.println("━━━ Demo 1: Key Generation Strategies ━━━");
        System.out.println("Compare Random, Sequence, and Range-Based.\n");

        long rangeSize = 100;

        // Strategy A: Random
        UrlDB dbA = new UrlDB(rangeSize);
        KeyGeneratorService genA = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANDOM, dbA, "RandomGen");
        System.out.println("  [RANDOM] Codes:");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + genA.generateCode());
        }

        // Strategy B: Sequence
        UrlDB dbB = new UrlDB(rangeSize);
        KeyGeneratorService genB = new KeyGeneratorService(
            KeyGeneratorService.Strategy.SEQUENCE, dbB, "SeqGen");
        System.out.println("\n  [SEQUENCE] Codes (base62 of 1, 2, 3):");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + genB.generateCode());
        }

        // Strategy C: Range
        UrlDB dbC = new UrlDB(rangeSize);
        KeyGeneratorService genC = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, dbC, "RangeGen");
        System.out.println("\n  [RANGE] Codes (from allocated range):");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + genC.generateCode());
        }
        System.out.println("\n  Random: unpredictable but may collide");
        System.out.println("  Sequence: predictable, single-writer bottleneck");
        System.out.println("  Range: no collision, no bottleneck ✓\n");
    }

    // ─── Demo 2: Range-Based Allocation (Multiple Instances) ───────────

    static void demoRangeAllocation() {
        System.out.println("━━━ Demo 2: Range-Based Allocation ━━━");
        System.out.println("Two instances share the same DB. Each gets non-overlapping ranges.\n");

        long rangeSize = 5;
        UrlDB sharedDB = new UrlDB(rangeSize);

        KeyGeneratorService instance1 = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, sharedDB, "Instance-A");
        KeyGeneratorService instance2 = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, sharedDB, "Instance-B");

        System.out.println("  Instance-A generates 3 codes:");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + instance1.generateCode());
        }

        System.out.println("\n  Instance-B generates 3 codes:");
        for (int i = 0; i < 3; i++) {
            System.out.println("    " + instance2.generateCode());
        }

        System.out.println("\n  No collisions — ranges don't overlap ✓\n");
    }

    // ─── Demo 3: Cache-First Redirect ──────────────────────────────────

    static void demoCacheFirstRedirect() {
        System.out.println("━━━ Demo 3: Cache-First Redirect ━━━");
        System.out.println("First redirect = cache MISS → DB → set cache. Second = cache HIT.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(100);
        EventBus eventBus = new EventBus();
        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        // Create a short URL
        UrlMapping mapping = service.createShortUrl(
            "https://www.example.com/very/long/article?id=12345",
            null, "user_1", null, 0);
        String code = mapping.getShortCode();

        // First redirect: cache miss
        System.out.println("\n  First redirect (cache MISS):");
        String url1 = service.redirect(code, "US", "mobile", "twitter.com");

        // Second redirect: cache hit
        System.out.println("\n  Second redirect (cache HIT):");
        String url2 = service.redirect(code, "IN", "desktop", "direct");

        System.out.println("\n  Cache stats: hits=" + cache.getHits() +
            " misses=" + cache.getMisses() +
            " hitRate=" + String.format("%.0f%%", cache.hitRate()) + " ✓\n");
    }

    // ─── Demo 4: LRU Cache Eviction ────────────────────────────────────

    static void demoLruEviction() {
        System.out.println("━━━ Demo 4: LRU Cache Eviction ━━━");
        System.out.println("Cache holds max 3 entries. 4th entry evicts least recently used.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(3); // Very small cache
        EventBus eventBus = new EventBus();
        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        // Create 4 short URLs
        String[] codes = new String[4];
        for (int i = 0; i < 4; i++) {
            UrlMapping m = service.createShortUrl(
                "https://example.com/page" + i, null, "user_1", null, 0);
            codes[i] = m.getShortCode();
        }

        // Redirect all 4 (populates cache; 4th evicts 1st)
        System.out.println("  Redirecting 4 URLs (cache max=3):");
        for (int i = 0; i < 4; i++) {
            service.redirect(codes[i], "US", "desktop", "direct");
        }

        System.out.println("\n  Cache size: " + cache.size() + " (max 3)");
        System.out.println("  First URL was evicted (LRU). Redirect again → cache MISS:");
        cache.resetStats();
        service.redirect(codes[0], "US", "desktop", "direct");
        System.out.println("  Cache misses: " + cache.getMisses() + " (evicted, re-fetched from DB) ✓\n");
    }

    // ─── Demo 5: Custom Alias ──────────────────────────────────────────

    static void demoCustomAlias() {
        System.out.println("━━━ Demo 5: Custom Alias ━━━");
        System.out.println("User picks 'mylink'. Second user tries same alias → 409 Conflict.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(100);
        EventBus eventBus = new EventBus();
        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        System.out.println("  User_1 creates custom alias 'mylink':");
        UrlMapping m1 = service.createShortUrl(
            "https://user1-blog.com/post", "mylink", "user_1", null, 0);
        System.out.println("  Result: " + (m1 != null ? "SUCCESS ✓" : "FAILED"));

        System.out.println("\n  User_2 tries same alias 'mylink':");
        UrlMapping m2 = service.createShortUrl(
            "https://user2-site.com/page", "mylink", "user_2", null, 0);
        System.out.println("  Result: " + (m2 != null ? "SUCCESS" : "FAILED → 409 Conflict ✓"));

        System.out.println("\n  Redirect 'mylink' → goes to User_1's URL:");
        String url = service.redirect("mylink", "US", "mobile", "direct");
        System.out.println("  → " + url + " ✓\n");
    }

    // ─── Demo 6: Idempotency ──────────────────────────────────────────

    static void demoIdempotency() {
        System.out.println("━━━ Demo 6: Idempotency (Create Retry) ━━━");
        System.out.println("Same Idempotency-Key → same short URL, no duplicate.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(100);
        EventBus eventBus = new EventBus();
        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        String idemKey = "req_abc_123";

        System.out.println("  First create (key=" + idemKey + "):");
        UrlMapping m1 = service.createShortUrl(
            "https://example.com/article", null, "user_1", idemKey, 0);

        System.out.println("\n  Retry with SAME key (network timeout, client retried):");
        UrlMapping m2 = service.createShortUrl(
            "https://example.com/article", null, "user_1", idemKey, 0);

        System.out.println("\n  Same code returned: " +
            m1.getShortCode() + " == " + m2.getShortCode() +
            " → " + m1.getShortCode().equals(m2.getShortCode()) + " ✓");
        System.out.println("  DB size: " + db.size() + " (only 1 entry, not 2) ✓\n");
    }

    // ─── Demo 7: Link Expiry ──────────────────────────────────────────

    static void demoLinkExpiry() {
        System.out.println("━━━ Demo 7: Link Expiry ━━━");
        System.out.println("Create link that expires in 1 sec. Wait. Redirect → 410 Gone.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(100);
        EventBus eventBus = new EventBus();
        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        long expiresAt = System.currentTimeMillis() + 1000; // 1 second
        UrlMapping mapping = service.createShortUrl(
            "https://promo.example.com/sale", null, "user_1", null, expiresAt);
        String code = mapping.getShortCode();

        System.out.println("\n  Redirect immediately (not expired):");
        String url1 = service.redirect(code, "US", "mobile", "direct");
        System.out.println("  → " + (url1 != null ? url1 + " ✓" : "EXPIRED"));

        System.out.println("\n  Waiting 1.2 sec for expiry...");
        try { Thread.sleep(1200); } catch (InterruptedException e) { }

        // Clear cache to force DB check (in production, TTL would expire the cache entry)
        cache.delete(code);

        System.out.println("\n  Redirect after expiry:");
        String url2 = service.redirect(code, "US", "mobile", "direct");
        System.out.println("  → " + (url2 != null ? url2 : "410 Gone ✓") + "\n");
    }

    // ─── Demo 8: Analytics via Kafka ──────────────────────────────────

    static void demoAnalytics() {
        System.out.println("━━━ Demo 8: Analytics via Kafka ━━━");
        System.out.println("Redirect events → Kafka → Analytics Service aggregates.\n");

        UrlDB db = new UrlDB(100);
        CacheStore cache = new CacheStore(100);
        EventBus eventBus = new EventBus();
        AnalyticsService analytics = new AnalyticsService();

        // Subscribe Analytics Service to Kafka
        eventBus.subscribe("AnalyticsService", event -> {
            analytics.processEvent(event);
        });

        KeyGeneratorService keyGen = new KeyGeneratorService(
            KeyGeneratorService.Strategy.RANGE, db, "Svc");
        UrlShortenerService service = new UrlShortenerService(db, cache, keyGen, eventBus);

        UrlMapping mapping = service.createShortUrl(
            "https://blog.example.com/top-post", null, "user_1", null, 0);
        String code = mapping.getShortCode();

        // Simulate clicks from different countries/devices
        System.out.println("\n  Simulating 5 redirects from various sources:");
        service.redirect(code, "US", "mobile", "twitter.com");
        service.redirect(code, "US", "desktop", "google.com");
        service.redirect(code, "IN", "mobile", "whatsapp");
        service.redirect(code, "UK", "desktop", "direct");
        service.redirect(code, "IN", "mobile", "twitter.com");

        System.out.println("\n  --- Analytics Report ---");
        analytics.printAnalytics(code);
        System.out.println("  Total events processed: " + analytics.getTotalEventsProcessed());

        System.out.println("\n  Cache stats: hits=" + cache.getHits() +
            " misses=" + cache.getMisses() +
            " hitRate=" + String.format("%.0f%%", cache.hitRate()));

        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("Demo complete!");
    }
}
