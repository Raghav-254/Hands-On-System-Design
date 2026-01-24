package com.videostreaming.pipeline;

import com.videostreaming.model.TranscodeTask;
import java.util.*;

/**
 * DAG Scheduler - orchestrates the transcoding pipeline as a Directed Acyclic Graph.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  DAG SCHEDULER (Figures 14-10, 14-12, 14-15)                                 ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  PURPOSE:                                                                    ║
 * ║  • Define task dependencies (what must complete before what)                ║
 * ║  • Schedule tasks in stages                                                 ║
 * ║  • Enable parallel processing where possible                                ║
 * ║                                                                               ║
 * ║  EXAMPLE DAG (Figure 14-15):                                                ║
 * ║  ────────────────────────────                                                ║
 * ║                                                                               ║
 * ║                    ┌──→ Video Encoding (360p) ───┐                          ║
 * ║                    │                              │                          ║
 * ║  Original ──→ Video ──→ Video Encoding (720p) ───┼──→ Merge ──→ Output      ║
 * ║     │          │   └──→ Video Encoding (1080p) ──┤                          ║
 * ║     │          │                                  │                          ║
 * ║     │          └──→ Thumbnail ───────────────────┘                          ║
 * ║     │                                                                        ║
 * ║     ├──→ Audio ──→ Audio Encoding ───────────────────┘                      ║
 * ║     │                                                                        ║
 * ║     └──→ Metadata                                                           ║
 * ║                                                                               ║
 * ║  STAGES:                                                                     ║
 * ║  ─────────                                                                   ║
 * ║  Stage 1: Split (video, audio, metadata)                                    ║
 * ║  Stage 2: Parallel encoding (each resolution + thumbnail + audio)           ║
 * ║  Stage 3: Merge and finalize                                                ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class DAGScheduler {
    
    private int taskIdCounter = 0;
    
    public static class DAG {
        public final String videoId;
        public final List<List<TranscodeTask>> stages;  // Stages of tasks
        public final Map<String, Set<String>> dependencies;  // taskId → dependent taskIds
        
        public DAG(String videoId) {
            this.videoId = videoId;
            this.stages = new ArrayList<>();
            this.dependencies = new HashMap<>();
        }
        
        public void addStage(List<TranscodeTask> tasks) {
            stages.add(tasks);
        }
        
        public int getTotalTasks() {
            return stages.stream().mapToInt(List::size).sum();
        }
    }
    
    /**
     * Create a DAG for video transcoding.
     */
    public DAG createDAG(String videoId, Preprocessor.VideoInfo info, List<String> targetResolutions) {
        System.out.println(String.format("\n[DAGScheduler] Creating DAG for video %s", videoId));
        
        DAG dag = new DAG(videoId);
        
        // Stage 1: Split task (already done by preprocessor, but included for completeness)
        TranscodeTask splitTask = createTask(videoId, TranscodeTask.TaskType.VIDEO_SPLIT);
        splitTask.setInputPath(String.format("s3://original-videos/%s/raw.mp4", videoId));
        dag.addStage(Collections.singletonList(splitTask));
        System.out.println("  [DAGScheduler] Stage 1: VIDEO_SPLIT");
        
        // Stage 2: Parallel encoding tasks
        List<TranscodeTask> stage2Tasks = new ArrayList<>();
        
        // Video encoding for each resolution
        for (String resolution : targetResolutions) {
            TranscodeTask encodeTask = createTask(videoId, TranscodeTask.TaskType.VIDEO_ENCODE);
            encodeTask.setTargetResolution(resolution);
            encodeTask.setInputPath(info.videoStreamPath);
            encodeTask.setOutputPath(String.format("temp/%s/%s.mp4", videoId, resolution));
            stage2Tasks.add(encodeTask);
            
            // Dependency: encode depends on split
            dag.dependencies.computeIfAbsent(encodeTask.getTaskId(), k -> new HashSet<>())
                           .add(splitTask.getTaskId());
        }
        
        // Audio encoding
        TranscodeTask audioTask = createTask(videoId, TranscodeTask.TaskType.AUDIO_ENCODE);
        audioTask.setInputPath(info.audioStreamPath);
        audioTask.setOutputPath(String.format("temp/%s/audio.aac", videoId));
        stage2Tasks.add(audioTask);
        dag.dependencies.computeIfAbsent(audioTask.getTaskId(), k -> new HashSet<>())
                       .add(splitTask.getTaskId());
        
        // Thumbnail generation
        TranscodeTask thumbnailTask = createTask(videoId, TranscodeTask.TaskType.THUMBNAIL);
        thumbnailTask.setInputPath(info.videoStreamPath);
        thumbnailTask.setOutputPath(String.format("temp/%s/thumbnail.jpg", videoId));
        stage2Tasks.add(thumbnailTask);
        dag.dependencies.computeIfAbsent(thumbnailTask.getTaskId(), k -> new HashSet<>())
                       .add(splitTask.getTaskId());
        
        dag.addStage(stage2Tasks);
        System.out.println(String.format("  [DAGScheduler] Stage 2: %d parallel tasks (%d video encodes + audio + thumbnail)",
            stage2Tasks.size(), targetResolutions.size()));
        
        // Stage 3: Merge tasks (one per resolution)
        List<TranscodeTask> stage3Tasks = new ArrayList<>();
        for (String resolution : targetResolutions) {
            TranscodeTask mergeTask = createTask(videoId, TranscodeTask.TaskType.MERGE);
            mergeTask.setTargetResolution(resolution);
            mergeTask.setOutputPath(String.format("s3://transcoded-videos/%s/%s.mp4", videoId, resolution));
            stage3Tasks.add(mergeTask);
            
            // Merge depends on corresponding video encode and audio encode
            dag.dependencies.computeIfAbsent(mergeTask.getTaskId(), k -> new HashSet<>())
                           .add(audioTask.getTaskId());
            // Find corresponding video encode task
            for (TranscodeTask t : stage2Tasks) {
                if (t.getType() == TranscodeTask.TaskType.VIDEO_ENCODE 
                    && resolution.equals(t.getTargetResolution())) {
                    dag.dependencies.get(mergeTask.getTaskId()).add(t.getTaskId());
                }
            }
        }
        dag.addStage(stage3Tasks);
        System.out.println(String.format("  [DAGScheduler] Stage 3: %d merge tasks", stage3Tasks.size()));
        
        System.out.println(String.format("  [DAGScheduler] ✓ DAG created: %d stages, %d total tasks", 
            dag.stages.size(), dag.getTotalTasks()));
        
        return dag;
    }
    
    /**
     * Get tasks that are ready to execute (all dependencies complete).
     */
    public List<TranscodeTask> getReadyTasks(DAG dag, Set<String> completedTasks) {
        List<TranscodeTask> ready = new ArrayList<>();
        
        for (List<TranscodeTask> stage : dag.stages) {
            for (TranscodeTask task : stage) {
                if (task.getStatus() == TranscodeTask.TaskStatus.PENDING) {
                    Set<String> deps = dag.dependencies.getOrDefault(task.getTaskId(), new HashSet<>());
                    if (completedTasks.containsAll(deps)) {
                        ready.add(task);
                    }
                }
            }
        }
        
        return ready;
    }
    
    private TranscodeTask createTask(String videoId, TranscodeTask.TaskType type) {
        String taskId = String.format("task_%s_%d", videoId, taskIdCounter++);
        return new TranscodeTask(taskId, videoId, type);
    }
}

