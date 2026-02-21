package com.logaggregation;

import com.logaggregation.model.*;
import com.logaggregation.storage.*;
import com.logaggregation.service.*;
import com.logaggregation.event.EventBus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public class LogAggregationDemo {

    public static void main(String[] args) {
        System.out.println("=".repeat(70));
        System.out.println("       LOG AGGREGATION SYSTEM DESIGN — DEMO");
        System.out.println("=".repeat(70));

        LogIndex logIndex = new LogIndex();
        ColdStorage coldStorage = new ColdStorage();
        MetadataDB metadataDB = new MetadataDB();
        EventBus kafka = new EventBus();

        LogProcessorService processor = new LogProcessorService(logIndex, 5);
        SearchService searchService = new SearchService(logIndex);
        AlertService alertService = new AlertService(metadataDB);
        RetentionService retentionService = new RetentionService(logIndex, coldStorage);

        kafka.subscribe(rawLine -> {
            LogEntry entry = processor.process(rawLine);
            if (entry != null) {
                alertService.evaluate(entry);
            }
        });

        demo1_IngestionPipeline(kafka, processor, logIndex);
        demo2_FullTextSearch(logIndex, searchService);
        demo3_FilteredSearch(logIndex, searchService);
        demo4_AlertingOnErrorSpike(kafka, metadataDB, alertService);
        demo5_AgentResilience(kafka, processor, logIndex);
        demo6_IndexLifecycle(logIndex, retentionService, coldStorage);
        demo7_SearchCaching(searchService);
        demo8_StructuredVsUnstructured(processor);
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 1: End-to-end ingestion pipeline
    // ──────────────────────────────────────────────────────────────────
    private static void demo1_IngestionPipeline(EventBus kafka, LogProcessorService processor, LogIndex logIndex) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 1: End-to-End Ingestion Pipeline");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Agent → Kafka → Log Processor (parse) → Elasticsearch (index)\n");

        Instant now = Instant.now();
        String[] rawLogs = {
            now.toString() + " INFO Request received path=/api/users latency=45ms host=host-01 service=user-service",
            now.plusMillis(100).toString() + " ERROR Database connection timeout retries=3 host=host-02 service=payment-service",
            now.plusMillis(200).toString() + " WARN High memory usage percent=85 host=host-03 service=auth-service",
            now.plusMillis(300).toString() + " INFO Order placed order_id=ord_123 amount=99.99 host=host-01 service=order-service",
            now.plusMillis(400).toString() + " ERROR Payment failed: card declined user_id=u_456 host=host-02 service=payment-service",
            now.plusMillis(500).toString() + " DEBUG Cache hit ratio=0.95 host=host-04 service=cache-service",
        };

        System.out.println("Agent pushes 6 raw log lines to Kafka:");
        for (String line : rawLogs) {
            kafka.publish(line);
        }
        processor.flushBulk();

        System.out.println("\nPipeline stats:");
        System.out.println("  Kafka published: " + kafka.getTotalPublished());
        System.out.println("  Processor processed: " + processor.getTotalProcessed());
        System.out.println("  ES indexed: " + logIndex.getTotalIndexed());
        System.out.println("  ES indices: " + logIndex.getIndexSummary());
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 2: Full-text search
    // ──────────────────────────────────────────────────────────────────
    private static void demo2_FullTextSearch(LogIndex logIndex, SearchService search) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 2: Full-Text Search");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Elasticsearch inverted index enables fast text search.\n");

        SearchQuery q1 = new SearchQuery("payment failed", null, null, null, null, 10);
        System.out.println("Query: \"payment failed\"");
        SearchResult r1 = search.search(q1);
        System.out.println("  " + r1);
        for (LogEntry e : r1.getLogs()) {
            System.out.println("  → " + e);
        }

        SearchQuery q2 = new SearchQuery("timeout", null, null, null, null, 10);
        System.out.println("\nQuery: \"timeout\"");
        SearchResult r2 = search.search(q2);
        System.out.println("  " + r2);
        for (LogEntry e : r2.getLogs()) {
            System.out.println("  → " + e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 3: Filtered search (service, level, time range)
    // ──────────────────────────────────────────────────────────────────
    private static void demo3_FilteredSearch(LogIndex logIndex, SearchService search) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 3: Filtered Search (Service + Level + Time Range)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Filter by service, log level, time range. ES uses keyword fields.\n");

        SearchQuery errorQuery = new SearchQuery(null, "payment-service", LogLevel.ERROR, null, null, 10);
        System.out.println("Query: service=payment-service, level>=ERROR");
        SearchResult r = search.search(errorQuery);
        System.out.println("  " + r);
        for (LogEntry e : r.getLogs()) {
            System.out.println("  → " + e);
        }

        SearchQuery allErrors = new SearchQuery(null, null, LogLevel.ERROR, null, null, 10);
        System.out.println("\nQuery: level>=ERROR (all services)");
        SearchResult r2 = search.search(allErrors);
        System.out.println("  " + r2);
        for (LogEntry e : r2.getLogs()) {
            System.out.println("  → " + e);
        }

        SearchQuery warnAndAbove = new SearchQuery(null, null, LogLevel.WARN, null, null, 10);
        System.out.println("\nQuery: level>=WARN (all services)");
        SearchResult r3 = search.search(warnAndAbove);
        System.out.println("  " + r3);
        for (LogEntry e : r3.getLogs()) {
            System.out.println("  → " + e);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 4: Alerting on error spike
    // ──────────────────────────────────────────────────────────────────
    private static void demo4_AlertingOnErrorSpike(EventBus kafka, MetadataDB metadataDB,
                                                    AlertService alertService) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 4: Real-Time Alerting on Error Spike");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Alert Engine evaluates rules on Kafka stream BEFORE storage.");
        System.out.println("Fires when count exceeds threshold within sliding window.\n");

        AlertRule rule = new AlertRule("rule_001", "Payment Errors Spike",
                "payment-service", LogLevel.ERROR, 5, 300, "https://pagerduty.com/webhook");
        metadataDB.addAlertRule(rule);
        System.out.println("Alert rule created: " + rule);

        alertService.clearAlerts();
        Instant now = Instant.now();
        System.out.println("\nSimulating burst of ERROR logs from payment-service:");
        for (int i = 0; i < 7; i++) {
            String rawLog = now.plusSeconds(i).toString() +
                    " ERROR Payment processing failed attempt=" + (i + 1) +
                    " host=host-02 service=payment-service";
            kafka.publish(rawLog);
        }

        System.out.println("\nAlert count for rule: " + alertService.getCurrentCount("rule_001"));
        System.out.println("Fired alerts: " + alertService.getFiredAlerts().size());
        for (AlertService.FiredAlert a : alertService.getFiredAlerts()) {
            System.out.println("  " + a);
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 5: Agent resilience (Kafka down → spool → recover)
    // ──────────────────────────────────────────────────────────────────
    private static void demo5_AgentResilience(EventBus kafka, LogProcessorService processor,
                                               LogIndex logIndex) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 5: Agent Resilience (Kafka Down → Local Spool → Recovery)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Agent buffers to local spool file when Kafka is unreachable.");
        System.out.println("Flushes spooled logs when Kafka reconnects. No data loss.\n");

        LogCollectorAgent agent = new LogCollectorAgent("host-10", "order-service", kafka, 3);
        Instant now = Instant.now();

        System.out.println("Phase 1: Normal operation (Kafka available)");
        agent.tailLogLine(now.toString() + " INFO Order created order_id=1");
        agent.tailLogLine(now.plusMillis(10).toString() + " INFO Order created order_id=2");
        agent.tailLogLine(now.plusMillis(20).toString() + " INFO Order created order_id=3");
        agent.flush();
        System.out.println("  Shipped: " + agent.getTotalShipped() + ", Spooled: " + agent.getTotalSpooled());

        System.out.println("\nPhase 2: Kafka goes down");
        agent.setKafkaAvailable(false);
        agent.tailLogLine(now.plusMillis(30).toString() + " ERROR Order failed order_id=4");
        agent.tailLogLine(now.plusMillis(40).toString() + " ERROR Order failed order_id=5");
        agent.flush();
        System.out.println("  Shipped: " + agent.getTotalShipped() + ", Spooled: " + agent.getTotalSpooled());
        System.out.println("  Spool file size: " + agent.getSpoolSize() + " lines");

        System.out.println("\nPhase 3: Kafka recovers — agent flushes spool");
        agent.setKafkaAvailable(true);
        agent.flush();
        processor.flushBulk();
        System.out.println("  Shipped: " + agent.getTotalShipped() + ", Spooled: " + agent.getTotalSpooled());
        System.out.println("  Spool file size: " + agent.getSpoolSize() + " (drained)");
        System.out.println("  Total indexed in ES: " + logIndex.getTotalIndexed());
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 6: Index lifecycle (hot → warm → cold)
    // ──────────────────────────────────────────────────────────────────
    private static void demo6_IndexLifecycle(LogIndex logIndex, RetentionService retention,
                                              ColdStorage coldStorage) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 6: Index Lifecycle Management (Hot → Warm → Cold)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Time-based indices. Old indices move SSD → HDD → S3.");
        System.out.println("Deleting old data = drop entire index (O(1), no per-doc delete).\n");

        Instant threeDaysAgo = Instant.now().minus(3, ChronoUnit.DAYS);
        Instant tenDaysAgo = Instant.now().minus(10, ChronoUnit.DAYS);

        for (int i = 0; i < 5; i++) {
            logIndex.index(new LogEntry(threeDaysAgo.plusSeconds(i), LogLevel.INFO,
                    "api-service", "host-01", "Request processed latency=" + (i * 10) + "ms", null));
        }
        for (int i = 0; i < 3; i++) {
            logIndex.index(new LogEntry(tenDaysAgo.plusSeconds(i), LogLevel.WARN,
                    "db-service", "host-02", "Slow query query_time=" + (i * 100) + "ms", null));
        }

        System.out.println("Before lifecycle:");
        retention.runLifecycleCheck();

        String threeDayIndex = "logs-" + threeDaysAgo.toString().substring(0, 10);
        String tenDayIndex = "logs-" + tenDaysAgo.toString().substring(0, 10);

        System.out.println("\nStep 1: Move 3-day-old index to WARM (SSD → HDD)");
        retention.moveToWarm(threeDayIndex);

        System.out.println("\nStep 2: Archive 10-day-old index to COLD (ES → S3)");
        retention.archiveToCold(tenDayIndex);

        System.out.println("\nAfter lifecycle:");
        retention.runLifecycleCheck();

        System.out.println("\nStep 3: Restore from cold (for incident investigation)");
        retention.restoreFromCold(tenDayIndex);

        System.out.println("\nAfter restore:");
        retention.runLifecycleCheck();
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 7: Search caching (Redis for dashboard queries)
    // ──────────────────────────────────────────────────────────────────
    private static void demo7_SearchCaching(SearchService search) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 7: Search Caching (Redis for Repeated Dashboard Queries)");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Dashboards auto-refresh every 30s. Cache reduces ES load.\n");

        SearchQuery q = new SearchQuery(null, null, LogLevel.ERROR, null, null, 10);

        System.out.println("First query (cache MISS → hits ES):");
        SearchResult r1 = search.search(q);
        System.out.println("  " + r1);

        System.out.println("\nSecond identical query (cache HIT → no ES):");
        SearchResult r2 = search.search(q);
        System.out.println("  " + r2);

        System.out.println("\nThird identical query (cache HIT → no ES):");
        SearchResult r3 = search.search(q);
        System.out.println("  " + r3);

        System.out.println("\nCache stats:");
        System.out.println("  Hits: " + search.getCacheHits());
        System.out.println("  Misses: " + search.getCacheMisses());
        System.out.printf("  Hit rate: %.0f%%%n",
                (double) search.getCacheHits() / (search.getCacheHits() + search.getCacheMisses()) * 100);
    }

    // ──────────────────────────────────────────────────────────────────
    // DEMO 8: Structured vs unstructured log parsing
    // ──────────────────────────────────────────────────────────────────
    private static void demo8_StructuredVsUnstructured(LogProcessorService processor) {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("DEMO 8: Structured vs Unstructured Log Parsing");
        System.out.println("─".repeat(70));
        System.out.println("Concept: Processor parses both formats. Structured (with known fields)");
        System.out.println("is clean. Unstructured falls back to raw message with parse_error tag.\n");

        Instant now = Instant.now();

        String structured = now.toString() + " ERROR Connection refused retries=5 host=host-01 service=db-service";
        System.out.println("Structured input:");
        System.out.println("  " + structured);
        LogEntry parsed1 = processor.parse(structured);
        System.out.println("Parsed result:");
        System.out.println("  Level:   " + parsed1.getLevel());
        System.out.println("  Service: " + parsed1.getService());
        System.out.println("  Host:    " + parsed1.getHost());
        System.out.println("  Message: " + parsed1.getMessage());
        System.out.println("  Meta:    " + parsed1.getMetadata());

        String unstructured = "Something weird happened in the system!!! Check logs";
        System.out.println("\nUnstructured input (no parseable fields):");
        System.out.println("  " + unstructured);
        LogEntry parsed2 = processor.parse(unstructured);
        System.out.println("Parsed result (fallback):");
        System.out.println("  Level:   " + parsed2.getLevel() + " (default)");
        System.out.println("  Service: " + parsed2.getService() + " (unknown)");
        System.out.println("  Message: " + parsed2.getMessage());
        System.out.println("  Meta:    " + parsed2.getMetadata());

        System.out.println("\nParse errors so far: " + processor.getParseErrors());
        System.out.println("Best practice: Services should emit structured JSON logs directly.");
    }
}
