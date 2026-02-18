package com.urlshortener.model;

public class ClickEvent {
    private final String shortCode;
    private final long timestamp;
    private final String country;
    private final String device;
    private final String referrer;

    public ClickEvent(String shortCode, String country, String device, String referrer) {
        this.shortCode = shortCode;
        this.timestamp = System.currentTimeMillis();
        this.country = country;
        this.device = device;
        this.referrer = referrer;
    }

    public String getShortCode() { return shortCode; }
    public long getTimestamp() { return timestamp; }
    public String getCountry() { return country; }
    public String getDevice() { return device; }
    public String getReferrer() { return referrer; }

    @Override
    public String toString() {
        return String.format("Click[%s from=%s device=%s ref=%s]", shortCode, country, device, referrer);
    }
}
