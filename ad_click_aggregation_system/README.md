# Ad Click Event Aggregation

Based on Alex Xu's System Design Interview Volume 2 - Chapter 6

## Overview

A simplified implementation of an ad click event aggregation system. Supports real-time click counting, top-N most clicked ads, filtering by attributes, and exactly-once processing with MapReduce-style aggregation.

## Key Concepts Demonstrated

- **Event Ingestion**: Log watcher → Message Queue pipeline for click events
- **MapReduce Aggregation**: Map (group by ad_id) → Aggregate (count) → Reduce (top-N)
- **Time Windowing**: Tumbling and sliding windows for minute-level aggregation
- **Exactly-Once Processing**: Atomic commit of aggregation results + offset updates
- **Filtering by Tags**: Aggregate by dimensions (country, device, ad format)
- **Data Recalculation**: Replay raw data from message queue for historical fixes
- **Watermarking**: Handle late-arriving events with configurable watermark delays
- **Lambda vs Kappa**: Architecture choices for batch + stream processing

## Architecture

```
Log Watcher → Message Queue → Aggregation Service → Message Queue → DB Writer → Aggregation DB
                   ↓                                                                    ↓
              DB Writer                                                         Query Service
                   ↓                                                           (Dashboard)
           Raw Data DB
```

## Running the Demo

```bash
# Option 1: Using Maven
mvn compile exec:java

# Option 2: Direct compilation
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `AdClickAggregationDemo.java` | Main demo showcasing all features |
| `model/` | Data models (AdClickEvent, AggregatedResult, TimeWindow) |
| `storage/` | Raw data store and aggregation result store |
| `service/` | Aggregation service, query service, filtering |
| `aggregation/` | MapReduce engine, windowing, watermark handling |
