package com.adclick.model;

import java.time.Instant;
import java.util.Map;
import java.util.HashMap;

/**
 * Represents a single ad click event.
 * Raw data ingested from log watchers via message queue.
 */
public class AdClickEvent {
    private final String adId;
    private final Instant clickTimestamp;
    private final String userId;
    private final String ip;
    private final String country;
    private final String deviceType;   // mobile, desktop, tablet
    private final String adFormat;     // banner, video, native

    public AdClickEvent(String adId, Instant clickTimestamp, String userId,
                        String ip, String country, String deviceType, String adFormat) {
        this.adId = adId;
        this.clickTimestamp = clickTimestamp;
        this.userId = userId;
        this.ip = ip;
        this.country = country;
        this.deviceType = deviceType;
        this.adFormat = adFormat;
    }

    public String getAdId() { return adId; }
    public Instant getClickTimestamp() { return clickTimestamp; }
    public String getUserId() { return userId; }
    public String getIp() { return ip; }
    public String getCountry() { return country; }
    public String getDeviceType() { return deviceType; }
    public String getAdFormat() { return adFormat; }

    /** Extract filterable attributes as tags (used for tag-based filtering) */
    public Map<String, String> getTags() {
        Map<String, String> tags = new HashMap<>();
        tags.put("country", country);
        tags.put("device", deviceType);
        tags.put("format", adFormat);
        return tags;
    }

    /** Get the minute bucket this event falls into */
    public long getMinuteBucket() {
        return clickTimestamp.getEpochSecond() / 60;
    }

    @Override
    public String toString() {
        return String.format("AdClick{ad=%s, user=%s, country=%s, device=%s, time=%s}",
                adId, userId, country, deviceType, clickTimestamp);
    }
}
