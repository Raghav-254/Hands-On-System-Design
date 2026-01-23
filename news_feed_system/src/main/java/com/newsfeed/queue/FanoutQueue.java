package com.newsfeed.queue;

import com.newsfeed.models.Post;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * FanoutQueue - Kafka-like Message Queue for event-driven fanout.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  EVENT-DRIVEN ARCHITECTURE (Production Pattern)                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  TOPIC: "post-events"                                                        ║
 * ║                                                                               ║
 * ║  ┌────────────────┐                                                          ║
 * ║  │  Post Service  │ ─── publish("PostCreated", post) ───▶ │                  ║
 * ║  │  (Producer)    │                                       │                  ║
 * ║  └────────────────┘                                       │                  ║
 * ║                                                           ▼                  ║
 * ║                                            ┌───────────────────────────────┐ ║
 * ║                                            │          KAFKA                │ ║
 * ║                                            │   Topic: "post-events"        │ ║
 * ║                                            │   ┌────┐ ┌────┐ ┌────┐       │ ║
 * ║                                            │   │ P1 │ │ P2 │ │ P3 │       │ ║
 * ║                                            │   └────┘ └────┘ └────┘       │ ║
 * ║                                            └───────────────────────────────┘ ║
 * ║                                                           │                  ║
 * ║  ┌────────────────┐                                       │                  ║
 * ║  │ Fanout Service │ ◀── consume() ────────────────────────┘                  ║
 * ║  │ (Consumer)     │                                                          ║
 * ║  └────────────────┘                                                          ║
 * ║          │                                                                   ║
 * ║          ▼                                                                   ║
 * ║  ┌────────────────────────────────────────────────────────────────────────┐  ║
 * ║  │  For each post:                                                        │  ║
 * ║  │  1. Get followers from Graph DB                                        │  ║
 * ║  │  2. If regular user: push to all followers' feed caches               │  ║
 * ║  │  3. If celebrity: skip (fanout on read)                               │  ║
 * ║  └────────────────────────────────────────────────────────────────────────┘  ║
 * ║                                                                               ║
 * ║  WHY EVENT-DRIVEN?                                                           ║
 * ║  ✅ Decoupled: PostService doesn't know about fanout                         ║
 * ║  ✅ Fast API: Returns immediately (< 100ms)                                  ║
 * ║  ✅ Reliable: Kafka persists events, retry on failure                        ║
 * ║  ✅ Scalable: Add more consumers independently                               ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 * 
 * In production: Kafka, RabbitMQ, or AWS SQS
 */
public class FanoutQueue {
    
    // ==================== Event Types ====================
    
    /**
     * PostCreated event - Published when a new post is created
     * In Kafka: This would be a message on the "post-events" topic
     */
    public static class PostCreatedEvent {
        private final Post post;
        private final long timestamp;
        
        public PostCreatedEvent(Post post) {
            this.post = post;
            this.timestamp = System.currentTimeMillis();
        }
        
        public Post getPost() { return post; }
        public long getTimestamp() { return timestamp; }
    }
    
    /**
     * FanoutTask - Task to be processed by fanout workers
     * This is created by FanoutService after consuming PostCreatedEvent
     */
    public static class FanoutTask {
        private final Post post;
        private final Set<Long> followerIds;
        private final long timestamp;
        
        public FanoutTask(Post post, Set<Long> followerIds) {
            this.post = post;
            this.followerIds = followerIds;
            this.timestamp = System.currentTimeMillis();
        }
        
        public Post getPost() { return post; }
        public Set<Long> getFollowerIds() { return followerIds; }
        public long getTimestamp() { return timestamp; }
    }
    
    // ==================== Queue State ====================
    
    // Event queue (simulates Kafka topic: "post-events")
    private final BlockingQueue<PostCreatedEvent> eventQueue = new LinkedBlockingQueue<>();
    
    // Task queue (for fanout workers after FanoutService processes events)
    private final BlockingQueue<FanoutTask> taskQueue = new LinkedBlockingQueue<>();
    
    // Consumers
    private final List<Consumer<PostCreatedEvent>> eventConsumers = new ArrayList<>();
    private final List<Consumer<FanoutTask>> taskWorkers = new ArrayList<>();
    
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private volatile boolean running = false;
    
    // Stats
    private long eventsPublished = 0;
    private long eventsConsumed = 0;
    private long tasksEnqueued = 0;
    private long tasksProcessed = 0;
    
    // ==================== Producer Methods ====================
    
    /**
     * Publish a PostCreated event (called by PostService)
     * In Kafka: producer.send(new ProducerRecord<>("post-events", postId, event))
     */
    public void publishPostCreatedEvent(Post post) {
        PostCreatedEvent event = new PostCreatedEvent(post);
        eventQueue.offer(event);
        eventsPublished++;
        System.out.println(String.format("  [Kafka] Published PostCreated event for post %d", 
            post.getPostId()));
    }
    
    // ==================== Consumer Registration ====================
    
    /**
     * Register a consumer for PostCreated events (FanoutService)
     * In Kafka: This would be a consumer subscribing to "post-events" topic
     */
    public void registerEventConsumer(Consumer<PostCreatedEvent> consumer) {
        eventConsumers.add(consumer);
        System.out.println("  [Kafka] Registered consumer for 'post-events' topic");
    }
    
    /**
     * Register a worker for fanout tasks (FanoutWorker)
     */
    public void registerWorker(Consumer<FanoutTask> worker) {
        taskWorkers.add(worker);
    }
    
    // ==================== Enqueue Fanout Task ====================
    
    /**
     * Enqueue a fanout task (called by FanoutService after processing event)
     */
    public void enqueue(FanoutTask task) {
        taskQueue.offer(task);
        tasksEnqueued++;
        System.out.println(String.format("  [Queue] Enqueued fanout task for post %d to %d followers",
            task.getPost().getPostId(), task.getFollowerIds().size()));
    }
    
    // ==================== Processing ====================
    
    /**
     * Start background processing (production mode)
     */
    public void start() {
        running = true;
        
        // Start event consumer threads
        executor.submit(() -> {
            while (running) {
                try {
                    PostCreatedEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        for (Consumer<PostCreatedEvent> consumer : eventConsumers) {
                            consumer.accept(event);
                        }
                        eventsConsumed++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        // Start task worker threads
        for (int i = 0; i < 3; i++) {
            executor.submit(() -> {
                while (running) {
                    try {
                        FanoutTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
                        if (task != null) {
                            for (Consumer<FanoutTask> worker : taskWorkers) {
                                worker.accept(task);
                            }
                            tasksProcessed++;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            });
        }
        
        System.out.println("  [Queue] Started event consumers and fanout workers");
    }
    
    /**
     * Stop processing
     */
    public void stop() {
        running = false;
        executor.shutdown();
    }
    
    /**
     * Process all pending events and tasks (for demo purposes)
     */
    public void processAll() throws InterruptedException {
        // Process all events first
        while (!eventQueue.isEmpty()) {
            PostCreatedEvent event = eventQueue.poll(100, TimeUnit.MILLISECONDS);
            if (event != null) {
                for (Consumer<PostCreatedEvent> consumer : eventConsumers) {
                    consumer.accept(event);
                }
                eventsConsumed++;
            }
        }
        
        // Then process all tasks
        while (!taskQueue.isEmpty()) {
            FanoutTask task = taskQueue.poll(100, TimeUnit.MILLISECONDS);
            if (task != null) {
                for (Consumer<FanoutTask> worker : taskWorkers) {
                    worker.accept(task);
                }
                tasksProcessed++;
            }
        }
    }
    
    // ==================== Stats ====================
    
    public int getEventQueueSize() { return eventQueue.size(); }
    public int getTaskQueueSize() { return taskQueue.size(); }
    
    public String getStats() {
        return String.format("FanoutQueue: events(published=%d, consumed=%d, pending=%d), tasks(enqueued=%d, processed=%d, pending=%d)",
            eventsPublished, eventsConsumed, eventQueue.size(),
            tasksEnqueued, tasksProcessed, taskQueue.size());
    }
}
