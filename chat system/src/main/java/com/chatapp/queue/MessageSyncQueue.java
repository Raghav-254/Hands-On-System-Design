package com.chatapp.queue;

import com.chatapp.models.Message;
import com.chatapp.models.GroupMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Message Sync Queue - Critical component for message delivery.
 * 
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║  REAL-WORLD MESSAGE QUEUE CONSUMPTION PATTERNS                               ║
 * ╠══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                              ║
 * ║  There are TWO main patterns for consuming messages from a queue:           ║
 * ║                                                                              ║
 * ║  1. PUSH-BASED (Pub/Sub) - Automatic notification                           ║
 * ║     ─────────────────────────────────────────────────                        ║
 * ║     - Consumer SUBSCRIBES to a topic/channel                                 ║
 * ║     - Broker PUSHES new messages to all subscribers immediately             ║
 * ║     - Very low latency (< 10ms typically)                                   ║
 * ║     - Consumer must be connected to receive                                  ║
 * ║                                                                              ║
 * ║     Used by: Redis Pub/Sub, NATS, WebSocket                                 ║
 * ║     Best for: Real-time chat, online users                                  ║
 * ║                                                                              ║
 * ║     Example in this code:                                                   ║
 * ║       messageQueue.subscribeUser(userId, msg -> handleMessage(msg));        ║
 * ║       // Handler called automatically when message arrives                  ║
 * ║                                                                              ║
 * ║  2. PULL-BASED (Long Polling) - Consumer fetches                            ║
 * ║     ─────────────────────────────────────────────────                        ║
 * ║     - Consumer POLLS the queue for new messages                             ║
 * ║     - Request blocks until data available (long polling)                    ║
 * ║     - Consumer controls consumption rate (backpressure)                     ║
 * ║     - Messages persist in queue until consumed                              ║
 * ║                                                                              ║
 * ║     Used by: Kafka, AWS SQS, RabbitMQ                                       ║
 * ║     Best for: Offline sync, guaranteed delivery, batch processing          ║
 * ║                                                                              ║
 * ║     Example in this code:                                                   ║
 * ║       Message msg = messageQueue.pollDirectMessage(userId);  // Blocking    ║
 * ║       List<Message> msgs = messageQueue.drainUserQueue(userId); // Batch    ║
 * ║                                                                              ║
 * ║  REAL CHAT SYSTEMS USE BOTH:                                                ║
 * ║  ─────────────────────────────                                              ║
 * ║  - Kafka for reliable queueing and persistence                              ║
 * ║  - Redis Pub/Sub or WebSocket for real-time push to connected clients       ║
 * ║                                                                              ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 * 
 * MESSAGE FLOW IN PRODUCTION:
 * 
 * 1. User A sends message
 * 2. Chat Server writes to Kafka (partition by recipient_id)
 * 3. TWO parallel paths:
 *    
 *    Path A - PERSISTENCE (Pull-based consumer):
 *    ┌─────────┐     ┌─────────────┐     ┌───────────┐
 *    │  Kafka  │────▶│  Consumer   │────▶│ KV Store  │
 *    │ Topic   │     │  (Worker)   │     │(Cassandra)│
 *    └─────────┘     └─────────────┘     └───────────┘
 *    Consumer polls Kafka, writes to DB, commits offset
 *    
 *    Path B - REAL-TIME DELIVERY (Push-based):
 *    ┌─────────┐     ┌─────────────┐     ┌───────────┐
 *    │  Kafka  │────▶│ Chat Server │────▶│  User B   │
 *    │ Topic   │     │ (Subscribed)│     │(WebSocket)│
 *    └─────────┘     └─────────────┘     └───────────┘
 *    Chat server subscribes to Kafka, pushes via WebSocket
 */
public class MessageSyncQueue {
    
    // ══════════════════════════════════════════════════════════════════════════
    // PULL-BASED QUEUES (Like Kafka partitions)
    // Messages persist here until consumed
    // ══════════════════════════════════════════════════════════════════════════
    
    // Per-user queues for direct message delivery (simulates Kafka partitions)
    private final Map<Long, BlockingQueue<Message>> userQueues;
    
    // Per-channel queues for group messages
    private final Map<Long, BlockingQueue<GroupMessage>> channelQueues;
    
    // ══════════════════════════════════════════════════════════════════════════
    // PUSH-BASED SUBSCRIBERS (Like Redis Pub/Sub)
    // Get notified immediately when message arrives
    // ══════════════════════════════════════════════════════════════════════════
    
    // Subscribers for real-time message delivery
    private final Map<Long, List<Consumer<Message>>> userSubscribers;
    private final Map<Long, List<Consumer<GroupMessage>>> channelSubscribers;
    
    // Thread pool for async message processing
    private final ExecutorService pushExecutor;  // For push notifications
    private final ExecutorService pullExecutor;  // For pull-based consumers
    
    public MessageSyncQueue() {
        this.userQueues = new ConcurrentHashMap<>();
        this.channelQueues = new ConcurrentHashMap<>();
        this.userSubscribers = new ConcurrentHashMap<>();
        this.channelSubscribers = new ConcurrentHashMap<>();
        this.pushExecutor = Executors.newFixedThreadPool(10);
        this.pullExecutor = Executors.newFixedThreadPool(5);
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // PRODUCER SIDE: Enqueue messages
    // In production: This would be Kafka producer.send()
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Enqueue a direct message for delivery to recipient.
     * This simulates: kafkaProducer.send(new ProducerRecord("user-inbox-" + recipientId, message))
     * 
     * TWO THINGS HAPPEN:
     * 1. Message added to queue (for persistence/reliable delivery)
     * 2. Push subscribers notified immediately (for real-time delivery)
     */
    public void enqueueDirectMessage(Message message) {
        long recipientId = message.getMessageTo();
        
        // ────────────────────────────────────────────────────────────────────
        // STEP 1: Add to persistent queue (Pull-based consumers will read this)
        // In production: Kafka writes to partition
        // ────────────────────────────────────────────────────────────────────
        userQueues.computeIfAbsent(recipientId, k -> new LinkedBlockingQueue<>());
        userQueues.get(recipientId).offer(message);
        
        System.out.printf("[MessageQueue] ENQUEUED message %d for user %d (queue size: %d)%n", 
            message.getMessageId(), recipientId, userQueues.get(recipientId).size());
        
        // ────────────────────────────────────────────────────────────────────
        // STEP 2: Notify push subscribers immediately (Real-time delivery)
        // In production: Kafka consumer group receives, pushes via WebSocket
        // ────────────────────────────────────────────────────────────────────
        notifyUserSubscribers(recipientId, message);
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // PATTERN 1: PUSH-BASED CONSUMPTION (Pub/Sub)
    // Consumer subscribes, gets notified automatically
    // Best for: Real-time delivery to online users
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Subscribe to real-time messages for a user.
     * 
     * This is the PUSH-BASED pattern:
     * - Consumer registers a callback
     * - When message arrives, callback is invoked AUTOMATICALLY
     * - No polling needed
     * 
     * In production (Kafka): Consumer joins consumer group, Kafka pushes new records
     * In production (Redis): SUBSCRIBE to channel, Redis pushes to subscribers
     * 
     * @param userId The user to receive messages for
     * @param callback Called automatically when message arrives
     */
    public void subscribeUser(long userId, Consumer<Message> callback) {
        userSubscribers.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        userSubscribers.get(userId).add(callback);
        System.out.printf("[MessageQueue] User %d SUBSCRIBED (push-based, auto-notification)%n", userId);
    }
    
    /**
     * Unsubscribe from user messages.
     */
    public void unsubscribeUser(long userId, Consumer<Message> callback) {
        List<Consumer<Message>> subs = userSubscribers.get(userId);
        if (subs != null) {
            subs.remove(callback);
        }
    }
    
    /**
     * Internal: Notify all push subscribers immediately.
     * This simulates the broker pushing to connected consumers.
     */
    private void notifyUserSubscribers(long userId, Message message) {
        List<Consumer<Message>> subs = userSubscribers.get(userId);
        if (subs != null && !subs.isEmpty()) {
            // Async push to all subscribers
            pushExecutor.submit(() -> {
                System.out.printf("[MessageQueue] PUSH notification to %d subscriber(s) for user %d%n", 
                    subs.size(), userId);
                for (Consumer<Message> sub : subs) {
                    try {
                        sub.accept(message);  // Callback invoked!
                    } catch (Exception e) {
                        System.err.printf("[MessageQueue] Error in push subscriber: %s%n", e.getMessage());
                    }
                }
            });
        } else {
            System.out.printf("[MessageQueue] No subscribers for user %d (will wait in queue for pull)%n", userId);
        }
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // PATTERN 2: PULL-BASED CONSUMPTION (Polling/Long-Polling)
    // Consumer fetches messages when ready
    // Best for: Offline sync, batch processing, backpressure control
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Poll for a single message (BLOCKING).
     * 
     * This is LONG-POLLING pattern:
     * - Consumer calls poll(), request BLOCKS until message available
     * - Returns immediately if message exists
     * - Lower latency than periodic polling, more efficient
     * 
     * In production (Kafka): consumer.poll(Duration.ofSeconds(30))
     * In production (SQS): receiveMessage with WaitTimeSeconds
     * 
     * @param userId The user's inbox to poll
     * @param timeout How long to wait for a message
     * @return Message if available, null if timeout
     */
    public Message pollDirectMessageBlocking(long userId, long timeout, TimeUnit unit) 
            throws InterruptedException {
        BlockingQueue<Message> queue = userQueues.get(userId);
        if (queue == null) {
            return null;
        }
        
        System.out.printf("[MessageQueue] User %d POLLING (blocking, waiting for message)...%n", userId);
        Message message = queue.poll(timeout, unit);  // BLOCKS until message or timeout
        
        if (message != null) {
            System.out.printf("[MessageQueue] User %d POLL returned message %d%n", 
                userId, message.getMessageId());
        } else {
            System.out.printf("[MessageQueue] User %d POLL timed out (no messages)%n", userId);
        }
        return message;
    }
    
    /**
     * Poll for a single message (NON-BLOCKING).
     * Returns immediately with message or null.
     */
    public Message pollDirectMessage(long userId) {
        BlockingQueue<Message> queue = userQueues.get(userId);
        if (queue != null) {
            return queue.poll();  // Returns immediately
        }
        return null;
    }
    
    /**
     * Drain all pending messages for a user (BATCH PULL).
     * 
     * This is used when:
     * - User comes online after being offline
     * - Device reconnects and needs to sync
     * - Batch processing for efficiency
     * 
     * In production (Kafka): consumer.poll() returns batch of records
     * 
     * @param userId The user's inbox to drain
     * @return All pending messages (empty list if none)
     */
    public List<Message> drainUserQueue(long userId) {
        BlockingQueue<Message> queue = userQueues.get(userId);
        List<Message> messages = new ArrayList<>();
        if (queue != null) {
            queue.drainTo(messages);
            System.out.printf("[MessageQueue] DRAINED %d messages for user %d%n", 
                messages.size(), userId);
        }
        return messages;
    }
    
    /**
     * Start a background consumer that pulls messages continuously.
     * 
     * This simulates a Kafka consumer loop:
     * while (true) {
     *     records = consumer.poll(Duration.ofSeconds(1));
     *     for (record : records) {
     *         process(record);
     *     }
     *     consumer.commitSync();
     * }
     */
    public void startPullConsumer(long userId, Consumer<Message> handler) {
        pullExecutor.submit(() -> {
            System.out.printf("[MessageQueue] Started PULL consumer for user %d%n", userId);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Message msg = pollDirectMessageBlocking(userId, 5, TimeUnit.SECONDS);
                    if (msg != null) {
                        handler.accept(msg);
                        // In Kafka: consumer.commitSync() here
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // GROUP MESSAGE HANDLING
    // ══════════════════════════════════════════════════════════════════════════
    
    /**
     * Enqueue a group message.
     * For small groups: creates per-user queues (fan-out on write)
     * For large groups: uses shared channel queue (fan-out on read)
     */
    public void enqueueGroupMessage(GroupMessage message, List<Long> memberIds, boolean isLargeGroup) {
        if (isLargeGroup) {
            enqueueToChannelQueue(message);
        } else {
            fanOutToMembers(message, memberIds);
        }
    }
    
    private void enqueueToChannelQueue(GroupMessage message) {
        long channelId = message.getChannelId();
        channelQueues.computeIfAbsent(channelId, k -> new LinkedBlockingQueue<>());
        channelQueues.get(channelId).offer(message);
        
        System.out.printf("[MessageQueue] Enqueued group message %d to channel %d queue%n",
            message.getMessageId(), channelId);
        
        notifyChannelSubscribers(channelId, message);
    }
    
    private void fanOutToMembers(GroupMessage message, List<Long> memberIds) {
        for (Long memberId : memberIds) {
            if (memberId != message.getUserId()) {
                System.out.printf("[MessageQueue] Fan-out group message %d to user %d%n",
                    message.getMessageId(), memberId);
                notifyChannelSubscribers(message.getChannelId(), message);
            }
        }
    }
    
    public void subscribeChannel(long channelId, Consumer<GroupMessage> callback) {
        channelSubscribers.computeIfAbsent(channelId, k -> new CopyOnWriteArrayList<>());
        channelSubscribers.get(channelId).add(callback);
        System.out.printf("[MessageQueue] Subscribed to channel %d (push-based)%n", channelId);
    }
    
    private void notifyChannelSubscribers(long channelId, GroupMessage message) {
        List<Consumer<GroupMessage>> subs = channelSubscribers.get(channelId);
        if (subs != null && !subs.isEmpty()) {
            pushExecutor.submit(() -> {
                for (Consumer<GroupMessage> sub : subs) {
                    try {
                        sub.accept(message);
                    } catch (Exception e) {
                        System.err.printf("[MessageQueue] Error notifying channel subscriber: %s%n", e.getMessage());
                    }
                }
            });
        }
    }
    
    // ══════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ══════════════════════════════════════════════════════════════════════════
    
    public int getUserQueueSize(long userId) {
        BlockingQueue<Message> queue = userQueues.get(userId);
        return queue != null ? queue.size() : 0;
    }
    
    public int getChannelQueueSize(long channelId) {
        BlockingQueue<GroupMessage> queue = channelQueues.get(channelId);
        return queue != null ? queue.size() : 0;
    }
    
    public int getSubscriberCount(long userId) {
        List<Consumer<Message>> subs = userSubscribers.get(userId);
        return subs != null ? subs.size() : 0;
    }
    
    public void shutdown() {
        pushExecutor.shutdown();
        pullExecutor.shutdown();
    }
}
