package com.logaggregation.model;

public class AlertRule {
    private final String ruleId;
    private final String name;
    private final String service;
    private final LogLevel level;
    private final int threshold;
    private final int windowSeconds;
    private final String webhookUrl;

    public AlertRule(String ruleId, String name, String service, LogLevel level,
                     int threshold, int windowSeconds, String webhookUrl) {
        this.ruleId = ruleId;
        this.name = name;
        this.service = service;
        this.level = level;
        this.threshold = threshold;
        this.windowSeconds = windowSeconds;
        this.webhookUrl = webhookUrl;
    }

    public String getRuleId() { return ruleId; }
    public String getName() { return name; }
    public String getService() { return service; }
    public LogLevel getLevel() { return level; }
    public int getThreshold() { return threshold; }
    public int getWindowSeconds() { return windowSeconds; }
    public String getWebhookUrl() { return webhookUrl; }

    public boolean matches(LogEntry entry) {
        boolean serviceMatch = service == null || service.equals(entry.getService());
        boolean levelMatch = entry.getLevel().isAtLeast(level);
        return serviceMatch && levelMatch;
    }

    @Override
    public String toString() {
        return String.format("AlertRule[%s: %s %s > %d in %ds]",
                ruleId, service != null ? service : "*", level, threshold, windowSeconds);
    }
}
