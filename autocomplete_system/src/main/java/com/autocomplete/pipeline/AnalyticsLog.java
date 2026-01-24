package com.autocomplete.pipeline;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * AnalyticsLog - Collects raw search query data.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  DATA COLLECTION PIPELINE (From Figure 13-9)                                 ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  Analytics Logs → Aggregators → Aggregated Data → Workers → Trie DB         ║
 * ║       │                                                          │           ║
 * ║       │                                              Weekly Snapshot         ║
 * ║       │                                                          │           ║
 * ║       ▼                                                          ▼           ║
 * ║  Raw search queries                                        Trie Cache        ║
 * ║  (every keystroke)                                                           ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 * 
 * In production:
 * - Logs are written to distributed log storage (Kafka, Kinesis)
 * - Billions of events per day
 * - Each event: query, timestamp, user_id, device, location
 * 
 * For this demo:
 * - In-memory queue simulating the log
 */
public class AnalyticsLog {
    
    // Raw log entries (in production: Kafka topic)
    private final BlockingQueue<LogEntry> logQueue;
    
    // For simulation: track all logged queries
    private final List<LogEntry> allLogs;
    
    public AnalyticsLog() {
        this.logQueue = new LinkedBlockingQueue<>();
        this.allLogs = new CopyOnWriteArrayList<>();
    }
    
    /**
     * Log a search query.
     * Called every time a user types in the search box.
     * 
     * In production: This would write to Kafka/Kinesis.
     * 
     * @param query The search query
     * @param userId The user ID (for personalization, optional)
     */
    public void logQuery(String query, String userId) {
        LogEntry entry = new LogEntry(query, userId, Instant.now());
        logQueue.offer(entry);
        allLogs.add(entry);
        
        System.out.println(String.format("  [AnalyticsLog] Logged: '%s' from user %s", query, userId));
    }
    
    /**
     * Log a search query (anonymous).
     */
    public void logQuery(String query) {
        logQuery(query, "anonymous");
    }
    
    /**
     * Poll log entries for processing.
     * Called by the Aggregator.
     * 
     * @param maxEntries Maximum entries to poll
     * @return List of log entries
     */
    public List<LogEntry> pollEntries(int maxEntries) {
        List<LogEntry> entries = new ArrayList<>();
        logQueue.drainTo(entries, maxEntries);
        return entries;
    }
    
    /**
     * Get all logs (for testing/debugging).
     */
    public List<LogEntry> getAllLogs() {
        return new ArrayList<>(allLogs);
    }
    
    /**
     * Get pending log count.
     */
    public int getPendingCount() {
        return logQueue.size();
    }
    
    /**
     * LogEntry - A single search query event.
     */
    public static class LogEntry {
        private final String query;
        private final String userId;
        private final Instant timestamp;
        
        public LogEntry(String query, String userId, Instant timestamp) {
            this.query = query.toLowerCase().replaceAll("[^a-z]", "");
            this.userId = userId;
            this.timestamp = timestamp;
        }
        
        public String getQuery() {
            return query;
        }
        
        public String getUserId() {
            return userId;
        }
        
        public Instant getTimestamp() {
            return timestamp;
        }
        
        @Override
        public String toString() {
            return String.format("LogEntry{query='%s', user='%s', time=%s}", 
                query, userId, timestamp);
        }
    }
}

