package com.videostreaming.queue;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Completion Queue - notifies when transcoding is complete.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  COMPLETION QUEUE (Figures 14-4, 14-5)                                       ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Notify downstream services when transcoding completes                    ║
 * ║  • Decouple transcoding from completion handling                            ║
 * ║  • Enable async processing of completion events                             ║
 * ║                                                                               ║
 * ║  FLOW:                                                                       ║
 * ║  ──────                                                                      ║
 * ║  Transcoding complete ──→ Completion Queue ──→ Completion Handler           ║
 * ║                                                      │                       ║
 * ║                                                      ├──→ Update Metadata DB║
 * ║                                                      ├──→ Invalidate Cache  ║
 * ║                                                      └──→ Notify User       ║
 * ║                                                                               ║
 * ║  IMPLEMENTATION:                                                            ║
 * ║  • Kafka topic: "video-transcoding-complete"                               ║
 * ║  • Event contains: videoId, resolutions, thumbnailUrl, status              ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class CompletionQueue {
    
    public static class TranscodingCompleteEvent {
        public final String videoId;
        public final List<String> completedResolutions;
        public final String thumbnailUrl;
        public final boolean success;
        public final String errorMessage;
        public final long timestamp;
        
        public TranscodingCompleteEvent(String videoId, List<String> resolutions, 
                                        String thumbnailUrl, boolean success, String error) {
            this.videoId = videoId;
            this.completedResolutions = resolutions;
            this.thumbnailUrl = thumbnailUrl;
            this.success = success;
            this.errorMessage = error;
            this.timestamp = System.currentTimeMillis();
        }
        
        @Override
        public String toString() {
            return String.format("TranscodingCompleteEvent[video=%s, success=%s, resolutions=%s]",
                videoId, success, completedResolutions);
        }
    }
    
    // Simulates Kafka topic
    private final BlockingQueue<TranscodingCompleteEvent> queue = new LinkedBlockingQueue<>();
    
    // Registered consumers
    private final List<Consumer<TranscodingCompleteEvent>> consumers = new ArrayList<>();
    
    // Background consumer thread
    private volatile boolean running = true;
    
    public CompletionQueue() {
        // Start background consumer
        startConsumer();
    }
    
    private void startConsumer() {
        Thread consumerThread = new Thread(() -> {
            while (running) {
                try {
                    TranscodingCompleteEvent event = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (event != null) {
                        System.out.println(String.format("\n[CompletionQueue] Processing: %s", event));
                        for (Consumer<TranscodingCompleteEvent> consumer : consumers) {
                            try {
                                consumer.accept(event);
                            } catch (Exception e) {
                                System.err.println("Consumer error: " + e.getMessage());
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        consumerThread.setDaemon(true);
        consumerThread.start();
    }
    
    /**
     * Publish a completion event.
     */
    public void publish(TranscodingCompleteEvent event) {
        queue.offer(event);
        System.out.println(String.format("[CompletionQueue] Published event for video %s", event.videoId));
    }
    
    /**
     * Publish success event.
     */
    public void publishSuccess(String videoId, List<String> resolutions, String thumbnailUrl) {
        publish(new TranscodingCompleteEvent(videoId, resolutions, thumbnailUrl, true, null));
    }
    
    /**
     * Publish failure event.
     */
    public void publishFailure(String videoId, String error) {
        publish(new TranscodingCompleteEvent(videoId, new ArrayList<>(), null, false, error));
    }
    
    /**
     * Register a consumer for completion events.
     */
    public void registerConsumer(Consumer<TranscodingCompleteEvent> consumer) {
        consumers.add(consumer);
    }
    
    /**
     * Stop the queue.
     */
    public void stop() {
        running = false;
    }
    
    /**
     * Get queue size.
     */
    public int size() {
        return queue.size();
    }
}

