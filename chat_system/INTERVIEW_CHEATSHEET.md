# Chat System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

> **This is your FINAL comprehensive reference for interview preparation.**

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Kafka** | Message routing | Disk (7 days) | Long-polling, consumer groups |
| **Cassandra** | Permanent storage | Disk (forever) | Partition by user_id/channel_id |
| **Redis** | Cache/Presence | Memory (TTL) | Sub-ms reads, device cursors |
| **Presence Server** | Online/Offline status | Redis (TTL 30s) | Heartbeat every 5s, Pub/Sub for friends |
| **Push Notification** | Notify offline users | Stateless | APNs (iOS), FCM (Android), just "wake up" |
| **Chat Server** | WebSocket connections | Stateful | Per-device connections, real-time delivery |

---

## 1. Requirements Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • 1-on-1 chat with low delivery latency                                    ║
║  • Group chat (max 100 users per group)                                     ║
║  • Online/Offline status (presence indicator)                               ║
║  • Multiple device support (sync across devices)                            ║
║  • Push notifications for offline users                                     ║
║  • Message history / offline message sync                                   ║
║                                                                               ║
║  OUT OF SCOPE (for this design):                                            ║
║  • End-to-end encryption                                                    ║
║  • Read receipts / typing indicators (simple extension)                     ║
║  • Media attachments (images, videos)                                       ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Latency: Real-time delivery (< 100ms for online users)                  ║
║  • Scale: 50M DAU                                                           ║
║  • Reliability: No message loss (at-least-once delivery)                   ║
║  • Ordering: Messages arrive in sent order (per conversation)              ║
║  • Highly available                                                         ║
║  • Persistence: Messages stored forever                                     ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  USERS:                                                                     ║
║  • 50M DAU                                                                  ║
║  • Average 40 messages sent/received per user/day                          ║
║  • Total: 50M × 40 = 2B messages/day                                       ║
║  • Messages/sec: 2B / 86400 = ~23K messages/sec                            ║
║  • Peak: 2-3× average = ~50-70K messages/sec                               ║
║                                                                               ║
║  CONNECTIONS:                                                               ║
║  • 50M concurrent WebSocket connections at peak                            ║
║  • If 1 server handles 10K connections → need 5,000 chat servers           ║
║                                                                               ║
║  STORAGE:                                                                   ║
║  • Average message: 100 bytes                                              ║
║  • 2B messages × 100 bytes = 200GB/day                                     ║
║  • 5 years retention: 200GB × 365 × 5 = ~365TB                             ║
║                                                                               ║
║  BANDWIDTH:                                                                 ║
║  • Inbound: 23K msg/sec × 100 bytes = ~2.3 MB/sec                         ║
║  • Outbound: 23K msg/sec × 100 bytes × 2 (avg recipients) = ~4.6 MB/sec   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. Message Flow: Two Approaches

### Approach 1: Dual Write (WhatsApp Style)
```
User A → Chat Server ─┬─→ Cassandra (direct write)
                      └─→ Kafka → Chat Server 2 → User B
```
- Chat Server does TWO writes
- Kafka has ONE consumer (delivery only)
- Simpler, but handle partial failures

### Approach 2: Kafka as Source of Truth (LinkedIn/Discord Style)
```
User A → Chat Server → Kafka ─┬─→ Consumer 1 → Cassandra
                              ├─→ Consumer 2 → WebSocket → User B
                              └─→ Consumer 3 → Elasticsearch
```
- Chat Server does ONE write (Kafka only)
- Multiple independent consumer groups
- More scalable, easier to add consumers

---

## 3. Kafka Deep-Dive

### Storage
- **Stores on DISK** (not memory!)
- Sequential I/O: ~600 MB/sec
- Messages NOT deleted after consumption
- Default retention: 7 days

### Topic vs Partition
```
TOPIC = Separate queue (user-inbox-123, channel-500)
PARTITION = Sub-queue for parallelism
```

### Partitioning for Chat
```
partition = hash(recipient_id) % num_partitions
```
- All messages TO same user → same partition → **ORDERED**

### Consumption: Long Polling
```java
ConsumerRecords records = consumer.poll(Duration.ofSeconds(1));
```
- **NOT periodic polling** - it BLOCKS
- Returns immediately if data arrives early
- Timeout only if no data

### Consumer Groups

| Same Group | Different Groups |
|------------|------------------|
| Load balancing | Pub/Sub |
| 1 consumer gets each msg | ALL groups get every msg |
| Work divided | Independent processing |

---

## 4. Fan-Out Strategies

### Fan-Out on WRITE (Small Groups < 100)
```
Topics: user-inbox-bob, user-inbox-charlie, user-inbox-dan
Writes: N (copy to each member's inbox)
Reads: 1 (own inbox)
```
✅ Simple reads, instant delivery  
❌ Write amplification

### Fan-Out on READ (Large Groups 100+)
```
Topics: channel-500 (single topic)
Writes: 1 (to channel)
Reads: N (all members read from channel)
```
✅ Efficient writes  
❌ Slightly complex reads

---

## 5. Offline User Flow

### When User is Offline
1. Message → Kafka
2. Consumer 1 → Cassandra (persisted ✓)
3. Consumer 2 → User offline → Push Notification

### When User Comes Online (Hybrid Pull + Push)
```
Step 1: PULL from Cassandra
  - Get cursor from Redis: cursor:{userId}:{deviceId}
  - Query: SELECT * FROM messages WHERE user_id = X AND msg_id > cursor
  - NO QUEUE involved!

Step 2: PUSH via Kafka
  - Subscribe to Kafka topic
  - Real-time delivery going forward
```

---

## 6. PRESENCE SERVER (Online/Offline Status)

### How Presence Works

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  PRESENCE ARCHITECTURE                                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────┐         ┌──────────────────┐         ┌─────────────┐         ║
║  │   Client    │◀───────▶│  Presence Server │◀───────▶│    Redis    │         ║
║  │  (App/Web)  │         │                  │         │   (Cache)   │         ║
║  └─────────────┘         └────────┬─────────┘         └─────────────┘         ║
║                                   │                                           ║
║                                   ▼                                           ║
║                          ┌──────────────────┐                                 ║
║                          │  Redis Pub/Sub   │ → Notify friends               ║
║                          └──────────────────┘                                 ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Heartbeat Mechanism

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  CLIENT HEARTBEAT FLOW                                                       │
├──────────────────────────────────────────────────────────────────────────────┤
│                                                                              │
│  Client sends heartbeat every 5 seconds:                                     │
│                                                                              │
│  Client ──[PING every 5s]──▶ Presence Server ──▶ Redis                      │
│                                                 SET presence:user123 "online" EX 30
│                                                                              │
│  If NO heartbeat for 30 seconds → TTL expires → User is OFFLINE             │
│                                                                              │
└──────────────────────────────────────────────────────────────────────────────┘
```

### Status Change Triggers

| Event | Action | Redis Command |
|-------|--------|---------------|
| **Login** | Set online, notify friends | `SET presence:123 "online" EX 30` + `PUBLISH` |
| **Logout** | Set offline, notify friends | `DEL presence:123` + `PUBLISH` |
| **Heartbeat** | Refresh TTL | `EXPIRE presence:123 30` |
| **Timeout** | Auto-offline (TTL expires) | Automatic by Redis |
| **App minimized** | Set "away" | `SET presence:123 "away" EX 30` |

### Friend Notification Flow

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  NOTIFY FRIENDS WHEN USER GOES ONLINE                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. Alice logs in                                                            ║
║     │                                                                         ║
║     ▼                                                                         ║
║  2. Presence Server sets: SET presence:alice "online" EX 30                  ║
║     │                                                                         ║
║     ▼                                                                         ║
║  3. Get Alice's friends from MySQL: [Bob, Charlie, Dan]                      ║
║     │                                                                         ║
║     ▼                                                                         ║
║  4. Publish: PUBLISH presence:updates "alice:online"                         ║
║     │                                                                         ║
║     ├──▶ Bob's Chat Server (subscribed) → Push to Bob's WebSocket           ║
║     ├──▶ Charlie's Chat Server → Push to Charlie's WebSocket                ║
║     └──▶ Dan is offline → No action                                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Optimization for Large Friend Lists

| Problem | Solution |
|---------|----------|
| User has 5000 friends | Only notify friends who are ONLINE |
| Frequent status changes | Debounce: Only publish if status actually changed |
| Notification storm | Batch updates: Group notifications every 1 second |
| Pub/Sub overhead | Use Kafka topics for presence (more scalable than Redis Pub/Sub) |

### Redis Data Model for Presence

```
# User's online status (with TTL)
SET presence:user123 "online" EX 30

# User's last seen (for "last seen at" feature)
SET lastseen:user123 "1705312800" (Unix timestamp)

# User's current device(s)
SADD devices:user123 "device-phone-1" "device-laptop-2"

# Which chat server is user connected to
SET user:server:user123 "chat-server-5"
```

---

## 7. PUSH NOTIFICATION SERVER

### When Push Notifications are Triggered

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  PUSH NOTIFICATION FLOW                                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  User A sends message to User B (who is OFFLINE)                             ║
║                                                                               ║
║  1. Message → Kafka                                                          ║
║     │                                                                         ║
║     ├──▶ Consumer 1 (Persistence) → Cassandra ✓                             ║
║     │                                                                         ║
║     └──▶ Consumer 2 (Delivery) → Check Presence                             ║
║                                    │                                          ║
║                                    ▼                                          ║
║                              GET presence:userB                              ║
║                              → NULL (offline!)                               ║
║                                    │                                          ║
║                                    ▼                                          ║
║                          ┌─────────────────────┐                              ║
║                          │ Push Notification   │                              ║
║                          │ Server              │                              ║
║                          └─────────┬───────────┘                              ║
║                                    │                                          ║
║                          ┌─────────┴───────────┐                              ║
║                          ▼                     ▼                              ║
║                     ┌────────┐            ┌────────┐                          ║
║                     │  APNs  │            │  FCM   │                          ║
║                     │(Apple) │            │(Google)│                          ║
║                     └────┬───┘            └────┬───┘                          ║
║                          │                     │                              ║
║                          ▼                     ▼                              ║
║                      iPhone                Android                            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Push Notification Data Model

```
# MySQL: device_tokens table
CREATE TABLE device_tokens (
    user_id         BIGINT,
    device_id       VARCHAR(100),
    platform        ENUM('ios', 'android', 'web'),
    push_token      VARCHAR(500),      -- APNs/FCM token
    created_at      TIMESTAMP,
    updated_at      TIMESTAMP,
    PRIMARY KEY (user_id, device_id)
);
```

### Push Notification Payload

```json
{
  "to": "FCM_DEVICE_TOKEN_HERE",
  "notification": {
    "title": "Alice",
    "body": "Hey! Are you coming to the party?"
  },
  "data": {
    "type": "new_message",
    "sender_id": "123",
    "channel_id": "456",
    "message_id": "789"
  }
}
```

### What Push Notification Server Does NOT Store

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  IMPORTANT: Push Notification Server is STATELESS                            ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ❌ Does NOT store messages                                                  ║
║  ❌ Does NOT queue messages for later                                        ║
║  ❌ Does NOT track delivery status                                           ║
║                                                                               ║
║  ✅ Only sends a "wake up" signal to device                                  ║
║  ✅ Message is already safely stored in Cassandra                            ║
║  ✅ Device fetches messages from Cassandra when app opens                    ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Push Notification Optimizations

| Optimization | How |
|--------------|-----|
| **Batching** | Group notifications for same user (10 new messages → 1 push) |
| **Rate limiting** | Max 1 push per minute per sender-receiver pair |
| **Silent push** | iOS background fetch without alert |
| **Priority** | High priority for 1:1, low for group mentions |
| **Collapse key** | FCM replaces old notification with new one |

---

## 8. MULTI-DEVICE SYNC (Two Devices Flow)

### Problem Statement

```
User has Phone + Laptop. Message should appear on BOTH devices!
```

### Solution: Per-Device Cursors

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  MULTI-DEVICE SYNC ARCHITECTURE                                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║                              ┌─────────────┐                                  ║
║                              │   REDIS     │                                  ║
║                              │   Cursors   │                                  ║
║                              └──────┬──────┘                                  ║
║                                     │                                         ║
║  cursor:alice:phone = 1000          │    cursor:alice:laptop = 950           ║
║                                     │                                         ║
║         ┌───────────────────────────┼───────────────────────────┐             ║
║         │                           │                           │             ║
║         ▼                           ▼                           ▼             ║
║  ┌─────────────┐             ┌─────────────┐             ┌─────────────┐      ║
║  │   Phone     │             │  Cassandra  │             │   Laptop    │      ║
║  │ (cursor=    │             │  Messages   │             │ (cursor=    │      ║
║  │   1000)     │             │  1-1000     │             │   950)      │      ║
║  └─────────────┘             └─────────────┘             └─────────────┘      ║
║                                                                               ║
║  Phone is up-to-date!              │              Laptop needs 950-1000!     ║
║                                     │                                         ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Device Cursor Data Model (Redis)

```
# Each device has its own cursor
SET cursor:alice:phone-123     "1000"    # Phone has seen up to msg 1000
SET cursor:alice:laptop-456    "950"     # Laptop has seen up to msg 950
SET cursor:alice:tablet-789    "980"     # Tablet has seen up to msg 980
```

### Sync Flow When Device Comes Online

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DEVICE RECONNECTION SYNC FLOW                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Alice's Laptop connects (was offline):                                      ║
║                                                                               ║
║  Step 1: Get device cursor from Redis                                        ║
║          GET cursor:alice:laptop-456  → 950                                  ║
║                                                                               ║
║  Step 2: Query Cassandra for missed messages                                 ║
║          SELECT * FROM user_messages                                         ║
║          WHERE user_id = 'alice'                                             ║
║          AND message_id > 950                                                ║
║          ORDER BY message_id ASC                                             ║
║          → Returns messages 951, 952, ... 1000                              ║
║                                                                               ║
║  Step 3: Send all 50 messages via WebSocket to laptop                       ║
║                                                                               ║
║  Step 4: Subscribe to Kafka for real-time messages going forward            ║
║                                                                               ║
║  Step 5: Update cursor after messages are delivered                         ║
║          SET cursor:alice:laptop-456 "1000"                                  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Real-Time Sync (Both Devices Online)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  BOTH DEVICES ONLINE - REAL-TIME DELIVERY                                    ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Bob sends message to Alice:                                                 ║
║                                                                               ║
║  Bob → Chat Server → Kafka (topic: user-inbox-alice)                        ║
║                              │                                               ║
║                              ├──▶ Cassandra (store forever)                 ║
║                              │                                               ║
║                              └──▶ Delivery Consumer                         ║
║                                   │                                          ║
║                                   │  Check: Which servers is Alice on?      ║
║                                   │  Redis: user:server:alice → [server-3, server-5]
║                                   │                                          ║
║                                   ├──▶ Chat Server 3 → Alice's Phone        ║
║                                   │    (via WebSocket)                       ║
║                                   │                                          ║
║                                   └──▶ Chat Server 5 → Alice's Laptop       ║
║                                        (via WebSocket)                       ║
║                                                                               ║
║  BOTH devices receive message simultaneously!                                ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Tracking Multiple Devices in Redis

```
# Which devices does user have?
SADD devices:alice "phone-123" "laptop-456" "tablet-789"

# Which server is each device connected to?
HSET device:connections:alice phone-123 "chat-server-3"
HSET device:connections:alice laptop-456 "chat-server-5"

# Or simpler: track all servers user is on
SADD user:servers:alice "chat-server-3" "chat-server-5"
```

### Read Receipts with Multi-Device

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  READ RECEIPT SYNC ACROSS DEVICES                                            ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Alice reads message on Phone:                                               ║
║                                                                               ║
║  1. Phone sends: "I read message 1000"                                       ║
║     │                                                                         ║
║     ▼                                                                         ║
║  2. Chat Server updates:                                                     ║
║     • Cassandra: UPDATE read_receipts SET read_at = now()                   ║
║     • Redis: SET cursor:alice:phone-123 "1000"                              ║
║     │                                                                         ║
║     ▼                                                                         ║
║  3. Notify sender (Bob) that message was read                               ║
║     │                                                                         ║
║     ▼                                                                         ║
║  4. Sync to Alice's other devices (optional):                               ║
║     Push "message 1000 is read" to Laptop & Tablet                          ║
║     → They mark it as read too (gray → blue checkmarks)                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Multi-Device Summary

| Scenario | How It Works |
|----------|--------------|
| **Device offline, comes online** | Pull from Cassandra using device cursor |
| **Both devices online** | Push to both via different Chat Servers |
| **New device added** | Cursor = 0, fetch all messages (with pagination) |
| **Device removed** | Delete cursor, revoke push token |
| **Read on one device** | Optionally sync read status to other devices |

---

## 9. DATABASE DEEP-DIVE (Complete Summary)

### Overview: All Databases

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATABASE                │  TYPE              │  STORAGE    │  RETENTION      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  1. Kafka                │  Message Queue     │  Disk       │  7 days         ║
║  2. Cassandra            │  Wide-Column DB    │  Disk       │  Forever        ║
║  3. Redis                │  In-Memory Cache   │  Memory     │  TTL-based      ║
║  4. MySQL (optional)     │  Relational DB     │  Disk       │  Forever        ║
║  5. Zookeeper (optional) │  Coordination      │  Disk       │  Forever        ║
║  6. Elasticsearch (opt)  │  Search Engine     │  Disk       │  Configurable   ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

### 5.1 KAFKA (Message Queue)

**Purpose:** Real-time message routing and delivery

**What it stores:**
- Messages in transit (for delivery)
- Topics: `user-inbox-{userId}`, `channel-{channelId}`

**Key properties:**
- Stores on DISK (sequential I/O, ~600 MB/sec)
- NOT deleted after consumption (unlike RabbitMQ)
- Each consumer group has its own offset
- Can replay messages!

**Retention policies:**
- Time-based: 7 days (default)
- Size-based: e.g., 100GB per partition
- Compact: Keep only latest per key

---

### 5.2 CASSANDRA (KV Store / Wide-Column DB)

**Purpose:** Permanent message storage, message history, offline sync

**Data Models:**

#### Table 1: `user_messages` (for 1:1 and small group - Fan-out on Write)
```sql
CREATE TABLE user_messages (
    user_id         BIGINT,          -- Partition key (recipient)
    message_id      BIGINT,          -- Clustering key (Snowflake ID)
    sender_id       BIGINT,
    channel_id      BIGINT,          -- NULL for 1:1
    content         TEXT,
    message_type    TEXT,            -- 'text', 'image', 'video'
    created_at      TIMESTAMP,
    PRIMARY KEY ((user_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```
- **Partition key:** `user_id` (recipient)
- **Clustering key:** `message_id` (sorted, for pagination)
- **Query:** `SELECT * FROM user_messages WHERE user_id = ? AND message_id > ?`

#### Table 2: `channel_messages` (for large groups - Fan-out on Read)
```sql
CREATE TABLE channel_messages (
    channel_id      BIGINT,          -- Partition key
    message_id      BIGINT,          -- Clustering key
    sender_id       BIGINT,
    content         TEXT,
    message_type    TEXT,
    created_at      TIMESTAMP,
    PRIMARY KEY ((channel_id), message_id)
) WITH CLUSTERING ORDER BY (message_id DESC);
```
- **Partition key:** `channel_id`
- **Query:** `SELECT * FROM channel_messages WHERE channel_id = ? AND message_id > ?`

#### Table 3: `channels` (group metadata)
```sql
CREATE TABLE channels (
    channel_id      BIGINT PRIMARY KEY,
    name            TEXT,
    owner_id        BIGINT,
    member_count    INT,
    created_at      TIMESTAMP
);
```

#### Table 4: `channel_members` (membership)
```sql
CREATE TABLE channel_members (
    channel_id      BIGINT,
    user_id         BIGINT,
    role            TEXT,            -- 'admin', 'member'
    joined_at       TIMESTAMP,
    PRIMARY KEY ((channel_id), user_id)
);
```

---

### 5.3 REDIS (In-Memory Cache)

**Purpose:** Fast lookups, presence tracking, multi-device sync

**Data Models (Key-Value):**

| Key Pattern | Value | TTL | Purpose |
|-------------|-------|-----|---------|
| `presence:{userId}` | `"online"` / `"offline"` | 30s | Online status |
| `cursor:{userId}:{deviceId}` | `12345` (message_id) | None | Multi-device sync |
| `session:{token}` | `{userId, deviceId, ...}` | 24h | Session data |
| `user:server:{userId}` | `"chat-server-3"` | 1h | Which server user is on |
| `typing:{channelId}:{userId}` | `1` | 3s | Typing indicator |

**Pub/Sub Channels:**
- `presence:updates` → Notify friends of online/offline
- `typing:{channelId}` → Typing indicators

**Example Usage:**
```
SET presence:user123 "online" EX 30
GET cursor:user123:device456        → 12345
PUBLISH presence:updates "user123:online"
```

---

### 5.4 MYSQL/POSTGRESQL (Relational DB) - Optional

**Purpose:** User profiles, group metadata, friendships (NOT for messages!)

**Tables:**

#### Table: `users`
```sql
CREATE TABLE users (
    user_id         BIGINT PRIMARY KEY,
    username        VARCHAR(50) UNIQUE,
    email           VARCHAR(255) UNIQUE,
    password_hash   VARCHAR(255),
    avatar_url      VARCHAR(500),
    created_at      TIMESTAMP
);
```

#### Table: `friendships`
```sql
CREATE TABLE friendships (
    user_id_1       BIGINT,
    user_id_2       BIGINT,
    status          ENUM('pending', 'accepted', 'blocked'),
    created_at      TIMESTAMP,
    PRIMARY KEY (user_id_1, user_id_2)
);
```

**Why NOT messages here?**
- Hard to scale horizontally
- Sharding is complex
- Wide-column (Cassandra) is better for time-series data

---

### 5.5 ZOOKEEPER / ETCD / CONSUL (Service Discovery) - Optional

**Purpose:** Chat server registry, health checks

**What it stores:**
```
/chat-servers/server-1  →  {host: "10.0.0.1", port: 8080, load: 5000}
/chat-servers/server-2  →  {host: "10.0.0.2", port: 8080, load: 3000}
/chat-servers/server-3  →  {host: "10.0.0.3", port: 8080, load: 7000}
```

**How it works:**
1. Chat servers register on startup
2. Load balancer queries for available servers
3. Ephemeral nodes auto-delete when server dies
4. Watchers notify on server changes

---

### 5.6 ELASTICSEARCH (Search Engine) - Optional

**Purpose:** Full-text search in messages

**Index: `messages`**
```json
{
  "message_id": 12345,
  "sender_id": 100,
  "channel_id": 500,
  "content": "Hey, did you see the project update?",
  "created_at": "2024-01-15T10:30:00Z"
}
```

**Query Example:**
```json
{
  "query": {
    "bool": {
      "must": { "match": { "content": "project update" } },
      "filter": { "term": { "channel_id": 500 } }
    }
  }
}
```

---

### 5.7 Visual: Data Flow Through Databases

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  USER A SENDS MESSAGE TO USER B                                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║         User A                                                                ║
║           │                                                                   ║
║           ▼                                                                   ║
║    ┌─────────────┐                                                            ║
║    │ Chat Server │                                                            ║
║    └──────┬──────┘                                                            ║
║           │                                                                   ║
║     ┌─────┴─────┬────────────────┬────────────────┐                           ║
║     │           │                │                │                           ║
║     ▼           ▼                ▼                ▼                           ║
║  ┌──────┐   ┌───────────┐  ┌──────────┐    ┌───────────┐                      ║
║  │KAFKA │   │ CASSANDRA │  │  REDIS   │    │ELASTIC-   │                      ║
║  │      │   │           │  │          │    │SEARCH     │                      ║
║  │Route │   │ Store     │  │ Update   │    │ Index     │                      ║
║  │to    │   │ message   │  │ cursor   │    │ for       │                      ║
║  │User B│   │ forever   │  │          │    │ search    │                      ║
║  └──┬───┘   └───────────┘  └──────────┘    └───────────┘                      ║
║     │                                                                         ║
║     ▼                                                                         ║
║  ┌──────────┐                                                                 ║
║  │Chat      │──────▶ User B (WebSocket)                                      ║
║  │Server 2  │                                                                 ║
║  └──────────┘                                                                 ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

### 5.8 Summary Table

| Database | Stores What | Key Access Pattern |
|----------|-------------|-------------------|
| **Kafka** | Messages in transit | Produce/consume by topic |
| **Cassandra** | All messages forever | Range query by user_id + msg_id |
| **Redis** | Presence, cursors | GET/SET by key |
| **MySQL** | User profiles, groups | JOIN queries |
| **Zookeeper** | Server registry | Watch for changes |
| **Elasticsearch** | Message index | Full-text search |

**Minimum Required: Kafka + Cassandra + Redis (3 core databases)**

---

## 10. DATABASE CHOICE TRADEOFFS (Non-Obvious Decisions)

### Why Cassandra for Messages (Not MySQL)?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  THE DECISION: CASSANDRA vs MYSQL FOR CHAT MESSAGES                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  CASSANDRA WINS FOR CHAT BECAUSE:                                            ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║  1. WRITE-HEAVY WORKLOAD                                                     ║
║     • Chat: Billions of messages/day (WhatsApp: 65B/day)                    ║
║     • Cassandra: LSM tree = O(1) writes, no read-before-write               ║
║     • MySQL: B-tree = O(log N) writes, locks, slower at scale               ║
║                                                                               ║
║  2. TIME-SERIES ACCESS PATTERN                                               ║
║     • Query: "Get messages for user X after timestamp Y"                    ║
║     • Cassandra: Partition by user, cluster by time = perfect!              ║
║     • MySQL: Would need index scan, less efficient                          ║
║                                                                               ║
║  3. NO JOINS NEEDED                                                          ║
║     • Messages are self-contained (sender_id, content, timestamp)           ║
║     • Never need: SELECT * FROM messages JOIN users...                      ║
║     • MySQL's JOINs = wasted capability                                     ║
║                                                                               ║
║  4. HORIZONTAL SCALING                                                       ║
║     • Cassandra: Add nodes, auto-rebalance, linear scalability              ║
║     • MySQL: Sharding is manual, complex, error-prone                       ║
║                                                                               ║
║  5. EVENTUAL CONSISTENCY IS OK                                               ║
║     • Message arrives 1 second late? User won't notice.                     ║
║     • No need for strict ACID on chat messages                              ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  MYSQL WOULD BE WRONG BECAUSE:                                               ║
║  ─────────────────────────────                                               ║
║     • Slower writes at massive scale                                        ║
║     • Sharding is painful (cross-shard queries for group chats)            ║
║     • ACID overhead we don't need                                           ║
║     • Would need to shard anyway → lose JOIN capability                    ║
║                                                                               ║
║  INTERVIEW ANSWER:                                                           ║
║  "Cassandra for messages because: (1) write-heavy - billions/day, LSM tree  ║
║   is faster than B-tree, (2) time-series access - partition by user,        ║
║   cluster by time, (3) no JOINs needed - messages are self-contained,       ║
║   (4) linear horizontal scaling. MySQL would struggle at this write volume  ║
║   and we'd end up sharding it anyway, losing the JOIN benefits."            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Kafka (Not Just Direct Delivery)?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  THE DECISION: WHY KAFKA BETWEEN CHAT SERVERS?                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WITHOUT KAFKA (Direct Server-to-Server):                                    ║
║  ─────────────────────────────────────────                                   ║
║                                                                               ║
║  Chat Server 1 ───HTTP/gRPC───► Chat Server 2                               ║
║                                                                               ║
║  Problems:                                                                   ║
║  ✗ If Server 2 is down → message LOST                                       ║
║  ✗ Every server must know about every other server (N² connections)         ║
║  ✗ No replay if consumer crashes                                            ║
║  ✗ No persistence for offline users                                         ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WITH KAFKA:                                                                 ║
║  ───────────                                                                 ║
║                                                                               ║
║  Chat Server 1 ──► Kafka ──► Chat Server 2                                  ║
║                      │                                                        ║
║                      └──► Cassandra (persistence)                            ║
║                      └──► Push Service (offline users)                       ║
║                                                                               ║
║  Benefits:                                                                   ║
║  ✓ Server 2 down? Kafka holds message, delivers when back                   ║
║  ✓ Decoupled: Servers don't know about each other                           ║
║  ✓ Replay: Consumer crashes? Resume from last offset                        ║
║  ✓ Multiple consumers: Same message → delivery + persistence + push         ║
║  ✓ Ordering: Partition by recipient_id = ordered delivery                   ║
║                                                                               ║
║  INTERVIEW ANSWER:                                                           ║
║  "Kafka between servers for: (1) durability - if recipient's server is      ║
║   down, message waits in Kafka, (2) decoupling - servers don't need to      ║
║   know each other, (3) multiple consumers - same event triggers delivery,   ║
║   persistence to Cassandra, and push notifications, (4) replay - consumer   ║
║   crashes can resume from offset."                                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Redis for Presence (Not Cassandra)?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  THE DECISION: REDIS vs CASSANDRA FOR ONLINE STATUS                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  REDIS WINS FOR PRESENCE BECAUSE:                                            ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║  1. TTL (Time-To-Live) BUILT-IN                                             ║
║     • SET presence:user123 "online" EX 30                                   ║
║     • Key auto-expires if no heartbeat → user is offline!                   ║
║     • Cassandra: Need to manually query and check timestamps                ║
║                                                                               ║
║  2. PUB/SUB FOR REAL-TIME UPDATES                                           ║
║     • Friend comes online → PUBLISH to subscribers instantly                ║
║     • Cassandra: No pub/sub, need to poll                                   ║
║                                                                               ║
║  3. EPHEMERAL DATA                                                          ║
║     • Online status doesn't need to persist forever                         ║
║     • If Redis restarts, everyone just re-heartbeats                        ║
║     • Cassandra: Optimized for durable, long-term storage                   ║
║                                                                               ║
║  4. SUB-MILLISECOND READS                                                   ║
║     • "Is user online?" needs to be FAST                                    ║
║     • Redis: In-memory = microseconds                                       ║
║     • Cassandra: Disk-based = milliseconds                                  ║
║                                                                               ║
║  INTERVIEW ANSWER:                                                           ║
║  "Redis for presence because: (1) TTL - heartbeat sets 30s expiry, key      ║
║   auto-deletes if no heartbeat = user offline, (2) Pub/Sub - notify         ║
║   friends instantly when status changes, (3) ephemeral - status doesn't     ║
║   need durability, (4) speed - sub-millisecond for 'is user online?'"       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 11. Snowflake ID

```
| 1 bit | 41 bits    | 5 bits  | 5 bits   | 12 bits  |
| sign  | timestamp  | DC ID   | machine  | sequence |
```
- Time-sortable (message ordering)
- Globally unique (no coordination)
- ~4M IDs/sec per machine

---

## 12. Interview Quick Answers

**Q: Push or pull for delivery?**
> "Both! Kafka uses long-polling (feels like push). poll() blocks until data or timeout. For offline sync, pull from Cassandra."

**Q: How many consumers from Kafka?**
> "Multiple consumer groups: persistence (Cassandra), delivery (WebSocket), search (Elasticsearch). Each group gets every message independently."

**Q: Where does offline user get messages?**
> "Cassandra! Direct DB read using device cursor. Not from queue. Then subscribe to Kafka for real-time."

**Q: Fan-out strategy?**
> "Write for small groups (<100 members) - copy to each inbox. Read for large groups (100+) - single copy in channel."

**Q: How many databases?**
> "3 core: Kafka (queue), Cassandra (persistence), Redis (cache). Optional: MySQL (metadata), Zookeeper (discovery), Elasticsearch (search)."

**Q: Does Kafka store on disk?**
> "Yes! Sequential I/O is very fast (~600 MB/sec). Uses OS page cache. Messages retained for 7 days (configurable)."

**Q: Why Cassandra over MySQL for messages?**
> "Cassandra scales horizontally, handles write-heavy workloads, and is optimized for time-series data. MySQL is hard to shard and doesn't handle high write throughput as well."

**Q: How does multi-device sync work?**
> "Each device has a cursor stored in Redis: `cursor:{userId}:{deviceId}`. On reconnect, query Cassandra for messages after that cursor."

**Q: How does presence/online status work?**
> "Client sends heartbeat every 5 seconds. Presence Server stores in Redis with 30s TTL. If no heartbeat, TTL expires and user is offline. Friends are notified via Redis Pub/Sub."

**Q: What triggers a push notification?**
> "Delivery consumer checks presence in Redis. If user is offline, it triggers push notification via APNs (iOS) or FCM (Android). Push is just a 'wake up' signal - messages are in Cassandra."

**Q: Does push notification server store messages?**
> "No! It's stateless. Just sends a notification. Messages are already in Cassandra. When user opens app, it pulls messages from Cassandra using device cursor."

**Q: How do you sync across phone and laptop?**
> "Each device has its own cursor in Redis. When device reconnects, query Cassandra for messages after that device's cursor. Both devices can be online simultaneously - message is pushed to both chat servers."

**Q: How do you know which server each device is on?**
> "Redis stores mapping: `user:servers:alice → [chat-server-3, chat-server-5]`. Delivery consumer pushes to ALL servers the user is connected to."

---

## 13. Visual Summary

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                       COMPLETE CHAT SYSTEM ARCHITECTURE                       │
├───────────────────────────────────────────────────────────────────────────────┤
│                                                                               │
│  ┌─────────┐    ┌───────────────┐    ┌──────────────────────┐                │
│  │ User A  │───▶│  Chat Server  │───▶│       KAFKA          │                │
│  │ (Phone) │    │    (WS)       │    │  (Messages Topic)    │                │
│  └─────────┘    └───────┬───────┘    └──────────┬───────────┘                │
│                         │                       │                             │
│                         │              ┌────────┼────────┬────────────┐       │
│                         │              ▼        ▼        ▼            ▼       │
│                         │         ┌────────┐ ┌────────┐ ┌────────┐ ┌──────┐  │
│                         │         │CASSAN- │ │DELIVERY│ │ELASTIC │ │PUSH  │  │
│                         │         │DRA     │ │CONSUMER│ │SEARCH  │ │NOTIF │  │
│                         │         │(Store) │ │        │ │        │ │SERVER│  │
│                         │         └────────┘ └───┬────┘ └────────┘ └──┬───┘  │
│                         │                        │                    │       │
│                         │         ┌──────────────┴──────────────┐     │       │
│                         │         │     Check PRESENCE          │     │       │
│                         │         │     in REDIS                │     │       │
│                         │         └──────────────┬──────────────┘     │       │
│                         │                        │                    │       │
│                         │              ┌─────────┴─────────┐          │       │
│                         │              ▼                   ▼          ▼       │
│                         │         ┌─────────┐         ┌─────────┐  ┌──────┐  │
│                         │         │ ONLINE  │         │ OFFLINE │  │APNs/ │  │
│                         │         │ User B  │         │ User B  │  │ FCM  │  │
│                         │         └────┬────┘         └─────────┘  └──┬───┘  │
│                         │              │                              │       │
│                         │              ▼                              ▼       │
│                         │    ┌────────────────────┐            ┌──────────┐  │
│                         │    │ Chat Server 2 (WS) │            │  Phone   │  │
│                         │    └─────────┬──────────┘            │  (Push)  │  │
│                         │              │                       └──────────┘  │
│                         │    ┌─────────┴─────────┐                           │
│                         │    ▼                   ▼                           │
│                         │ ┌──────┐           ┌────────┐                      │
│                         │ │Phone │           │ Laptop │                      │
│                         │ │User B│           │ User B │                      │
│                         │ └──────┘           └────────┘                      │
│                         │                                                    │
│  ┌──────────────────────┴────────────────────────────────────────────────┐   │
│  │                            REDIS                                       │   │
│  │  • presence:userB → "online"              (TTL 30s)                   │   │
│  │  • cursor:userB:phone → 1000              (per-device sync)           │   │
│  │  • cursor:userB:laptop → 980              (per-device sync)           │   │
│  │  • user:servers:userB → [server-2]        (which chat server)         │   │
│  │  • Pub/Sub: presence:updates              (notify friends)            │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
│  ┌───────────────────────────────────────────────────────────────────────┐   │
│  │                       PRESENCE SERVER                                  │   │
│  │  • Receives heartbeat every 5s from clients                           │   │
│  │  • Updates Redis TTL: EXPIRE presence:userB 30                        │   │
│  │  • On status change: PUBLISH presence:updates "userB:online"          │   │
│  └───────────────────────────────────────────────────────────────────────┘   │
│                                                                               │
└───────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│              SMALL GROUP vs LARGE GROUP                         │
├────────────────────────────┬────────────────────────────────────┤
│  FAN-OUT ON WRITE          │  FAN-OUT ON READ                   │
│  (< 100 members)           │  (100+ members)                    │
├────────────────────────────┼────────────────────────────────────┤
│                            │                                    │
│  Topics:                   │  Topics:                           │
│  • user-inbox-bob          │  • channel-500                     │
│  • user-inbox-charlie      │                                    │
│  • user-inbox-dan          │  Write ONCE to channel             │
│                            │  ALL members READ from it          │
│  Copy to EACH inbox        │                                    │
│                            │                                    │
└────────────────────────────┴────────────────────────────────────┘
```

---

## 14. Scalability Strategies

| Strategy | How |
|----------|-----|
| **Horizontal Scaling** | Add more chat servers behind Service Discovery |
| **Database Sharding** | Shard by user_id (1:1) or channel_id (groups) |
| **Caching** | Recent messages + presence in Redis |
| **Geographic Distribution** | Regional chat servers (US, EU, Asia) |
| **Rate Limiting** | Max messages per minute per user |

---

## 15. Common Failure Scenarios

| Scenario | Solution |
|----------|----------|
| Chat server crashes | Service Discovery detects, users reconnect to healthy server |
| Kafka broker dies | Replicas take over, no message loss |
| Cassandra node dies | Data replicated (RF=3), reads continue |
| Redis fails | Degraded presence, but messages still work |
| User disconnects | Messages in Cassandra, sync on reconnect |

---

*Good luck with your interview! 🎯*
