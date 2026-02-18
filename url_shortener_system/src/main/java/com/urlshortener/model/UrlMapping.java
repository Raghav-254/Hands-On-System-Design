package com.urlshortener.model;

public class UrlMapping {
    private final String shortCode;
    private final String longUrl;
    private final String userId;
    private final String idempotencyKey;
    private final long createdAt;
    private final long expiresAt; // 0 = never expires

    public UrlMapping(String shortCode, String longUrl, String userId,
                      String idempotencyKey, long expiresAt) {
        this.shortCode = shortCode;
        this.longUrl = longUrl;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.createdAt = System.currentTimeMillis();
        this.expiresAt = expiresAt;
    }

    public String getShortCode() { return shortCode; }
    public String getLongUrl() { return longUrl; }
    public String getUserId() { return userId; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }

    public boolean isExpired() {
        return expiresAt > 0 && System.currentTimeMillis() > expiresAt;
    }

    @Override
    public String toString() {
        String exp = expiresAt > 0 ? (isExpired() ? "EXPIRED" : "expires=" + expiresAt) : "permanent";
        return String.format("UrlMapping[%s → %s (%s)]", shortCode, longUrl, exp);
    }
}
