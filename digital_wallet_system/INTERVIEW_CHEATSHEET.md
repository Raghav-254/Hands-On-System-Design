# 💰 Digital Wallet System - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 12

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **Wallet Service** | API layer for balance transfers | Validates requests, routes to correct partition |
| **Command Queue** | Ordered list of transfer requests | Append-only, input to state machine |
| **Event Queue** | Ordered list of validated state changes | Append-only, source of truth for the system |
| **State (RocksDB)** | Current account balances | Derived from events, can be rebuilt anytime |
| **Raft Node Group** | Replicates events across nodes | Leader + Followers, consensus before commit |
| **Reverse Proxy** | Converts async to sync API | Receives real-time state updates, responds to client |
| **Saga Coordinator** | Cross-partition transactions | Runs Saga steps; triggers compensating actions on failure |

---

## The Story: Building a Digital Wallet

You're building a digital wallet like Apple Pay, Google Pay, or an in-app wallet for an e-commerce platform. Users transfer money between accounts. The challenge? **1 million transfers per second** with 99.99% reliability, full auditability, and the ability to reproduce any historical state. Let's evolve the design step by step.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Balance transfer** between two digital wallets (e.g., A sends $1 to C)
- Support **reproducibility** — ability to replay history and verify any past state

### Non-Functional Requirements

- **1,000,000 TPS** (transfers per second)
- **99.99% reliability**
- **Transactional correctness** — no money lost, no money created
- **Auditability** — full history of every state change

---

## 2. Back-of-the-Envelope Estimation

### How Many Database Nodes?

Each transfer = **2 operations** (debit sender + credit receiver):
- 1M transfers/sec = **2M TPS** actual database operations

| Per-Node TPS | Nodes Required |
|-------------|---------------|
| 100 | 20,000 |
| 1,000 | 2,000 |
| 10,000 | 200 |

> **Design goal:** Maximize per-node TPS to reduce hardware cost. This drives us toward local storage (no network hops to external DB) and sequential I/O.

---

## 3. API Design

```
POST /v1/wallet/transfer
Headers:
  Idempotency-Key: "uuid-abc-123"

Body: {
  "from_account": "A",
  "to_account": "C",
  "amount": 1.00,
  "currency": "USD"
}

Response: {
  "transfer_id": "txn_789",
  "status": "SUCCESS"
}
```

---

## 4. Design Evolution — The Journey

The key insight of this design is that we don't jump to the final architecture. We **evolve** through 5 iterations, each solving a problem the previous one couldn't:

```
Attempt 1: Redis (in-memory)         → Problem: Not durable
    │
    ▼
Attempt 2: Database + 2PC/TCC/Saga   → Problem: Audit = separate log; state and history can drift
    │
    ▼
Attempt 3: Event Sourcing (external) → Problem: Too slow (network hops)
    │
    ▼
Attempt 4: Event Sourcing (local)    → Problem: Single point of failure
    │
    ▼
Attempt 5: Raft + CQRS + Reverse Proxy → Final design ✅
```

---

## 5. Attempt 1: In-Memory Store (Redis)

### Simple picture (single node)

```
Client ──→ Wallet Service ──→ Redis
                                ├── A: $5
                                ├── B: $4
                                ├── C: $3
                                └── D: $2
```

Transfer A → C ($1): `DECRBY A 1` then `INCRBY C 1`. On a **single** Redis, these can be wrapped in a MULTI/EXEC for atomicity.

### Scaled picture

In a real deployment we’d have **multiple Wallet Service** instances and **sharded Redis** (each shard holds a subset of accounts). **Zookeeper** (or similar) holds **partition info** so wallet services know which Redis shard owns which account and can route requests correctly.

```
Transfer command (A -$1 → B)  →  Wallet Service  →  Partition Info (Zookeeper)
                                       │
                    ┌──────────────────┼──────────────────┐
                    ▼                  ▼                  ▼
              Redis {A}          Redis {B}          Redis {C}
```

- **Multiple wallet services:** horizontal scaling and availability.
- **Redis cluster + Zookeeper:** partition info tells which shard has A, which has B; no single Redis holds all balances.

### Why this still fails

**1. Atomicity (cross-shard transfers).** When A and B live on **different** Redis shards, a transfer is **two separate operations** (debit A, credit B). There is no single atomic transaction across shards: one can succeed and the other fail (e.g. network/timeout after debit), so we can lose or create money. Fixing this would require 2PC, sagas, or distributed locks — complex and slow. So in-memory + sharding doesn’t give us safe cross-shard transfers.

**2. Durability.** Even on a single shard, Redis is in-memory. Crashes lose data. RDB/AOF reduce but don’t eliminate the window of loss; for real money, **zero data loss** is required. So in-memory is unacceptable for wallet balances regardless of atomicity.

**Pros:** Very fast per node, simple on a single Redis.

**Fatal flaws:** (1) **Not atomic** across shards. (2) **Not durable** — crashes can lose balances.

> Redis is great for caches and leaderboards where losing a few seconds of data is acceptable. For wallets holding real money, we need durability and atomic cross-account transfers, so we move on.

---

## 6. Attempt 2: Transactional Database

### How It Works

Replace Redis with MySQL/PostgreSQL. Now data is durable (WAL + fsync).

```
Client ──→ Wallet Service ──→ Database
                                ├── account: A, balance: $5
                                ├── account: B, balance: $4
                                ├── account: C, balance: $3
                                └── account: D, balance: $2
```

Transfer A → C ($1):
```sql
BEGIN TRANSACTION;
  UPDATE wallets SET balance = balance - 1 WHERE account = 'A';
  UPDATE wallets SET balance = balance + 1 WHERE account = 'C';
COMMIT;
```

**Pros:** Durable, ACID guarantees, simple.

### The Scale Problem

A single DB node handles ~1,000 TPS. For 1M TPS, we need **2,000 nodes** (since each transfer = 2 ops). That means we must **shard** — partition accounts across multiple DB nodes.

But what if account A is on Node 1 and account C is on Node 3? We need a **distributed transaction**.

### Distributed Transaction Options

When A is on Node 1 and C on Node 3, we need a protocol that commits both updates atomically (or neither). Three common approaches:

---

#### Option A: Two-Phase Commit (2PC)

**Roles:** One **coordinator** (e.g. wallet service or transaction manager); each DB node is a **participant**.

```
Coordinator
    │
    ├── Phase 1 (Prepare): "Can you debit A?"  → Node 1: "Yes, locked"
    │                       "Can you credit C?" → Node 3: "Yes, locked"
    │
    └── Phase 2 (Commit):  "Commit debit A"    → Node 1: "Done"
                            "Commit credit C"   → Node 3: "Done"
```

- **Phase 1:** Coordinator asks every participant to prepare (execute locally but do not commit; hold locks). Participants vote Yes/No. If any says No, coordinator sends **Abort** to all; they release locks and roll back.
- **Phase 2:** If all voted Yes, coordinator sends **Commit** to all; they commit and release locks.

**Pros:** Strong consistency — all commit or all abort; no “half-applied” transfer.

**Cons:**
- **Blocking:** If the coordinator crashes after Phase 1 but before Phase 2, participants have already prepared and are holding locks. They cannot unilaterally commit or abort; they must wait for the coordinator’s recovery (or a timeout and heuristic decision). During that time, those rows are locked — other transfers touching A or C are blocked.
- **Latency:** Two round-trips (prepare + commit) and lock hold time limit throughput.
- **Single point of failure:** In practice we run **multiple coordinator nodes** (for capacity and availability) with **one logical coordinator per transaction**. The “single point of failure” is per transaction: if the node coordinating *this* transaction fails, *this* transaction is stuck until recovery (new coordinator asks participants their state and decides Commit/Abort, or a standby with replicated state takes over). The whole system does not have only one coordinator machine — but until recovery, participants for that transaction keep holding locks.

Used in XA transactions and some legacy systems; rarely a good fit for high-TPS wallet transfers.

---

#### Option B: Try-Confirm/Cancel (TCC)

Each logical transfer is split into three **separate local transactions** (no distributed lock across nodes).

```
Coordinator
    │
    ├── Try:     Reserve $1 from A (frozen, not deducted yet)
    │            Reserve slot for C (prepared to receive)
    │
    ├── Confirm: Deduct $1 from A, Credit $1 to C
    │
    └── Cancel:  (if anything fails) Release all reservations
```

- **Try:** Participants reserve resources (e.g. “A’s balance -1 reserved”, “C has a +1 slot”). No money moves yet; both sides are prepared.
- **Confirm:** If all Try succeeded, coordinator calls Confirm on each participant: actually debit A, actually credit C. If any Confirm fails, coordinator calls **Cancel** on all participants that did Try (release reservations).

**How is this different from 2PC?** In 2PC, Prepare means "I've done the work but **not committed**; I'm **holding a lock** and waiting for your Commit/Abort." So participants block with locks held until Phase 2. In TCC, Try means "I've **already committed** a local transaction" (e.g. "reserve $1 from A" is committed on Node 1). There is no lock held waiting for the coordinator — the reservation is durable. Confirm and Cancel are later local transactions that consume or release that reservation. So if the coordinator dies after Try, participants are not stuck holding locks; a new coordinator or timeout can run Confirm or Cancel. That's why TCC is **non-blocking** and 2PC is blocking.

**Pros:** Non-blocking — no long-held distributed locks. Each phase is a short local transaction. If coordinator dies, participants can eventually resolve via timeouts and cancellation of stale reservations.

**Cons:**
- **Complexity:** Every operation needs three business operations: Try, Confirm, Cancel. Compensating logic (Cancel) must be correct and idempotent (retries are common).
- **Resource design:** You must model “reservations” (e.g. frozen balance, pending credits). That adds schema and application complexity.
- **Partial visibility:** Until Confirm, the transfer isn’t visible as “completed”; consistency is still coordinated, but the programming model is heavier than a single ACID transaction.

Better than 2PC for high throughput when you can afford the three-phase design and reservation model.

---

#### Option C: Saga

A sequence of **local transactions**, each with a **compensating transaction** that undoes its effect if a later step fails.

```
Step 1: Debit A ($1)                    ──→ Success
Step 2: Credit C ($1)                   ──→ Fails!
Compensating action: Credit A ($1) back ──→ Undo step 1
```

- **Choreography:** Each service does its step and publishes an event; the next service reacts. On failure, someone triggers compensations (e.g. “credit A back”) often via another event or callback.
- **Orchestration:** A central orchestrator calls each service in order; if step N fails, it runs compensations for steps N−1, N−2, … in reverse order.

**Pros:** No distributed locks; each step is a single local transaction. Simpler to add new steps (new service + compensation) than 2PC. Works across heterogeneous systems.

**Cons:**
- **Eventual consistency:** Between “debit A” and “credit C” (and during compensation), balances are temporarily wrong. A is debited but C isn’t credited yet — total money in the system is inconsistent; if the system fails before compensation runs, we can lose or duplicate money unless compensations are guaranteed (e.g. via retries and idempotency). *Not the same as 2PC/TCC:* in 2PC nothing commits until Phase 2 (all or nothing), so we never have "A debited, C not credited" as a committed state; in TCC we only commit *reservations* in Try, not the actual debit/credit, so total money stays consistent. In Saga we **commit** the debit first, then the credit — so only Saga has this visible inconsistent window.
- **Compensation failures:** If “credit A back” fails, we need retries, dead-letter queues, or manual intervention. Compensations themselves must be designed for idempotency and failure.
- **Risky for wallets:** For financial ledgers we usually want strong consistency (no visible intermediate state where one side is debited and the other not). Saga is a better fit for booking flows where a short inconsistent window is acceptable.

---

### Why All Three Fall Short (for our wallet)

| Approach | Consistency | Failure / blocking | Complexity | Audit trail |
|----------|-------------|--------------------|------------|-------------|
| **2PC** | Strong (all or nothing) | Blocking — coordinator or participant failure leaves locks held; stalls other transfers | Medium (coordinator + participants) | No — only final state |
| **TCC** | Strong (if Confirm/Cancel correct) | Non-blocking; timeouts and Cancel resolve coordinator failure | High — Try/Confirm/Cancel + reservations for every op | No — only final state |
| **Saga** | Eventual — temporary wrong balances | Non-blocking; compensation can fail and need retries | Medium — one compensating tx per step | No — only final state |
| **All three** | — | — | — | **No event history** — we store current balance, not the sequence of events that produced it |

**Audit trail is the real killer:** With a transactional DB we store `A: $4` but not **why** it’s $4 (one $1 transfer vs two $0.50 transfers). For disputes, compliance, and debugging we need to reproduce history. That leads us to **Event Sourcing** — store every change as an event and derive state by replay.

*Can't we have audits with 2PC or Saga?* Yes. We can add an **audit_log** (or **transactions**) table and write every debit/credit there in the same transaction as the balance update. Then we have current state (balance table) + history (audit log) and can replay for auditing. The catch: we're maintaining **two things** that must stay in sync. If the balance update commits but the audit write fails (or we have a bug in one path), state and history **diverge**. In **event sourcing**, the event log is the **only** source of truth; state is **derived** by replay. We don't "add" an audit log — the design guarantees that state = replay(events), so history and state cannot drift. That's why we say event sourcing gives auditability "by construction," and 2PC/Saga give it only if we add and keep in sync a separate log.

---

## 7. Attempt 3: Event Sourcing (External DB + Queue)

### The Core Idea

Instead of storing current state (`A: $4`), we store **every event** that changed the state:

```
Traditional DB:          Event Sourcing:
┌──────────────┐         ┌──────────────────────────┐
│ A: $4        │         │ Event 1: A -$1 → C       │
│ B: $4        │         │ Event 2: A -$1 → B       │
│ C: $4        │         │ Event 3: A -$1 → D       │
│ D: $3        │         │ ...                       │
└──────────────┘         └──────────────────────────┘
  "What is the             "What happened to get here?"
   balance now?"            State = replay all events
```

**Key benefit:** We can always **reproduce** any historical state by replaying events up to that point. Full auditability.

### How Event Sourcing Works (State Machine)

```
   Command Queue                              State
  ┌────────────────────┐                   ┌──────────┐
  │ A→C  │ A→B  │ A→D  │                   │ A: $5    │
  └──┬───┴──┬───┴──┬───┘                   │ B: $4    │
     │      │      │     Validate           │ C: $3    │
     └──────┴──────┘  ② Read ──────────────→│ D: $2    │
           │          ① Process             └────┬─────┘
           ▼              command                │
     ┌───────────┐                          ⑤ Update
     │   State   │                           State
     │  Machine  │                               │
     └─────┬─────┘                               │
           │                                     │
     ③ Generate                                  │
       Events                                    │
           │                                     │
           ▼               ④ Read                │
  ┌────────────────────┐  events    ┌────────────┘
  │ A:-$1 │ C:+$1 │... │──────────→│
  └────────────────────┘            │
      Event Queue            State Machine
                             applies events
```

**State machine:** The component (implemented as a class, service, or module) that holds current state, validates incoming commands (e.g. sufficient balance), produces events, and applies them to update state. In code it’s often a class with methods like `handle(command)` and `apply(event)`.

**Step-by-step for "A sends $1 to C":**

| Step | What happens |
|------|-------------|
| ① | Command `{A→C, $1}` enters the Command Queue |
| ② | State Machine reads current state: A has $5, C has $3 |
| ③ | Validates (A has enough balance) → generates events: `[A:-$1, C:+$1]` |
| ④ | Events are appended to the Event Queue (append-only, immutable) |
| ⑤ | State Machine applies events → State becomes A:$4, C:$4 |

### Why Events Are the Source of Truth

- **State** is just a cache — derived from events. If corrupted, replay all events to rebuild it.
- **Events** are append-only and immutable — never modified or deleted.
- **Reproducibility** — replay events 1 through N to see what the state was at any point in history.

### The Performance Problem

Here we implement the flow above using **external** infrastructure: the Command Queue might be Kafka (or another queue), the Event Queue and State (or event log) live in MySQL or another DB. The **state machine** runs inside the Wallet Service, so for every transfer it must (① read state from DB, ② append events to DB, ③ maybe enqueue/consume from the queue) — each step is a **network round-trip**. So the same logical flow (command → validate → events → apply) is now spread across the network:

```
Wallet Service (State Machine) ──network──→ External Queue (Kafka) ──network──→ External DB (MySQL)
         ↑                                        ~1ms                              ~1ms
         └── read state, append events, etc. = multiple round-trips per transfer
```

Every transfer therefore involves **multiple network round-trips**. At 1M TPS, network latency dominates. We end up limited to ~1,000 TPS per node, no better than the DB-only approach.

> **The bottleneck is the network, not the computation.** The event-sourcing *model* is right (commands, events, state machine); the *placement* is wrong. If we keep Command Queue, Event Queue, and State **on the same machine**, we remove the network hops and get to Attempt 4.

---

## 8. Attempt 4: Event Sourcing (Local Storage)

### The Breakthrough: Everything on One Node

Move Command Queue, Event Queue, and State to **local disk and memory**:

```
┌──────────────────────────────────────────┐
│              Single Node                  │
│                                           │
│   memory                                  │
│  ┌───────────┐     ┌───────────┐         │
│  │ Command   │────→│  Event    │         │
│  │  List     │     │  List     │         │
│  └───────────┘     └─────┬─────┘         │
│       │                  │               │
│       │   ┌──────────┐   │               │
│       └──→│ RocksDB  │◄──┘               │
│           │  (State)  │                   │
│           └──────┬───┘                   │
│                  │  mmap                  │
│   disk           ▼                        │
│  ┌──────┐  ┌────────┐  ┌──────┐         │
│  │Cmd   │  │RocksDB │  │Event │         │
│  │File  │  │ File   │  │File  │         │
│  └──────┘  └────────┘  └──────┘         │
└──────────────────────────────────────────┘
```

**Key techniques:**
- **RocksDB**: An embedded key-value store (runs inside our process, no network). Stores current state (account → balance). **Read state** = read from RocksDB; when data is in its block cache, it’s a memory-speed read (~μs). No network. (See “Why RocksDB vs MySQL?” below.)
- **mmap (memory-mapped files)**: The OS makes a file on disk “appear” as a region of your process’s memory. *Simple analogy:* Imagine a big book on a shelf. Normally you walk to the shelf, bring the book to your desk, open it, read a page — that’s like `read()` (go get data from disk). With mmap, the OS puts a **magic window** on your desk: when you look through the window, you see the book’s pages *as if* the book were right there. You don’t walk to the shelf; “reading” is just looking at the window. The book is still on the shelf (disk); the window is a view into it. So “read the file” becomes “read from this memory address” — same speed as reading RAM, and the OS handles loading pages from disk when needed and saving changes back.
- **Sequential I/O**: Command and Event files are append-only → sequential disk writes → **10-100x faster** than random I/O.

**Why RocksDB (embedded) vs MySQL (server)? Why no network?**

- **MySQL** = a **separate server process**. Your wallet service runs in one process (or on one machine); MySQL runs in another process, often on another machine. To read or write, your app sends a request over the **network** (or socket) and waits for a reply. That round-trip is the ~1ms we’re trying to avoid.
- **RocksDB** = a **library** that runs **inside your process**. There is no separate “RocksDB server.” Your code calls RocksDB’s API; RocksDB reads and writes **files on the same machine’s disk** and uses an **in-memory block cache**. *What cache?* RocksDB’s block cache: when it reads a chunk of data from disk, it keeps that chunk in RAM so the next read for the same (or nearby) key may not touch disk. *Who stores it?* RocksDB itself — you set a cache size (e.g. 1GB); the library owns that memory and evicts old blocks when full. So “read state” = a function call + maybe a disk read or a cache hit — **no network**.

**Tradeoffs:**

| | RocksDB (embedded) | MySQL (server) |
|---|-------------------|----------------|
| **Network** | No — same process, local disk | Yes — client ↔ server round-trip |
| **Latency** | Very low (μs–sub-ms) | Higher (ms) due to round-trip |
| **Who can access** | Only this process | Many clients, multiple apps |
| **Replication** | Not built-in — we add it (e.g. Raft) | Built-in (replicas, primary-secondary) |
| **Query model** | Key-value (get/put by key) | SQL (flexible queries, joins) |
| **Use here** | We need max TPS per node and control over replication (Raft); one process per partition is fine. | Better when many services need to share one DB or you want SQL and built-in replication. |

### Why This Is Fast

| Operation | External DB | Local Storage |
|-----------|-------------|---------------|
| Write event | Network round-trip (~1ms) | Local disk append (~0.01ms) |
| Read state | Network round-trip (~1ms) | Read from RocksDB (in-process; cache/memory ~0.001ms) |
| TPS per node | ~1,000 | **~10,000+** |

With 10,000 TPS per node, we only need **200 nodes** (down from 2,000).

### Partitioning (Sharding)

**Why multiple nodes?** A single node can only handle on the order of **~10,000 TPS** (bounded by local disk and CPU). Our target is **1M TPS**, so we must spread the load across many nodes. That’s why we need partitioning: each node owns a subset of accounts and handles the transfers that touch those accounts. More nodes → more total TPS.

Accounts are partitioned across nodes so that most transfers stay **within one node**:

```
Partition 1 (Node Group 1): Accounts A, B
Partition 2 (Node Group 2): Accounts C, D
```

**Partition key**: `hash(account_id) % num_partitions`

If both sender and receiver are on the same partition → single local transaction (fast).
If they're on different partitions → need cross-partition coordination (Saga — more on this later).

### The Fatal Flaw: Single Point of Failure

If the node crashes, we lose:
- In-memory state (RocksDB cache)
- Any events not yet fsynced to disk

*What does "node crash" actually mean?* The **node** is the machine (or container) running our process (wallet service + state machine + RocksDB). "Crash" means that node stops working or loses data — for example: process killed (OOM, bug, `kill -9`), machine power loss, hardware failure (CPU/memory/disk), disk corruption, or the node becoming unreachable (network partition). In any of these cases, anything that existed only on that node (in-memory cache, unflushed writes) is lost. So we can't rely on a single node for 99.99% reliability.

*What are we replicating?* We replicate the **event log** (and the data needed to rebuild state). So the same sequence of events is stored on **multiple nodes** (e.g. 3 nodes in a Raft group). Each node can rebuild current state by replaying events. If one node crashes, the others still have the events and (after electing a new leader) can keep serving. So we're not replicating "the machine" — we're replicating the **events** (and thus the source of truth) so that no single node failure loses data.

---

## 9. Attempt 5: Raft Consensus (Final Design)

### Solving the Reliability Problem

We replicate the Event List across **multiple nodes** using the **Raft consensus algorithm**:

```
┌─────────────────────────────────────────────────┐
│                Raft Node Group                    │
│                (Partition 1: Accounts A, B)       │
│                                                   │
│  ┌─────────────┐                                 │
│  │  Follower   │  Events → ○ → ○ → □ → □        │
│  └─────────────┘                                 │
│         ▲ Raft replication                       │
│         │                                         │
│  ┌─────────────┐                                 │
│  │   Leader    │  Commands → ○ → Events → ○ → □  │──→ Read
│  └─────────────┘                                 │
│         │                                         │
│         ▼ Raft replication                       │
│  ┌─────────────┐                                 │
│  │  Follower   │  Events → ○ → ○ → □ → □        │
│  └─────────────┘                                 │
│                                                   │
│  ◄─────── Write ────────►  ◄──── Read ────►      │
└─────────────────────────────────────────────────┘
```

### How Raft Works Here

1. **Leader** receives the command and processes it (validates, generates events)
2. **Leader replicates events** to followers
3. **Majority (2 of 3) acknowledge** → event is considered committed
4. **Leader applies event** to local state and responds to client

If the leader crashes:
- Followers detect the absence (heartbeat timeout)
- They elect a **new leader** among themselves
- The new leader has all committed events (Raft guarantee)
- System continues with minimal downtime

**Who implements Raft?** Not the application from scratch — Raft is complex (leader election, log replication, safety). In practice: (1) **Embed a Raft library** (e.g. HashiCorp Raft in Go, or similar in Java/C++) inside your wallet process; the library handles consensus; the app uses it to replicate the event log (append to Raft log, read committed entries). (2) Or use a **separate Raft-based service** (e.g. etcd, Consul) that exposes a replicated log API; that service implements Raft, and the app just reads/writes the log via its API. So it's application-level **integration** with Raft (via library or service), not implementing the algorithm yourself.

### Why Raft and Not Just Leader-Follower Replication?

| | Simple Replication | Raft Consensus |
|---|---|---|
| **Consistency** | Eventual — followers may lag | Strong — majority must agree before commit |
| **Leader election** | Manual or external (ZooKeeper) | Built-in — automatic leader election |
| **Split brain** | Possible — two leaders serving writes | Impossible — quorum prevents it |
| **Data loss** | Possible — uncommitted writes lost on crash | None — only committed (majority-ack'd) events are applied |

### Snapshots: Preventing Unbounded Event Growth

Over time, the Event List grows indefinitely. Replaying millions of events on startup would be slow. **Snapshots** solve this:

```
Event List:  [e1, e2, e3, ..., e1000000, e1000001, ...]
                                   │
                              Take snapshot
                                   │
                                   ▼
Snapshot (saved to object store / HDFS):
  ┌──────────────────────────────┐
  │ Snapshot @ event 1,000,000   │
  │ A: $4, B: $3, C: $7, D: $1  │
  │ Timestamp: 2024-03-03 24:00  │
  └──────────────────────────────┘

On restart: Load snapshot → replay only events after 1,000,000
```

Snapshots are stored in durable, scalable storage — e.g. an **object store** (S3, GCS) or a **distributed file system** (HDFS). Both work: you need a place to write a large blob (full state) and read it back on restart. Taken periodically (e.g., daily). On restart, we load the latest snapshot and only replay events that came after it.

---

## 10. CQRS: Separating Reads and Writes

### The Problem

In the Raft setup, the **Leader** handles both writes (processing commands) and reads (balance queries). Under heavy load, reads compete with writes on the same node.

### The Solution: Command Query Responsibility Segregation (CQRS)

Separate the **write path** (commands/events/state updates) from the **read path** (balance queries):

```
                    Write Path                          Read Path
                   ┌─────────────────────┐           ┌─────────────────┐
  Command ───→     │  Command Queue      │           │   Event List    │──→ Query
                   │      │              │  Replicate │       │         │
                   │      ▼              │ ─────────→│       ▼         │
                   │  Event Queue        │           │  Read-only      │
                   │      │              │           │  State Machine  │
                   │      ▼              │           │       │         │
                   │  State (RocksDB)    │           │       ▼         │
                   │  (latest balances)  │           │  Historical     │
                   └─────────────────────┘           │  State          │
                     Leader Node                     └─────────────────┘
                                                       Follower Node
```

Yes — this is a **single Raft group** (one partition): one Leader, one or more Followers. The "Replicate" arrow is Raft replicating the Event List from Leader to Followers within this group. We have one such Raft group per partition (e.g. Partition 1 = one group, Partition 2 = another group).

**Why multiple Raft groups? Why not one Raft group with many nodes?** In Raft there is **only one Leader per group**. All writes go to that leader; followers only replicate. So if we had a **single** Raft group for the whole system, we’d have **one leader** handling every transfer for every account — a single bottleneck. We can’t scale writes horizontally. With **multiple Raft groups** (one per partition), we get **one leader per partition**: Partition 1’s leader handles A and B, Partition 2’s leader handles C and D, etc. So we get **multiple leaders** in the system (one per group), and write load is spread across them. Multiple Raft groups = multiple leaders = scalable writes. Single Raft group = single leader = bottleneck.

**Write path (Leader):**
- Receives commands → validates → generates events → updates state
- Only the leader writes

**Read path (Follower):**
- Receives replicated events → rebuilds state
- Serves read queries (balance lookups)
- Can have multiple read replicas for horizontal read scaling

**Benefit:** Writes and reads don't compete. Leader is dedicated to processing transfers. Followers handle all balance queries.

---

## 11. Making It Synchronous: Reverse Proxy

### The Problem

Event Sourcing is inherently **asynchronous**. A command is accepted, but the result isn't immediately available — we have to wait for the event to be processed and state to be updated. But the client expects a **synchronous** API: "I sent $1 to C, tell me the new balances."

### The Solution: Reverse Proxy

```
Client ──① Request──→ Reverse Proxy ──③ Command──→ Leader Node
                           │                           │
                           │                      ④ Process
                           │                      command
                           │                           │
                           │                      ⑤ Event
                           │                      replicated
                           │                           │
                      ⑥ Push latest ◄──────────────────┘
                        status in
                        real-time
                           │
Client ◄─⑦ Response───────┘
```

| Step | What happens |
|------|-------------|
| ① | Client sends transfer request to Reverse Proxy |
| ② | Reverse Proxy generates a unique command ID |
| ③ | Forwards command to the Leader node of the correct partition |
| ④ | Leader validates and generates events |
| ⑤ | Events replicated to followers (Raft); when **majority acks**, event is **committed** |
| ⑤b | **State update:** Leader **applies** the committed event to its local state (e.g. update RocksDB: A's balance, C's balance). So state is updated on the leader only **after** Raft commit. Followers apply the same event when they receive it via replication. |
| ⑥ | Leader pushes the result (e.g. new balances or success) back to Reverse Proxy |
| ⑦ | Reverse Proxy returns synchronous response to client |

So the pipeline is: validate → emit event → replicate (Raft) → **commit** → **apply event to state** (leader and followers) → then we have "new balances" to send back. The state update is the "apply" step that runs after the event is committed.

### Why "Reverse" Proxy? Purpose and Tradeoffs

**Reverse vs forward:** A **forward proxy** sits in front of **clients** (e.g. browsers); clients send requests to it, and it forwards to origin servers. A **reverse proxy** sits in front of **servers**; clients send requests to it, and it forwards to backend servers. Here, the client talks to one entry point (the proxy), and the proxy talks to the backend (Raft leaders). So we're in front of the **servers** → **reverse** proxy. The client doesn't know about leaders or partitions; they just call "the wallet API," which is the proxy.

**Purpose:** The write path is **async** (command → event → replicate → apply → result). The client wants **sync** (request → response with result). The reverse proxy **holds the client connection open**, forwards the command to the leader, waits for the leader to finish (commit + apply) and push back the result, then returns that to the client. So its job is **async→sync conversion** and **single entry point** (routing to the right partition/leader).

**Is it mandatory?** No. **Tradeoffs:**

| | With reverse proxy (sync API) | Without (async API) |
|---|-------------------------------|----------------------|
| **Client** | Simple: send request, get response. | Must poll (e.g. GET /transfer/{id}) or subscribe (WebSocket) to get result. |
| **Connections** | Proxy holds one connection per in-flight request; under high load, many long-lived connections. | No long-held connection; client gets command_id and disconnects. |
| **Use when** | Most clients expect sync (e.g. mobile app, simple integration). | Clients can handle async (e.g. batch jobs, internal services that poll). |

So the reverse proxy is a **design choice** to offer a synchronous API; you can instead expose an async API (return command_id, client polls or subscribes) and avoid the proxy, at the cost of a more complex client contract.

---

## 12. Cross-Partition Transfers: Saga Coordinator

### The Problem

If account A is on Partition 1 and account C is on Partition 2, a single transfer touches **two** Raft node groups. We can't use a single Raft group's consensus for this.

### The Solution: Saga Coordinator

We use **Saga**: a sequence of local steps, each with a **compensating action** if a later step fails. Easy to reason about — no Try/Confirm/Cancel, just "do step 1, do step 2; if step 2 fails, undo step 1."

```
                    ┌───────────────────────────────────────┐
                    │     Partition 1 (Accounts A, B)        │
                    │  ┌──────────┐                         │
          ③ Step 1  │  │ Leader   │  Raft replication       │
     ┌──────────────┤  │ (Debit A)│──→ Followers             │
     │    ⑦ Done   │  └──────────┘                         │
     │              └───────────────────────────────────────┘
     │
  ┌──┴────────────┐
  │    Saga      │  Tracks in-flight transfer; on failure,
  │  Coordinator  │  runs compensating actions in reverse order
  └──┬────────────┘
     │
     │              ┌───────────────────────────────────────┐
     │    ⑨ Step 2  │     Partition 2 (Accounts C, D)        │
     │              │  ┌──────────┐                         │
     └──────────────┤  │ Leader   │  Raft replication       │
        ⑬ Done      │  │(Credit C)│──→ Followers             │
                    │  └──────────┘                         │
                    └───────────────────────────────────────┘
```

### Saga Flow for "A sends $1 to C" (Cross-Partition)

| Step | Action (partition) | If this step fails → compensating action |
|------|--------------------|------------------------------------------|
| 1 | Debit A ($1) on Partition 1 | — (nothing to undo) |
| 2 | Credit C ($1) on Partition 2 | **Compensate step 1:** Credit A ($1) back on Partition 1 |

If step 2 fails (e.g. Partition 2 leader error), the coordinator runs the compensating action for step 1: credit $1 back to A. So we end up with A unchanged and C unchanged — no lost or created money.

**Tradeoff:** There is a short window where A is debited but C isn't credited yet (eventual consistency). For cross-partition we accept this; compensations and idempotent retries keep the system correct.

---

## 13. The Final Architecture (Putting It All Together)

```
                    Reverse Proxy
                   (sync API layer)
                    ┌──────────┐
  Client ──①──────→│    ②     │  ① Request  ⑮ Response
  Client ◄─⑮───────│          │
                    └────┬─────┘
                         │ ③, ⑨ commands
                    ┌────▼─────┐
                    │  Saga  ②│◄──→ In-flight transfer state
                    │Coordinator│
                    └──┬────┬──┘
                       │    │
            ③ Step 1   │    │  ⑨ Step 2
                       ▼    ▼
  ┌─────────────────────┐  ┌─────────────────────┐
  │ Partition 1 (A, B)  │  │ Partition 2 (C, D)  │
  │ ④⑤ write  ⑥ push  ⑦ │  │ ⑩⑪ write ⑫ push ⑬ │
  │ ┌────── Leader ───┐ │  │ ┌────── Leader ───┐ │
  │ │ Cmd → Event →   │ │  │ │ Cmd → Event →   │ │
  │ │ State (RocksDB)  │ │  │ │ State (RocksDB)  │ │
  │ └─────────────────┘ │  │ └─────────────────┘ │
  │    ▲ Raft  ▼ Raft   │  │    ▲ Raft  ▼ Raft   │
  │ ┌──────┐ ┌──────┐   │  │ ┌──────┐ ┌──────┐   │
  │ │Follow│ │Follow│   │  │ │Follow│ │Follow│   │
  │ └──────┘ └──────┘   │  │ └──────┘ └──────┘   │
  └─────────────────────┘  └─────────────────────┘
         Write  Read              Write  Read
```
*(Step numbers ①–⑮ match the flow table below.)*

**End-to-end flow for "A sends $1 to C" (cross-partition, Saga):**

| Step | Component | Action |
|------|-----------|--------|
| ① | Client → Reverse Proxy | `POST /v1/wallet/transfer {A→C, $1}` |
| ② | Coordinator | Records in-flight transfer (for compensation if needed) |
| ③ | Coordinator → Partition 1 Leader | Saga step 1: "Debit A $1" |
| ④-⑤ | Partition 1 Leader | Processes command, generates event, Raft replicates; A: $5→$4 |
| ⑥ | Partition 1 → Reverse Proxy | Pushes updated state (A: $4) |
| ⑦ | Partition 1 → Coordinator | "Step 1 succeeded" |
| ⑨ | Coordinator → Partition 2 Leader | Saga step 2: "Credit C $1" |
| ⑩-⑪ | Partition 2 Leader | Processes command, generates event, Raft replicates; C: $3→$4 |
| ⑫ | Partition 2 → Reverse Proxy | Pushes updated state |
| ⑬ | Partition 2 → Coordinator | "Step 2 succeeded" |
| ⑮ | Reverse Proxy → Client | Returns final result: `{A: $4, C: $4, status: SUCCESS}` |

If step 2 fails, coordinator runs compensating action: credit $1 back to A on Partition 1.

**How does the client read data?** Two cases. (1) **Transfer response (steps ⑥, ⑫, ⑮):** The leader, after applying the event to its state, **pushes** the updated state (e.g. new balances) to the Reverse Proxy; the proxy then returns it to the client. So the client gets the result from the proxy, which got it from the leader. (2) **Explicit read (e.g. GET balance):** The client sends a read request → Reverse Proxy → request is routed to the **correct partition**. That partition serves the read from its **Follower** (CQRS: followers handle reads) or from the Leader; the node reads the balance from its **RocksDB** state and returns it. So "where is the updated status?" — it's in RocksDB on the leader (and on followers after replication); the leader pushes it to the proxy for the transfer response, or the client can GET it later from any replica.

**Where is state stored? Is RocksDB in-memory? What if the node stops?** RocksDB is **not** in-memory only. It stores data on **disk** (with an in-memory block cache for speed). So when we write "State (RocksDB)", that state is **persisted to disk**. If the node stops, the data on disk is still there; when the node restarts, RocksDB loads from disk. In addition: (a) the **event log** is **replicated** by Raft to other nodes — so even if one node's disk were lost, the events exist on the majority; (b) we have **snapshots** in object store / HDFS. So we don't lose state when a node stops: RocksDB persists to disk; the event log is replicated; we have snapshots. The only thing we could lose is **unflushed** data (e.g. writes in RocksDB's cache not yet flushed to disk, or events not yet replicated) — and Raft doesn't consider an event committed until the majority has it, so we don't respond "success" to the client until the event is durable on multiple nodes.

---

## 14. Data Model

### Event Store (Append-Only Event File)

```
┌──────────┬──────────┬─────────┬────────┬────────────────┐
│ event_id │ sequence │ account │ amount │   timestamp    │
├──────────┼──────────┼─────────┼────────┼────────────────┤
│ evt_001  │    1     │    A    │  -1.00 │ 2024-01-01 ... │
│ evt_002  │    2     │    C    │  +1.00 │ 2024-01-01 ... │
│ evt_003  │    3     │    A    │  -1.00 │ 2024-01-01 ... │
│ evt_004  │    4     │    B    │  +1.00 │ 2024-01-01 ... │
└──────────┴──────────┴─────────┴────────┴────────────────┘
```

- **Append-only** — events are never modified or deleted
- **Sequence number** — total order within a partition
- This is the **source of truth** for the entire system

### State Store (RocksDB — Derived from Events)

```
┌─────────┬─────────┬───────────────────┐
│ account │ balance │  last_event_seq   │
├─────────┼─────────┼───────────────────┤
│    A    │  $4.00  │        3          │
│    B    │  $5.00  │        4          │
│    C    │  $4.00  │        2          │
│    D    │  $2.00  │        0          │
└─────────┴─────────┴───────────────────┘
```

- `last_event_seq` tracks which event was last applied — used for crash recovery (replay from this point)
- This is a **cache** — if corrupted, rebuild by replaying all events (or loading snapshot + replaying recent events)

### In-Flight Transfer State (Saga Coordinator) — same idea as "Phase Status Table"

This is the same concept as a **phase status table**: the coordinator stores the status of each in-flight cross-partition transfer so it can recover after a crash. We used to call it "Phase Status Table" when we had TCC (Try/Confirm/Cancel phases). For Saga we track **steps** and **compensation** instead:

```
┌─────────────┬───────────┬───────────┬────────────────┬────────┐
│ transfer_id │ step_1(P1)│ step_2(P2)│ compensation    │ status │
├─────────────┼───────────┼───────────┼────────────────┼────────┤
│ txn_789     │  DONE     │  DONE     │ —               │ SUCCESS│
│ txn_790     │  DONE     │  PENDING  │ —               │ IN_PROG│
│ txn_791     │  DONE     │  FAILED   │ credit A back   │ ROLLED │
└─────────────┴───────────┴───────────┴────────────────┴────────┘
```

- Used for crash recovery — if coordinator restarts, it reads this table: incomplete step 2 → retry or run compensation for step 1

---

## 15. Why Event Sourcing Solves the Audit Problem

### Traditional DB vs Event Sourcing

| Scenario | Traditional DB | Event Sourcing |
|----------|---------------|----------------|
| "What is A's balance?" | `SELECT balance WHERE account = 'A'` → $4 | Replay events → $4 (or read from State cache) |
| "Why is A's balance $4?" | No idea — we only stored the current value | Replay events: started at $5, sent $1 to C → $4 |
| "What was A's balance yesterday?" | Gone — we overwrote it | Replay events up to yesterday's timestamp |
| "Is there a bug?" | Can't tell — no history | Compare replayed state vs stored state — if different, there's a bug |
| "Regulator wants an audit" | Build a separate audit log (duplicate data, might drift) | Event log IS the audit trail |

> **Reproducibility** is the killer feature. Given the same sequence of events, you always get the same state. This makes debugging, auditing, and compliance straightforward.

---

## 16. Performance Optimizations

### Why Local Storage Is Fast

| Technique | How it helps |
|-----------|-------------|
| **mmap** | OS maps disk files to memory. Reads/writes are memory operations. OS handles flushing to disk in background. |
| **Sequential I/O** | Event files are append-only → sequential writes → disk throughput is 10-100x faster than random I/O |
| **RocksDB** | LSM-tree based — optimized for write-heavy workloads. Runs embedded (no network). |
| **Batching** | Multiple commands can be batched into a single Raft round, amortizing consensus overhead |

### Throughput Comparison

| Design | Per-Node TPS | Nodes for 1M TPS |
|--------|-------------|-------------------|
| External DB | ~1,000 | 2,000 |
| Local Event Sourcing | ~10,000 | 200 |
| With Raft batching | ~10,000+ | ~200 |

---

## 17. Fault Tolerance

### What If the Leader Crashes?

1. Followers detect missing heartbeat (timeout)
2. **Raft automatic leader election** — a follower with the most up-to-date event log becomes the new leader
3. New leader resumes processing from the last committed event
4. **No data loss** — only committed events (majority-acknowledged) were applied

### What If a Follower Crashes?

1. Leader continues operating with remaining nodes (as long as majority is alive)
2. When follower recovers, it catches up by replaying events from the leader
3. If too far behind → load latest snapshot + replay recent events

### What If the Coordinator Crashes?

1. Reads in-flight transfer state on restart
2. Step 1 done, step 2 pending → retry step 2 (idempotent)
3. Step 2 failed → run compensating action (credit A back); idempotent
4. No money is lost or duplicated

### What If a Network Partition Occurs?

- Raft requires **majority** to commit → minority side cannot commit new events → reads stale but consistent data
- When partition heals → minority catches up from majority

---

## 18. Key Interview Talking Points

### "Walk Me Through the Design Evolution"

> "We started with Redis for speed, but it's not durable enough for financial data. Then we tried a transactional DB, but at 1M TPS we need 2,000 sharded nodes, and distributed transactions (2PC/TCC/Saga) don't give us auditability. So we adopted Event Sourcing — storing every event instead of just current state. We moved storage local to avoid network hops, added Raft for replication, CQRS for read/write separation, and a Reverse Proxy to give clients a synchronous API."

### "Why Not Just Use a Database?"

> "A database stores current state. For a wallet, we need to answer 'why is A's balance $4?' — not just 'what is it now?' Event Sourcing gives us full history, reproducibility, and auditability. Plus, local storage with mmap gives us 10x the TPS of an external DB."

### "How Do You Handle Cross-Partition Transfers?"

> "We use a Saga coordinator. Step 1: debit the sender on their partition. Step 2: credit the receiver on their partition. If step 2 fails, we run the compensating action — credit the sender back. The coordinator tracks in-flight transfers so on crash we can retry or compensate. Simple to reason about; there's a short window where one side is debited and the other not yet credited."

### "What's the Tradeoff of Event Sourcing?"

> "Complexity. The programming model is harder — you think in events, not state. Rebuilding state requires replaying events (mitigated by snapshots). Cross-partition coordination adds Saga overhead. But for a financial system requiring auditability and reproducibility, it's worth it."

---

## 19. Visualization: Architecture Summary

### Architecture Overview

High-level view of the final system: sync API at the edge, Saga for cross-partition, Raft per partition for replication.

```
                         Client (sync API)
                              │
                              ▼
                    ┌──────────────────┐
                    │  Reverse Proxy   │  Single entry point; holds connection until result
                    └────────┬─────────┘
                             │
                    ┌────────▼─────────┐
                    │ Saga Coordinator │  Cross-partition only; in-flight state for recovery
                    └────────┬─────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐
  │ Partition 1  │    │ Partition 2  │    │ Partition N  │   ... (one per partition)
  │ (e.g. A, B)  │    │ (e.g. C, D)  │    │              │
  │              │    │              │    │              │
  │ Leader       │    │ Leader       │    │ Leader       │   Write path: Cmd → Event → State
  │ Followers    │    │ Followers    │    │ Followers    │   Read path: Followers serve queries
  │ (Raft group)│    │ (Raft group)│    │ (Raft group)│   State = RocksDB; Event log replicated
  └──────────────┘    └──────────────┘    └──────────────┘
```

**Components in one line:** Reverse Proxy (sync API) → Saga Coordinator (cross-partition) → many Partitions; each partition = one Raft group (Leader + Followers), event-sourced state in RocksDB, snapshots in object store/HDFS.

### Key Flows

**Flow 1: Same-Partition Transfer (A → B, both on Partition 1)**
```
Client → Reverse Proxy → Leader (P1) → validate → event → Raft replicate
                                                              → commit
                                                              → update state
                              ← push result ← Leader (P1)
Client ← Reverse Proxy ←
```

**Flow 2: Cross-Partition Transfer (A → C, different partitions, Saga)**
```
Client → Reverse Proxy → Coordinator → Step 1: P1 (debit A) → Raft
                                       → Step 2: P2 (credit C) → Raft
                                       (if step 2 fails → compensate: credit A back on P1)
                              ← push result
Client ← Reverse Proxy ←
```

**Flow 3: Crash Recovery**
```
Node restarts → Load latest snapshot from object store / HDFS
              → Replay events after snapshot sequence number
              → State rebuilt
              → Resume processing
```
