# Search Autocomplete System

A hands-on Java implementation of a Search Autocomplete System based on **Alex Xu's System Design Interview - Chapter 13**.

## Overview

This implementation demonstrates the core concepts of building a scalable autocomplete system that can handle 10M+ DAU with <100ms latency.

## Key Features

- **Trie with Top-K Caching**: O(p) query time where p = prefix length
- **Data Pipeline**: Analytics → Aggregators → Workers → Trie DB
- **Caching Layer**: In-memory cache with weekly snapshots
- **Sharding**: Smart sharding based on query distribution
- **API Layer**: Rate-limited, stateless API servers

## Project Structure

```
autocomplete_system/
├── src/main/java/com/autocomplete/
│   ├── trie/                     # Core Trie Data Structure
│   │   ├── Trie.java            # Main trie implementation
│   │   └── TrieNode.java        # Node with top-k cache
│   │
│   ├── pipeline/                 # Data Collection Pipeline
│   │   ├── AnalyticsLog.java    # Raw query logging (Kafka simulation)
│   │   ├── Aggregator.java      # Frequency aggregation
│   │   └── TrieWorker.java      # Trie builder
│   │
│   ├── storage/                  # Storage Layer
│   │   ├── TrieDB.java          # Persistent trie storage
│   │   └── ShardManager.java    # Sharding logic
│   │
│   ├── cache/                    # Caching Layer
│   │   └── TrieCache.java       # In-memory cache
│   │
│   ├── api/                      # API Layer
│   │   └── AutocompleteService.java  # API endpoint handling
│   │
│   └── AutocompleteDemo.java     # Main demo
│
├── INTERVIEW_CHEATSHEET.md       # Comprehensive interview notes
├── pom.xml                       # Maven configuration
└── README.md                     # This file
```

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          QUERY PATH (READ)                              │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   User types "be" → Load Balancer → API Server → Trie Cache → Response │
│                                                      │                  │
│                                               (cache miss)              │
│                                                      ▼                  │
│                                                  Trie DB                │
│                                                                         │
├─────────────────────────────────────────────────────────────────────────┤
│                         DATA PIPELINE (WRITE)                           │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│   Analytics Logs → Aggregators → Workers → Trie DB → Trie Cache        │
│   (Kafka)          (Spark)       (Build)   (Store)   (Refresh)          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## Key Concepts

### 1. Trie with Top-K Caching (Critical Optimization!)

Without caching:
- Query "be" → DFS all children → collect words → sort → return top 5
- Time: O(prefix + total_chars_under_prefix)

With caching:
- Query "be" → traverse to node → return cached top-5
- Time: O(prefix_length) ✓

### 2. Weekly Trie Rebuild

Why not real-time updates?
- 24K QPS × update all ancestors = too slow
- Inconsistent during updates

Solution:
1. Collect queries all week
2. Aggregate frequencies (dedup, time decay)
3. Build NEW trie offline
4. Atomically swap old → new
5. Refresh cache

### 3. Smart Sharding

Problem: 's' has 12% of queries, 'x' has 0.5%
Solution: Shard by query distribution, not alphabet

```
Shard 0: s (12% alone)
Shard 1: c, p (8% + 7%)
Shard 2: a, b, m, t (combined ~22%)
Shard 3: rest
```

## Running the Demo

```bash
# Compile
mvn compile

# Run the demo
mvn exec:java -Dexec.mainClass="com.autocomplete.AutocompleteDemo"
```

## Demo Output

The demo covers:
1. **Trie Data Structure**: Building trie, querying, updating frequencies
2. **Data Pipeline**: Logging queries, aggregation, trie building
3. **Query Flow**: API → Cache → DB path with metrics
4. **Sharding**: Simple vs smart sharding comparison

## Interview Preparation

See [INTERVIEW_CHEATSHEET.md](INTERVIEW_CHEATSHEET.md) for:
- Quick reference card
- Detailed architecture diagrams
- Time complexity analysis
- Sharding strategies
- Common interview Q&A
- Failure scenarios

## Key Interview Points

1. **Top-K caching is critical** - transforms O(n) to O(p)
2. **Weekly rebuild, not real-time** - consistency and performance
3. **Smart sharding** - balance load based on query distribution
4. **Cache + DB** - cache for latency, DB for durability
5. **Kafka for logging** - high throughput, durability, replay

## References

- Alex Xu's System Design Interview (Chapter 13)
- Facebook Autocomplete System
- Google Search Suggestions

