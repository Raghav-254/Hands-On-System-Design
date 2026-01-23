package com.chatapp;

/**
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║            CHAT SYSTEM - COMPREHENSIVE INTERVIEW GUIDE                       ║
 * ║                    (Senior Engineer Deep-Dive)                               ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║  This file contains in-depth concepts and talking points for system design  ║
 * ║  interviews. Covers architecture, Kafka internals, database choices, and    ║
 * ║  trade-offs that senior engineers are expected to discuss.                  ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 1: REQUIREMENTS GATHERING
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * FUNCTIONAL REQUIREMENTS:
 * - 1:1 chat vs group chat vs both?
 * - What's the max group size?
 * - Text only or media (images, videos)?
 * - Message history/persistence needed?
 * - Read receipts, typing indicators?
 * - Online presence needed?
 * - Push notifications for offline users?
 *
 * NON-FUNCTIONAL REQUIREMENTS:
 * - Scale: How many DAU? (e.g., 50M DAU)
 * - Messages per day? (e.g., 100M messages/day)
 * - Message latency requirement? (e.g., < 100ms)
 * - Availability target? (e.g., 99.99%)
 *
 * QUICK ESTIMATES (for 50M DAU):
 * - 50M * 40 messages/day = 2B messages/day
 * - 2B / 86400 seconds = ~23K messages/second
 * - If avg message = 100 bytes: 200GB/day storage
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 2: HIGH-LEVEL ARCHITECTURE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * STATELESS SERVICES (scale horizontally):
 * - API Servers: Login, user profile, group management
 * - Notification Servers: Push notifications
 *
 * STATEFUL SERVICES (require service discovery):
 * - Chat Servers: WebSocket connections, real-time messaging
 * - Presence Servers: Online/offline status
 *
 * WHY WEBSOCKET FOR CHAT?
 * - Full-duplex, persistent connection
 * - Server can push to client (no polling)
 * - Low latency for real-time messaging
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 3: MESSAGE FLOW - TWO APPROACHES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  APPROACH 1: DUAL WRITE (WhatsApp style)                                    │
 * │  ───────────────────────────────────────                                    │
 * │                                                                              │
 * │  Chat Server does TWO writes:                                               │
 * │  1. Write to KV Store (Cassandra) - for persistence                         │
 * │  2. Write to Message Queue (Kafka) - for delivery                           │
 * │                                                                              │
 * │  User A → Chat Server 1 ─┬─→ Cassandra (direct write)                       │
 * │                          └─→ Kafka → Chat Server 2 → User B                 │
 * │                                                                              │
 * │  • Queue has ONE consumer (delivery only)                                   │
 * │  • Cassandra write is NOT via consumer                                      │
 * │  • Simpler, but need to handle partial failures                             │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  APPROACH 2: KAFKA AS SOURCE OF TRUTH (LinkedIn/Discord style)              │
 * │  ─────────────────────────────────────────────────────────────              │
 * │                                                                              │
 * │  Chat Server does ONE write to Kafka only:                                  │
 * │                                                                              │
 * │  User A → Chat Server 1 → Kafka ─┬─→ Consumer 1 → Cassandra (persistence)   │
 * │                                  ├─→ Consumer 2 → Chat Server 2 → User B    │
 * │                                  └─→ Consumer 3 → Elasticsearch (search)    │
 * │                                                                              │
 * │  • MULTIPLE independent consumer groups                                     │
 * │  • Each group has its own offset                                            │
 * │  • More scalable, easier to add new consumers                               │
 * │  • Kafka guarantees ordering and durability                                 │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 4: KAFKA DEEP-DIVE
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  KAFKA STORAGE                                                              │
 * │  ─────────────                                                              │
 * │                                                                              │
 * │  • Kafka stores ALL messages on DISK (not memory!)                          │
 * │  • Uses sequential I/O (very fast: ~600 MB/sec)                             │
 * │  • Messages kept for retention period (default 7 days)                      │
 * │  • Messages NOT deleted after consumption (unlike RabbitMQ)                 │
 * │  • Each consumer group has its own offset (can replay!)                     │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  KAFKA TOPICS AND PARTITIONS                                                │
 * │  ───────────────────────────                                                │
 * │                                                                              │
 * │  TOPIC = Separate queue (e.g., "user-inbox-123", "channel-500")             │
 * │  PARTITION = Sub-queue within a topic (for parallelism)                     │
 * │                                                                              │
 * │  For chat, partition by RECIPIENT user_id:                                  │
 * │  • All messages TO same user → same partition → ORDERED                     │
 * │  • partition = hash(recipient_id) % num_partitions                          │
 * │                                                                              │
 * │  Example: 1000 partitions                                                   │
 * │  • User 42's messages → partition hash(42) % 1000 = 42                      │
 * │  • Guarantees message ordering for User 42                                  │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  KAFKA CONSUMPTION: PUSH vs PULL                                            │
 * │  ───────────────────────────────                                            │
 * │                                                                              │
 * │  Kafka uses LONG POLLING (feels like push):                                 │
 * │                                                                              │
 * │  ConsumerRecords records = consumer.poll(Duration.ofSeconds(1));            │
 * │                                                                              │
 * │  What poll(1 second) means:                                                 │
 * │  • Wait UP TO 1 second for messages                                         │
 * │  • If messages arrive in 50ms → returns immediately with data              │
 * │  • If no messages after 1 sec → returns empty (timeout)                    │
 * │  • NOT periodic polling - it BLOCKS until data or timeout                   │
 * │                                                                              │
 * │  Batching configs:                                                          │
 * │  • fetch.min.bytes = 1KB (wait for at least 1KB before returning)          │
 * │  • fetch.max.wait.ms = 500 (max wait on broker side)                       │
 * │                                                                              │
 * │  For chat (low latency): fetch.min.bytes = 1, fetch.max.wait.ms = 100      │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  KAFKA CONSUMER GROUPS                                                      │
 * │  ─────────────────────                                                      │
 * │                                                                              │
 * │  SAME GROUP: Competing consumers (load balancing)                           │
 * │  • Only ONE consumer in the group gets each message                         │
 * │  • Work is divided among consumers                                          │
 * │  • If consumer dies → Kafka rebalances partitions to others                │
 * │                                                                              │
 * │  DIFFERENT GROUPS: Independent processing (pub/sub)                         │
 * │  • ALL groups get every message                                             │
 * │  • Each group has its own offset                                            │
 * │  • Group A consuming doesn't affect Group B                                 │
 * │                                                                              │
 * │  Chat system example:                                                       │
 * │  • persistence-group → writes to Cassandra                                  │
 * │  • delivery-group → pushes to WebSocket                                     │
 * │  • search-group → indexes in Elasticsearch                                  │
 * │  All 3 groups get every message independently!                              │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 5: FAN-OUT ON WRITE vs FAN-OUT ON READ
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  FAN-OUT ON WRITE (Small Groups < 100 members)                              │
 * │  ─────────────────────────────────────────────                              │
 * │                                                                              │
 * │  Kafka Topics: user-inbox-1, user-inbox-2, user-inbox-3, ...                │
 * │                                                                              │
 * │  When Alice sends to group [Bob, Charlie, Dan]:                             │
 * │  • Write to user-inbox-bob                                                  │
 * │  • Write to user-inbox-charlie                                              │
 * │  • Write to user-inbox-dan                                                  │
 * │                                                                              │
 * │  WRITES: N (one per member)                                                 │
 * │  READS: 1 (each member reads own inbox)                                     │
 * │                                                                              │
 * │  ✓ Simple reads, instant delivery                                          │
 * │  ✗ Write amplification (N copies)                                          │
 * │                                                                              │
 * │  Use for: WhatsApp groups, Slack DMs, friend groups                        │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  FAN-OUT ON READ (Large Groups 100+ members)                                │
 * │  ───────────────────────────────────────────                                │
 * │                                                                              │
 * │  Kafka Topics: channel-500, channel-501, channel-502, ...                   │
 * │                                                                              │
 * │  When CEO sends to company channel (10,000 members):                        │
 * │  • Write ONCE to channel-500                                                │
 * │  • All members READ from channel-500                                        │
 * │                                                                              │
 * │  WRITES: 1 (single write to channel)                                        │
 * │  READS: N (each member reads from channel)                                  │
 * │                                                                              │
 * │  ✓ Efficient writes (no amplification)                                     │
 * │  ✗ Slightly more complex reads                                             │
 * │                                                                              │
 * │  Use for: Discord servers, Telegram channels, announcements                │
 * │                                                                              │
 * │  NOTE: Storage is in CHANNEL table (not sender's table!)                   │
 * │  • Cassandra: channel_messages (partition key: channel_id)                 │
 * │  • All members query same table                                             │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 6: OFFLINE USER FLOW
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  WHEN RECIPIENT IS OFFLINE                                                  │
 * │  ─────────────────────────                                                  │
 * │                                                                              │
 * │  1. Message written to Kafka                                                │
 * │  2. Consumer 1 (persistence) → writes to Cassandra ✓                       │
 * │  3. Consumer 2 (delivery) → checks presence → user offline!               │
 * │  4. Consumer 2 → triggers Push Notification (APNs/FCM)                     │
 * │                                                                              │
 * │  Push Notification stores NOTHING (just a "wake up" signal)                │
 * │  Message is safely stored in Cassandra                                      │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  WHEN USER COMES BACK ONLINE                                                │
 * │  ───────────────────────────                                                │
 * │                                                                              │
 * │  HYBRID APPROACH (Pull + Push):                                             │
 * │                                                                              │
 * │  Step 1: PULL from Cassandra (sync missed messages)                         │
 * │  • Get device cursor from Redis: cursor:{userId}:{deviceId}                │
 * │  • Query: SELECT * FROM messages WHERE user_id = X AND msg_id > cursor     │
 * │  • Send all missed messages via WebSocket                                   │
 * │  • NO QUEUE involved - direct database read!                               │
 * │                                                                              │
 * │  Step 2: PUSH via Kafka (real-time going forward)                          │
 * │  • Subscribe to Kafka topic for this user                                   │
 * │  • New messages pushed automatically                                        │
 * │                                                                              │
 * │  This ensures:                                                              │
 * │  ✓ No missed messages (pull from Cassandra)                                │
 * │  ✓ Real-time delivery (push via Kafka)                                     │
 * │  ✓ Multi-device sync (per-device cursors)                                  │
 * │                                                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 7: DATABASE SUMMARY
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  1. KAFKA (Message Queue)                                                   │
 * │     Type: Distributed Log                                                   │
 * │     Storage: Disk (7 days retention)                                        │
 * │     Stores: Messages in transit, routing to consumers                       │
 * │     Purpose: Real-time delivery, decoupling, traffic spikes               │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  2. CASSANDRA (KV Store)                                                    │
 * │     Type: Wide-Column NoSQL                                                 │
 * │     Storage: Disk (permanent)                                               │
 * │     Stores: All messages (forever), message history                        │
 * │     Tables:                                                                 │
 * │     • user_messages (partition: user_id) - for fan-out on write           │
 * │     • channel_messages (partition: channel_id) - for fan-out on read      │
 * │     Purpose: Persistence, history, offline sync                            │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  3. REDIS (Cache)                                                           │
 * │     Type: In-Memory KV Store                                                │
 * │     Storage: Memory (with TTL)                                              │
 * │     Stores:                                                                 │
 * │     • presence:{userId} → online/offline                                   │
 * │     • cursor:{userId}:{deviceId} → last_seen_message_id                   │
 * │     • session:{token} → user session data                                  │
 * │     • Pub/Sub for presence updates                                         │
 * │     Purpose: Fast lookups, presence, multi-device sync                     │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  4. MYSQL (Relational) - Optional                                           │
 * │     Stores: User profiles, group metadata, friendships                      │
 * │     NOT for messages (doesn't scale horizontally)                           │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  5. ZOOKEEPER (Coordination) - Optional                                     │
 * │     Stores: Chat server registry, health status                             │
 * │     Purpose: Service discovery                                              │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * ┌──────────────────────────────────────────────────────────────────────────────┐
 * │  6. ELASTICSEARCH (Search) - Optional                                       │
 * │     Stores: Message content index                                           │
 * │     Purpose: Full-text search in messages                                   │
 * └──────────────────────────────────────────────────────────────────────────────┘
 *
 * MINIMUM REQUIRED: Kafka + Cassandra + Redis (3 core databases)
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 8: SNOWFLAKE ID GENERATION
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * STRUCTURE (64 bits):
 * | 1 bit | 41 bits    | 5 bits  | 5 bits   | 12 bits  |
 * | sign  | timestamp  | DC ID   | machine  | sequence |
 *
 * WHY SNOWFLAKE?
 * ✓ Time-sortable (critical for message ordering)
 * ✓ Globally unique (no coordination needed)
 * ✓ Decentralized generation (~4M IDs/sec per machine)
 * ✓ 64-bit (efficient storage)
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 9: COMMON INTERVIEW QUESTIONS
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Q: "How do you ensure message ordering?"
 * A: Partition by recipient_id. All messages to same user go to same Kafka
 *    partition, which is ordered. Snowflake IDs are time-sortable.
 *
 * Q: "Push or pull for message delivery?"
 * A: Both! Kafka uses long-polling (feels like push). poll() blocks until
 *    data arrives or timeout. For offline sync, pull from Cassandra.
 *
 * Q: "What if consumer is slow/busy?"
 * A: Messages wait in Kafka (stored on disk). If consumer dies, Kafka
 *    rebalances partitions to other consumers. Messages never lost.
 *
 * Q: "Fan-out on write vs read?"
 * A: Write for small groups (<100) - copy to each inbox.
 *    Read for large groups (100+) - single copy in channel, members read.
 *    Twitter uses both: regular users = write, celebrities = read.
 *
 * Q: "How many databases?"
 * A: 3 core: Kafka (queue), Cassandra (persistence), Redis (cache/presence).
 *    Optional: MySQL (metadata), Zookeeper (discovery), Elasticsearch (search).
 *
 * Q: "Where does offline user get messages from?"
 * A: Cassandra! Not from queue. Pull directly from DB using device cursor.
 *    Then subscribe to Kafka for real-time going forward.
 *
 *
 * ════════════════════════════════════════════════════════════════════════════════
 * SECTION 10: QUICK REFERENCE - INTERVIEW ANSWERS
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * MESSAGE SEND FLOW:
 * "Chat Server writes to Kafka, which fans out to consumers for persistence
 *  (Cassandra) and delivery (WebSocket). If user offline, push notification
 *  is triggered. User syncs from Cassandra when back online."
 *
 * KAFKA CONSUMPTION:
 * "Kafka uses long-polling - consumer.poll() blocks until data arrives or
 *  timeout. It's not periodic polling. Latency is ~10-50ms, feels like push."
 *
 * MULTIPLE CONSUMERS:
 * "Different consumer groups process independently. Persistence group writes
 *  to Cassandra, delivery group pushes to WebSocket, search group indexes
 *  in Elasticsearch. All get every message."
 *
 * FAN-OUT STRATEGY:
 * "Small groups use fan-out on write with per-user inbox topics.
 *  Large groups use fan-out on read with shared channel topics.
 *  Threshold is typically 100 members."
 *
 * OFFLINE SYNC:
 * "Direct read from Cassandra using device cursor (last_seen_message_id).
 *  No queue involved for sync. Then subscribe to Kafka for real-time."
 *
 * ════════════════════════════════════════════════════════════════════════════════
 */
public class InterviewNotes {
    
    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       CHAT SYSTEM - INTERVIEW PREPARATION GUIDE             ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Read the JavaDoc comments in this file for comprehensive notes!");
        System.out.println();
        System.out.println("Key sections covered:");
        System.out.println("  1. Requirements Gathering");
        System.out.println("  2. High-Level Architecture");
        System.out.println("  3. Message Flow (Dual Write vs Kafka Source of Truth)");
        System.out.println("  4. Kafka Deep-Dive (Storage, Topics, Consumption)");
        System.out.println("  5. Fan-Out on Write vs Read");
        System.out.println("  6. Offline User Flow");
        System.out.println("  7. Database Summary (Kafka, Cassandra, Redis)");
        System.out.println("  8. Snowflake ID Generation");
        System.out.println("  9. Common Interview Questions");
        System.out.println(" 10. Quick Reference Answers");
        System.out.println();
        System.out.println("Run ChatSystemDemo.java for hands-on code demonstration!");
        System.out.println("Run MessageFlowDemo.java to see message flow comparison!");
    }
}
