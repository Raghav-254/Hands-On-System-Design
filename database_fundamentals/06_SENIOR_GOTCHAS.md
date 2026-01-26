# Level 6: Senior "Gotcha" Questions

> These are the edge-case questions that distinguish senior engineers. They probe failure modes, distributed systems complexities, and operational realities.

---

## Table of Contents
1. [Connection & State Management](#1-connection--state-management)
2. [Distributed Systems Edge Cases](#2-distributed-systems-edge-cases)
3. [Data Integrity & Recovery](#3-data-integrity--recovery)
4. [Performance & Scaling](#4-performance--scaling)
5. [Operational Realities](#5-operational-realities)
6. [Deep Dive Answers](#6-deep-dive-answers)

---

## The Six "Gotcha" Questions

### 1. "What happens to your WebSocket connection if the server restarts?"

**Why This Is Tricky:**
- WebSocket connections are stateful
- Server restart = all connections dropped
- Clients may be in the middle of operations
- State stored in server memory is lost

**Key Points to Cover:**
```
1. CONNECTION HANDLING
   • Client must detect disconnect (onclose event)
   • Implement exponential backoff reconnection
   • Don't retry immediately (thundering herd)

2. STATE RECOVERY
   • Don't store critical state in WebSocket server memory
   • Use external store (Redis) for session state
   • Client should request "catch-up" on reconnect

3. MESSAGE DELIVERY
   • In-flight messages may be lost
   • Client should track last received message ID
   • Request missed messages on reconnect
   
   WHERE TO GET MISSED MESSAGES FROM?
   ┌─────────────────────────────────────────────────────────────────┐
   │  Option 1: Redis Message Buffer                                │
   │  • Store last N messages per user/channel in Redis List        │
   │  • On reconnect: Client sends lastMessageId                    │
   │  • Server replays messages after that ID from Redis            │
   │                                                                 │
   │  Option 2: Database (for persistence)                          │
   │  • Messages stored in DB with timestamp/sequence               │
   │  • On reconnect: Query messages WHERE id > lastMessageId       │
   │                                                                 │
   │  Option 3: Kafka (for ordered replay)                          │
   │  • Messages persisted in Kafka topic                           │
   │  • On reconnect: Consumer seeks to last offset + 1             │
   └─────────────────────────────────────────────────────────────────┘
   
   COMMON PATTERN:
   • Real-time delivery: Redis Pub/Sub (ephemeral)
   • Missed messages: Redis List or Database (persistent)
   • Never rely on WebSocket server memory!

4. GRACEFUL SHUTDOWN
   • Drain connections before shutdown
   • Send "server going down" message
   • Use rolling restarts with load balancer
```

**Sample Answer:**
> "When the server restarts, all WebSocket connections are terminated. Our clients detect this via the `onclose` event and implement exponential backoff reconnection to avoid thundering herd. We store session state in Redis, not in server memory, so no state is lost. On reconnect, the client sends its last-received message ID, and the server replays any missed messages from our Redis-backed message buffer. For deployments, we use rolling restarts—the load balancer drains connections from one server before shutting it down."

---

### 2. "How do you handle out-of-order messages in a distributed system?"

**Why This Is Tricky:**
- Network delays cause reordering
- Multiple producers with different latencies
- Consumer parallelism adds more reordering
- Idempotency doesn't solve ordering

**Key Points to Cover:**
```
1. DETERMINE IF ORDER MATTERS
   • User profile updates: Order matters
   • Page view events: Order may not matter
   • Financial transactions: Order is CRITICAL

2. SOLUTIONS BY SCENARIO

   A. KAFKA PARTITION ORDERING
      • Same key → same partition → ordered
      • Trade-off: Limits parallelism

   B. SEQUENCE NUMBERS
      • Producer assigns monotonic sequence
      • Consumer buffers until gaps filled
      • Trade-off: Latency for ordering

   C. VECTOR CLOCKS / LAMPORT TIMESTAMPS
      • Track causality, not wall-clock time
      • Detect concurrent writes
      • Trade-off: Complexity

   D. LAST-WRITER-WINS (LWW)
      • Highest timestamp wins
      • Acceptable for some use cases
      • Trade-off: May lose updates

3. PRACTICAL IMPLEMENTATION
   • Buffer window (wait N ms for stragglers)
   • Sequence gaps trigger re-request or timeout
   • Dead letter queue for unrecoverable cases
```

**Sample Answer:**
> "First, I'd assess if ordering actually matters for the use case. For our order processing, we use Kafka with order_id as the partition key—all events for one order go to one partition and are processed in order. For cases where we can't control partitioning, we embed a sequence number in messages. The consumer maintains a buffer and only processes when sequences are contiguous. If a gap persists beyond a timeout, we either request retransmission or send to a dead letter queue for manual inspection."

---

### 3. "Your database primary fails. Walk me through what happens and what could go wrong."

**Why This Is Tricky:**
- Failure detection is imperfect
- Failover has multiple steps
- Data loss is possible
- Split-brain is possible

**Key Points to Cover:**
```
1. FAILURE DETECTION
   • Health checks from replicas/orchestrator
   • Timeout period before declaring dead
   • False positive: Network issue, not actual failure
   
2. LEADER ELECTION
   • Who decides? (Sentinel, Orchestrator, Consensus)
   • Which replica becomes primary?
   • Most up-to-date replica should win
   
3. FAILOVER EXECUTION
   • Promote replica to primary
   • Update DNS/routing
   • Redirect client connections
   
4. WHAT COULD GO WRONG

   A. SPLIT BRAIN
      • Old primary recovers, thinks it's still leader
      • Two primaries accepting writes
      • Data divergence disaster
      
      SOLUTION: Fencing tokens, STONITH (Shoot The Other Node In The Head)
   
   B. DATA LOSS (Async Replication)
      • Primary had commits not yet replicated
      • New primary is behind
      • Those transactions are GONE
      
      SOLUTION: Sync replication, or accept loss
   
   C. CLIENT STALE ROUTING
      • Clients cached old primary address
      • Continue sending to dead/old primary
      
      SOLUTION: ZooKeeper + Load Balancer (they do DIFFERENT things!)
      
      ┌─────────────────────────────────────────────────────────────────┐
      │  ZOOKEEPER / etcd / Consul = SERVICE DISCOVERY                 │
      │  "Which instances exist and are healthy?"                      │
      │                                                                 │
      │  • Services REGISTER themselves on startup                     │
      │    → "I am order-service-3 at IP 10.0.0.5, I'm alive!"        │
      │  • Health checks detect dead instances                         │
      │  • WATCH mechanism for instant notifications                   │
      │                                                                 │
      ├─────────────────────────────────────────────────────────────────┤
      │  LOAD BALANCER = TRAFFIC DISTRIBUTION                          │
      │  "Which of the 5 healthy instances should handle this request?"│
      │                                                                 │
      │  • Distributes requests across instances                       │
      │  • Algorithms: Round-robin, Least connections, Weighted        │
      │  • L4 (TCP) or L7 (HTTP) load balancing                       │
      │                                                                 │
      ├─────────────────────────────────────────────────────────────────┤
      │  HOW THEY WORK TOGETHER:                                       │
      │                                                                 │
      │  ┌──────────┐     ┌──────────┐     ┌──────────────────────┐   │
      │  │ Service  │────►│ZooKeeper │◄────│   Load Balancer      │   │
      │  │Instances │     │(registry)│     │                      │   │
      │  │ register │     │          │     │ "Give me list of     │   │
      │  │themselves│     │          │     │  healthy instances"  │   │
      │  └──────────┘     └──────────┘     └──────────────────────┘   │
      │                                              │                 │
      │                                              ▼                 │
      │                   ┌────────────────────────────────────────┐   │
      │                   │  Client request → LB → one of 5       │   │
      │                   │  healthy instances (round-robin, etc.) │   │
      │                   └────────────────────────────────────────┘   │
      │                                                                 │
      │  EXAMPLE FLOW:                                                 │
      │  1. 5 instances of order-service register in ZooKeeper        │
      │  2. Instance-3 crashes, ZK detects via health check           │
      │  3. ZK removes instance-3 from registry                       │
      │  4. LB refreshes list from ZK, now routes to 4 instances      │
      │  5. Clients never notice (LB handles it!)                     │
      │                                                                 │
      └─────────────────────────────────────────────────────────────────┘
      
      TL;DR: ZooKeeper = "who is available?"
             Load Balancer = "distribute traffic to available ones"
   
   D. REPLICATION LAG VISIBILITY
      • New primary was behind
      • Clients see "old" data after failover
      
      SOLUTION: Check replica lag before promotion
```

**Sample Answer:**
> "When the primary fails, our orchestrator detects it via missed heartbeats—typically a 10-second timeout. It then initiates election: the replica with the lowest replication lag is promoted. We use synchronous replication to one replica, so we lose at most in-flight transactions. The new primary's address is updated in our service discovery, and clients reconnect. 

> The main risks are: (1) Split-brain if the old primary comes back—we use fencing tokens to prevent it from accepting writes. (2) Stale client connections—our clients have a 30-second connection timeout and retry with service discovery on failure. (3) Brief unavailability during promotion—typically 15-30 seconds."

---

### 4. "You're seeing database connection pool exhaustion. How do you diagnose and fix it?"

**Why This Is Tricky:**
- Many possible root causes
- Symptoms can mislead
- Fix depends on root cause
- Can cascade into system-wide outage

**Key Points to Cover:**
```
1. SYMPTOMS
   • "Connection pool exhausted" errors
   • Request timeouts
   • Increasing latency
   • Thread/goroutine growth

2. DIAGNOSIS STEPS
   
   A. CHECK ACTIVE CONNECTIONS
      • SELECT * FROM pg_stat_activity;
      • Are they idle or active?
      
   B. IDENTIFY LONG QUERIES
      • Slow queries holding connections
      • SELECT query, state, wait_event FROM pg_stat_activity
        WHERE state != 'idle' ORDER BY query_start;
      
   C. CHECK FOR LEAKS
      • Connections not returned to pool
      • Code paths missing conn.close()
      • Exceptions bypassing cleanup
      
   D. MONITOR POOL METRICS
      • Pool size vs active connections
      • Wait time for connection
      • Connection creation rate

3. COMMON CAUSES & FIXES

   CAUSE: Slow queries holding connections
   FIX: Optimize queries, add indexes, set query timeout

   CAUSE: Connection leak in application
   FIX: Use try-finally or context managers, connection timeout
   
   ┌─────────────────────────────────────────────────────────────────┐
   │  WHAT IS A CONNECTION LEAK?                                    │
   │                                                                 │
   │  = A connection is borrowed from pool but NEVER returned       │
   │                                                                 │
   │  HOW IT HAPPENS:                                                │
   │  ┌───────────────────────────────────────────────────────────┐ │
   │  │  conn = pool.getConnection();   // Borrow connection      │ │
   │  │  result = conn.query("SELECT..."); // Do work             │ │
   │  │  // EXCEPTION THROWN HERE! 💥                             │ │
   │  │  conn.close();  // ← This line NEVER runs!                │ │
   │  │                                                           │ │
   │  │  // Connection is now "leaked" - pool thinks it's in use  │ │
   │  │  // but no one will ever return it                        │ │
   │  └───────────────────────────────────────────────────────────┘ │
   │                                                                 │
   │  CONSEQUENCE:                                                   │
   │  • Pool has 100 connections max                                │
   │  • Leak 1 connection per request                               │
   │  • After 100 requests → pool exhausted!                        │
   │  • New requests wait forever for a connection → app hangs     │
   │                                                                 │
   │  FIX: Always use try-finally or context managers               │
   │  ┌───────────────────────────────────────────────────────────┐ │
   │  │  # Python example                                         │ │
   │  │  with pool.getConnection() as conn:  # Auto-closes!      │ │
   │  │      result = conn.query("SELECT...")                     │ │
   │  │  # Connection returned even if exception occurs           │ │
   │  │                                                           │ │
   │  │  // Java example                                          │ │
   │  │  try (Connection conn = pool.getConnection()) {           │ │
   │  │      // Auto-closes in finally                            │ │
   │  │  }                                                        │ │
   │  └───────────────────────────────────────────────────────────┘ │
   │                                                                 │
   │  NOT ABOUT THREADS: It's about connection OBJECTS.             │
   │  (Though each connection may also consume a thread in some DBs)│
   └─────────────────────────────────────────────────────────────────┘

   CAUSE: Pool too small for load
   FIX: Increase pool size (but not too much—DB has limits!)

   CAUSE: N+1 query pattern
   FIX: Batch queries, use JOINs, DataLoader pattern

   CAUSE: Transaction held open during external call
   FIX: Keep transactions short, do I/O outside transaction

4. CONNECTION POOLING BEST PRACTICES
   • Use a dedicated connection pooler (PgBouncer)
   • Set connection timeout (e.g., 30 seconds)
   • Set statement timeout (e.g., 5 seconds)
   • Pool size = (core_count * 2) + effective_spindle_count
```

**Sample Answer:**
> "I'd start by checking `pg_stat_activity` to see what connections are doing. If most are `idle in transaction`, we have a code path holding transactions open too long—likely doing HTTP calls inside a transaction block. If they're `active`, I'd check for slow queries and add appropriate indexes. 

> For the fix: (1) Set a statement_timeout to kill long queries. (2) Audit code for connection leaks—ensure we're using context managers. (3) If it's traffic growth, use PgBouncer for transaction-mode pooling—it can multiplex 1000s of app connections onto 100 DB connections. Long term, we might need read replicas to offload query traffic."

---

### 5. "How do you handle schema migrations in a zero-downtime deployment?"

**Why This Is Tricky:**
- Old and new code run simultaneously during deploy
- Schema changes can break old code
- Some changes require table locks
- Rollback may be needed

**Key Points to Cover:**
```
1. THE PROBLEM
   • Rolling deploy: Old and new app versions coexist
   • Database change must work for BOTH versions
   • Adding NOT NULL column breaks old code
   • Renaming column breaks old code

2. SAFE MIGRATION PATTERNS

   A. ADD COLUMN
      Safe: New code uses it, old code ignores it
      Risk: If NOT NULL, old inserts fail
      Pattern: Add nullable, backfill, then add constraint
   
   B. REMOVE COLUMN
      Safe: After all code stops using it
      Pattern: 
        1. Deploy code that doesn't use column
        2. Wait for rollout
        3. Drop column
   
   C. RENAME COLUMN
      Never safe to do directly
      Pattern:
        1. Add new column
        2. Deploy code that writes to both
        3. Backfill old column → new column
        4. Deploy code that reads from new
        5. Deploy code that only writes new
        6. Drop old column
   
   D. CHANGE COLUMN TYPE
      Pattern: Similar to rename, create new column

3. ONLINE DDL
   • PostgreSQL: Most DDL takes AccessExclusive lock
   • Use pg_repack for online table rewrites
   • MySQL: Online DDL with ALGORITHM=INPLACE
   
4. ROLLBACK CONSIDERATIONS
   • Can old code run with new schema?
   • Keep migrations reversible
   • Feature flags to control rollout
```

**Sample Answer:**
> "We follow expand-contract pattern. For adding a column: (1) Add it as nullable with a default, no lock contention. (2) Deploy new code that writes to it. (3) Backfill historical data in batches. (4) Once verified, add NOT NULL constraint if needed—but this requires a table scan on Postgres.

> For removing a column: (1) Deploy code that doesn't read it. (2) Deploy code that doesn't write it. (3) Only then drop the column. 

> For large table changes, we use gh-ost or pg-online-schema-change to avoid locking. We also keep migrations backward-compatible so we can rollback the application without schema rollback."

---

### 6. "Your cache is returning stale data after a database update. How do you debug and fix it?"

**Why This Is Tricky:**
- Race conditions between cache and DB
- Multiple sources of truth
- Distributed systems timing issues
- Fix might introduce new problems

**Key Points to Cover:**
```
1. COMMON CAUSES

   A. CACHE INVALIDATION RACE CONDITION
      Time:    T1          T2          T3          T4
      Thread1: Read DB     Write Cache
      Thread2:     Update DB    Invalidate Cache
      
      Result: Cache has old value from T1!
   
   B. CACHE POPULATED BEFORE TRANSACTION COMMIT
      Cache updated, transaction rolls back
      Cache has data that doesn't exist
   
   C. REPLICATION LAG
      Write to primary → Read from replica → Cache stale data
   
   D. MULTIPLE CACHE LAYERS
      CDN cached, application cache invalidated
      User sees CDN stale data

2. DEBUGGING STEPS
   • Add cache-debug header showing cache source + timestamp
   • Log cache operations with correlation ID
   • Compare cache value timestamp vs DB updated_at
   • Check for replication lag on read replicas

3. SOLUTIONS

   A. WRITE-THROUGH CACHE
      • Update DB and cache atomically
      • Cache always fresh after writes
   
   B. CACHE-ASIDE WITH VERSION
      • Store version/updated_at in cache
      • On read: compare with DB version
   
   C. CDC-BASED INVALIDATION
      • Debezium watches DB changes
      • Invalidates cache asynchronously
      • Guaranteed to catch all changes
   
   D. SHORT TTL AS SAFETY NET
      • Eventual consistency via expiration
      • Even if invalidation misses, TTL fixes it
   
   E. DELETE INSTEAD OF UPDATE
      • On DB write: delete cache key
      • Next read: cache miss → fresh from DB
      • Avoids race of stale write

4. THE INFAMOUS DOUBLE-DELETE
   Pattern for cache-aside:
   1. Delete cache key
   2. Update database
   3. Delete cache key again (after short delay)
   
   Why: Handles race where read repopulates before write completes
```

**Sample Answer:**
> "I'd first verify it's actually stale—check if the cache key has a timestamp and compare with the database record's updated_at. If the cache is newer than the DB update, we have a race condition.

> Root cause is usually: (1) Read-modify-write race—another thread read stale data and cached it after our invalidation. (2) We're reading from a replica that's behind.

> Fix: We switched to CDC-based invalidation using Debezium. The CDC event triggers cache deletion. Since CDC reads from the WAL, it sees all changes in commit order, eliminating races. We also use 'delete on write' instead of 'update on write' to avoid the race of caching stale data."

---

## Additional Senior Questions

### 7. "How do you ensure exactly-once processing in a distributed system?"

```
ANSWER FRAMEWORK:

1. IMPOSSIBILITY RESULT
   • True exactly-once is impossible in async distributed systems
   • We aim for "effectively exactly-once" via idempotency

2. STRATEGIES

   A. IDEMPOTENT OPERATIONS
      • Design operations that can be safely repeated
      • Increment counter → Set counter (idempotent)
      
   B. IDEMPOTENCY KEYS
      • Client generates unique request ID
      • Server deduplicates by ID
      • Store processed IDs in DB (with TTL cleanup)
      
      ┌─────────────────────────────────────────────────────────────────┐
      │  WHAT IS AN IDEMPOTENCY KEY?                                   │
      │                                                                 │
      │  = A unique ID sent by client to prevent duplicate processing  │
      │                                                                 │
      │  EXAMPLE: Payment API                                          │
      │  ───────────────────────────────────────────────────────────── │
      │                                                                 │
      │  Request:                                                       │
      │  POST /api/payments                                             │
      │  Headers:                                                       │
      │    Idempotency-Key: pay_a1b2c3d4e5f6    ← Client generates     │
      │  Body:                                                          │
      │    { "amount": 100, "to": "merchant_123" }                     │
      │                                                                 │
      │  ───────────────────────────────────────────────────────────── │
      │                                                                 │
      │  SCENARIO: Network timeout, client retries                     │
      │                                                                 │
      │  1. User clicks "Pay $100"                                     │
      │  2. Request sent with key: pay_a1b2c3d4e5f6                   │
      │  3. Server processes payment ✅                                │
      │  4. Network timeout - client never gets response! 😱           │
      │  5. Client retries with SAME key: pay_a1b2c3d4e5f6            │
      │  6. Server checks: "Already processed this key"               │
      │  7. Server returns CACHED response (no re-processing!)        │
      │  8. User charged only ONCE ✅                                  │
      │                                                                 │
      │  ───────────────────────────────────────────────────────────── │
      │                                                                 │
      │  SERVER LOGIC:                                                  │
      │  ┌───────────────────────────────────────────────────────────┐ │
      │  │  def process_payment(idempotency_key, amount):            │ │
      │  │      # Check if already processed                         │ │
      │  │      existing = db.get(f"idem:{idempotency_key}")         │ │
      │  │      if existing:                                         │ │
      │  │          return existing  # Return cached result          │ │
      │  │                                                           │ │
      │  │      # Process new request                                │ │
      │  │      result = charge_user(amount)                         │ │
      │  │                                                           │ │
      │  │      # Store result with TTL (e.g., 24 hours)             │ │
      │  │      db.set(f"idem:{idempotency_key}", result, ttl=86400) │ │
      │  │      return result                                        │ │
      │  └───────────────────────────────────────────────────────────┘ │
      │                                                                 │
      │  KEY FORMAT EXAMPLES:                                          │
      │  • UUID: 550e8400-e29b-41d4-a716-446655440000                  │
      │  • Stripe style: req_abc123xyz                                 │
      │  • Custom: {user_id}_{action}_{timestamp}_{random}             │
      │                                                                 │
      │  USED BY: Stripe, PayPal, AWS, most payment/financial APIs    │
      └─────────────────────────────────────────────────────────────────┘
   
   C. TRANSACTIONAL OUTBOX
      • Write to DB + outbox in same transaction
      • Outbox reader marks as processed
      • Consumers check "already processed" table
   
   D. KAFKA TRANSACTIONS
      • Producer: enable.idempotence=true
      • Consumer: read_committed isolation
      • Exactly-once semantics within Kafka
```

### 8. "What happens when your Redis cache runs out of memory?"

```
EVICTION POLICIES (maxmemory-policy):

• noeviction: Return errors for writes (dangerous!)
• allkeys-lru: Evict least recently used keys
• volatile-lru: Evict LRU among keys with TTL
• allkeys-lfu: Evict least frequently used
• volatile-ttl: Evict keys with shortest TTL

WHAT TO DISCUSS:
• Monitor memory usage before it's critical
• Set maxmemory to 80% of available RAM
• Use allkeys-lru for cache use case
• Have alerting for eviction rate spikes
• Consider Redis Cluster for horizontal scaling
```

### 9. "A single slow query is taking down your database. What do you do?"

```
IMMEDIATE: 
• Identify: pg_stat_activity / SHOW PROCESSLIST
• Kill: pg_terminate_backend(pid) / KILL <id>
• Throttle: Connection limits per user/app

SHORT-TERM:
• Add statement_timeout (e.g., 30s)
• Query analysis: EXPLAIN ANALYZE
• Add missing indexes
• Rewrite query (avoid sequential scan)

LONG-TERM:
• Slow query logging (log_min_duration_statement)
• Query review in CI/CD
• Read replicas for analytics queries
• Separate OLTP and OLAP databases
```

### 10. "How do you handle distributed transactions across microservices?"

```
OPTIONS:

1. TWO-PHASE COMMIT (2PC)
   • Coordinator manages prepare/commit
   • Blocking; coordinator failure is problematic
   • Use only within single database cluster

2. SAGA PATTERN
   • Chain of local transactions
   • Compensating transactions for rollback
   • Eventually consistent
   
   CHOREOGRAPHY: Services react to events
   ORCHESTRATION: Central saga coordinator

3. OUTBOX + CDC
   • Each service writes to local DB + outbox
   • CDC publishes events
   • Other services eventually update
   
4. TCC (Try-Confirm/Cancel)
   • Try: Reserve resources
   • Confirm: Commit reservation
   • Cancel: Release reservation
   • Requires business logic changes
```

---

## Interview Tips

### When You Don't Know
```
DON'T: "I don't know"

DO: 
"I haven't encountered this specific scenario, but based on 
distributed systems principles, I'd approach it by:
1. [Apply relevant principle]
2. [Reason through trade-offs]
3. [Propose a solution]
4. [Acknowledge limitations]"
```

### Demonstrate Seniority
```
1. ASK CLARIFYING QUESTIONS
   • "What's the consistency requirement?"
   • "What's the acceptable downtime?"
   • "What's the scale we're designing for?"

2. DISCUSS TRADE-OFFS
   • Every solution has downsides
   • Senior engineers articulate them

3. REFERENCE REAL EXPERIENCE
   • "At my previous company, we faced..."
   • "We learned the hard way that..."

4. CONSIDER OPERATIONAL ASPECTS
   • Monitoring and alerting
   • Rollback procedures
   • Team capabilities
```

---

## Next Steps

Review the **[Quick Reference Card](QUICK_REFERENCE_CARD.md)** for a 1-page summary before your interview.

