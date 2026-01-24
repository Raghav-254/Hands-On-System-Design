package com.videostreaming.model;

/**
 * Represents a transcoding task in the DAG pipeline.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  TASK TYPES IN VIDEO PROCESSING                                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  VIDEO_SPLIT     | Split video into video/audio/metadata streams             ║
 * ║  VIDEO_ENCODE    | Encode video to specific resolution (360p, 720p, etc.)    ║
 * ║  AUDIO_ENCODE    | Encode audio (AAC, MP3, etc.)                             ║
 * ║  THUMBNAIL       | Generate thumbnail images                                 ║
 * ║  WATERMARK       | Add watermark overlay                                     ║
 * ║  MERGE           | Merge video + audio into final output                     ║
 * ║  INSPECTION      | Quality check and validation                              ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class TranscodeTask {
    
    public enum TaskType {
        VIDEO_SPLIT,
        VIDEO_ENCODE,
        AUDIO_ENCODE,
        THUMBNAIL,
        WATERMARK,
        MERGE,
        INSPECTION
    }
    
    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED
    }
    
    private final String taskId;
    private final String videoId;
    private final TaskType type;
    private TaskStatus status;
    private String inputPath;
    private String outputPath;
    private String targetResolution;  // For VIDEO_ENCODE tasks
    private int priority;
    private long createdAt;
    private long startedAt;
    private long completedAt;
    private String errorMessage;
    
    public TranscodeTask(String taskId, String videoId, TaskType type) {
        this.taskId = taskId;
        this.videoId = videoId;
        this.type = type;
        this.status = TaskStatus.PENDING;
        this.priority = 0;
        this.createdAt = System.currentTimeMillis();
    }
    
    // Getters and setters
    public String getTaskId() { return taskId; }
    public String getVideoId() { return videoId; }
    public TaskType getType() { return type; }
    public TaskStatus getStatus() { return status; }
    public void setStatus(TaskStatus status) { this.status = status; }
    public String getInputPath() { return inputPath; }
    public void setInputPath(String path) { this.inputPath = path; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String path) { this.outputPath = path; }
    public String getTargetResolution() { return targetResolution; }
    public void setTargetResolution(String resolution) { this.targetResolution = resolution; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public long getCreatedAt() { return createdAt; }
    public long getStartedAt() { return startedAt; }
    public void setStartedAt(long time) { this.startedAt = time; }
    public long getCompletedAt() { return completedAt; }
    public void setCompletedAt(long time) { this.completedAt = time; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String msg) { this.errorMessage = msg; }
    
    public void markRunning() {
        this.status = TaskStatus.RUNNING;
        this.startedAt = System.currentTimeMillis();
    }
    
    public void markCompleted() {
        this.status = TaskStatus.COMPLETED;
        this.completedAt = System.currentTimeMillis();
    }
    
    public void markFailed(String error) {
        this.status = TaskStatus.FAILED;
        this.completedAt = System.currentTimeMillis();
        this.errorMessage = error;
    }
    
    @Override
    public String toString() {
        return String.format("TranscodeTask[id=%s, video=%s, type=%s, status=%s, resolution=%s]",
            taskId, videoId, type, status, targetResolution);
    }
}

