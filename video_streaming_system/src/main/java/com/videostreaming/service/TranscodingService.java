package com.videostreaming.service;

import com.videostreaming.model.*;
import com.videostreaming.storage.*;
import com.videostreaming.cache.MetadataCache;
import com.videostreaming.pipeline.*;
import com.videostreaming.queue.CompletionQueue;
import java.util.*;

/**
 * Transcoding Service - orchestrates video transcoding.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TRANSCODING SERVICE                                                         ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  TRANSCODING FLOW (Figures 14-8, 14-10):                                    ║
 * ║  ─────────────────────────────────────                                       ║
 * ║                                                                               ║
 * ║  1. PREPROCESSOR                                                            ║
 * ║     • Split video into streams (video, audio, metadata)                     ║
 * ║     • Validate file                                                         ║
 * ║     • Extract properties                                                    ║
 * ║                                                                               ║
 * ║  2. DAG SCHEDULER                                                           ║
 * ║     • Create task dependency graph                                          ║
 * ║     • Determine which tasks can run in parallel                             ║
 * ║                                                                               ║
 * ║  3. RESOURCE MANAGER                                                        ║
 * ║     • Match tasks to workers                                                ║
 * ║     • Manage task queues                                                    ║
 * ║                                                                               ║
 * ║  4. TASK WORKERS (Figure 14-19)                                             ║
 * ║     • Encoder: Encode to specific resolution                                ║
 * ║     • Thumbnail: Generate thumbnails                                        ║
 * ║     • Watermark: Add watermarks                                             ║
 * ║     • Merger: Combine video + audio                                         ║
 * ║                                                                               ║
 * ║  5. COMPLETION                                                              ║
 * ║     • Store to Transcoded Storage                                           ║
 * ║     • Update Metadata DB                                                    ║
 * ║     • Push to CDN                                                           ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TranscodingService {
    
    private final Preprocessor preprocessor;
    private final DAGScheduler dagScheduler;
    private final ResourceManager resourceManager;
    private final TranscodedStorage transcodedStorage;
    private final MetadataDB metadataDB;
    private final MetadataCache metadataCache;
    private final CompletionQueue completionQueue;
    
    public TranscodingService(TranscodedStorage transcodedStorage, MetadataDB metadataDB,
                              MetadataCache metadataCache, CompletionQueue completionQueue) {
        this.preprocessor = new Preprocessor();
        this.dagScheduler = new DAGScheduler();
        this.resourceManager = new ResourceManager();
        this.transcodedStorage = transcodedStorage;
        this.metadataDB = metadataDB;
        this.metadataCache = metadataCache;
        this.completionQueue = completionQueue;
        
        // Set up completion handler
        setupCompletionHandler();
    }
    
    private void setupCompletionHandler() {
        completionQueue.registerConsumer(event -> {
            if (event.success) {
                // Update metadata
                VideoMetadata metadata = metadataDB.get(event.videoId);
                if (metadata != null) {
                    for (String resolution : event.completedResolutions) {
                        metadata.addResolution(resolution);
                    }
                    metadata.setThumbnailUrl(event.thumbnailUrl);
                    metadataDB.update(metadata);
                    
                    // Invalidate cache
                    metadataCache.invalidate(event.videoId);
                    
                    System.out.println(String.format("  [CompletionHandler] Updated metadata for %s", event.videoId));
                }
            }
        });
    }
    
    /**
     * Start transcoding for a video.
     */
    public void startTranscoding(String videoId, byte[] rawVideo) {
        System.out.println(String.format("\n[TranscodingService] Starting transcoding for video %s", videoId));
        
        // Step 1: Validate
        if (!preprocessor.validate(rawVideo)) {
            completionQueue.publishFailure(videoId, "Validation failed");
            return;
        }
        
        // Step 2: Preprocess
        Preprocessor.VideoInfo info = preprocessor.process(videoId, rawVideo);
        
        // Step 3: Determine target resolutions
        List<String> targetResolutions = preprocessor.determineTargetResolutions(info);
        
        // Step 4: Create DAG
        DAGScheduler.DAG dag = dagScheduler.createDAG(videoId, info, targetResolutions);
        
        // Step 5: Submit all tasks to resource manager
        for (List<TranscodeTask> stage : dag.stages) {
            resourceManager.submitTasks(stage);
        }
        
        // Step 6: Process queue (simulated)
        processTranscoding(videoId, dag, targetResolutions);
    }
    
    /**
     * Simulate transcoding process.
     */
    private void processTranscoding(String videoId, DAGScheduler.DAG dag, List<String> resolutions) {
        System.out.println("\n[TranscodingService] Processing transcoding tasks...");
        
        // Simulate processing stages
        for (int i = 0; i < dag.stages.size(); i++) {
            List<TranscodeTask> stage = dag.stages.get(i);
            System.out.println(String.format("  [TranscodingService] Processing stage %d: %d tasks in parallel", 
                i + 1, stage.size()));
            
            for (TranscodeTask task : stage) {
                // Simulate task execution
                task.markRunning();
                simulateTaskExecution(task);
                task.markCompleted();
                
                // Store result if it's a final encoding
                if (task.getType() == TranscodeTask.TaskType.MERGE && task.getTargetResolution() != null) {
                    byte[] encodedData = new byte[1000];  // Simulated
                    transcodedStorage.store(videoId, task.getTargetResolution(), encodedData);
                }
                
                if (task.getType() == TranscodeTask.TaskType.THUMBNAIL) {
                    byte[] thumbnail = new byte[100];  // Simulated
                    transcodedStorage.storeThumbnail(videoId, thumbnail);
                }
            }
        }
        
        // Publish completion event
        String thumbnailUrl = transcodedStorage.getThumbnailUrl(videoId);
        completionQueue.publishSuccess(videoId, resolutions, thumbnailUrl);
        
        System.out.println(String.format("[TranscodingService] ✓ Transcoding complete for %s", videoId));
    }
    
    private void simulateTaskExecution(TranscodeTask task) {
        try {
            // Simulate processing time
            int time = 50;  // ms
            switch (task.getType()) {
                case VIDEO_ENCODE: time = 200; break;
                case AUDIO_ENCODE: time = 50; break;
                case THUMBNAIL: time = 30; break;
                case MERGE: time = 100; break;
            }
            Thread.sleep(time);
            
            System.out.println(String.format("    [Worker] Completed %s %s", 
                task.getType(), task.getTargetResolution() != null ? "(" + task.getTargetResolution() + ")" : ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Get transcoding status.
     */
    public String getStatus(String videoId) {
        return resourceManager.getStatus();
    }
}

