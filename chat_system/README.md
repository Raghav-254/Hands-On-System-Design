# Chat System - Hands-on System Design

This is a hands-on implementation of the chat system from **Alex Xu's System Design Interview** book (Chapter 12). The code demonstrates all critical flows you should understand for system design interviews.

## 🎯 Purpose

This implementation is designed to help you:
- **Understand the architecture** through working code
- **Practice explaining flows** in interviews
- **Get hands-on experience** with distributed system concepts

## 📐 Architecture Overview

```
┌─────────────┐         ┌─────────────────────────────────────┐
│    User     │◄───ws───┤     Real-time Service               │
│  (Client)   │         │  ┌─────────────┐ ┌───────────────┐  │
└──────┬──────┘         │  │Chat Servers │ │Presence Server│  │
       │                │  └──────┬──────┘ └───────┬───────┘  │
       │http            │         │                │          │
       ▼                └─────────┼────────────────┼──────────┘
┌─────────────┐                   │                │
│    Load     │                   ▼                ▼
│  Balancer   │          ┌────────────────────────────────────┐
└──────┬──────┘          │        Message Sync Queue          │
       │                 │           (Kafka/Redis)            │
       ▼                 └────────────────┬───────────────────┘
┌─────────────┐                           │
│    API      │                           ▼
│   Servers   │◄─────────────────►┌───────────────┐
└──────┬──────┘                   │   KV Store    │
       │                          │ (Cassandra)   │
       ▼                          └───────────────┘
┌─────────────┐
│  Service    │
│ Discovery   │
│ (Zookeeper) │
└─────────────┘
```

## 📁 Project Structure

```
src/main/java/com/chatapp/
├── ChatSystemDemo.java          # Main demo - run this!
├── models/
│   ├── User.java               # User model
│   ├── Message.java            # 1:1 message model
│   ├── GroupMessage.java       # Group message model
│   └── Channel.java            # Group/channel model
├── idgen/
│   └── SnowflakeIdGenerator.java  # Unique ID generation
├── storage/
│   ├── KVStore.java            # KV store interface
│   ├── InMemoryKVStore.java    # In-memory implementation
│   └── MessageStore.java       # Message storage layer
├── queue/
│   └── MessageSyncQueue.java   # Message delivery queue
├── discovery/
│   └── ServiceDiscovery.java   # Zookeeper simulation
├── server/
│   └── ChatServer.java         # WebSocket chat server
├── presence/
│   └── PresenceService.java    # Online/offline tracking
├── notification/
│   └── PushNotificationService.java  # Push notifications
└── api/
    └── ApiServer.java          # REST API server
```

## 🚀 Running the Demo

### Option 1: Using Maven
```bash
cd chat_system
mvn compile exec:java
```

### Option 2: Using Java directly
```bash
cd chat_system
javac -d target/classes src/main/java/com/chatapp/**/*.java
java -cp target/classes com.chatapp.ChatSystemDemo
```

### Option 3: Using an IDE
Open the project in IntelliJ IDEA or Eclipse and run `ChatSystemDemo.java`

## 📚 Key Flows Demonstrated

### 1. Service Discovery (Figure 12-11)
```
User Login → API Server → Service Discovery → Assigns Chat Server → User connects via WebSocket
```

**Interview talking points:**
- Zookeeper maintains list of available chat servers
- Load balancing based on server capacity
- Consistent user-to-server mapping

### 2. 1:1 Messaging (Figure 12-12)
```
User A → Chat Server 1 → ID Generator → Message Queue → KV Store
                                              │
                              ┌───────────────┴───────────────┐
                              ▼                               ▼
                         (if online)                     (if offline)
                      Chat Server 2 → User B         Push Notification
```

**Interview talking points:**
- Snowflake IDs for sortable, unique message IDs
- Message queue for reliable delivery
- Separate paths for online/offline users

### 3. Multi-device Sync (Figure 12-13)
```
Each device maintains: cur_max_message_id
When reconnecting: fetch messages where id > cur_max_message_id
```

**Interview talking points:**
- Per-device cursors track sync state
- Pull-based sync on reconnection
- Push-based delivery when connected

### 4. Small Group Chat (Figure 12-14) - Fan-out on Write
```
User A sends → Create copy for each member → Each member has own queue
```

**Interview talking points:**
- Good for small groups (< 100 members)
- Each member gets their own message copy
- Simple but write-amplification issue

### 5. Large Group Chat (Figure 12-15) - Fan-out on Read
```
User A sends → Single message to channel queue → Members pull from shared queue
```

**Interview talking points:**
- Good for large groups (100+ members)
- Single write, multiple reads
- More complex but efficient

### 6. Presence (Figure 12-16)
```
User A status change → Presence Server → Pub/Sub channels → Notify subscribers
```

**Interview talking points:**
- Heartbeat mechanism for online detection
- Pub/Sub for efficient status distribution
- Only notify friends/relevant users

## 🔑 Key Design Decisions

### Why WebSocket for chat?
- Bidirectional, persistent connection
- Low latency for real-time messages
- Server can push messages to clients

### Why Snowflake IDs?
- Globally unique without coordination
- Time-sortable (important for message ordering)
- 64-bit fits in most databases efficiently

### Why KV Store (Cassandra)?
- High write throughput
- Easy horizontal scaling
- Good for time-series data (messages)

### Why Message Queue (Kafka)?
- Decouples sender from receiver
- Handles traffic spikes
- Enables reliable delivery

## 📋 Interview Cheatsheet

**👉 See [`INTERVIEW_CHEATSHEET.md`](./INTERVIEW_CHEATSHEET.md) for a comprehensive reference!**

The cheatsheet covers:
- Message flow (Dual Write vs Kafka as Source of Truth)
- Kafka deep-dive (storage, topics, partitions, consumer groups)
- Fan-out strategies (write vs read)
- Presence Server (online/offline with heartbeat)
- Push Notification Server flow
- Multi-device sync (per-device cursors)
- Complete database summary with data models (Kafka, Cassandra, Redis)
- Ready-to-use interview answers

---

## 💡 Interview Tips

1. **Start with requirements**: "How many users? Message types? Real-time needed?"

2. **Draw the high-level diagram first**: Client → Load Balancer → Services → Storage

3. **Dive deep on asked areas**: Be ready to explain any component in detail

4. **Discuss trade-offs**:
   - Fan-out on write vs read
   - Push vs pull for sync
   - Consistency vs availability

5. **Mention scalability strategies**:
   - Horizontal scaling of chat servers
   - Database sharding by user_id
   - Geographic distribution

## 🔧 Extending the Demo

Ideas for further exploration:
- Add actual WebSocket implementation (Netty/Spring)
- Implement message encryption
- Add read receipts
- Implement typing indicators
- Add media message support
- Implement message search

## 📖 References

- Alex Xu's "System Design Interview" - Chapter 12
- Discord Engineering Blog
- WhatsApp Architecture Papers
- Slack Engineering Blog

