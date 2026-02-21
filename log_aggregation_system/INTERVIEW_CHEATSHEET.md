# 📊 Log Aggregation System - Interview Cheatsheet

> Design a centralized logging platform that collects logs from thousands of servers, processes and indexes them for fast full-text search, and supports real-time alerting — at a scale of 10+ TB/day.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **Log Agent (Filebeat/Fluentd)** | Tail log files on each host, ship to Kafka | Lightweight; push model; local disk buffer for resilience |
| **Kafka** | Durable transport buffer between collection and processing | Decouples producers from consumers; handles burst traffic; partitioned by service_id |
| **Log Processor (Logstash)** | Parse raw log lines into structured JSON, enrich with metadata | Kafka consumer; timestamp normalization, service tagging, field extraction |
| **Elasticsearch** | Index and search structured logs | Inverted index; time-based indices (one per day); hot/warm/cold tiering |
| **Object Storage (S3)** | Cold archival for logs older than retention window | Cheap, durable; not directly searchable; restore to ES if needed |
| **Search/Query API** | Full-text search with filters (service, level, time range) | Translates user queries to Elasticsearch DSL |
| **Alert Service** | Real-time pattern detection on log stream | Evaluates rules before storage; fires alerts on error spikes, patterns |
| **Dashboard (Kibana/Grafana)** | Visualization, dashboards, log tailing | Reads from Elasticsearch; live tail via polling |
| **Metadata DB (MySQL)** | Alert rules, retention policies, user configs | Small, relational; not on the hot path |
| **Redis** | Cache frequent queries, rate limit alerts | Short TTL; reduces ES load for repeated dashboard queries |

---

## The Story: Building a Log Aggregation System

You have thousands of microservices running across hundreds of servers. Each generates log files — error messages, request traces, debug info. When something goes wrong at 3 AM, an engineer needs to search "show me all ERROR logs from the payment service in the last 10 minutes." The system must ingest millions of log lines per second, parse them into structured data, index them for fast search, and alert on anomalies in real-time. The core challenge is the **pipeline**: how do logs flow from thousands of sources to a searchable index without losing data, falling behind, or running out of storage? Staff-level depth means we cover the agent-to-index pipeline, Kafka as a buffer, Elasticsearch indexing strategy, hot/warm/cold tiering, alerting before storage, backpressure handling, and failure scenarios at each hop.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Log ingestion**: Collect logs from thousands of hosts. Support structured (JSON) and unstructured (plaintext) formats.
- **Log processing**: Parse raw log lines into structured records: timestamp, log level, service name, host, message, optional key-value metadata.
- **Search**: Full-text search across all logs. Filter by service, log level, time range, host. Support wildcards and regex.
- **Alerting**: Define rules like "alert if ERROR count from payment-service exceeds 100 in 5 minutes." Real-time evaluation.
- **Dashboards**: Visualize log volume over time, error rates by service, top error messages.
- **Retention**: Configurable retention per service. Auto-delete or archive to cold storage after retention window.
- **Live tail**: Stream new logs in real-time for a given service/filter (like `tail -f` but centralized).

### Non-Functional Requirements

- **Scale**: 10 TB/day of raw logs. 500K log lines/second ingestion. 100M+ log lines stored per day.
- **Latency**: Log → searchable in < 10 seconds (near real-time, not strict real-time). Search results < 2 seconds.
- **Durability**: No log loss under normal operations. Tolerate brief agent outages without data loss.
- **Availability**: Search must be available even during ingestion spikes. Ingestion must not block on search availability.
- **Cost efficiency**: Old logs must move to cheaper storage. Don't store DEBUG logs for 30 days on SSDs.

### Scope (What We're Not Covering)

- Distributed tracing (Jaeger/Zipkin) — related but separate system (trace IDs, span trees).
- Metrics aggregation (Prometheus/StatsD) — numbers, not log lines.
- APM (Application Performance Monitoring) — combines logs, metrics, and traces.

---

## 2. Back-of-the-Envelope Estimation

### Log Volume

```
Services:     2,000 microservices
Hosts:        5,000 servers (some services have multiple instances)
Avg log rate: 100 lines/second per host
Total:        5,000 × 100 = 500,000 lines/second = 500K/s

Avg log line: 500 bytes (structured JSON with metadata)
Throughput:   500K × 500 bytes = 250 MB/s ≈ 21 TB/day raw

With compression (Kafka + ES): ~3:1 ratio → ~7 TB/day stored
```

### Storage

```
Hot  (last 24h, SSD):   7 TB × 1 day = 7 TB
Warm (1-30 days, HDD):  7 TB × 29 days = 203 TB
Cold (30-365 days, S3):  7 TB × 335 days = 2.3 PB/year
Total ES cluster:        ~210 TB (hot + warm)

Elasticsearch overhead:  ~1.5× raw data (inverted index + metadata)
ES cluster storage:      ~315 TB
```

### QPS

```
Ingestion:   500K writes/s to Kafka → 500K writes/s to Elasticsearch
Search:      ~1K searches/second (engineers querying dashboards)
Alert eval:  Stream processing, not request-based
Dashboard:   ~500 req/s (auto-refresh every 30s for active dashboards)
```

### Key Takeaways

| Dimension | Value | Implication |
|-----------|-------|-------------|
| Ingestion rate | 500K lines/s | Kafka is essential as a buffer; direct-to-ES would overload it |
| Daily volume | 21 TB/day raw | Hot/warm/cold tiering is mandatory for cost |
| ES cluster | ~315 TB | Shard across many nodes; time-based indices |
| Search QPS | ~1K/s | Redis cache for frequent dashboard queries |
| Retention | 365 days | Cold storage (S3) for anything > 30 days |

---

## 3. Core Concept: The Log Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                    THE LOG PIPELINE                                   │
│       (Every log line flows through these 5 stages)                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  STAGE 1: COLLECT                                                    │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                            │
│  │ Host A   │ │ Host B   │ │ Host C   │  ... 5,000 hosts           │
│  │ ┌──────┐ │ │ ┌──────┐ │ │ ┌──────┐ │                            │
│  │ │Agent │ │ │ │Agent │ │ │ │Agent │ │  Agent = Filebeat/Fluentd  │
│  │ └──┬───┘ │ │ └──┬───┘ │ │ └──┬───┘ │  Tails log files, pushes  │
│  └────┼─────┘ └────┼─────┘ └────┼─────┘                            │
│       │            │            │                                    │
│       └────────────┼────────────┘                                    │
│                    ▼                                                  │
│  STAGE 2: TRANSPORT                                                  │
│  ┌──────────────────────────────────────┐                            │
│  │             KAFKA                     │                            │
│  │  Topic: "raw-logs"                    │                            │
│  │  Partitioned by service_id            │                            │
│  │  Durable buffer (7-day retention)     │                            │
│  │  Handles burst: agents push at        │                            │
│  │  varying rates; consumers pull at     │                            │
│  │  their own pace.                      │                            │
│  └──────────────┬───────────────────────┘                            │
│                 │                                                     │
│       ┌─────────┴──────────┐                                         │
│       ▼                    ▼                                         │
│  STAGE 3: PROCESS     STAGE 3b: ALERT                                │
│  ┌──────────────┐    ┌──────────────┐                                │
│  │Log Processor │    │Alert Engine  │                                │
│  │(Logstash)    │    │              │                                │
│  │              │    │Evaluate rules│                                │
│  │Parse raw →   │    │BEFORE storage│                                │
│  │structured    │    │              │                                │
│  │JSON          │    │Fire alerts   │                                │
│  │              │    │if threshold  │                                │
│  │Enrich with   │    │breached      │                                │
│  │host, service │    │              │                                │
│  └──────┬───────┘    └──────────────┘                                │
│         │                                                            │
│         ▼                                                            │
│  STAGE 4: INDEX + STORE                                              │
│  ┌──────────────────────────────────────┐                            │
│  │         ELASTICSEARCH                 │                            │
│  │                                       │                            │
│  │  Index: logs-2025-02-15 (one/day)    │                            │
│  │                                       │                            │
│  │  ┌─────────┐ ┌─────────┐ ┌────────┐ │                            │
│  │  │  HOT    │ │  WARM   │ │  COLD  │ │                            │
│  │  │ (SSD)   │ │ (HDD)   │ │ (S3)   │ │                            │
│  │  │ 0-24h   │ │ 1-30d   │ │ 30d+   │ │                            │
│  │  └─────────┘ └─────────┘ └────────┘ │                            │
│  └──────────────────────────────────────┘                            │
│                                                                      │
│  STAGE 5: QUERY                                                      │
│  ┌──────────────┐    ┌──────────────┐                                │
│  │ Search API   │    │ Dashboard    │                                │
│  │              │◄───│ (Kibana)     │                                │
│  │ Full-text    │    │              │                                │
│  │ + filters    │    │ Visualize    │                                │
│  └──────────────┘    └──────────────┘                                │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### How the Agent Works (Pull from File, Push to Kafka)

The agent runs **on the same host** as the application. The application doesn't know the agent exists — it just writes to its log file as usual. The agent tails the file (like `tail -f`), reads new lines, batches them, and pushes to Kafka.

```
Application → writes to → /var/log/app.log (local file, just normal logging)
                                 ↑
Agent (Filebeat) ────────── tails file (pull model)
     │                     reads new lines since last offset
     │                     batches 100-1000 lines
     ▼
   Kafka (push model) ←── agent pushes batch
```

The agent tracks its **file offset** — "I've read up to byte 12345." On restart, it resumes from the last offset, so no logs are duplicated or lost.

### Why Agent-on-Host? (vs Central Collector)

| Aspect | Agent on Host (chosen) | Central Collector (pull from hosts) |
|--------|----------------------|-------------------------------------|
| **Resilience** | Agent buffers to local spool if Kafka is down — no log loss | Central collector down = ALL hosts' logs lost |
| **Scaling** | Scales naturally — each host handles its own logs | Central collector is a bottleneck; must scale for all hosts |
| **Latency** | Low — agent is local, reads file immediately | Higher — must wait for pull cycle |
| **Offset tracking** | Agent tracks per-file offset locally; restart = resume | Central must manage offsets for 5,000 hosts — complex |
| **Network** | Agent batches + compresses before sending — efficient | Central pulls from 5,000 hosts — O(N) connections |
| **Resource cost** | ~50MB RAM, <1% CPU per host (negligible vs the application) | No per-host overhead, but single point of failure |
| **Deployment** | Must deploy agent on every host (config mgmt overhead) | Single deployment point |

The industry standard is **agent-on-host** (Filebeat, Fluentd, Datadog Agent). The ~50MB per host is negligible, and the resilience benefits (local buffering, offset tracking) are critical for a logging system where "lost logs during an outage" defeats the purpose.

### Why Kafka Between Collection and Processing?

Without Kafka, agents push directly to the Log Processor (or Elasticsearch). Problems:

1. **Burst traffic**: A deployment generates 10× normal log volume for 5 minutes. Without a buffer, the processor drops logs or crashes.
2. **Consumer downtime**: If the processor restarts, all logs during downtime are lost.
3. **Backpressure**: If ES is slow (rebalancing shards), the entire pipeline backs up to the agents.

Kafka solves all three: it's a **durable, replayable buffer**. Agents write at their pace; processors consume at theirs. If the processor is down for 10 minutes, Kafka retains the logs, and the processor catches up when it restarts.

### Why Alert Before Storage?

Alerts must fire in near real-time (seconds, not minutes). If we alert only after logs are indexed in ES, we add indexing latency (could be 5-10 seconds under load). By evaluating alert rules on the Kafka stream (before ES), alerts fire as soon as the log arrives in the pipeline.

---

## 4. API Design

### Ingest Logs (HTTP Fallback — NOT the primary path)

**Primary path**: Agents on each host write directly to Kafka using the Kafka producer protocol. No HTTP involved.

**Fallback HTTP endpoint** (for environments that can't run a Kafka client — AWS Lambda, serverless, third-party integrations):

```
POST /api/v1/logs/ingest
Headers: X-Service-Id: payment-service
         X-Host: host-042
{
  "logs": [
    {
      "timestamp": "2025-02-15T12:00:00.123Z",
      "level": "ERROR",
      "message": "Payment failed: timeout connecting to stripe",
      "metadata": {
        "request_id": "req_abc123",
        "user_id": "u_456",
        "latency_ms": 5000
      }
    }
  ]
}
→ 202 Accepted (async — logs queued for processing)
```

- Returns 202 (Accepted), not 200, because logs are queued in Kafka, not yet indexed.
- Batch API: sends logs in batches of 100-1000 for efficiency.
- The HTTP gateway internally publishes to the same Kafka topic ("raw-logs") that agents write to directly.

### Search Logs

```
GET /api/v1/logs/search
  ?q=payment+failed+timeout
  &service=payment-service
  &level=ERROR
  &from=2025-02-15T11:00:00Z
  &to=2025-02-15T12:00:00Z
  &limit=50
  &offset=0

Response 200:
{
  "total_hits": 234,
  "logs": [
    {
      "log_id": "log_abc123",
      "timestamp": "2025-02-15T11:55:32.456Z",
      "level": "ERROR",
      "service": "payment-service",
      "host": "host-042",
      "message": "Payment failed: timeout connecting to stripe",
      "metadata": {"request_id": "req_abc123", "latency_ms": 5000}
    }
  ],
  "took_ms": 45
}
```

- `q`: Full-text search across message field (Elasticsearch query string syntax).
- Filters: `service`, `level`, `host`, time range.
- Pagination via `offset`/`limit`.

### Create Alert Rule

```
POST /api/v1/alerts/rules
{
  "name": "Payment Errors Spike",
  "condition": {
    "service": "payment-service",
    "level": "ERROR",
    "threshold": 100,
    "window_seconds": 300,
    "aggregation": "count"
  },
  "action": {
    "type": "webhook",
    "url": "https://pagerduty.com/webhook/..."
  }
}
→ 201: {"rule_id": "rule_001", ...}
```

---

## 5. High-Level System Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LOG AGGREGATION ARCHITECTURE                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐                                    │
│  │ Host A   │ │ Host B   │ │ Host C   │  ... 5,000 hosts                   │
│  │ (Agent)  │ │ (Agent)  │ │ (Agent)  │                                    │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘                                    │
│       │            │            │                                            │
│       └────────────┼────────────┘                                            │
│                    │ ① push raw logs                                         │
│                    ▼                                                          │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │                          KAFKA                                        │    │
│  │  Topic: "raw-logs" (partitioned by service_id)                       │    │
│  │  Retention: 7 days (replay buffer)                                    │    │
│  └───────────┬──────────────────────────────────┬───────────────────────┘    │
│              │ ② consume                        │ ②b consume                 │
│              ▼                                   ▼                           │
│  ┌──────────────────┐                    ┌──────────────┐                    │
│  │  Log Processor    │                    │ Alert Engine │                    │
│  │  (Logstash)       │                    │              │                    │
│  │                   │                    │ Evaluate     │                    │
│  │  Parse → struct   │                    │ rules on     │                    │
│  │  Enrich metadata  │                    │ stream       │                    │
│  │  Normalize time   │                    │ ③ fire       │                    │
│  └────────┬──────────┘                    └──────┬───────┘                    │
│           │ ④ bulk index                         │                           │
│           ▼                                      ▼                           │
│  ┌──────────────────┐                    ┌──────────────┐                    │
│  │  Elasticsearch    │                    │  PagerDuty / │                    │
│  │                   │                    │  Slack /      │                    │
│  │  Time-based       │                    │  Webhook      │                    │
│  │  indices:         │                    └──────────────┘                    │
│  │  logs-2025-02-15  │                                                       │
│  │  logs-2025-02-14  │      ┌──────────────┐                                │
│  │  ...              │◄─────│ Search API   │◄──── ⑤ query                   │
│  └────────┬──────────┘      └──────┬───────┘                                │
│           │                        │                                         │
│           │ ⑥ lifecycle            │                                         │
│           ▼                        ▼                                         │
│  ┌──────────────┐          ┌──────────────┐                                  │
│  │  S3 (cold)   │          │  Dashboard   │                                  │
│  │  Archived    │          │  (Kibana)    │                                  │
│  │  indices     │          │              │                                  │
│  └──────────────┘          └──────────────┘                                  │
│                                                                              │
│  ┌──────────────┐   ┌──────────────┐                                        │
│  │ Metadata DB  │   │ Redis Cache  │                                        │
│  │ (MySQL)      │   │ (query cache)│                                        │
│  │ Alert rules  │   │              │                                        │
│  │ Retention    │   │ Frequent     │                                        │
│  │ policies     │   │ dashboard    │                                        │
│  └──────────────┘   │ queries      │                                        │
│                      └──────────────┘                                        │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Sync vs. Async Communication

| Hop | Protocol | Sync/Async | Why |
|-----|----------|------------|-----|
| Agent → Kafka | Kafka PRODUCE | **Async (fire-and-forget with acks=1)** | Agent should not block on downstream. Local disk buffer if Kafka is unreachable. |
| Kafka → Log Processor | Kafka CONSUME | **Async (pull-based)** | Processor consumes at its own pace; Kafka retains until consumed. |
| Kafka → Alert Engine | Kafka CONSUME | **Async (parallel consumer)** | Alert evaluation is a separate consumer group; independent of indexing. |
| Log Processor → Elasticsearch | Bulk HTTP POST | **Semi-sync (batch)** | Bulk index every 5 seconds or 1000 docs, whichever comes first. Backpressure: slow down consumption if ES is slow. |
| Engineer → Search API | HTTP GET | **Sync** | Engineer is waiting for results. |
| Search API → Elasticsearch | HTTP GET | **Sync** | On the critical read path. |
| Search API → Redis (cache check) | Redis GET | **Sync** | Sub-ms check before hitting ES. |
| Alert Engine → PagerDuty/Slack | HTTP POST (webhook) | **Async (fire-and-forget)** | Alert delivery shouldn't block stream processing. Retry on failure. |

**Rule of thumb**: The ingestion pipeline is entirely async (agent → Kafka → processor → ES). Only the search/query path is sync.

---

## 7. Log Ingestion Flow (End-to-End)

```
┌─────────────────────────────────────────────────────────────────────┐
│                    LOG INGESTION — STEP BY STEP                      │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ① Application writes to local log file:                            │
│     /var/log/payment-service/app.log                                │
│     "2025-02-15T12:00:00.123Z ERROR Payment failed: timeout ..."   │
│     │                                                                │
│     ▼                                                                │
│  ② Agent (Filebeat) tails the file:                                 │
│     ├── Reads new lines since last offset                           │
│     ├── Batches 100-1000 lines                                      │
│     ├── Adds metadata: host, file path, service tag                 │
│     └── Pushes batch to Kafka                                       │
│     │                                                                │
│     │  If Kafka is unreachable:                                     │
│     │  Agent buffers to local disk (spool file).                    │
│     │  Retries until Kafka is back. No log loss.                    │
│     │                                                                │
│     ▼                                                                │
│  ③ Kafka receives raw log batch:                                    │
│     Topic: "raw-logs", Partition: hash(service_id) % N              │
│     Retained for 7 days (even after consumed — replay buffer).      │
│     │                                                                │
│     ├──────────────────────────┐                                    │
│     ▼                          ▼                                    │
│  ④ Log Processor consumes:   ④b Alert Engine consumes:             │
│     ├── Parse raw line into      ├── Evaluate each log against      │
│     │   structured JSON:         │   active alert rules              │
│     │   {                        ├── If rule triggered:              │
│     │     "timestamp": "...",    │   fire webhook/PagerDuty          │
│     │     "level": "ERROR",      └── Maintains sliding window       │
│     │     "service": "payment",      counts per rule                 │
│     │     "host": "host-042",                                        │
│     │     "message": "Payment                                        │
│     │       failed: timeout...",                                     │
│     │     "metadata": {...}                                          │
│     │   }                                                            │
│     ├── Normalize timestamp                                         │
│     │   (handle multiple formats)                                   │
│     └── Buffer for bulk index                                       │
│     │                                                                │
│     ▼                                                                │
│  ⑤ Bulk index to Elasticsearch:                                    │
│     POST /_bulk                                                      │
│     Index: logs-2025-02-15                                          │
│     Every 5 seconds or 1000 docs (whichever first)                  │
│     │                                                                │
│     ▼                                                                │
│  ⑥ Log is now searchable (~5-10 sec end-to-end latency)            │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Structured vs Unstructured Log Parsing

```
Unstructured (raw plaintext):
  "2025-02-15 12:00:00.123 ERROR [payment-service] Payment failed: timeout connecting to stripe req_id=abc123 latency=5000ms"

  → Parsed via regex/grok pattern into:

Structured (JSON):
  {
    "timestamp": "2025-02-15T12:00:00.123Z",
    "level": "ERROR",
    "service": "payment-service",
    "message": "Payment failed: timeout connecting to stripe",
    "metadata": {
      "request_id": "abc123",
      "latency_ms": 5000
    }
  }

Best practice: Services should emit structured JSON logs directly.
The processor still handles legacy plaintext via configurable parsers.
```

---

## 8. Search and Query Flow

```
┌─────────────────────────────────────────────────────────────────────┐
│                    SEARCH — STEP BY STEP                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  ① Engineer types query in dashboard:                               │
│     "payment failed timeout" service=payment-service level=ERROR    │
│     time range: last 1 hour                                         │
│     │                                                                │
│     ▼                                                                │
│  ② Search API receives request:                                     │
│     GET /api/v1/logs/search?q=payment+failed+timeout&...            │
│     │                                                                │
│     ▼                                                                │
│  ③ Check Redis cache:                                               │
│     Key: hash(query + filters + time_range)                         │
│     Cache HIT → return immediately (frequent dashboard queries)     │
│     Cache MISS → continue to Elasticsearch                          │
│     │                                                                │
│     ▼                                                                │
│  ④ Build Elasticsearch query:                                       │
│     {                                                                │
│       "bool": {                                                      │
│         "must": [                                                    │
│           {"match": {"message": "payment failed timeout"}},          │
│           {"term": {"service": "payment-service"}},                  │
│           {"term": {"level": "ERROR"}},                              │
│           {"range": {"timestamp": {"gte": "...", "lte": "..."}}}    │
│         ]                                                            │
│       }                                                              │
│     }                                                                │
│     │                                                                │
│     │  Time range determines which indices to query:                │
│     │  Last 1 hour → only "logs-2025-02-15" (today's hot index)    │
│     │  Last 7 days → logs-2025-02-09 through logs-2025-02-15       │
│     │  This is why time-based indices matter — skip irrelevant days │
│     │                                                                │
│     ▼                                                                │
│  ⑤ Elasticsearch returns matching documents sorted by timestamp     │
│     │                                                                │
│     ▼                                                                │
│  ⑥ Cache results in Redis (TTL: 30 seconds) → return to client     │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Why Time-Based Indices?

Instead of one giant `logs` index, we create one index per day: `logs-2025-02-15`, `logs-2025-02-14`, etc.

| Benefit | Explanation |
|---------|-------------|
| **Query efficiency** | Searching "last 1 hour" only hits today's index, not 30 days of data |
| **Retention** | Deleting 30-day-old logs = drop an entire index (instant, no per-doc delete) |
| **Tiering** | Move yesterday's index from SSD to HDD. Move 30-day-old to S3. Index-level operation. |
| **Shard management** | Each day gets fresh shards; avoids ever-growing shard sizes |

---

## 9. Data Model

### Structured Log Entry (Elasticsearch Document)

```json
{
  "log_id": "log_abc123",
  "timestamp": "2025-02-15T12:00:00.123Z",
  "level": "ERROR",
  "service": "payment-service",
  "host": "host-042",
  "message": "Payment failed: timeout connecting to stripe",
  "metadata": {
    "request_id": "req_abc123",
    "user_id": "u_456",
    "latency_ms": 5000,
    "http_status": 504
  },
  "tags": ["payment", "timeout", "critical"],
  "ingested_at": "2025-02-15T12:00:05.789Z"
}
```

### Elasticsearch Index Mapping

```json
{
  "mappings": {
    "properties": {
      "log_id":     {"type": "keyword"},
      "timestamp":  {"type": "date"},
      "level":      {"type": "keyword"},
      "service":    {"type": "keyword"},
      "host":       {"type": "keyword"},
      "message":    {"type": "text", "analyzer": "standard"},
      "metadata":   {"type": "object", "dynamic": true},
      "tags":       {"type": "keyword"},
      "ingested_at": {"type": "date"}
    }
  }
}
```

**Key field types:**
- `keyword`: Exact match, filterable, aggregatable. Used for service, level, host.
- `text`: Full-text search with tokenization and scoring. Used for message.
- `date`: Time range queries. Used for timestamp.
- `object` with `dynamic: true`: Allows arbitrary key-value metadata without schema changes.

### Alert Rule (MySQL)

```
┌───────────┬──────────────────┬─────────┬───────────┬────────────┬──────────┐
│ rule_id   │ name             │ service │ level     │ threshold  │ window_s │
│ (PK)      │                  │ (filter)│ (filter)  │ (count)    │          │
├───────────┼──────────────────┼─────────┼───────────┼────────────┼──────────┤
│ rule_001  │ Payment Errors   │ payment │ ERROR     │ 100        │ 300      │
│ rule_002  │ Auth Failures    │ auth    │ WARN      │ 50         │ 60       │
└───────────┴──────────────────┴─────────┴───────────┴────────────┴──────────┘
```

### Retention Policy (MySQL)

```
┌──────────────────┬──────────┬──────────┬──────────┐
│ service          │ hot_days │ warm_days│ cold_days│
├──────────────────┼──────────┼──────────┼──────────┤
│ payment-service  │ 7        │ 30       │ 365      │
│ debug-service    │ 1        │ 7        │ 30       │
│ DEFAULT          │ 1        │ 30       │ 90       │
└──────────────────┴──────────┴──────────┴──────────┘
```

---

## 10. Hot/Warm/Cold Storage Tiering

```
┌─────────────────────────────────────────────────────────────────────┐
│                    INDEX LIFECYCLE MANAGEMENT (ILM)                   │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Day 0 (today)           Day 1-30              Day 30+              │
│  ┌──────────────┐        ┌──────────────┐      ┌──────────────┐    │
│  │     HOT      │───────►│    WARM      │─────►│    COLD      │    │
│  │              │        │              │      │              │    │
│  │  SSD storage │        │  HDD storage │      │  S3 (archive)│    │
│  │  Full replicas│       │  Fewer       │      │              │    │
│  │  Active writes│       │  replicas    │      │  Not directly│    │
│  │              │        │  Read-only   │      │  searchable  │    │
│  │  Newest index│        │  Older       │      │  Restore to  │    │
│  │  logs-today  │        │  indices     │      │  ES if needed│    │
│  │              │        │              │      │              │    │
│  │  Cost: $$$$  │        │  Cost: $$    │      │  Cost: $     │    │
│  └──────────────┘        └──────────────┘      └──────────────┘    │
│                                                                      │
│  Transitions are index-level operations:                            │
│  • Hot → Warm: Change index settings (move to HDD nodes,           │
│    reduce replicas, mark read-only). Triggered after 24h.          │
│  • Warm → Cold: Snapshot index to S3, delete from ES.              │
│    Triggered after retention_warm_days.                              │
│  • Cold → Delete: Delete S3 objects after retention_cold_days.      │
│                                                                      │
│  WHY THIS MATTERS:                                                   │
│  • 95% of searches are for logs < 24 hours old (hot).              │
│  • Keeping 365 days on SSDs would cost 50× more than tiering.      │
│  • Deleting an entire index is O(1) — no per-document deletion.    │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 11. Alerting Pipeline

```
┌─────────────────────────────────────────────────────────────────────┐
│                    ALERTING — REAL-TIME STREAM PROCESSING             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                      │
│  Kafka "raw-logs" topic                                             │
│       │                                                              │
│       ▼                                                              │
│  Alert Engine (Kafka consumer, separate consumer group)             │
│       │                                                              │
│       ├── For each log entry:                                       │
│       │     1. Match against active rules (service + level filter)  │
│       │     2. Increment sliding window counter                     │
│       │     3. If counter > threshold within window → FIRE          │
│       │                                                              │
│       │  Example:                                                    │
│       │  Rule: "payment-service ERROR > 100 in 5 min"              │
│       │                                                              │
│       │  Sliding window (tumbling or sliding):                      │
│       │  ┌──────────────────────────────┐                           │
│       │  │  5-minute window             │                           │
│       │  │  11:55 → 12:00              │                           │
│       │  │  ERROR count: 47             │  ← under threshold       │
│       │  └──────────────────────────────┘                           │
│       │  ┌──────────────────────────────┐                           │
│       │  │  5-minute window             │                           │
│       │  │  11:56 → 12:01              │                           │
│       │  │  ERROR count: 103            │  ← FIRE ALERT!           │
│       │  └──────────────────────────────┘                           │
│       │                                                              │
│       ▼                                                              │
│  Alert fires:                                                        │
│     ├── POST to PagerDuty webhook                                   │
│     ├── Send Slack notification                                     │
│     └── Suppress duplicate alerts (cooldown: 10 min)                │
│                                                                      │
│  KEY INSIGHT: Alerting happens on the Kafka stream BEFORE           │
│  logs reach Elasticsearch. This means alerts fire within            │
│  seconds, not after indexing delay.                                  │
│                                                                      │
└─────────────────────────────────────────────────────────────────────┘
```

### Where Does the Alert Engine Store Window State?

**In-memory** on the alert engine instance — not in a database.

```
Example: Rule "payment-service ERROR > 100 in 60 min"

In-memory data structure per rule (time-bucketed ring buffer):

  Key: (rule_id, service_id) = ("rule_42", "payment-service")

  ┌────────┬────────┬────────┬──── ... ────┬────────┐
  │ 11:00  │ 11:01  │ 11:02  │             │ 11:59  │
  │ count:3│ count:7│ count:0│             │ count:5│
  └────────┴────────┴────────┴──── ... ────┴────────┘
  60 buckets (1-min granularity for a 60-min window)

  On each incoming log:
    1. Does it match the rule filter (service=payment, level=ERROR)?
    2. If yes → increment count in current minute's bucket
    3. Sum all 60 buckets → if sum > threshold → FIRE

  When the clock advances past a bucket:
    → Old bucket is evicted (ring buffer wraps around)
    → Only the last 60 minutes of counts are kept
```

**Why in-memory (not Redis/MySQL)?**

| Approach | Writes/s at 500K logs/s | Latency per log | Practical? |
|----------|------------------------|-----------------|-----------|
| In-memory counter | 0 (just RAM) | ~microseconds | Yes |
| Redis increment | Up to 500K/s | ~1ms per call | Borderline — adds latency, Redis becomes bottleneck |
| MySQL update | Up to 500K/s | ~5ms per call | No — would collapse under load |

**Why this doesn't lose data:**

1. **Kafka partitions by `service_id`** → all logs for `payment-service` go to the **same** alert engine instance. The counter for that rule lives on exactly one machine. No distributed counting or coordination needed.

2. **On restart → bounded replay from Kafka**: if the alert engine crashes, it restarts and replays the last 60 minutes of logs from Kafka (retained for 7 days). Counters are rebuilt. Alerts may be delayed by the replay time, but no window state is permanently lost.

3. **On rebalance** (Kafka consumer group reassignment): same bounded replay. The new owner of a partition replays recent logs to rebuild counters for the rules it now owns.

### Do We Need Flink / Spark for Aggregation?

It depends on rule complexity. Here's the progression:

**Level 1 — Simple Kafka consumer (what we chose):**

```
Rule: "payment-service ERROR > 100 in 5 min"
→ Just a counter per (rule, service) in a ring buffer.
→ Plain Kafka consumer + in-memory state.
→ Covers ~90% of real-world alerting needs.
```

**Level 2 — Apache Flink (when rules get complex):**

```
Rule: "alert if p99 latency > 5s AND error rate > 10%
       across payment-service AND gateway-service
       in a 60-min sliding window"
→ Multi-stream joins, percentile aggregation, session windows.
→ Flink has built-in support for all of this.
→ State is checkpointed to RocksDB + HDFS — survives crashes
   without Kafka replay.
```

**Level 3 — Spark Streaming (NOT for alerting):**

```
Use case: "daily report of top-10 error-producing services"
→ Micro-batch (seconds-to-minutes latency) — too slow for paging an
   on-call engineer at 3 AM.
→ Great for offline log analytics, dashboards, trend reports.
```

| Aspect | Simple Kafka Consumer | Apache Flink | Spark Streaming |
|--------|----------------------|-------------|-----------------|
| **Latency** | Milliseconds | Milliseconds | Seconds to minutes |
| **Windowing** | Manual (ring buffer) | Built-in: tumbling, sliding, session | Built-in (micro-batch) |
| **State on crash** | Lost → bounded replay from Kafka | Checkpointed to RocksDB + HDFS — no replay needed | Checkpointed |
| **Exactly-once** | At-least-once (may double-count during replay) | Exactly-once via Flink checkpoints | Exactly-once via micro-batch |
| **Complex rules** | Hard — must hand-code joins, correlations | Built-in: multi-stream joins, CEP, pattern detection | Possible but high latency |
| **Ops overhead** | Just a JAR — deploy like any Kafka consumer | Full Flink cluster (JobManager + TaskManagers) | Full Spark cluster |
| **Best for** | Simple threshold alerts | Complex real-time alerting | Offline analytics & reports |

**Our choice: start with Level 1.** A plain Kafka consumer handles simple threshold/count rules with microsecond overhead and zero additional infrastructure. If requirements grow to need multi-stream correlation or exactly-once counting, migrate the alert engine to Flink — it consumes from the same Kafka topic, so the rest of the pipeline doesn't change.

---

## 12. Consistency and Reliability

| Data | Consistency Level | Why |
|------|------------------|-----|
| Log ingestion (agent → Kafka) | **At-least-once** | Agent retries on failure; may send duplicates. ES deduplicates by log_id. |
| Kafka → Log Processor | **At-least-once** (consumer commits offset after processing) | If processor crashes mid-batch, Kafka replays from last committed offset. |
| Elasticsearch indexing | **Eventual** (bulk index latency ~5-10s) | Logs are searchable seconds after ingestion, not instantly. Acceptable. |
| Alert evaluation | **Best-effort real-time** | Stream processing; may miss logs during consumer rebalance. Brief gap acceptable. |
| Search results | **Eventual** (ES refresh interval ~1s) | Newly indexed docs visible after next refresh. |
| Cold storage (S3) | **Strong** (immutable snapshots) | Once archived, data is durable (11 nines). |

### At-Least-Once vs Exactly-Once

Logging systems typically use **at-least-once** delivery, not exactly-once. Why?

- **Exactly-once is expensive**: Requires idempotent producers, transactional consumers, dedup logic.
- **Duplicates are tolerable**: A duplicate log line is far less harmful than a lost one. Engineers can filter duplicates manually or by `log_id`.
- **Loss is unacceptable**: A missing ERROR log during an outage means engineers can't diagnose the problem.

---

## 13. Failure Scenarios and Handling

| Failure | Risk | Mitigation | User impact |
|---------|------|------------|-------------|
| **Agent crash** | Logs on that host not collected | Agent tracks file offset on disk. On restart, resumes from last offset. Systemd auto-restarts agent. | Brief gap; no data loss after restart. |
| **Kafka broker down** | Log ingestion blocked | Kafka replication (RF=3). If one broker down, partitions served by replicas. Agent retries. | No impact if replicas healthy. |
| **Kafka unreachable from agent** | Logs pile up on host | Agent buffers to local spool file (configurable size, e.g., 1GB). Flushes to Kafka when reconnected. | Delayed ingestion; no loss up to spool limit. |
| **Log Processor down** | Logs pile up in Kafka | Kafka retains logs for 7 days. Processor catches up on restart. Scale consumer group. | Delayed indexing; logs still safe in Kafka. |
| **Elasticsearch down** | Logs not indexed, search unavailable | Processor stops consuming (backpressure). Kafka retains. ES restarts → processor catches up. | Search unavailable during outage. Ingestion paused but safe. |
| **ES cluster full (disk)** | Index rejection | ILM moves warm indices to cold (S3). Emergency: drop DEBUG level logs. | Possible log loss for low-priority levels. |
| **Ingestion spike (10× normal)** | Pipeline lag increases | Kafka absorbs burst. Auto-scale processor instances. ES bulk size adapts. | Increased ingestion-to-searchable latency. |
| **Alert engine down** | Alerts not firing | Separate consumer group; independent of indexing. Kafka replays missed logs on restart. | Alerts delayed during downtime. |
| **S3 archive fails** | Old indices not moved to cold | Retry archive operation. Keep warm indices longer (cost increase, not data loss). | Higher storage cost temporarily. |

### Backpressure Strategy

```
If Elasticsearch is slow (bulk index taking > 10s):
  1. Log Processor slows down Kafka consumption (reduces poll batch size)
  2. Kafka buffers the excess (7-day retention)
  3. Agents continue writing to Kafka normally (agents are unaffected)
  4. When ES recovers, Processor catches up by consuming faster

If Kafka is full (unlikely with 7-day retention + sufficient capacity):
  1. Agents detect Kafka rejection
  2. Buffer to local spool file
  3. Retry with exponential backoff
```

---

## 14. Scale and Sharding

```
┌──────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                   │
│                                                                       │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐    │
│  │ Kafka            │   │ Elasticsearch   │   │ Log Processor   │    │
│  │                  │   │                  │   │                  │    │
│  │ 50+ partitions   │   │ Time-based      │   │ Kafka consumer   │    │
│  │ for "raw-logs"   │   │ indices:        │   │ group:           │    │
│  │ topic            │   │ 1 index/day     │   │ scale instances  │    │
│  │                  │   │                  │   │ = partitions     │    │
│  │ Partition by     │   │ 5 shards per    │   │                  │    │
│  │ service_id       │   │ index           │   │ Each instance    │    │
│  │                  │   │                  │   │ processes N      │    │
│  │ Throughput:      │   │ Hot nodes: SSD  │   │ partitions       │    │
│  │ 500K msgs/s      │   │ Warm nodes: HDD │   │                  │    │
│  │ across cluster   │   │                  │   │ Stateless:       │    │
│  │                  │   │ Total: 20-50    │   │ auto-scale       │    │
│  │                  │   │ data nodes      │   │ on lag            │    │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘    │
│                                                                       │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐    │
│  │ Agents           │   │ Redis Cache     │   │ Alert Engine    │    │
│  │                  │   │                  │   │                  │    │
│  │ 1 per host       │   │ Cache frequent  │   │ Separate Kafka   │    │
│  │ (5,000 total)    │   │ dashboard       │   │ consumer group   │    │
│  │                  │   │ queries          │   │                  │    │
│  │ Lightweight:     │   │                  │   │ Scale            │    │
│  │ ~50MB RAM each   │   │ TTL: 30 sec     │   │ independently   │    │
│  │                  │   │                  │   │ of processor     │    │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘    │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

| Component | Scale Strategy | Why |
|-----------|---------------|-----|
| **Kafka** | 50+ partitions, partitioned by `service_id`. 3-5 brokers with RF=3. | Ordering per service. Parallelism = partition count. |
| **Log Processor** | Consumer group with instances = partition count. Auto-scale on consumer lag. | Each instance handles a subset of partitions. Stateless. |
| **Elasticsearch** | Time-based indices, 5 primary shards per index. 20-50 data nodes. Hot (SSD), warm (HDD). | Shard count = parallelism for writes and searches. Time-based for lifecycle. |
| **Alert Engine** | Separate consumer group. Scale independently. | Alert evaluation is lightweight; fewer instances needed than processor. |
| **Agents** | One per host. Lightweight. | No central coordination needed; each agent is independent. |
| **Redis** | Cluster mode for dashboard query cache. | Reduces ES load for auto-refreshing dashboards. |

### Handling Log Spikes

When a deployment or incident causes 10× normal log volume:
1. **Kafka absorbs the burst** — agents write at higher rate; Kafka buffers.
2. **Processor auto-scales** — Kubernetes HPA scales based on consumer lag metric.
3. **ES bulk size adapts** — larger batches during spikes for better throughput.
4. **Sampling** — if all else fails, sample DEBUG logs (keep 10%) to reduce volume. Never sample ERROR.

---

## 15. Final Architecture (Putting It All Together)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                    LOG AGGREGATION — COMPLETE ARCHITECTURE                    │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐ ┌──────┐                              │
│  │Host 1│ │Host 2│ │Host 3│ │......│ │Host N│   5,000 hosts                │
│  │Agent │ │Agent │ │Agent │ │      │ │Agent │   with log agents            │
│  └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘ └──┬───┘                              │
│     │        │        │        │        │                                    │
│     └────────┴────────┼────────┴────────┘                                    │
│                       │ ① push raw logs                                      │
│                       ▼                                                      │
│  ┌──────────────────────────────────────────────────────────────────────┐    │
│  │  KAFKA  ("raw-logs", 50 partitions, by service_id, 7-day retain)    │    │
│  └─────────┬────────────────────────────────────────────┬──────────────┘    │
│            │ ②                                          │ ②b                │
│            ▼                                             ▼                   │
│  ┌──────────────────┐                            ┌──────────────┐           │
│  │  Log Processor    │                            │ Alert Engine │           │
│  │  (parse, enrich)  │                            │ (stream eval)│           │
│  └────────┬──────────┘                            └──────┬───────┘           │
│           │ ③ bulk index                                 │ ③b fire          │
│           ▼                                              ▼                   │
│  ┌──────────────────────────────┐                 ┌──────────────┐          │
│  │     ELASTICSEARCH             │                 │ PagerDuty /  │          │
│  │                               │                 │ Slack        │          │
│  │  ┌────────┐ ┌──────┐ ┌────┐ │                 └──────────────┘          │
│  │  │  HOT   │ │ WARM │ │COLD│ │                                            │
│  │  │(SSD,   │ │(HDD, │ │(S3)│ │    ┌──────────────┐                       │
│  │  │today)  │ │1-30d)│ │    │ │◄───│ Search API   │ ④                     │
│  │  └────────┘ └──────┘ └──┬─┘ │    └──────┬───────┘                       │
│  └──────────────────────────┼───┘           │                               │
│                             │               ▼                               │
│                             │        ┌──────────────┐                       │
│                             │        │  Dashboard   │ ⑤                     │
│                             ▼        │  (Kibana)    │                       │
│                      ┌──────────┐    └──────────────┘                       │
│                      │    S3    │                                            │
│                      │(archive) │                                            │
│                      └──────────┘                                            │
│                                                                              │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Numbered Flow Summary

| Step | What | From → To | Notes |
|------|------|-----------|-------|
| ① | Agents tail log files, push batches to Kafka | Agents → Kafka | Async, at-least-once, local spool buffer |
| ② | Log Processor consumes, parses raw → structured JSON, enriches | Kafka → Processor | Consumer group, auto-scalable |
| ②b | Alert Engine consumes same stream in parallel, evaluates rules | Kafka → Alert Engine | Separate consumer group |
| ③ | Processor bulk-indexes structured logs to Elasticsearch | Processor → ES | Bulk every 5s or 1000 docs |
| ③b | Alert fires if threshold breached | Alert Engine → PagerDuty/Slack | Webhook, with cooldown |
| ④ | Engineer searches logs via API | Search API → ES | Full-text + filters, cached in Redis |
| ⑤ | Dashboard visualizes log volume, error rates | Dashboard → Search API | Auto-refresh every 30s |

---

## 16. Trade-off Summary

| Decision | Chosen | Alternative | Why |
|----------|--------|------------|-----|
| **Transport** | Kafka (durable buffer) | Direct agent → ES | Kafka decouples collection from indexing; handles bursts and consumer downtime |
| **Delivery guarantee** | At-least-once | Exactly-once | Exactly-once is expensive; duplicate logs are tolerable, lost logs are not |
| **Index strategy** | Time-based (1 index/day) | Single rolling index | Time-based enables efficient retention (drop whole index), time-range query pruning, and hot/warm/cold tiering |
| **Alert timing** | Stream-based (before storage) | Query-based (after indexing) | Alerts fire within seconds, not after 10s+ indexing delay |
| **Storage tiering** | Hot/warm/cold (SSD → HDD → S3) | All SSD | Cost: 365 days on SSD would cost 50× more. 95% of searches hit last 24h. |
| **Search engine** | Elasticsearch | PostgreSQL full-text / Loki | ES built for log-scale full-text search. Loki trades indexing for cheaper storage but slower queries. |
| **Agent model** | Push (agent → Kafka) | Pull (central collector polls hosts) | Push scales better; agent knows local file state; pull requires central coordination. |
| **Bulk indexing** | Batch (5s or 1000 docs) | Per-document index | Bulk is 10-50× more efficient for ES. Slight latency trade-off (5s). |
| **Cache** | Redis for frequent dashboard queries | No cache | Dashboards auto-refresh every 30s; same query hits ES repeatedly. Cache reduces load. |

---

## 17. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Correct Approach |
|---------|----------------|------------------|
| Sending logs directly to Elasticsearch (no Kafka) | ES can't handle burst traffic; no buffer during ES restarts | Kafka as durable buffer between agents and ES |
| Single giant index for all logs | Can't drop old data efficiently; queries scan everything | Time-based indices (one per day); drop whole index for retention |
| Alerting by querying ES periodically | Adds minutes of latency; wastes ES resources | Stream-based alerting on Kafka, before indexing |
| Keeping all logs on SSD forever | Prohibitively expensive at PB scale | Hot/warm/cold tiering; 95% of searches are < 24h old |
| Per-document indexing (no batching) | 50× slower than bulk; overwhelms ES | Bulk index every 5 seconds or 1000 docs |
| No backpressure handling | Pipeline crash under load; log loss | Processor slows Kafka consumption; Kafka buffers; agents spool locally |
| Exactly-once delivery for logs | Unnecessary complexity; duplicates are harmless | At-least-once with dedup by log_id if needed |
| Logging in unstructured plaintext | Requires complex regex parsing; error-prone | Structured JSON logs from services; parser as fallback for legacy |
| No agent-side buffering | Kafka outage = immediate log loss | Agent buffers to local spool file; retries when Kafka is back |

---

## 18. Interview Talking Points

### "Walk me through the architecture"

> Five stages: (1) Lightweight agents on each host tail log files and push batches to Kafka. (2) Kafka acts as a durable buffer — decouples collection from processing, handles bursts, retains logs for 7 days for replay. (3) Log Processor consumes from Kafka, parses raw lines into structured JSON, enriches with metadata, and bulk-indexes to Elasticsearch. In parallel, an Alert Engine consumes the same stream to evaluate alerting rules in real-time. (4) Elasticsearch stores logs in time-based indices with hot/warm/cold tiering — today on SSDs, 1-30 days on HDDs, 30+ days archived to S3. (5) Engineers search via an API backed by Elasticsearch, with Redis caching for frequent dashboard queries.

### "Why Kafka and not send logs directly to Elasticsearch?"

> Three reasons: (1) Burst handling — a deployment can generate 10× normal log volume; Kafka absorbs the spike while ES processes at its own pace. (2) Consumer downtime — if the Log Processor or ES restarts, Kafka retains logs until they're consumed. No data loss. (3) Decoupling — we can add new consumers (alert engine, analytics pipeline, audit archive) without modifying the collection layer. Each consumer group reads independently.

### "How do you handle 500K logs/second?"

> Horizontally at every stage. Agents are per-host (5,000 of them). Kafka has 50+ partitions — agents write in parallel. Log Processor is a Kafka consumer group — scale instances to match partition count. Elasticsearch uses time-based indices with 5 shards each — writes parallelize across shards and nodes. Bulk indexing (1000 docs per batch) reduces per-doc overhead by 50×.

### "How does alerting work?"

> The Alert Engine is a separate Kafka consumer group that reads the raw-logs stream. For each log, it checks against active rules (e.g., "payment-service ERROR > 100 in 5 min"). It maintains sliding window counters per rule. If a threshold is breached, it fires a webhook to PagerDuty/Slack with a cooldown to suppress duplicates. This happens BEFORE logs reach Elasticsearch, so alerts fire within seconds of the log being produced.

### "How do you manage storage costs?"

> Hot/warm/cold tiering with Index Lifecycle Management. Today's index lives on SSDs (fast writes and searches). After 24 hours, the index moves to HDD nodes (still searchable, cheaper). After 30 days, the index is snapshot-archived to S3 and deleted from ES. 95% of searches hit the last 24 hours, so most queries only touch hot storage. Deleting old data is O(1) — drop the entire daily index.

### "What happens if Elasticsearch is down?"

> Ingestion continues — agents keep writing to Kafka, and Kafka retains logs for 7 days. The Log Processor detects ES is unreachable, pauses consumption (backpressure), and resumes when ES is back. Kafka's retention ensures no logs are lost. Search is unavailable during the outage, but that's the trade-off for a system that separates ingestion from querying. When ES recovers, the Processor catches up by consuming the backlog.

### "Why time-based indices instead of one big index?"

> Three benefits: (1) Query pruning — searching "last 1 hour" only hits today's index, skipping 29 days of data. (2) Retention — deleting 30-day-old logs means dropping one index, which is instant and free (no per-document deletion). (3) Tiering — we can move entire indices between hot/warm/cold storage tiers without per-document operations.
