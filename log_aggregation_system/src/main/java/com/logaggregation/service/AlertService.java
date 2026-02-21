package com.logaggregation.service;

import com.logaggregation.model.AlertRule;
import com.logaggregation.model.LogEntry;
import com.logaggregation.storage.MetadataDB;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Real-time alerting engine.
 * Evaluates each log entry against active rules using sliding window counters.
 * Fires alerts when threshold is breached. Implements cooldown to suppress duplicates.
 */
public class AlertService {
    private final MetadataDB metadataDB;
    private final Map<String, List<Instant>> windowCounters = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastFired = new ConcurrentHashMap<>();
    private final List<FiredAlert> firedAlerts = Collections.synchronizedList(new ArrayList<>());
    private static final long COOLDOWN_MS = 10_000; // 10 second cooldown for demo (10 min in prod)

    public AlertService(MetadataDB metadataDB) {
        this.metadataDB = metadataDB;
    }

    /**
     * Evaluate a log entry against all active alert rules.
     * Called for every log in the stream (before indexing).
     */
    public void evaluate(LogEntry entry) {
        for (AlertRule rule : metadataDB.getActiveRules()) {
            if (rule.matches(entry)) {
                String key = rule.getRuleId();
                windowCounters.computeIfAbsent(key, k -> Collections.synchronizedList(new ArrayList<>()))
                        .add(entry.getTimestamp());

                cleanWindow(key, rule.getWindowSeconds(), entry.getTimestamp());

                int count = windowCounters.get(key).size();
                if (count >= rule.getThreshold()) {
                    fireIfNotCoolingDown(rule, count, entry.getTimestamp());
                }
            }
        }
    }

    private void cleanWindow(String ruleId, int windowSeconds, Instant now) {
        List<Instant> timestamps = windowCounters.get(ruleId);
        if (timestamps == null) return;
        Instant cutoff = now.minusSeconds(windowSeconds);
        synchronized (timestamps) {
            timestamps.removeIf(t -> t.isBefore(cutoff));
        }
    }

    private void fireIfNotCoolingDown(AlertRule rule, int count, Instant now) {
        Instant last = lastFired.get(rule.getRuleId());
        if (last != null && now.toEpochMilli() - last.toEpochMilli() < COOLDOWN_MS) {
            return;
        }

        lastFired.put(rule.getRuleId(), now);
        FiredAlert alert = new FiredAlert(rule, count, now);
        firedAlerts.add(alert);
        System.out.printf("  *** ALERT FIRED: %s — %d %s logs in %ds window (threshold: %d) ***%n",
                rule.getName(), count, rule.getLevel(), rule.getWindowSeconds(), rule.getThreshold());
    }

    public List<FiredAlert> getFiredAlerts() { return Collections.unmodifiableList(firedAlerts); }
    public void clearAlerts() { firedAlerts.clear(); windowCounters.clear(); lastFired.clear(); }

    public int getCurrentCount(String ruleId) {
        List<Instant> timestamps = windowCounters.get(ruleId);
        return timestamps != null ? timestamps.size() : 0;
    }

    public static class FiredAlert {
        public final AlertRule rule;
        public final int count;
        public final Instant firedAt;

        public FiredAlert(AlertRule rule, int count, Instant firedAt) {
            this.rule = rule;
            this.count = count;
            this.firedAt = firedAt;
        }

        @Override
        public String toString() {
            return String.format("FiredAlert[%s, count=%d, at=%s]", rule.getName(), count, firedAt);
        }
    }
}
