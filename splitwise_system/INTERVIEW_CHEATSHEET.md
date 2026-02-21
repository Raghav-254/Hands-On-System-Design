# 💸 Splitwise - Interview Cheatsheet

> Design a system where users in a group record who paid for what; the system tracks who owes whom and simplifies debts so the minimum number of payments settles everyone.

## Quick Reference Card

| Component | Purpose | Key Points |
|-----------|---------|------------|
| **API Gateway** | Route requests, auth, rate limit | TLS, route by path, per-user rate limit |
| **User / Group Service** | User registration, group management | Groups, members, permissions; read-heavy (cache-friendly) |
| **Expense Service** | Create and list expenses | Who paid, amount, split type (equal/exact/percentage); append-only |
| **Balance Service** | Compute and store pairwise balances | Eagerly updated on every expense/settlement; source of truth for "who owes whom" |
| **Simplification Engine** | Minimize number of settlements | Net balances → greedy algorithm; computed lazily on GET |
| **Settlement Service** | Record "A paid B $X" | Updates balances; marks debt cleared |
| **Notification Service** | Expense/settlement alerts | Async via Kafka; push/email when someone adds expense or settles |
| **Kafka** | Event bus for async side effects | Expense and settlement events → notifications, activity feed |
| **Balance DB (MySQL/PG)** | Persist expenses, balances, settlements | Shard by group_id; all group data on same shard |
| **Cache (Redis)** | Group balances, simplified view | Short TTL; invalidate on expense/settlement |

---

## The Story: Building Splitwise

Friends share expenses (dinner, rent, trip). One person pays; the cost is split among participants. The system tracks **who owes whom how much**. Instead of many pairwise payments (A→B, B→C, C→A), we **simplify** to the minimum number of transactions (e.g. A→C only). The design focuses on correct balance computation, the debt simplification algorithm, concurrency when multiple users add expenses simultaneously, and ensuring balances never become inconsistent. Staff-level depth means we cover the simplification algorithm step by step, balance storage trade-offs, transactional consistency, failure handling, and scale decisions.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- **Users and groups**: Create groups; add/remove members. A user can be in many groups.
- **Expenses**: "A paid $100 for dinner; split equally among A, B, C." Support split types: EQUAL, EXACT (explicit amounts per user), PERCENTAGE.
- **Balances**: Show per user "you owe $X to Y" or "Y owes you $Z"; show group total balance (should be zero).
- **Debt simplification**: Given all expenses/settlements, compute "simplified" view: minimum set of payments that settles everyone.
- **Settle up**: Record "A paid B $30" in real life → update balances.
- **History**: List expenses and settlements in a group (paginated).
- **Notifications**: When someone adds an expense or settles, notify affected users.

### Non-Functional Requirements

- **Correctness**: Balances must always be consistent. Sum of all net balances in a group must be zero. Simplification must produce correct minimal transactions.
- **Scale**: Millions of users, millions of groups; read-heavy (view balances often, add expenses less frequently).
- **Availability**: Adding an expense and viewing balances should work reliably.
- **Latency**: Balance read < 50ms; expense creation < 200ms; simplification < 100ms (small groups).
- **Consistency**: Balance must be strongly consistent (no stale "you owe $0" when you actually owe $50).

### Scope (What We're Not Covering)

- Multi-currency conversion — mention briefly; use a currency service for real-time rates.
- Payment integration (actual money transfer via UPI/bank) — separate payment service.
- Recurring expenses — simple cron that creates expenses periodically.
- Receipt scanning / OCR — separate ML service.

---

## 2. Back-of-the-Envelope Estimation

```
┌────────────────────────────────────────────────────────────────────────┐
│  Users:                  50 million                                    │
│  Groups:                 20 million                                    │
│  Avg group size:         4-5 members                                   │
│  Avg expenses/group/day: 2                                             │
│                                                                        │
│  Expenses/day:           20M groups × 2 = 40M expenses                 │
│  → Expenses/sec:         40M / 86,400 ≈ 460 writes/sec                 │
│  → Peak (5×):            ~2,300 writes/sec                             │
│                                                                        │
│  Balance reads/day:      Users check balances 2-3× per day             │
│  → Balance reads:        50M × 2.5 = 125M reads/day                    │
│  → Balance reads/sec:    125M / 86,400 ≈ 1,450 reads/sec               │
│  → Peak (5×):            ~7,250 reads/sec                              │
│                                                                        │
│  Read:Write ratio:       ~3:1 (moderately read-heavy)                  │
│                                                                        │
│  Storage (5 years):                                                    │
│    Expenses: 40M/day × 365 × 5 = 73B records × 200 bytes = ~14 TB      │
│    Balances: 20M groups × 10 pairs avg × 50 bytes = ~10 GB (small)     │
│    Settlements: fraction of expenses ≈ 2 TB                            │
│                                                                        │
│  Simplification: 4-5 users → greedy runs in O(n log n)                 │
│    100 users → still sub-ms                                            │
└────────────────────────────────────────────────────────────────────────┘
```

**Key insights**: Write volume is moderate (460/sec). Balance reads are more frequent but easily served from cache. The data that's large is expense history (14 TB over 5 years) — shardable and archivable. The balance table itself is tiny (~10 GB). Simplification is a CPU-only operation on small datasets (group size) — always fast.

---

## 3. Core Concept: How Balances Work

### Single Expense Example

When **A pays $90** for A, B, C (equal split, 3-way):

```
Each person's share: $90 / 3 = $30

A paid $90, A's share is $30 → A is owed $60 (by B and C)
B paid $0,  B's share is $30 → B owes $30 (to A)
C paid $0,  C's share is $30 → C owes $30 (to A)

Pairwise balances after this expense:
  B → A: $30  (B owes A)
  C → A: $30  (C owes A)

Net balances: A = +60, B = -30, C = -30 (sum = 0 ✓)
```

### Multiple Expenses

Now C pays $60 for B and C (B's share = $30, C's share = $30):

```
Before:  B → A: $30,  C → A: $30
New:     B → C: $30 (B owes C for the new expense)

After:
  B → A: $30
  C → A: $30
  B → C: $30

Net balances: A = +60, B = -60, C = 0 (sum = 0 ✓)
```

### Balance Storage: Two Approaches

| Approach | How | Pros | Cons |
|----------|-----|------|------|
| **Eager (stored balances)** | On every expense: update `balances` table rows (creditor, debtor, amount). On settlement: decrease the row. | Fast reads (O(1) per pair). Balance always ready. | Write amplification: one expense with N participants → N-1 balance updates. Concurrency needs locking. |
| **Lazy (computed)** | Store only raw expenses. On read: iterate all expenses/settlements and compute balances. | Simple writes (just INSERT expense). No concurrency issue on balances. | Slow reads for groups with many expenses. Recomputation on every view. |

**Our choice: Eager** — update stored balances on every expense/settlement. Balance reads are 3× more frequent than writes, and we want sub-50ms latency on balance views. The write amplification is bounded by group size (typically 4-5 pairs per expense).

---

## 4. Debt Simplification Algorithm

**Problem**: Given N users with pairwise debts, find the minimum number of transactions that settle everyone.

**Step 1: Compute net balance per user**

For each user: `net = (total owed to me) - (total I owe others)`.
- Positive net → creditor (others owe me)
- Negative net → debtor (I owe others)
- Zero → settled (no action needed)

**Step 2: Greedy matching**

```
function simplify(netBalances):
    creditors = sorted list of users with net > 0 (descending)
    debtors   = sorted list of users with net < 0 (ascending, most negative first)

    transactions = []
    while creditors and debtors are not empty:
        creditor = creditors[0]   // largest creditor
        debtor   = debtors[0]     // largest debtor

        amount = min(creditor.net, |debtor.net|)

        transactions.add(debtor → creditor, amount)

        creditor.net -= amount
        debtor.net   += amount

        if creditor.net == 0: remove from creditors
        if debtor.net   == 0: remove from debtors

    return transactions
```

### Worked Example

```
Group: A, B, C, D

Raw debts:
  A → B: $10
  B → C: $20
  C → D: $30
  D → A: $15

Step 1: Net balances
  A: owed $15 (from D), owes $10 (to B) → net = +5
  B: owed $10 (from A), owes $20 (to C) → net = -10
  C: owed $20 (from B), owes $30 (to D) → net = -10
  D: owed $30 (from C), owes $15 (to A) → net = +15
  Sum: +5 + (-10) + (-10) + 15 = 0 ✓

Step 2: Greedy
  Creditors: D(+15), A(+5)
  Debtors:   B(-10), C(-10)

  Round 1: B pays D $10. D: +5, B: 0 → B done.
  Round 2: C pays D $5.  D: 0, C: -5 → D done.
  Round 3: C pays A $5.  A: 0, C: 0 → both done.

  Result: 3 transactions (vs 4 original). Simplified ✓
```

### Is Greedy Optimal?

The general minimum-transaction problem is NP-hard. The greedy approach gives **at most N-1** transactions (where N = number of users with non-zero net), which is **optimal in most practical cases** (small groups of 4-10 people). For interviews, greedy is the expected answer.

> **In the interview**: "We compute net balance per user (O(n)), sort creditors and debtors (O(n log n)), then greedily match the largest creditor with the largest debtor. At most N-1 transactions. Runs in sub-millisecond for typical group sizes. Not globally optimal for all cases (NP-hard), but optimal for most practical groups."

---

## 5. API Design

### Create Group

```
POST /v1/groups
Content-Type: application/json

Body: {
  "name": "Trip to Goa",
  "user_ids": ["u_001", "u_002", "u_003"]
}

Response: 201 Created
{
  "group_id": "g_100",
  "name": "Trip to Goa",
  "members": ["u_001", "u_002", "u_003"],
  "created_at": "2026-02-15T10:00:00Z"
}
```

### Add Expense

```
POST /v1/groups/{group_id}/expenses
Idempotency-Key: <client-generated UUID>

Body: {
  "payer_id": "u_001",
  "amount": 900.00,
  "currency": "INR",
  "description": "Hotel room",
  "split_type": "EQUAL",
  "user_ids": ["u_001", "u_002", "u_003"]
}

Response: 201 Created
{
  "expense_id": "e_001",
  "group_id": "g_100",
  "payer_id": "u_001",
  "amount": 900.00,
  "splits": [
    {"user_id": "u_001", "amount": 300.00},
    {"user_id": "u_002", "amount": 300.00},
    {"user_id": "u_003", "amount": 300.00}
  ]
}
```

- **Idempotency**: Client-generated key prevents duplicate expenses on retry.
- **Split types**: EQUAL (amount / N), EXACT (explicit per-user amounts; must sum to total), PERCENTAGE (per-user %; must sum to 100%).
- **Validation**: All user_ids must be group members. Amounts must sum to total. Payer must be in the group.

### Get Balances

```
GET /v1/groups/{group_id}/balances

Response: 200 OK
{
  "group_id": "g_100",
  "balances": [
    {"debtor": "u_002", "creditor": "u_001", "amount": 300.00},
    {"debtor": "u_003", "creditor": "u_001", "amount": 300.00}
  ],
  "net_balances": [
    {"user_id": "u_001", "net": 600.00},
    {"user_id": "u_002", "net": -300.00},
    {"user_id": "u_003", "net": -300.00}
  ]
}
```

- **No caching concern**: Balances must be consistent. We serve from DB (or very short TTL cache invalidated on write).

### Get Simplified Debts

```
GET /v1/groups/{group_id}/simplified

Response: 200 OK
{
  "group_id": "g_100",
  "transactions": [
    {"from": "u_002", "to": "u_001", "amount": 300.00},
    {"from": "u_003", "to": "u_001", "amount": 300.00}
  ]
}
```

- Computed lazily on each GET. Not stored. Cheap for typical group sizes (4-10 users).

### Settle Up

```
POST /v1/groups/{group_id}/settlements
Idempotency-Key: <client-generated UUID>

Body: {
  "from_user_id": "u_002",
  "to_user_id": "u_001",
  "amount": 300.00
}

Response: 201 Created
{
  "settlement_id": "s_001",
  "from": "u_002",
  "to": "u_001",
  "amount": 300.00,
  "remaining_debt": 0.00
}
```

- Settlement reduces the pairwise balance. If `from_user` owes `to_user` $300 and settles $300 → balance becomes $0.
- **Validation**: Amount must not exceed what `from_user` owes `to_user`.
- **Idempotency**: Same key → return existing settlement.

### Get Expense History

```
GET /v1/groups/{group_id}/activity?page=1&size=20

Response: 200 OK
{
  "items": [
    {"type": "EXPENSE", "id": "e_001", "description": "Hotel room", "payer": "u_001", "amount": 900, ...},
    {"type": "SETTLEMENT", "id": "s_001", "from": "u_002", "to": "u_001", "amount": 300, ...}
  ],
  "has_next": true
}
```

---

## 6. High-Level System Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  Mobile App  │  POST /expenses                         │  Web App     │                │
│  │              │  GET /balances                           │              │                │
│  │              │  POST /settlements                       │              │                │
│  │              │  GET /simplified                         │              │                │
│  └──────┬───────┘                                          └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│                     Auth, rate limit, TLS, route by path                                   │
└─────────┬──────────────────────┬───────────────────────────┬──────────────────────────────┘
          │                      │                           │
   ┌──────▼───────┐    ┌────────▼────────┐        ┌─────────▼──────────┐
   │ Expense       │    │ Balance Service │        │ Settlement         │
   │ Service       │    │                 │        │ Service            │
   │               │    │ ⑥ read balances │        │                    │
   │ ① validate    │    │   (cache / DB)  │        │ ⑧ validate settle  │
   │ ② write       │    │ ⑦ run simplify  │        │ ⑨ update balance   │
   │   expense +   │    │   (on GET)      │        │ ⑩ write settlement │
   │   update      │    │                 │        │                    │
   │   balances    │    │                 │        │                    │
   │   (txn)       │    │                 │        │                    │
   └───┬──────┬────┘    └────────┬────────┘        └───┬──────────┬────┘
       │      │                  │                     │          │
       │      │ ②               │ ⑥                  │          │ ⑪
       │      │ write            │ read                │          │ publish
       │      ▼                  ▼                     │          ▼
       │  ┌──────────────────────────────┐             │    ┌──────────────┐
       │  │         BALANCE DB           │             │    │    KAFKA      │
       │  │         (MySQL/PG)           │◄────────────┘    │              │
       │  │                              │                  │ expense-     │
       │  │  expenses    expense_splits  │                  │ events       │
       │  │  balances    settlements     │                  │ settlement-  │
       │  │  groups      group_members   │                  │ events       │
       │  │  idempotency_keys            │                  └──────┬───────┘
       │  └──────────────────────────────┘                         │
       │                                                      ┌────┴─────────┐
       │  ③ invalidate                                        │ Notification │
       ▼                                                      │ Service      │
  ┌──────────────┐                                            │              │
  │ Redis Cache  │                                            │ Push/Email   │
  │              │                                            └──────────────┘
  │ group_id →   │
  │ balances     │
  │ (short TTL)  │
  └──────────────┘

Three flows:
  Add expense:   ① validate → ② write DB (txn) → ③ invalidate cache → ④ publish event → ⑤ notify
  Read balances: ⑥ read from cache/DB → ⑦ run simplify (on GET /simplified)
  Settle up:     ⑧ validate → ⑨ update balance (txn) → ⑩ write settlement → ⑪ publish event
```

### What Does Each Component Do?

| Component | Responsibility | Why Separate? |
|-----------|---------------|---------------|
| **Expense Service** | Validate and persist expenses; update pairwise balances in same transaction | Core write path; idempotency check; publishes events |
| **Balance Service** | Read pairwise balances; run simplification on demand | Read-only path; serves GET /balances and GET /simplified |
| **Settlement Service** | Record real-world payments; decrease pairwise balances | Similar to expense but decreases debt instead of increasing it |
| **Simplification Engine** | Greedy algorithm on net balances | Pure CPU computation; called by Balance Service on GET /simplified |
| **Kafka** | Decouple side effects from write path | Notifications shouldn't block expense creation |
| **Redis Cache** | Cache group balances | Avoid DB hit on repeated balance views; invalidated on writes |
| **Notification Service** | Push/email alerts | Consume Kafka events; notify group members asynchronously |

---

## 7. Sync vs. Async Communication

| Caller → Callee | Protocol | Sync / Async | Why |
|-----------------|----------|-------------|-----|
| Client → Expense Service (create) | HTTP POST | **Sync** | User is waiting for confirmation |
| Expense Service → Balance DB (INSERT expense + UPDATE balances) | SQL transaction | **Sync** | Must be atomic; user needs to know expense was recorded |
| Expense Service → Redis (invalidate cache) | Redis DEL | **Sync** (fast, ~0.5ms) | Ensure stale balance isn't served after expense |
| Expense Service → Kafka | Kafka PRODUCE | **Async (fire-and-forget)** | Notification is a side effect; shouldn't block response |
| Client → Balance Service (read) | HTTP GET | **Sync** | User is waiting for balances |
| Balance Service → Redis (cache check) | Redis GET | **Sync** | On critical read path |
| Balance Service → DB (cache miss) | SQL SELECT | **Sync** | User is waiting |
| Client → Settlement Service (settle) | HTTP POST | **Sync** | User needs confirmation |
| Settlement Service → DB (UPDATE balances) | SQL transaction | **Sync** | Must be atomic |
| Kafka → Notification Service | Kafka CONSUME | **Async** | Notification processed independently |

**Design principle**: All writes are synchronous with the DB (expense + balance update in same transaction). Side effects (notifications) are async via Kafka. Reads are cache-first, falling through to DB on miss.

---

## 8. Add Expense Flow (End-to-End)

```
Client                  Expense Service              Balance DB            Redis       Kafka
  │                         │                           │                    │           │
  │ ① POST /expenses        │                           │                    │           │
  │ (payer, amount, split)  │                           │                    │           │
  │─────────────────────────→                           │                    │           │
  │                         │                           │                    │           │
  │                    ② Validate                      │                    │           │
  │                    - payer is member                │                    │           │
  │                    - amounts sum to total           │                    │           │
  │                    - all participants in group      │                    │           │
  │                         │                           │                    │           │
  │                    ③ Idempotency check              │                    │           │
  │                    (SELECT WHERE idem_key=?)        │                    │           │
  │                    → if found, return existing      │                    │           │
  │                         │                           │                    │           │
  │                    ④ Compute splits                 │                    │           │
  │                    (EQUAL: amount/N per user)       │                    │           │
  │                         │                           │                    │           │
  │                         │ ⑤ BEGIN TRANSACTION       │                    │           │
  │                         │──────────────────────────→│                    │           │
  │                         │  INSERT expense           │                    │           │
  │                         │  INSERT expense_splits    │                    │           │
  │                         │  UPDATE balances          │                    │           │
  │                         │    (for each non-payer:   │                    │           │
  │                         │     increase payer→user   │                    │           │
  │                         │     debt)                 │                    │           │
  │                         │  INSERT idempotency_key   │                    │           │
  │                         │ COMMIT                    │                    │           │
  │                         │◄──────────────────────────│                    │           │
  │                         │                           │                    │           │
  │                         │ ⑥ DEL cache (group_id)    │                    │           │
  │                         │───────────────────────────────────────────────→│           │
  │                         │                           │                    │           │
  │                         │ ⑦ Publish EXPENSE_ADDED   │                    │           │
  │                         │──────────────────────────────────────────────────────────→│
  │                         │                           │                    │           │
  │ ⑧ 201 Created           │                           │                    │           │
  │ {expense_id, splits}    │                           │                    │           │
  │◄─────────────────────────                           │                    │           │
```

**Critical detail**: Step ⑤ is a **single DB transaction**. The expense, splits, balance updates, and idempotency key are all written atomically. If any part fails, everything rolls back.

**Why a transaction (why not eventual consistency)?** Expenses and balances are two views of the same truth. If the expense INSERT succeeds but the balance UPDATE fails, users see wrong balances and make real-world payment decisions based on them — Bob sees "you owe $0" when he actually owes Alice $300. Unlike notifications (where seconds of delay are fine), an incorrect balance directly causes someone to overpay or underpay. There's no background reconciliation that can fix this safely without user confusion. So we need **strong consistency**: all writes succeed together or none do.

**Why is the idempotency key in the DB (not Redis)?** Because it must be atomic with the expense write. If we stored it in Redis separately, the DB write could succeed but the Redis write could fail — and on retry, we wouldn't find the key, so we'd create a duplicate expense with wrong balances. By putting the key in the same DB transaction, either both the expense and the key are recorded, or neither is. On retry, we find the key and return the existing expense. Same pattern used in BookMyShow and Uber.

### Balance Update Logic (Inside the Transaction)

For an expense where `payer_id = A`, participants = [A, B, C], equal split of $90:

```sql
-- B's share is $30. B now owes A $30 more.
UPDATE balances
SET amount = amount + 30
WHERE group_id = ? AND creditor_id = 'A' AND debtor_id = 'B';

-- C's share is $30. C now owes A $30 more.
UPDATE balances
SET amount = amount + 30
WHERE group_id = ? AND creditor_id = 'A' AND debtor_id = 'C';

-- If the row doesn't exist yet (first expense between this pair), INSERT it.
-- Use INSERT ... ON DUPLICATE KEY UPDATE (upsert).
```

---

## 9. Settle Up Flow (End-to-End)

```
Client                  Settlement Service           Balance DB            Kafka
  │                         │                           │                    │
  │ ① POST /settlements     │                           │                    │
  │ (from: B, to: A, $300)  │                           │                    │
  │─────────────────────────→                           │                    │
  │                         │                           │                    │
  │                    ② Validate                      │                    │
  │                    - both users in group            │                    │
  │                    - B actually owes A              │                    │
  │                    - amount ≤ current debt          │                    │
  │                         │                           │                    │
  │                    ③ Idempotency check              │                    │
  │                         │                           │                    │
  │                         │ ④ BEGIN TRANSACTION       │                    │
  │                         │──────────────────────────→│                    │
  │                         │  INSERT settlement        │                    │
  │                         │  UPDATE balances           │                    │
  │                         │    SET amount = amount-300 │                    │
  │                         │    WHERE creditor=A        │                    │
  │                         │      AND debtor=B          │                    │
  │                         │  INSERT idempotency_key   │                    │
  │                         │ COMMIT                    │                    │
  │                         │◄──────────────────────────│                    │
  │                         │                           │                    │
  │                         │ ⑤ Publish DEBT_SETTLED    │                    │
  │                         │──────────────────────────────────────────────→│
  │                         │                           │                    │
  │ ⑥ 201 Created           │                           │                    │
  │ {settlement_id,         │                           │                    │
  │  remaining_debt: 0}     │                           │                    │
  │◄─────────────────────────                           │                    │
```

---

## 10. Data Model

### Tables

```
┌──────────────────────────────────────────────────────────────┐
│  users                                                        │
│  ┌──────────┬──────────┬───────────────────┐                  │
│  │ user_id  │ name     │ email             │                  │
│  │ (PK)     │          │                   │                  │
│  ├──────────┼──────────┼───────────────────┤                  │
│  │ u_001    │ Alice    │ alice@example.com │                  │
│  │ u_002    │ Bob      │ bob@example.com   │                  │
│  └──────────┴──────────┴───────────────────┘                  │
│                                                                │
│  groups                                                        │
│  ┌──────────┬────────────────┬────────────┐                   │
│  │ group_id │ name           │ created_at │                   │
│  │ (PK)     │                │            │                   │
│  ├──────────┼────────────────┼────────────┤                   │
│  │ g_100    │ Trip to Goa    │ 2026-01-01 │                   │
│  └──────────┴────────────────┴────────────┘                   │
│                                                                │
│  group_members (join table — many-to-many: user ↔ group)       │
│  ┌──────────┬──────────┐                                      │
│  │ group_id │ user_id  │   (composite PK)                     │
│  ├──────────┼──────────┤                                      │
│  │ g_100    │ u_001    │                                      │
│  │ g_100    │ u_002    │                                      │
│  │ g_100    │ u_003    │                                      │
│  └──────────┴──────────┘                                      │
│  Separate from groups because one user can be in many groups   │
│  and one group has many users. Avoids duplicating group        │
│  metadata on every member row.                                 │
│                                                                │
│  expenses (one row per expense — the "what happened")          │
│  ┌────────────┬──────────┬──────────┬────────┬────────────┐   │
│  │ expense_id │ group_id │ payer_id │ amount │ split_type │   │
│  │ (PK)       │ (FK)     │ (FK)     │        │ EQUAL/     │   │
│  │            │          │          │        │ EXACT/PCT  │   │
│  ├────────────┼──────────┼──────────┼────────┼────────────┤   │
│  │ e_001      │ g_100    │ u_001    │ 900.00 │ EQUAL      │   │
│  └────────────┴──────────┴──────────┴────────┴────────────┘   │
│  group_id is a FK here because groups → expenses is one-to-   │
│  many: one group has many expenses, but each expense belongs   │
│  to exactly one group. No join table needed — just a FK on     │
│  the "many" side.                                              │
│                                                                │
│  expense_splits (many-to-many join table: expenses ↔ users)    │
│  ┌────────────┬──────────┬────────┐                           │
│  │ expense_id │ user_id  │ amount │  (composite PK)           │
│  ├────────────┼──────────┼────────┤                           │
│  │ e_001      │ u_001    │ 300.00 │  (payer's own share)      │
│  │ e_001      │ u_002    │ 300.00 │                           │
│  │ e_001      │ u_003    │ 300.00 │
│  Join table for expenses ↔ users (many-to-many with payload). │
│  Separate from expenses because merging would duplicate        │
│  expense metadata (payer, amount, description) on every row.   │
│  This is normalization — not denormalization.                   │
│  └────────────┴──────────┴────────┘                           │
│                                                                │
│  balances (eagerly updated)                                    │
│  ┌──────────┬─────────────┬───────────┬────────┐              │
│  │ group_id │ creditor_id │ debtor_id │ amount │              │
│  │          │             │           │        │              │
│  │  (composite PK: group_id + creditor_id + debtor_id)       │
│  ├──────────┼─────────────┼───────────┼────────┤              │
│  │ g_100    │ u_001       │ u_002     │ 300.00 │              │
│  │ g_100    │ u_001       │ u_003     │ 300.00 │              │
│  └──────────┴─────────────┴───────────┴────────┘              │
│                                                                │
│  settlements                                                   │
│  ┌───────────────┬──────────┬──────────┬──────────┬────────┐  │
│  │ settlement_id │ group_id │ from_id  │ to_id    │ amount │  │
│  │ (PK)          │ (FK)     │          │          │        │  │
│  ├───────────────┼──────────┼──────────┼──────────┼────────┤  │
│  │ s_001         │ g_100    │ u_002    │ u_001    │ 300.00 │  │
│  └───────────────┴──────────┴──────────┴──────────┴────────┘  │
│                                                                │
│  idempotency_keys                                              │
│  ┌──────────────────┬────────────┬────────────┐               │
│  │ idempotency_key  │ entity_id  │ created_at │               │
│  │ (PK)             │ (expense   │            │               │
│  │                  │  or settle)│            │               │
│  └──────────────────┴────────────┴────────────┘               │
└──────────────────────────────────────────────────────────────┘
```

### Why Do We Need Each Table?

| Table | What it answers | Can we skip it? | Why not? |
|-------|----------------|-----------------|----------|
| `expenses` | "What was that $900 charge for?" "Show me all expenses in this group." | No | Without it, there's no history, no undo, no activity feed. Balances only show the current total — not which expenses contributed to it. |
| `expense_splits` | "How much was my share of the hotel?" "Alice paid $900 but my share was only $200." | No | One expense has N participants with potentially different shares (EXACT/PERCENTAGE). This is a one-to-many relationship — can't fit in the expenses table without duplicating expense metadata or using awkward JSON columns. |
| `balances` | "How much does Bob owe Alice right now?" | Technically yes — recompute from expenses + settlements on every read. | But that makes reads O(E) where E = total expenses in the group. For a group with 500 expenses, every balance view scans all 500. We keep balances eagerly updated for O(1) reads. |
| `settlements` | "When did Bob pay Alice back?" | No | Audit trail for real-world payments. Also needed to correctly compute balances (settlements reduce debt). |
| `idempotency_keys` | "Has this request been processed before?" | No | Prevents duplicate expenses on retry. Must be in the same DB transaction as the expense (not in Redis) to guarantee atomicity. |

**In short**: `expenses` + `expense_splits` = **what happened** (history, audit). `balances` = **current state** (derived, kept for fast reads). `settlements` = **what was paid back**. All three serve different purposes.

### Table Relationships and When to Split

| Relationship | Type | How to model | Example here |
|-------------|------|-------------|-------------|
| A group has many expenses; an expense belongs to one group | **One-to-many** | FK on the "many" side (`expenses.group_id`) | No join table needed |
| A group has many users; a user has many groups | **Many-to-many** | Join table with two FKs | `group_members(group_id, user_id)` |
| An expense has many participants; a user has many expenses | **Many-to-many with extra data** | Join table with two FKs + extra columns | `expense_splits(expense_id, user_id, amount)` |

**Thumb rule for when to create a separate table:**
- **One-to-many**: Put a FK column on the "many" side. No extra table needed.
- **Many-to-many**: You MUST create a join table — there's no way to represent this with just a FK column (where would you put it?).
- **If you're duplicating data on every row** (e.g., repeating group name for each member), that's a sign you need to normalize (split into two tables).

Note: `expense_splits` and `group_members` are both join tables for many-to-many relationships. The difference is that `expense_splits` carries extra data (`amount` — each user's share), while `group_members` is just two FKs.

**ORM mapping (Hibernate/JPA):** These table structures are directly reflected in JPA annotations. `@ManyToMany @JoinTable` auto-creates a join table for simple links (group_members). When the join table has extra columns (expense_splits.amount), you model it as an explicit entity with `@OneToMany` on both sides instead.

> **Want to see how these join tables are used in actual SQL?** See [Appendix A: SQL Queries — Join Tables in Practice](#appendix-a-sql-queries--join-tables-in-practice) at the end of this document.

### Why Composite PK on Balances?

The `balances` table uses `(group_id, creditor_id, debtor_id)` as a composite primary key. This ensures:
- One row per directional pair per group
- Efficient lookup: "how much does B owe A in group G?"
- Efficient scan: "all balances for group G" (prefix scan on group_id)

### Why Store Pairwise Instead of Net Only?

Storing only net balances (user_id → net_amount) would be simpler but loses information. If A's net is +$60, we don't know if that's from B, C, or both. Pairwise lets us show "B owes you $30, C owes you $30" and lets users settle specific debts.

---

## 11. Concurrency: Same Group, Multiple Expenses

Two users add expenses to the same group at the same time. Both need to update the same balance rows.

### Our Approach: DB Transaction with Row-Level Locking

```sql
BEGIN TRANSACTION;
  -- Lock the balance rows for this group (pessimistic)
  SELECT * FROM balances WHERE group_id = ? FOR UPDATE;

  INSERT INTO expenses (...) VALUES (...);
  INSERT INTO expense_splits (...) VALUES (...);

  UPDATE balances SET amount = amount + ?
  WHERE group_id = ? AND creditor_id = ? AND debtor_id = ?;

COMMIT;
```

Two concurrent transactions on the same group: one acquires the lock first, the other waits. Both succeed sequentially. No inconsistency.

### Why Not Optimistic Locking Here?

Optimistic locking (version column, retry on conflict) works but adds retry logic complexity. For Splitwise, write contention on a single group is low (how often do two people add an expense to the same group at the exact same millisecond?). Pessimistic locking is simpler and sufficient.

### Why Not Append-Only (Event Sourcing)?

Append-only expenses with recomputed balances on read avoids write conflicts entirely, but makes reads expensive for groups with many expenses (scan all expenses). Our eagerly-stored balances make reads fast (single query), which matters because reads are 3× more frequent than writes. For a system where Event Sourcing IS the right choice (auditability, reproducibility, high TPS), see the [Digital Wallet System Design](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) — it stores every event and derives state by replay.

| Approach | Write Complexity | Read Complexity | Concurrency Handling |
|----------|-----------------|-----------------|---------------------|
| **Eager + pessimistic lock** (our choice) | Transaction: INSERT + UPDATE (N rows) | SELECT balances (fast) | DB row lock; serialized per group |
| Optimistic locking | INSERT + UPDATE with version check; retry on conflict | SELECT balances (fast) | Retry loop; higher throughput under low contention |
| Append-only (event sourcing) | INSERT expense only (simple) | Recompute from all expenses (slow for large groups) | No conflict (append-only); but read amplification |

---

## 12. Consistency and Reliability

### Consistency Model

| Data | Consistency | Why |
|------|-------------|-----|
| Balances | **Strong** (same transaction as expense) | Incorrect balance = users pay wrong amounts. Unacceptable. |
| Expense records | **Strong** (DB write) | Source of truth for who paid what. |
| Simplified view | **Computed on read** (not stored) | Derived from balances; always consistent because balances are. |
| Notifications | **Eventual** (Kafka consumer lag) | Side effect; expense is recorded regardless of notification delivery. |
| Cache (Redis) | **Eventual** (invalidated on write) | Brief stale window between DB write and cache invalidation. Acceptable. |

**Cache invalidation**: Done by application code (`redis.delete("balances:group:{id}")`) right after DB transaction commits. No CDC needed here — all balance writes go through the app, and a short TTL (30s) acts as a safety net if the delete fails.

**Redis cache data model:**

```
Key:   balances:group:{group_id}
Value: JSON map of pairwise balances
TTL:   30 seconds

Example:
  Key:   balances:group:g_100
  Value: [
    {"creditor": "u_001", "debtor": "u_002", "amount": 300.00},
    {"creditor": "u_001", "debtor": "u_003", "amount": 300.00}
  ]
```

**Kafka topics:**

| Topic | Key | Value | Producer | Consumer |
|-------|-----|-------|----------|----------|
| `expense-events` | `group_id` | `{event_type, expense_id, group_id, payer, amount, splits, timestamp}` | Outbox publisher | Notification Service |
| `settlement-events` | `group_id` | `{event_type, settlement_id, group_id, from, to, amount, timestamp}` | Outbox publisher | Notification Service |

Partitioned by `group_id` so events for one group are ordered (expense before settlement).

### Transactional Outbox for Event Publishing

Same pattern as Uber and BookMyShow: expense write and Kafka event must not diverge.

```
Expense Service records expense:
   │
   ▼
BEGIN TRANSACTION;
  INSERT INTO expenses (...);
  INSERT INTO expense_splits (...);
  UPDATE balances SET amount = amount + ? WHERE ...;
  INSERT INTO idempotency_keys (...);
  INSERT INTO outbox (event_type, payload, created_at)
    VALUES ('EXPENSE_ADDED', '{"expense_id":"e_001",...}', NOW());
COMMIT;
   │
   ▼ (all rows in same DB → atomic)

Outbox Publisher (CDC / polling)
   ├── Reads outbox table
   ├── Publishes to Kafka "expense-events"
   └── Marks row as published
```

### What If Kafka Is Down?

Events accumulate in outbox table. Publisher retries. When Kafka recovers, backlog drains. Notifications are delayed but not lost. Expense and balance data in DB is always correct regardless.

### What If DB Is Down?

Expense creation fails (return 503). Client retries with same idempotency key. When DB recovers, retry succeeds. Balances are never partially updated because everything is in a single transaction.

---

## 13. Failure Scenarios and Handling

| Scenario | What Breaks | Handling | Data Impact |
|----------|-------------|----------|-------------|
| **Two users add expense to same group** | Concurrent writes to same balance rows | Pessimistic lock (FOR UPDATE): first transaction commits, second waits then commits. Both succeed. | Balances correct. No conflict. |
| **Expense retry (network timeout)** | Client doesn't know if first request succeeded | Idempotency key: same key → return existing expense. No duplicate. | No duplicate expense or balance change. |
| **Invalid split amounts** | Amounts don't sum to expense total | Validate on write: reject with 400. Sum of splits must equal total. | No write occurs. |
| **Settlement exceeds debt** | User tries to settle $500 but only owes $300 | Validate: amount ≤ current balance. Return 400. | No write occurs. |
| **DB write fails mid-transaction** | Partial writes | Single transaction: all or nothing. Rollback. Return 500. Client retries. | No partial state. |
| **Kafka down** | Events not published | Transactional outbox: events safe in DB. Published when Kafka recovers. | Notifications delayed. |
| **Notification Service down** | Push/email not sent | Events queue in Kafka. Service recovers → consumes backlog. | UX degraded (no alert), not broken. |
| **Cache stale after expense** | User sees old balances | Cache invalidated (DEL) on every expense/settlement write. Brief race window (~ms). | Transient; next read is correct. |
| **User removed from group** | User still has outstanding balance | Settle all debts before removal (or auto-create settlements). Reject removal if unsettled balances remain. | Enforced by validation. |
| **Large group (100+ members)** | Simplification and balance updates are slower | Simplification is O(n log n) — sub-ms even for 100 users. Balance updates: N-1 rows per expense. For N=100 → 99 updates per transaction. May want batch updates. | Performance acceptable for realistic groups. |

---

## 14. Scale and Sharding

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         SCALE STRATEGY BY COMPONENT                          │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐   ┌─────────────────┐           │
│  │ Balance DB       │   │ Redis Cache     │   │ Expense/Balance │           │
│  │ (MySQL/PG)       │   │                  │   │ Service          │           │
│  │                  │   │ Cache group      │   │ STATELESS        │           │
│  │ Shard by         │   │ balances         │   │ (just add more   │           │
│  │ GROUP_ID         │   │ (short TTL,      │   │  instances       │           │
│  │ (all data for    │   │  invalidate on   │   │  behind LB)      │           │
│  │ one group on     │   │  write)          │   │                  │           │
│  │ same shard)      │   │                  │   │ Each instance    │           │
│  │                  │   │                  │   │ reads/writes     │           │
│  │ 460 writes/sec   │   │ 7K reads/sec     │   │ same DB          │           │
│  │ → single DB is   │   │ → Redis handles  │   │                  │           │
│  │ fine initially    │   │ easily           │   │                  │           │
│  └─────────────────┘   └─────────────────┘   └─────────────────┘           │
│                                                                              │
│  ┌─────────────────┐   ┌─────────────────┐                                  │
│  │ Kafka            │   │ Notification     │                                  │
│  │                  │   │ Service          │                                  │
│  │ Partition by     │   │ Scale consumers  │                                  │
│  │ group_id         │   │ independently    │                                  │
│  │                  │   │ (Kafka consumer  │                                  │
│  │                  │   │  groups)         │                                  │
│  └─────────────────┘   └─────────────────┘                                  │
└─────────────────────────────────────────────────────────────────────────────┘
```

| Component | Shard/Scale Strategy | Why |
|-----------|---------------------|-----|
| **Balance DB** | Shard by **group_id**. All expenses, balances, settlements for one group on same shard. | Expense transaction updates multiple balance rows for the same group → must be on same shard for atomicity. |
| **Redis Cache** | Key by group_id → balances. Short TTL (30 sec). Invalidate on write. | Most balance reads served from cache. |
| **Services** | Stateless → horizontal scale behind LB. | No in-memory state. Each instance reads/writes the same DB shard. |
| **Kafka** | Partition by group_id. | Events for one group are ordered (expense before settlement). |
| **Notification** | Scale via Kafka consumer group. | Independent of expense throughput. |

### Why Shard by group_id?

All balance operations are group-scoped. An expense in group G updates balances in group G. Settle up in group G updates balances in group G. If we sharded by user_id, a single expense would need to update rows across multiple shards (one per participant) — requiring distributed transactions. Sharding by group_id keeps everything local.

**Downside**: A user in multiple groups has data on multiple shards. "Show me all my balances across all groups" requires scatter-gather (query each shard). Mitigation: maintain a separate "user_groups" index to know which shards to query. This cross-group view is rare compared to within-group balance views.

---

## 15. Final Architecture (Putting It All Together)

```
┌──────────────────────────────────────────────────────────────────────────────────────────┐
│                                     CLIENTS                                                │
│  ┌──────────────┐                                          ┌──────────────┐                │
│  │  Mobile App  │ ① POST /groups/{id}/expenses             │  Web App     │                │
│  │              │ ② GET /groups/{id}/balances               │              │                │
│  │              │ ③ POST /groups/{id}/settlements           │              │                │
│  │              │ ④ GET /groups/{id}/simplified             │              │                │
│  └──────┬───────┘                                          └──────┬───────┘                │
└─────────┼────────────────────────────────────────────────────────┼────────────────────────┘
          │                                                        │
          ▼                                                        ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                API GATEWAY / LB                                              │
│                     Auth, rate limit, TLS, route by path                                   │
└─────────┬──────────────────────┬───────────────────────────┬──────────────────────────────┘
          │                      │                           │
   ┌──────▼───────┐    ┌────────▼────────┐        ┌─────────▼──────────┐
   │ Expense       │    │ Balance Service │        │ Settlement         │
   │ Service       │    │                 │        │ Service            │
   │               │    │ ② read from     │        │                    │
   │ ① validate    │    │   cache/DB      │        │ ③ validate         │
   │ ① idem check  │    │ ④ simplify      │        │ ③ idem check       │
   │ ① INSERT +    │    │   (greedy algo) │        │ ③ UPDATE balance   │
   │   UPDATE      │    │                 │        │ ③ INSERT settle    │
   │   (txn)       │    │                 │        │   (txn)            │
   │ ① invalidate  │    │                 │        │ ③ invalidate cache │
   │   cache       │    │                 │        │ ③ publish event    │
   │ ① publish     │    │                 │        │                    │
   │   event       │    │                 │        │                    │
   └───┬──────┬────┘    └────────┬────────┘        └───┬──────────┬────┘
       │      │                  │                     │          │
       │   ┌──┴──────────────────┴─────────────────────┘          │
       │   │                                                      │
       │   ▼                                                      │
       │ ┌──────────────────────────────┐                         │
       │ │      BALANCE DB (MySQL/PG)   │                         │
       │ │      Shard by group_id       │                         │
       │ │                              │                         │
       │ │ expenses    expense_splits   │                         │
       │ │ balances    settlements      │                         │
       │ │ groups      group_members    │                         │
       │ │ idempotency_keys  outbox     │                         │
       │ └──────────────────────────────┘                         │
       │                                                          │
       │ invalidate                                   ⑤ publish   │
       ▼                                                          ▼
  ┌──────────────┐                                         ┌──────────────┐
  │ Redis Cache  │                                         │    KAFKA      │
  │              │                                         │              │
  │ group:{id}   │                                         │ expense-     │
  │ → balances   │                                         │ events       │
  └──────────────┘                                         └──────┬───────┘
                                                                  │
                                                             ┌────┴─────────┐
                                                             │ Notification │
                                                             │ Service      │
                                                             │              │
                                                             │ Push/Email:  │
                                                             │ "Alice added │
                                                             │  $900 for    │
                                                             │  Hotel room" │
                                                             └──────────────┘
```

### Step-by-Step Flow (Numbered)

| Step | Action | Service | Protocol | Data Store |
|------|--------|---------|----------|------------|
| ① | User adds expense (payer, amount, split) | Client → Expense Service | HTTP POST | |
| ② | Validate: members, amounts, idempotency | Expense Service | Internal + DB read | Balance DB |
| ③ | Atomic transaction: INSERT expense + splits + UPDATE balances + idempotency | Expense Service | DB write | Balance DB |
| ④ | Invalidate cached balances for this group | Expense Service | Redis DEL | Redis |
| ⑤ | Publish EXPENSE_ADDED event (via outbox) | Outbox → Kafka | Async | Kafka |
| ⑥ | Notification Service sends push/email | Kafka → Notification Service | Async | |
| ⑦ | User views balances | Client → Balance Service | HTTP GET | |
| ⑧ | Check Redis cache; on miss → query DB | Balance Service | Redis GET / DB read | Redis / DB |
| ⑨ | User views simplified debts | Client → Balance Service | HTTP GET | |
| ⑩ | Read balances → compute net → run greedy → return | Balance Service (Simplification Engine) | CPU | |
| ⑪ | User settles a debt | Client → Settlement Service | HTTP POST | |
| ⑫ | Atomic transaction: INSERT settlement + UPDATE balance | Settlement Service | DB write | Balance DB |
| ⑬ | Invalidate cache + publish DEBT_SETTLED event | Settlement Service | Redis DEL + Kafka | Redis + Kafka |

---

## 16. Trade-off Summary

| Decision | Our Choice | Alternative | Why Our Choice |
|----------|-----------|-------------|----------------|
| **Balance storage** | Eager (stored, updated on write) | Lazy (recompute on read from expenses) | Reads are 3× more frequent. Eager = fast reads. Lazy = slow reads for groups with many expenses. |
| **Concurrency** | Pessimistic locking (FOR UPDATE) | Optimistic locking / append-only | Low contention per group (rare concurrent writes). Pessimistic is simpler; no retry logic. |
| **Simplification timing** | Lazy (compute on GET /simplified) | Eager (recompute and store on every expense) | Group size is small (4-10); computation is sub-ms. No need to store derived data. |
| **Simplification algorithm** | Greedy (largest creditor ↔ largest debtor) | Min-flow / NP-hard optimal | Greedy gives at most N-1 transactions; optimal in practice for small groups. Simpler to implement and explain. |
| **Balance granularity** | Pairwise (A owes B $X) | Net only (A's net = +$60) | Pairwise lets users settle specific debts and see who owes whom. Net only loses this info. |
| **Sharding** | By group_id | By user_id | All balance updates for an expense are within one group → one shard → one transaction. User_id would require distributed transactions. |
| **Event publishing** | Transactional outbox + CDC | Direct publish after commit | No lost events. If Kafka is down, events in outbox. |
| **Cache** | Redis with short TTL, invalidate on write | No cache / longer TTL | Balances are read 3× more than written. Cache reduces DB load. Short TTL + invalidation ensures freshness. |
| **Notification** | Async via Kafka | Sync HTTP from Expense Service | Notifications shouldn't block expense creation. If Notification is down, expense still succeeds. |
| **Idempotency** | DB table (same transaction as expense) | Redis | Must be atomic with expense write. Same DB transaction guarantees both succeed or both fail. |
| **Expense storage** | Append-only (never update/delete) | Mutable (allow edits) | Simpler audit trail. "Edit" = add a reversal expense + new expense. Preserves history. |

---

## 17. Common Mistakes to Avoid

| Mistake | Why It's Wrong | Better Approach |
|---------|---------------|-----------------|
| Computing balances from expenses on every read | Scans all expenses in a group; O(E) per read. For groups with thousands of expenses, unacceptably slow. | Store balances eagerly; update on write. Read is O(P) where P = number of pairs (typically < 20). |
| Storing net balance only (not pairwise) | "Alice's net is +$60" — but who owes her? Can't show "Bob owes you $30, Charlie owes you $30." | Store pairwise: (creditor, debtor, amount). Derive net from pairwise. |
| No idempotency on expense creation | Network timeout → user retries → duplicate expense → wrong balances | Idempotency key in request; stored in DB atomically with expense. |
| Updating balances outside the expense transaction | Expense INSERT succeeds, but balance UPDATE fails → inconsistent state | Single DB transaction: expense + splits + balance updates. All or nothing. |
| Sharding by user_id | One expense with 5 participants → 4 balance updates across 4 shards → distributed transaction needed | Shard by group_id: all balance rows for a group on same shard. |
| Not validating split amounts | Splits don't sum to expense total → phantom money created/destroyed; group net ≠ 0 | Validate on write: sum(splits) must equal expense amount. Reject otherwise. |
| Allowing settlement > debt | User settles $500 but only owes $300 → negative balance (user is "owed" by settling too much) | Validate: settlement amount ≤ current pairwise debt. |
| Running simplification on write path | Recompute simplified view on every expense → wasted CPU; view only needed on demand | Compute lazily on GET /simplified. Sub-ms for typical group sizes. |
| No cache invalidation after expense | User adds expense → immediately views balances → sees stale cached balance | DEL cache key on every expense/settlement write. |
| Direct Kafka publish (no outbox) | DB write succeeds, Kafka publish fails → expense recorded but notification never sent | Transactional outbox: event in same DB transaction as expense. |

---

## 18. Interview Talking Points

### "Walk me through the architecture"

> We have three core services: Expense Service (create expenses, update pairwise balances in same DB transaction), Balance Service (read balances from cache/DB, run simplification algorithm on demand), and Settlement Service (record settlements, decrease balances). All share a Balance DB sharded by group_id so all data for one group lives on one shard — enabling single-transaction writes. Kafka handles async side effects (notifications when someone adds an expense). Redis caches group balances with short TTL, invalidated on every write.

### "How do you track who owes whom?"

> We store pairwise balances: a row per `(group_id, creditor_id, debtor_id, amount)`. When A pays $90 for A, B, C (equal split), we update two rows: B owes A +$30, C owes A +$30. This gives us the granularity to show "Bob owes you $30" and to settle specific debts. Net balance per user is derived by summing their pairwise rows.

### "How does debt simplification work?"

> Step 1: compute net balance per user (sum of what I'm owed minus what I owe). Step 2: greedy matching — take the largest creditor and largest debtor, transfer min(creditor_net, |debtor_net|), update nets, repeat until all zero. This produces at most N-1 transactions. For a group of 5 people, that's at most 4 transactions. Runs in O(n log n), sub-millisecond.

### "When do you compute simplification?"

> Lazily on GET /simplified. Not stored. Group sizes are typically 4-10 people — the greedy algorithm takes microseconds. No reason to precompute and store. If we wanted to optimize for very large groups (100+ users), we could cache the simplified view and invalidate on expense/settlement.

### "How do you handle concurrent expenses?"

> Pessimistic locking. The expense transaction does SELECT ... FOR UPDATE on the balance rows for the group, then updates them. Two concurrent expenses: one acquires the lock first, the other waits. Both succeed. No retry logic needed. Contention per group is very low — two people rarely add an expense at the exact same moment.

### "Why shard by group_id and not user_id?"

> An expense with 5 participants updates 4 balance rows — all within the same group. Sharding by group_id keeps all these rows on one shard, so we can do a single local transaction. If we sharded by user_id, those 4 rows would be on 4 different shards, requiring a distributed transaction — complex and slow. The tradeoff: "show all my balances across all groups" requires scatter-gather. But that's rare compared to within-group operations.

### "What if the same expense is submitted twice?"

> Client-generated idempotency key in the request header. On first request, we store the key in the DB in the same transaction as the expense. On retry, we find the key and return the existing expense. No duplicate balance change. Same pattern as Uber and BookMyShow.

### "How do you ensure balances are always correct?"

> Single DB transaction: the expense INSERT, expense_splits INSERT, and balance UPDATEs are all in one ACID transaction. If any part fails, everything rolls back. The sum of net balances in a group is always zero — we validate split amounts sum to the expense total on write. Settlements are validated to not exceed the current debt. These invariants are enforced at the write path, not the read path.

### "How is this different from a payment system?"

> Splitwise tracks **IOUs** (who owes whom), not actual money movement. There's no payment gateway, no double-entry ledger, no reconciliation with a bank. The "settle up" just records that a real-world payment happened — it's a trust-based system. The core challenge is the simplification algorithm and maintaining consistent pairwise balances across concurrent writes, not handling financial transactions.

---

## Appendix A: SQL Queries — Join Tables in Practice

This section demonstrates how the join tables (`group_members`, `expense_splits`) are used in actual SQL queries. Useful for building intuition around many-to-many relationships and normalization.

### What is a join table?

A **join table** (also called association/bridge table) exists solely to link two other tables. On its own, a row like `(group_id=5, user_id=12)` tells you nothing meaningful — you MUST join it with the parent tables to get useful data. That's why it's called a "join table" — its only purpose is to be joined.

**Quick test**: If a table has only FK columns (and a composite PK of those FKs), it's a join table. If it has its own independent data columns, it's an entity table.

### `group_members` — Pure join table (just two FKs)

```sql
-- Who are the members of the "Trip to Goa" group?
SELECT u.name, u.email
FROM group_members gm
JOIN users u ON u.id = gm.user_id
WHERE gm.group_id = 'g_1';
-- Alice, Bob, Charlie

-- What groups does Alice belong to?
SELECT g.name, g.created_at
FROM group_members gm
JOIN groups g ON g.id = gm.group_id
WHERE gm.user_id = 'alice';
-- "Trip to Goa", "Flat Roommates", "Office Lunch"

-- Full join — both sides: show every group with all its members
SELECT g.name AS group_name, u.name AS member_name
FROM group_members gm
JOIN groups g ON g.id = gm.group_id
JOIN users u ON u.id = gm.user_id
ORDER BY g.name;
-- Trip to Goa | Alice
-- Trip to Goa | Bob
-- Trip to Goa | Charlie
-- Flat Roommates | Alice
-- Flat Roommates | Dave
```

### `expense_splits` — Join table with extra data (`amount`)

```sql
-- Who owes what for a specific expense?
SELECT u.name, es.amount
FROM expense_splits es
JOIN users u ON u.id = es.user_id
WHERE es.expense_id = 'exp_1';
-- Alice: 100, Bob: 100, Charlie: 100

-- All expenses Alice is part of (even ones she didn't pay for)
SELECT e.description, e.amount AS total, es.amount AS alice_share, e.paid_by
FROM expense_splits es
JOIN expenses e ON e.id = es.expense_id
WHERE es.user_id = 'alice';
-- "Dinner"  | 300 | 100 | bob
-- "Cab"     | 150 |  50 | alice
-- "Hotel"   | 900 | 300 | charlie

-- Total amount Alice owes vs is owed across all groups
SELECT
    SUM(CASE WHEN e.paid_by != 'alice' THEN es.amount ELSE 0 END) AS alice_owes,
    SUM(CASE WHEN e.paid_by = 'alice' THEN e.amount - es.amount ELSE 0 END) AS others_owe_alice
FROM expense_splits es
JOIN expenses e ON e.id = es.expense_id
WHERE es.user_id = 'alice';
```

### Combining both join tables

```sql
-- Show all expenses in groups Alice belongs to (chains two join tables)
SELECT g.name AS group_name, e.description, e.amount, e.paid_by
FROM group_members gm
JOIN groups g ON g.id = gm.group_id
JOIN expenses e ON e.group_id = g.id
WHERE gm.user_id = 'alice'
ORDER BY e.created_at DESC;
-- Trip to Goa     | Dinner      | 300 | bob
-- Trip to Goa     | Cab         | 150 | alice
-- Flat Roommates  | Electricity | 200 | dave
```

### Without the join table — why it breaks

```sql
-- If we tried to store membership directly in the groups table:
-- groups(id, name, member_id) — duplicates the group for each member!
--   id  | name         | member_id
--   g_1 | Trip to Goa  | alice      ← "Trip to Goa" repeated 3 times
--   g_1 | Trip to Goa  | bob        ← redundant, wastes space
--   g_1 | Trip to Goa  | charlie    ← update name? must update all 3 rows

-- With a join table, the group name lives in ONE place:
-- groups: (g_1, "Trip to Goa")                                ← single source of truth
-- group_members: (g_1, alice), (g_1, bob), (g_1, charlie)     ← just links
```

**The pattern**: Whenever you write `JOIN some_table ON ...` and that table only has FK columns, it's a join table doing its job — connecting two entities in a many-to-many relationship.
