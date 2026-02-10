# 📧 Distributed Email Service - Interview Cheatsheet

> Based on Alex Xu's System Design Interview Volume 2 - Chapter 8

## Quick Reference Card

| Component | Purpose | Tech | Key Points |
|-----------|---------|------|------------|
| **Web Servers** | Handle HTTP requests (send, read, search) | Stateless, horizontally scaled | HTTPS from webmail clients |
| **Real-time Servers** | Push new email notifications | WebSocket | Persistent connections, notify when online |
| **SMTP Outgoing** | Send emails to external servers | SMTP protocol | Spam/virus check, retry logic, deliverability |
| **SMTP Incoming** | Accept emails from external servers | SMTP protocol | Acceptance policy, rate limiting |
| **Outgoing Queue** | Buffer outgoing emails | Message queue (Kafka) | Decouples web servers from SMTP sending |
| **Incoming Queue** | Buffer incoming emails for processing | Message queue (Kafka) | Decouples SMTP servers from mail processing |
| **Mail Processing** | Process incoming emails | Workers | Spam filter, virus scan, store, index |
| **Metadata DB** | Email headers, flags, folders | Distributed DB (Bigtable/Cassandra) | Partitioned by user_id |
| **Attachment Store** | Email attachment files | Object Storage (S3) | Binary blobs, referenced by attachment_id |
| **Search Store** | Full-text email search | Elasticsearch | Inverted index, partitioned by user_id |
| **Distributed Cache** | Recent/popular emails | Redis | Latest emails, reduce DB load |

---

## The Story: Building a Gmail-Scale Email Service

Let me walk you through designing an email service for 1 billion users — the kind of scale Gmail or Outlook operates at.

---

## 1. What Are We Building? (Requirements)

### Functional Requirements

- Send and receive emails (with attachments)
- Fetch all emails (inbox, sent, folders)
- Filter by read/unread status
- Search emails by subject, sender, and body
- Anti-spam and anti-virus protection
- Folder management (inbox, sent, drafts, trash, custom)
- Communication via HTTP (modern webmail, not legacy IMAP/POP)

### Non-Functional Requirements

- **Reliability:** Must NOT lose email data
- **Availability:** Auto-replicate data across nodes, function despite partial failures
- **Scalability:** Handle growing users and emails without performance degradation
- **Flexibility:** Easy to add new features (unlike rigid IMAP/POP protocols)

### Back-of-the-Envelope Estimation

```
┌──────────────────────────────────────────────────────────────┐
│  Users:                     1 billion                        │
│  Emails sent/day/user:      10                               │
│  Emails received/day/user:  40 (from book's assumption)      │
│                                                              │
│  QPS:                                                        │
│    Send QPS:    1B × 10 / 86,400 = ~100,000 QPS             │
│    Receive QPS: 1B × 40 / 86,400 = ~400,000 QPS             │
│                                                              │
│  Storage (we calculate on RECEIVED emails because each       │
│  received email is stored in the recipient's mailbox;        │
│  sent emails are already counted as someone else's received  │
│  email — so 40 received/day covers the total email volume):  │
│                                                              │
│    Metadata per email:      50 KB (headers, subject, flags)  │
│    Metadata storage/year:   1B users                         │
│                             × 40 emails received/day         │
│                             × 365 days                       │
│                             × 50 KB                          │
│                             = 730 PB                         │
│                                                              │
│    20% emails have attachments, avg 500 KB each              │
│    Attachment storage/year: 1B × 40 × 365 × 20% × 500KB    │
│                           = 1,460 PB                         │
│                                                              │
│  Total storage/year:        ~2,190 PB ≈ 2.2 Exabytes        │
│                                                              │
│  → This is a STORAGE-HEAVY system!                           │
│  → Key challenges: massive storage, search at scale,         │
│    email deliverability, spam protection                     │
└──────────────────────────────────────────────────────────────┘
```

---

## 2. API Design

### Send Email

```
POST /emails/send
  Body: {
    from: "alice@example.com",
    to: ["bob@example.com"],
    cc: ["charlie@example.com"],
    bcc: [],
    subject: "Project Update",
    body: "Hi Bob, ...",
    attachments: [{ file_name, content_type, data (base64) }]
  }
  Returns: { email_id: "EMAIL-1234", status: "sent" }
  → email_id = unique message identifier (NOT the sender's email address)
```

### Fetch Emails

```
GET /folders/{folder_id}/emails?limit=50&offset=0
  Returns: { emails: [...], total_count, has_more }
  → Listing is folder-scoped (mirrors DB: partition by user, cluster by folder)
  → Sorted by timestamp DESC (newest first)
  → Pagination with limit/offset

GET /folders/{folder_id}/emails?is_read=false
  Returns: Only unread emails in this folder

GET /emails/{email_id}
  Returns: Full email (metadata + body + attachment URLs)
  → Single email lookup is flat (email_id is globally unique)
```

> **Why mixed?** Listing is naturally folder-scoped (you always browse within
> a folder), so `GET /folders/{folder_id}/emails`. But fetching a single email
> uses flat `GET /emails/{email_id}` since email_id is globally unique — no
> need to know the folder. Simpler, and avoids requiring folder context for
> direct links (e.g., notification click → open email).

### Search

```
GET /emails/search?query=project&from=alice@example.com
  Returns: Emails matching the search criteria
  → Backed by Elasticsearch (not the Metadata DB)
  → Search is cross-folder, so it sits at /emails level (not under a folder)
```

### Folder Management

```
GET    /folders                     → List all folders
POST   /folders   { name: "Work" } → Create custom folder
PATCH  /emails/{email_id}          → Move to folder, mark read/unread
DELETE /emails/{email_id}          → Move to trash (soft delete)
```

---

## 3. Understanding Traditional Email (Why It Matters)

Before designing our system, we need to understand how email works traditionally — because our system still needs to **interoperate** with the rest of the internet.

### Traditional Email Protocols

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│  Alice (Outlook)                         Bob (Gmail)             │
│       │                                       ▲                  │
│  ① Send (SMTP)                           ④ Fetch (IMAP/POP)     │
│       │                                       │                  │
│       ▼                                       │                  │
│  ┌──────────────┐   ② SMTP    ┌──────────────┐                 │
│  │ Outlook SMTP │────────────▶│ Gmail SMTP   │                  │
│  │ Server       │             │ Server       │                  │
│  └──────┬───────┘             └──────┬───────┘                  │
│         │                            │                           │
│    ┌────┴────┐                  ┌────┴────┐                     │
│    │ Storage │                  │ Storage │                      │
│    └─────────┘                  └─────────┘                     │
│  outlook.com server            gmail.com server                  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

| Protocol | Purpose | Direction |
|----------|---------|-----------|
| **SMTP** | Send emails between servers | Client → Server, Server → Server |
| **IMAP** | Fetch & sync emails (keeps on server) | Client ← Server |
| **POP** | Download emails (removes from server) | Client ← Server |

> **Why not just use SMTP/IMAP/POP?**
> These are legacy protocols with limited functionality. They don't support:
> - Push notifications (IMAP uses polling)
> - Rich search (basic text search only)
> - Custom features (labels, snooze, smart folders)
>
> Modern email services use **HTTP + WebSocket** for client communication,
> but still use **SMTP for server-to-server** email delivery. Why?
>
> | | SMTP (server-to-server) | HTTP (client-to-server) |
> |---|---|---|
> | **Why used** | Internet standard since 1982 — every mail server speaks it | We control both client and server, can use any protocol |
> | **Interoperability** | Gmail, Outlook, Yahoo ALL speak SMTP — it's the universal language | HTTP would require every mail server in the world to agree on a new API |
> | **Built-in features** | MX record discovery, retry on failure, bounce handling | Would need to rebuild all of this from scratch |
> | **Can we change it?** | No — we must interoperate with billions of existing mail servers | Yes — we control our own clients |
>
> **In short:** We use HTTP for our clients because we control both sides and
> want modern features. But for server-to-server, we MUST speak SMTP because
> that's what every other mail server on the internet understands. There's no
> technical limitation — SMTP is simply the standard agreed upon in 1982 (before
> HTTP even existed), and DNS MX records route email to SMTP endpoints. Changing
> it would require every mail server on the planet to agree on a new protocol.

---

## 4. The Big Picture (High-Level Architecture)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║              DISTRIBUTED EMAIL SERVICE - HIGH-LEVEL DESIGN                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌──────────────────────┐                                                    ║
║  │   Webmail Clients    │                                                    ║
║  │ (Browser / Mobile)   │                                                    ║
║  └────┬─────────────┬───┘                                                    ║
║       │             │                                                        ║
║    HTTPS        WebSocket                                                    ║
║       │             │                                                        ║
║       ▼             ▼                                                        ║
║  ┌──────────┐  ┌──────────────┐                                             ║
║  │   Web    │  │  Real-time   │                                             ║
║  │ Servers  │  │  Servers     │                                             ║
║  └────┬─────┘  └──────┬───────┘                                             ║
║       │               │                                                      ║
║       └───────┬───────┘                                                      ║
║               │                                                              ║
║               ▼                                                              ║
║  ┌──────────────────────────────────────────────────────────┐               ║
║  │                    Storage Layer                          │               ║
║  │                                                           │               ║
║  │  ┌───────────┐ ┌────────────┐ ┌───────────┐ ┌─────────┐   │               ║
║  │  │ Metadata  │ │ Attachment │ │Distributed│ │ Search  │   │               ║
║  │  │    DB     │ │   Store    │ │  Cache    │ │  Store  │   │               ║
║  │  │(Bigtable/ │ │   (S3)    │ │ (Redis)   │ │(Elastic-││               ║
║  │  │Cassandra) │ │           │ │           │ │ search) ││               ║
║  │  └───────────┘ └────────────┘ └───────────┘ └─────────┘│               ║
║  └──────────────────────────────────────────────────────────┘               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Two Types of Servers?

| | Web Servers | Real-time Servers |
|---|---|---|
| **Protocol** | HTTPS (request-response) | WebSocket (persistent connection) |
| **Purpose** | Send emails, fetch inbox, search | Push new email notifications |
| **Stateless?** | Yes — easy to scale | Stateful — each user has a connection |
| **When used** | User actively browsing email | User has email tab open (background) |

---

## 5. Deep Dive: Email Sending Flow

When a user clicks "Send," here's the journey of that email.

```
┌──────────┐     ┌─────────────┐     ┌─────────────────-┐
│ Webmail  │ ①   │    Web      │ ②  │  Basic           │
│ Client   │────▶│   Servers   │────▶│  Validation      │
└──────────┘HTTPS└─────────────┘     │  (size, format)  │
                                      └────────┬────────┘
                                               │
                         ┌─────────────────────┘
                         │ ③
                         ▼
                  ┌──────────────┐     ┌────────────────┐
                  │ Store copy   │     │  Outgoing      │ ④.a
                  │ in sender's  │     │  Queue         │◀──── Success
                  │ "Sent" folder│     │  (Kafka)       │
                  │ (metadata +  │     └───────┬────────┘
                  │ search index)│
                  └──────────────┘             │        ┌──────────┐
                         ③                     │   ④.b  │  Error   │
                                               │  ◀──── │  Queue   │
                                               ▼        └──────────┘
                                       ┌──────────────┐
                                       │ SMTP Outgoing│
                                       │              │
                                       │ • Check spam │
                                       │ • Check virus│ ⑤
                                       │ • Retry      │
                                       └──────┬───────┘
                                              │
                                              ▼ ⑥
                                        ┌──────────┐
                                        │ Internet │
                                        │(recipient│
                                        │ server)  │
                                        └──────────┘
```

### Step-by-Step

| Step | What Happens | Details |
|------|-------------|---------|
| ① | User sends email via HTTPS | POST /emails/send with body, recipients, attachments |
| ② | Web server validates | Check size limits, valid recipients, rate limiting |
| ③ | Store in sender's Sent folder | Save metadata to DB, index in search store |
| ④.a | Enqueue to outgoing queue | Decouples web server from slow SMTP delivery |
| ④.b | If validation fails → Error queue | For retry or alerting |
| ⑤ | SMTP outgoing processes | Spam check, virus check. Retry on temporary failures |
| ⑥ | Deliver to recipient's mail server | Via SMTP protocol (internet standard) |

> **Step ③ and ④ — Sequential or Parallel?**
>
> | Approach | How it works | Tradeoff |
> |----------|-------------|----------|
> | **Store first, then queue** | Save to Sent folder → enqueue to Kafka | Safer: even if Kafka is down, email appears in Sent. But slower (two sequential writes). |
> | **Parallel** | Fire both writes simultaneously | Faster response. But partial failure possible: email delivered but not in Sent (or vice versa). |
>
> **Preferred: Parallel with retry.** Both writes go out simultaneously for lower
> latency. If the DB write fails, a background retry job re-stores it. If the
> queue write fails, the web server retries. Since both Kafka and the Metadata DB
> have their own durability guarantees, partial failures are rare and recoverable.

> **Two independent copies are created:**
>
> ```
> Alice sends email to Bob
>       │
>       ├──▶ Copy 1: Alice's "Sent" folder (step ③, stored in Alice's partition)
>       │
>       └──▶ Copy 2: Bob's "Inbox" (created by recipient's server after delivery)
> ```
>
> If both are on **our service** → same Metadata DB, different partitions
> (partitioned by user_id). Alice's partition has her Sent copy, Bob's
> partition has his Inbox copy. Logically separate, never read each other's data.
>
> If Bob is on **Gmail** → completely separate infrastructure. Our SMTP
> delivers to Gmail's server, Gmail creates Bob's copy independently.
>
> In both cases, the copies are fully independent — deleting one doesn't
> affect the other. Like physical mail: once delivered, sender and
> recipient each have their own copy.

### What Exactly Is in the Queue?

```
The outgoing queue message contains everything needed to deliver the email:

{
  email_id:    "EMAIL-1234",
  from:        "alice@ourservice.com",
  to:          ["bob@gmail.com"],
  subject:     "Project Update",
  body:        "Hi Bob, ..." (or pointer to blob storage if large),
  attachments: ["s3://email-attachments/ATT-001/report.pdf"],
  retry_count: 0,
  created_at:  "2024-07-15T10:30:00Z"
}

The queue holds the full email payload so that the SMTP Outgoing server
can construct and deliver the SMTP message without needing to query the DB.
```

### Why 3 Servers? Why Can't SMTP Push Directly to the Recipient's Client?

```
The 3 servers in the sending flow:

  ① Our Web Server (HTTP)         — handles OUR user's request
  ② Our SMTP Outgoing Server      — delivers email to the OUTSIDE world
  ③ Recipient's Mail Server (SMTP)— Gmail/Outlook's server that RECEIVES email

Why can't ② push directly to Bob's Gmail client (browser/phone)?

  Because our server has NO IDEA where Bob's client is!

  Bob's Gmail client is behind:
    • NAT (no public IP)
    • Firewall
    • Could be offline
    • Could be on any device (phone, laptop, tablet)
    • Only Gmail's server knows Bob's connection

  Email delivery works like postal mail:
    You → Post Office (our SMTP) → Recipient's Post Office (Gmail) → Recipient's Mailbox
    You can't deliver directly to someone's house — you go through their post office.

  The flow is ALWAYS:
    Our SMTP → DNS MX lookup for gmail.com → Gmail's SMTP server (port 25)
    → Gmail stores it in Bob's mailbox
    → Bob's client fetches it from Gmail (via HTTP/WebSocket)

  We deliver to the SERVER, not the CLIENT. The recipient's server
  is responsible for getting it to the client (push via WebSocket
  if online, or stored until client fetches via HTTP).
```

```
Analogy:

  Alice (ourservice.com) sends to Bob (gmail.com):

  Alice's browser                              Bob's browser
       │                                            ▲
       │ HTTP (we control this)                     │ HTTP/WebSocket (Gmail controls this)
       ▼                                            │
  ┌─────────────┐                            ┌─────────────┐
  │ Our Web     │                            │ Gmail Web   │
  │ Server      │                            │ Server      │
  └──────┬──────┘                            └──────┬──────┘
         │                                          │
         ▼                                          │
  ┌─────────────┐    SMTP (port 25)          ┌─────┴───────┐
  │ Our SMTP    │───────────────────────────▶│ Gmail SMTP  │
  │ Outgoing    │  "Here's an email for Bob" │ Incoming    │
  └─────────────┘                            └─────────────┘
         │                                          │
   Our infrastructure                        Gmail's infrastructure
   (we control)                              (they control)
```

> **Why the outgoing queue?** SMTP delivery to external servers can be slow
> (DNS MX lookup, TCP connection setup, TLS handshake, retries on failure).
> The queue decouples the user-facing web server from the slow SMTP sending,
> so the user gets an immediate "sent" response without waiting for delivery.

---

## 6. Deep Dive: Email Receiving Flow

When someone outside sends an email to our user, here's how it arrives.

```
┌──────────┐      ┌─────────────┐     ┌────────────────┐
│ External │  ①   │   SMTP      │ ②   │  Acceptance    │
│ Server   │─────▶│   Servers   │────▶│  Policy        │
│(internet)│ SMTP └─────────────┘     │(valid domain?) │
└──────────┘                          └───────┬────────┘
                                              │ ③
                                              ▼
                                     ┌────────────────┐
                                     │  Incoming      │
                                     │  Queue (Kafka) │
                                     └───────┬────────┘
                                             │ ④
                                             ▼
                                     ┌────────────────┐
                                     │ Mail Processing│
                                     │                │
                                     │ • Spam check   │ ⑤
                                     │ • Virus check  │
                                     └───────┬────────┘
                                             │ ⑥
                         ┌───────────────────┼──────────────────┐
                         ▼                   ▼                  ▼
                  ┌──────────────┐  ┌──────────────┐  ┌────────────┐
                  │ Metadata DB  │  │ Search Store │  │  Cache     │
                  │ (store email)│  │ (index email)│  │ (latest)   │
                  └──────────────┘  └──────────────┘  └────────────┘
                                             │ ⑦
                                             ▼
                                     ┌──────────────┐
                         ┌───────────│  Is user      │───────────┐
                         │  YES      │  online?      │   NO      │
                         ▼           └──────────────┘            ▼
                  ┌──────────────┐                     (stored, user
                  │  Real-time   │                      fetches on
                  │  Server      │ ⑧                    next login
                  │  (WebSocket) │                      via HTTP)
                  └──────┬───────┘
                         │
                         ▼
                  ┌──────────────┐
                  │   Webmail    │
                  │   Client    │
                  └──────────────┘
```

### Step-by-Step

| Step | What Happens | Details |
|------|-------------|---------|
| ① | External server delivers via SMTP | Standard internet email delivery |
| ② | Acceptance policy check | Valid domain? Not blacklisted? Rate limit OK? |
| ③ | Enqueue to incoming queue | Decouples SMTP servers from processing |
| ④ | Mail processing workers consume | Pull from queue, process in parallel |
| ⑤ | Spam + virus check | ML-based spam detection, virus scanning |
| ⑥ | Store in all storage systems | Metadata DB + Search Store + Cache |
| ⑦ | Check if user is online | Does user have an active WebSocket connection? |
| ⑧ | Push notification | If online → push via WebSocket. If offline → fetch on next login |

> **What if there's no Kafka (incoming queue)?**
>
> Without Kafka, the SMTP server must do everything synchronously:
>
> ```
> With Kafka (async):                    Without Kafka (sync):
>
> SMTP Server                            SMTP Server
>   ① Accept email                         ① Accept email
>   ② Enqueue to Kafka                     ② Spam check (slow, ML model)
>   ③ Respond "250 OK" to sender           ③ Virus scan (slow, file scan)
>   (done! fast response)                  ④ Store in Metadata DB
>                                          ⑤ Index in Elasticsearch
> Mail Processing (async, later):          ⑥ Update cache
>   ④ Spam check                           ⑦ Push WebSocket notification
>   ⑤ Virus scan                           ⑧ THEN respond "250 OK"
>   ⑥ Store in DB                          (slow! sender waits for ALL steps)
>   ⑦ Index in ES
>   ⑧ Notify via WebSocket
> ```
>
> Problems without the queue:
> - **Slow response:** Sender's SMTP server waits for all processing → may timeout
> - **No burst absorption:** A spike of 10× emails overwhelms the SMTP server
>   (with Kafka, SMTP just enqueues fast, workers process at their own pace)
> - **No retry:** If DB or Elasticsearch is temporarily down, the email is lost.
>   With Kafka, the message stays in the queue until processing succeeds
> - **Tight coupling:** SMTP server must know about DB, ES, cache, WebSocket.
>   With Kafka, SMTP only knows about the queue — clean separation

---

## 7. Deep Dive: Metadata Database

This is the most critical storage decision — how to store email metadata at petabyte scale.

### Why Not MySQL/PostgreSQL?

```
At 730 PB/year of metadata alone:
  → Single MySQL instance: MAX ~10 TB before performance degrades
  → Even with sharding: managing 73,000+ MySQL shards is a nightmare
  → Relational model overhead: joins, foreign keys, schema rigidity

Emails are fundamentally:
  → Write-once (immutable after sending)
  → Read by a single user (owner)
  → Naturally partitioned by user
  → Need to handle massive scale with simple access patterns

→ Perfect fit for a distributed wide-column store like Bigtable or Cassandra
```

### Data Model (Bigtable / Cassandra Style)

```
┌──────────────────────────────────────────────────────────────────┐
│ emails table                                                     │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│ Partition key:  user_id                                          │
│ Clustering key: (folder_id, email_id)  ← sorted within folder   │
│                                                                  │
│ Columns:                                                         │
│   user_id        │ "bob@example.com"                             │
│   folder_id      │ "inbox"                                       │
│   email_id       │ "EMAIL-1234" (time-based UUID for ordering)   │
│   from           │ "alice@example.com"                           │
│   to             │ ["bob@example.com"]                           │
│   subject        │ "Project Update"                              │
│   body           │ "Hi Bob, ..." (or pointer to blob store)      │
│   is_read        │ false                                         │
│   is_spam        │ false                                         │
│   attachment_ids │ ["ATT-001"]                                   │
│   created_at     │ 1706000000                                    │
│                                                                  │
├──────────────────────────────────────────────────────────────────┤
│ Why this partition key?                                           │
│                                                                  │
│ Partition by user_id because:                                    │
│ • ALL email queries are scoped to a single user                  │
│ • User's entire mailbox lives on same partition → fast reads     │
│ • No cross-user queries needed                                   │
│                                                                  │
│ Cluster by (folder_id, email_id) because:                        │
│ • "Get all emails in Bob's inbox" → single partition scan        │
│ • email_id is time-based → newest first ordering                 │
│ • Folder grouping → filter by folder without scanning all emails │
└──────────────────────────────────────────────────────────────────┘
```

### All Tables & Stores in the System

```
┌────────────────────────────────────────────────────────────────────┐
│                    METADATA DB (Bigtable / Cassandra)              │
│                                                                    │
│  ① emails table (shown above)                                     │
│     Partition: user_id  │  Cluster: (folder_id, email_id)         │
│     The main table — stores all email metadata                    │
│                                                                    │
│  ② folders table                                                  │
│     Partition: user_id                                             │
│     Columns: folder_id, folder_name, is_system, created_at        │
│     Stores folder definitions (inbox, sent, trash, custom folders) │
│                                                                    │
│  ③ users table                                                    │
│     Partition: user_id                                             │
│     Columns: email_address, display_name, settings, created_at    │
│     User accounts and preferences                                 │
│                                                                    │
├────────────────────────────────────────────────────────────────────┤
│                    SEPARATE STORAGE SYSTEMS                        │
│                                                                    │
│  ④ Attachment Store (S3 / Object Storage)                         │
│     Key: attachment_id → binary file (PDF, image, etc.)           │
│     Not in the DB — too large, stored as blobs in S3              │
│                                                                    │
│  ⑤ Search Store (Elasticsearch)                                   │
│     Partition: user_id                                             │
│     Inverted index on: subject, body, from, to                    │
│     Separate from DB — optimized for full-text search             │
│                                                                    │
│     (See Section 9: Email Search for inverted index examples)     │
│                                                                    │
│  ⑥ Distributed Cache (Redis)                                      │
│     Key: user_id:folder_id → latest 50 email metadata             │
│     Hot data only — reduces DB reads for inbox listing             │
│                                                                    │
│     Sample Redis entries:                                          │
│     KEY: "bob@example.com:inbox"                                  │
│     VALUE: [                                                       │
│       { email_id: "EMAIL-5678", from: "charlie@..", subject: "Meeting", is_read: false },
│       { email_id: "EMAIL-1234", from: "alice@..",   subject: "Project", is_read: true  },
│       ... (up to 50 most recent)                                  │
│     ]                                                              │
│     TTL: 10 minutes (auto-refresh on next read)                   │
│                                                                    │
└────────────────────────────────────────────────────────────────────┘
```

> **Why not one big table?** Each store is optimized for a different access pattern:
> - Metadata DB → point lookups and range scans (emails by user + folder)
> - S3 → large binary blobs (attachments)
> - Elasticsearch → full-text search (inverted index)
> - Redis → low-latency reads (recent emails)
>
> Trying to put everything in one system would mean no system is optimized
> for anything. This is a classic "use the right tool for the right job" pattern.

### Email Body: Inline vs Separate?

```
Option A: Store body inline in Metadata DB
  Pros: Single read for email + body
  Cons: Large bodies bloat the row (some emails are HTML with images)

Option B: Store body in separate blob storage (S3)
  Pros: Metadata DB stays lean, body can be any size
  Cons: Extra read for body (but only when user opens the email)

→ Best practice: Store small bodies inline (<100KB), large bodies in S3.
  Most listing views only need subject, sender, timestamp — not body.
```

---

## 8. Deep Dive: Attachment Storage

```
Attachments are stored in Object Storage (S3), NOT in the Metadata DB.

Why?
  • Attachments are binary blobs (PDFs, images) — not suitable for databases
  • Average size: 500KB (some are 25MB+)
  • Object Storage is cheaper, designed for large files, globally distributed

How?
  ① User sends email with attachment
  ② Web server uploads attachment to S3
  ③ S3 returns an object URL (e.g., s3://email-attachments/ATT-001/report.pdf)
  ④ Metadata DB stores the attachment_id and URL (not the file itself)
  ⑤ When recipient opens email → client fetches attachment from S3 directly

Storage per year: 1B × 40 × 365 × 20% × 500KB = 1,460 PB
```

---

## 9. Deep Dive: Email Search

Users need to search across subject, body, and sender — at petabyte scale.

### Why Not Search in the Metadata DB?

```
Cassandra/Bigtable support:  ✓ Point queries (get email by ID)
                             ✓ Range queries (emails in a folder)
                             ✗ Full-text search ("emails containing 'project'")

Full-text search needs an inverted index:
  Word → list of emails containing that word
  "project"  → [EMAIL-1234, EMAIL-5678, EMAIL-9012]
  "meeting"  → [EMAIL-5678, EMAIL-3456]

→ This is exactly what Elasticsearch is built for.
```

### Search Architecture

```
┌──────────────────────────────────────────────────────────────┐
│  Elasticsearch Cluster                                        │
│                                                               │
│  Partitioned by user_id (same as Metadata DB)                │
│                                                               │
│  Index structure:                                             │
│    user_id:  "bob@example.com"                                │
│    fields:   subject (text), body (text), from (keyword),     │
│              to (keyword), timestamp (date), is_read (bool)   │
│                                                               │
│  Inverted index example:                                      │
│    "project" → [EMAIL-1234, EMAIL-5678]                       │
│    "meeting" → [EMAIL-5678, EMAIL-3456]                       │
│    "alice"   → [EMAIL-1234]                                   │
│                                                               │
│  Query: "emails from alice about project"                     │
│  → from:"alice" AND body:"project"                            │
│  → Returns EMAIL-1234                                         │
└──────────────────────────────────────────────────────────────┘
```

### When Is Elasticsearch Updated?

```
Synchronous (at write time):
  ① Email received → ② Stored in Metadata DB → ③ Indexed in Elasticsearch

Why synchronous?
  Users expect to search for an email immediately after receiving it.
  A small delay (seconds) is acceptable, but minutes is not.

Tradeoff:
  Synchronous indexing adds latency to the receive path.
  If Elasticsearch is slow/down, emails are still stored (DB is the source
  of truth), and a background job re-indexes missed emails.
```

---

## 10. Deep Dive: Email Deliverability

If our emails go to recipients' spam folders, the service is useless.
Email deliverability is about making sure our emails are accepted and trusted.

### The Problem

```
When our SMTP server sends email to gmail.com:
  Gmail asks: "Can I trust this sender?"

If the answer is NO → email goes to spam or is rejected entirely.
```

### Authentication Mechanisms

```
┌──────────────────────────────────────────────────────────────┐
│ SPF (Sender Policy Framework)                                │
│   DNS record lists which IP addresses can send email for     │
│   our domain. Recipient checks: "Is sender's IP in the      │
│   SPF record?"                                               │
│                                                              │
│ DKIM (DomainKeys Identified Mail)                            │
│   Attach a cryptographic signature to each outgoing email.   │
│   Recipient verifies the signature using our public key      │
│   (published in DNS). Proves email wasn't tampered.          │
│                                                              │
│ DMARC (Domain-based Message Authentication)                  │
│   Policy that tells receivers what to do if SPF/DKIM fail.   │
│   "If this email fails SPF and DKIM, reject it."            │
│                                                              │
│ All three work together:                                     │
│   SPF  → Is the IP authorized?                               │
│   DKIM → Was the email tampered?                             │
│   DMARC → What to do on failure?                             │
└──────────────────────────────────────────────────────────────┘
```

### Dedicated IPs & Warm-up

```
Problem: New IP addresses have no reputation → emails go to spam.

Solution:
  ① Use dedicated IPs for sending (not shared with other customers)
  ② IP warm-up: Gradually increase sending volume over weeks
     Day 1: Send 100 emails
     Day 7: Send 10,000 emails
     Day 30: Send 1,000,000 emails
     → ISPs slowly build trust for the IP

  ③ Separate IPs by email type:
     Transactional (password resets, confirmations) → High-priority IP pool
     Marketing (newsletters, promotions) → Separate IP pool
     → If marketing emails get spam complaints, transactional emails aren't affected
```

### Feedback Loops

```
When a recipient marks our email as spam:
  ① Gmail/Outlook sends a "feedback loop" notification back to us
  ② We add that recipient to a suppression list
  ③ We stop sending them emails
  → Protects our sender reputation
```

---

## 11. Deep Dive: Caching & Real-time

### What to Cache

```
┌─────────────────────────┬──────────┬─────────────────────────────────┐
│ Data                    │ Cache?   │ Why                             │
├─────────────────────────┼──────────┼─────────────────────────────────┤
│ Recent emails (inbox)   │ YES ✓    │ Most users check latest emails  │
│ Email metadata          │ YES ✓    │ Subject, sender for list views  │
│ User's folder list      │ YES ✓    │ Rarely changes                  │
│ Email body              │ NO ✗     │ Too large, read once            │
│ Attachments             │ NO ✗     │ Large, served from S3 directly  │
│ Search results          │ NO ✗     │ Too many possible queries       │
└─────────────────────────┴──────────┴─────────────────────────────────┘

Cache key format: user_id:folder_id → latest 50 email metadata
```

### Real-time Notifications (WebSocket)

```
Connection Registry in Redis:

  When Bob opens Gmail in his browser:
    ① Bob's client connects to a Real-time Server via WebSocket
    ② Real-time Server registers in Redis:
       KEY:   "ws:bob@example.com"
       VALUE: { server_id: "rt-server-7", connected_at: "2024-07-15T10:30:00Z" }
       TTL:   60 seconds (refreshed by heartbeat every 30 seconds)

  While Bob is online:
    → Real-time Server sends heartbeat every 30s → Redis TTL resets
    → Bob stays registered as "online"

  When Bob closes the tab or disconnects:
    → Heartbeats stop → Redis key expires after 60s
    → Bob is now considered "offline"

  If Real-time Server crashes:
    → Heartbeats stop for ALL its users → their Redis keys expire
    → Users auto-reconnect to a different Real-time Server
    → New server registers them again in Redis

When a new email arrives for Bob:

  ① Mail processing completes → email stored in DB
  ② Look up "ws:bob@example.com" in Redis
  ③ Found → route notification to rt-server-7
     → rt-server-7 pushes via Bob's WebSocket
     → Bob's browser immediately shows "1 new email" badge
  ④ Not found (key expired / doesn't exist) → Bob is offline
     → Email is stored. Bob fetches via HTTP on next login.

Why WebSocket and not long polling?
  → 1 billion users, many have email open all day
  → WebSocket: single persistent connection, server pushes when needed
  → Long polling: repeated HTTP requests, wasteful at scale
```

> **Note:** This is a separate use of Redis from the email cache. Same Redis
> cluster, but different key patterns:
> - `"ws:*"` → connection registry (which user is on which server)
> - `"user:folder"` → email cache (latest emails for fast inbox loading)

---

## 12. Scaling

### Metadata DB Scaling

```
730 PB/year → Cannot fit in a single cluster

Partitioning strategy: Partition by user_id
  → Each user's entire mailbox lives on one partition
  → Queries are always scoped to one user → no cross-partition reads
  → Natural load distribution (users have varying mailbox sizes)

For very heavy users (millions of emails):
  → Sub-partition by folder_id within the user partition
  → Most queries are "emails in this folder" → efficient range scan
```

### Search Store Scaling

```
Elasticsearch cluster, also partitioned by user_id.
  → Each user's search index is on one shard
  → Search query → route to correct shard → return results
  → Replication factor: 2-3 for availability
```

### Web Server Scaling

```
Stateless → horizontally scale behind load balancer
  → Add more instances during peak hours
  → Any server can handle any user's request
```

### Real-time Server Scaling

```
Stateful (each holds WebSocket connections)
  → Must route notifications to the correct server
  → Connection registry in Redis: user_id → server_id
  → When email arrives → look up which server has Bob's connection
                       → route notification to that server
```

### SMTP Scaling

```
Outgoing:
  → Pool of SMTP servers behind the outgoing queue
  → Scale based on queue depth
  → Separate pools for different email types (transactional vs marketing)

Incoming:
  → Multiple SMTP servers behind load balancer
  → MX (Mail Exchange) DNS records point to multiple IPs
  → Incoming queue absorbs burst traffic
```

---

## 13. What Can Go Wrong? (Failure Handling)

### Email Loss

**Scenario:** Email received but DB write fails
**Solution:** The incoming queue (Kafka) retains messages until confirmed processed. If processing fails, the message stays in the queue for retry. Kafka's durability guarantees no data loss.

### Search Out of Sync

**Scenario:** Email stored in DB but Elasticsearch indexing fails
**Solution:** Background reconciliation job compares DB and search index. Missing emails are re-indexed. DB is always the source of truth.

### Attachment Upload Fails

**Scenario:** S3 upload fails mid-transfer
**Solution:** Client retries the upload. S3 supports multipart upload for large files — if one part fails, only that part is retried.

### SMTP Delivery Fails

**Scenario:** Recipient's server is down
**Solution:** Outgoing queue retries with exponential backoff (1 min, 5 min, 30 min, 2 hours...). After multiple failures (e.g., 3 days), send a bounce notification back to the sender.

### Real-time Server Crashes

**Scenario:** WebSocket server crashes, users lose connection
**Solution:** Client auto-reconnects to another Real-time Server. Connection registry in Redis is updated. Missed notifications are picked up via HTTP polling on reconnect.

---

## 14. Why These Choices? (Key Design Decisions)

### Decision #1: Bigtable/Cassandra Over MySQL

**Problem:** Which database for email metadata?

**Why distributed DB:** 730 PB/year cannot fit in MySQL. Emails are write-once, read by a single user, and naturally partitioned by user_id — a perfect fit for wide-column stores. Simple access patterns (get emails by user + folder) don't need relational joins.

### Decision #2: S3 Over Database for Attachments

**Problem:** Where to store attachments?

**Why S3:** Attachments are binary blobs up to 25MB. Object storage is 10× cheaper than database storage, designed for large files, and globally distributed via CDN. The metadata DB only stores a reference (URL).

### Decision #3: Elasticsearch for Search

**Problem:** How to search emails at petabyte scale?

**Why Elasticsearch:** Full-text search with inverted index is exactly what's needed. Cassandra/Bigtable don't support full-text search. Elasticsearch handles complex queries (subject + sender + date range) efficiently. Partitioned by user_id for query isolation.

### Decision #4: HTTP + WebSocket Over IMAP/POP

**Problem:** How should clients communicate with the service?

**Why HTTP + WebSocket:** IMAP/POP are limited legacy protocols. HTTP gives us full control over APIs (custom features, pagination, rich search). WebSocket provides real-time push notifications (no polling). We still use SMTP for server-to-server delivery (internet standard).

### Decision #5: Message Queues for Send/Receive

**Problem:** How to handle email flow?

**Why queues:** Email sending (SMTP delivery) and receiving (spam/virus processing) can be slow. Queues decouple fast web servers from slow processing, absorb traffic spikes, and provide retry capability. If processing fails, the message stays in the queue.

---

## 15. Interview Pro Tips

### Opening Statement
"An email service at Gmail scale is fundamentally a storage-heavy, write-once-read-many system handling ~2 PB/year. I'd use a distributed wide-column store (Bigtable) for metadata partitioned by user_id, S3 for attachments, Elasticsearch for search, HTTP+WebSocket for client communication, and SMTP for server-to-server delivery. Message queues decouple the send/receive flows from processing."

### Key Talking Points
1. **Traditional vs Modern:** SMTP for server-to-server (internet standard), HTTP+WebSocket for clients (modern, flexible)
2. **Storage:** Metadata in Bigtable (730 PB/year), attachments in S3 (1,460 PB/year), search in Elasticsearch
3. **Send flow:** Web Server → Outgoing Queue → SMTP Outgoing → Internet
4. **Receive flow:** SMTP Server → Incoming Queue → Mail Processing → Storage → WebSocket notification
5. **Deliverability:** SPF + DKIM + DMARC, dedicated IPs, warm-up, feedback loops
6. **Partitioning:** Everything by user_id (metadata, search, cache)
7. **Real-time:** WebSocket for push, HTTP for pull (fallback)

### Common Follow-ups

**Q: How would you handle a user with millions of emails?**
A: Sub-partition their data by folder_id within the user partition. Most queries are folder-scoped ("inbox emails"), so this avoids scanning their entire mailbox. For search, Elasticsearch handles large user indices well with proper shard sizing.

**Q: How do you prevent spam from being sent FROM your service?**
A: Rate limit per user, ML-based content analysis on outgoing emails, monitor complaint feedback loops. If a user sends spam → throttle, then suspend the account.

**Q: What happens if Elasticsearch is down?**
A: Search is unavailable, but email send/receive/read continues normally. DB is the source of truth. A background job re-indexes once Elasticsearch recovers.

**Q: How do you handle email threading (conversations)?**
A: Add `thread_id` and `in_reply_to` fields to the email metadata. Group emails by thread_id in the UI. The `References` and `In-Reply-To` SMTP headers provide threading info from external emails.

**Q: How do you ensure emails aren't lost?**
A: Multi-layer durability — Kafka queues retain until confirmed, Bigtable replicates across data centers, S3 has 99.999999999% (eleven 9s) durability. No single point of failure.

---

## 16. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║             DISTRIBUTED EMAIL SERVICE - COMPLETE FLOW                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  SENDING:                                                                     ║
║  ┌────────┐    ┌─────────┐    ┌──────────┐    ┌──────────┐    ┌─────────┐  ║
║  │Webmail │───▶│  Web    │───▶│ Outgoing │───▶│  SMTP    │───▶│Internet │  ║
║  │Client  │HTTPS│Servers │    │  Queue   │    │ Outgoing │SMTP│(recipient║  ║
║  └────────┘    └────┬────┘    └──────────┘    │(spam/    │    │ server) │  ║
║                     │                          │virus chk)│    └─────────┘  ║
║                     ▼                          └──────────┘                  ║
║              ┌─────────────┐                                                 ║
║              │Store in Sent│                                                 ║
║              │folder + Index│                                                ║
║              └─────────────┘                                                 ║
║                                                                               ║
║  RECEIVING:                                                                   ║
║  ┌─────────┐   ┌──────────┐   ┌──────────┐   ┌──────────┐                  ║
║  │Internet │──▶│  SMTP    │──▶│ Incoming │──▶│  Mail    │                  ║
║  │(sender  │SMTP│ Servers │   │  Queue   │   │Processing│                  ║
║  │ server) │   └──────────┘   └──────────┘   │(spam/    │                  ║
║  └─────────┘                                  │virus chk)│                  ║
║                                               └─────┬────┘                  ║
║                                                     │                        ║
║                                    ┌────────────────┼───────────┐           ║
║                                    ▼                ▼           ▼           ║
║                             ┌───────────┐   ┌───────────┐ ┌────────┐       ║
║                             │Metadata DB│   │Search     │ │ Cache  │       ║
║                             │(Bigtable) │   │(Elastic)  │ │(Redis) │       ║
║                             └───────────┘   └───────────┘ └────────┘       ║
║                                                     │                        ║
║                                                     ▼                        ║
║                                              ┌──────────────┐               ║
║                                              │  Real-time   │               ║
║                                              │  Server      │               ║
║                                              │ (WebSocket)  │               ║
║                                              └──────┬───────┘               ║
║                                                     │                        ║
║                                                     ▼                        ║
║                                              ┌──────────┐                   ║
║                                              │ Webmail  │                   ║
║                                              │ Client   │                   ║
║                                              └──────────┘                   ║
║                                                                               ║
║  STORAGE LAYER:                                                               ║
║  ┌──────────────────────────────────────────────────────────────────────┐    ║
║  │ Metadata DB (Bigtable) │ Attachment Store (S3)                       │    ║
║  │ 730 PB/year            │ 1,460 PB/year                               │    ║
║  │ Partition: user_id     │ Key: attachment_id                          │    ║
║  │                        │                                              │    ║
║  │ Search Store (ES)      │ Cache (Redis)                               │    ║
║  │ Partition: user_id     │ Key: user_id:folder → latest emails         │    ║
║  └──────────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║  KEY DESIGN DECISIONS:                                                        ║
║  • Bigtable for metadata (730 PB/year, partitioned by user_id)               ║
║  • S3 for attachments (1,460 PB/year, cheap blob storage)                    ║
║  • Elasticsearch for search (inverted index, partitioned by user_id)         ║
║  • HTTP + WebSocket for clients (modern, flexible, replaces IMAP/POP)        ║
║  • SMTP for server-to-server (internet standard, must interoperate)          ║
║  • Message queues for send/receive (decouple, buffer, retry)                 ║
║  • SPF + DKIM + DMARC for deliverability (trust, authentication)             ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```
