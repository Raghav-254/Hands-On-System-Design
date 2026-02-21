package com.logaggregation.storage;

import com.logaggregation.model.AlertRule;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates MySQL for alert rules and retention policies.
 */
public class MetadataDB {
    private final Map<String, AlertRule> alertRules = new ConcurrentHashMap<>();
    private final Map<String, RetentionPolicy> retentionPolicies = new ConcurrentHashMap<>();

    public void addAlertRule(AlertRule rule) {
        alertRules.put(rule.getRuleId(), rule);
    }

    public void removeAlertRule(String ruleId) {
        alertRules.remove(ruleId);
    }

    public Collection<AlertRule> getActiveRules() {
        return alertRules.values();
    }

    public AlertRule getRule(String ruleId) {
        return alertRules.get(ruleId);
    }

    public void setRetentionPolicy(String service, int hotDays, int warmDays, int coldDays) {
        retentionPolicies.put(service, new RetentionPolicy(service, hotDays, warmDays, coldDays));
    }

    public RetentionPolicy getRetentionPolicy(String service) {
        RetentionPolicy policy = retentionPolicies.get(service);
        if (policy != null) return policy;
        return retentionPolicies.getOrDefault("DEFAULT", new RetentionPolicy("DEFAULT", 1, 30, 90));
    }

    public static class RetentionPolicy {
        public final String service;
        public final int hotDays;
        public final int warmDays;
        public final int coldDays;

        public RetentionPolicy(String service, int hotDays, int warmDays, int coldDays) {
            this.service = service;
            this.hotDays = hotDays;
            this.warmDays = warmDays;
            this.coldDays = coldDays;
        }

        @Override
        public String toString() {
            return String.format("Retention[%s: hot=%dd, warm=%dd, cold=%dd]",
                    service, hotDays, warmDays, coldDays);
        }
    }
}
