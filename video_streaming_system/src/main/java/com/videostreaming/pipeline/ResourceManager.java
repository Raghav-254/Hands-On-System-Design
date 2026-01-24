package com.videostreaming.pipeline;

import com.videostreaming.model.TranscodeTask;
import java.util.*;
import java.util.concurrent.*;

/**
 * Resource Manager - manages task workers and job scheduling.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  RESOURCE MANAGER (Figure 14-17)                                             ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  COMPONENTS:                                                                 ║
 * ║  ────────────                                                                ║
 * ║  1. TASK QUEUE                                                              ║
 * ║     • Priority queue of pending tasks                                       ║
 * ║     • Higher priority = processed first                                     ║
 * ║                                                                               ║
 * ║  2. WORKER QUEUE                                                            ║
 * ║     • Available workers waiting for tasks                                   ║
 * ║     • Different worker types for different tasks                            ║
 * ║                                                                               ║
 * ║  3. RUNNING QUEUE                                                           ║
 * ║     • Currently executing tasks                                             ║
 * ║     • Track progress and handle failures                                    ║
 * ║                                                                               ║
 * ║  4. TASK SCHEDULER                                                          ║
 * ║     • Matches tasks to workers                                              ║
 * ║     • Considers worker capabilities and load                                ║
 * ║                                                                               ║
 * ║  FLOW:                                                                       ║
 * ║  ──────                                                                      ║
 * ║  Task Queue ──→ Task Scheduler ←── Worker Queue                             ║
 * ║                      │                                                       ║
 * ║                      ▼                                                       ║
 * ║                Running Queue ──→ Workers                                    ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class ResourceManager {
    
    // Task queue (priority-based)
    private final PriorityBlockingQueue<TranscodeTask> taskQueue;
    
    // Worker pool
    private final List<Worker> workerPool;
    
    // Running tasks
    private final Map<String, TranscodeTask> runningTasks;
    
    // Completed tasks
    private final Set<String> completedTasks;
    
    // Listeners for task completion
    private final List<TaskCompletionListener> listeners;
    
    public interface TaskCompletionListener {
        void onTaskComplete(TranscodeTask task);
        void onTaskFailed(TranscodeTask task);
    }
    
    public static class Worker {
        public final String workerId;
        public final TranscodeTask.TaskType specialization;  // null = general purpose
        public boolean busy;
        
        public Worker(String workerId, TranscodeTask.TaskType specialization) {
            this.workerId = workerId;
            this.specialization = specialization;
            this.busy = false;
        }
        
        public boolean canHandle(TranscodeTask.TaskType type) {
            return specialization == null || specialization == type;
        }
    }
    
    public ResourceManager() {
        // Priority queue: higher priority first
        this.taskQueue = new PriorityBlockingQueue<>(100, 
            (a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
        this.workerPool = new ArrayList<>();
        this.runningTasks = new ConcurrentHashMap<>();
        this.completedTasks = ConcurrentHashMap.newKeySet();
        this.listeners = new ArrayList<>();
        
        initializeWorkers();
    }
    
    private void initializeWorkers() {
        // Create specialized workers (Figure 14-19)
        workerPool.add(new Worker("encoder-1", TranscodeTask.TaskType.VIDEO_ENCODE));
        workerPool.add(new Worker("encoder-2", TranscodeTask.TaskType.VIDEO_ENCODE));
        workerPool.add(new Worker("encoder-3", TranscodeTask.TaskType.VIDEO_ENCODE));
        workerPool.add(new Worker("audio-1", TranscodeTask.TaskType.AUDIO_ENCODE));
        workerPool.add(new Worker("thumbnail-1", TranscodeTask.TaskType.THUMBNAIL));
        workerPool.add(new Worker("merger-1", TranscodeTask.TaskType.MERGE));
        workerPool.add(new Worker("general-1", null));  // General purpose
        workerPool.add(new Worker("general-2", null));
        
        System.out.println(String.format("[ResourceManager] Initialized %d workers", workerPool.size()));
    }
    
    /**
     * Submit a task to the queue.
     */
    public void submitTask(TranscodeTask task) {
        taskQueue.offer(task);
        System.out.println(String.format("  [ResourceManager] Task %s queued (priority: %d)", 
            task.getTaskId(), task.getPriority()));
    }
    
    /**
     * Submit multiple tasks.
     */
    public void submitTasks(List<TranscodeTask> tasks) {
        for (TranscodeTask task : tasks) {
            submitTask(task);
        }
    }
    
    /**
     * Find an available worker for a task.
     */
    public Worker findWorker(TranscodeTask task) {
        // First, try to find a specialized worker
        for (Worker worker : workerPool) {
            if (!worker.busy && worker.specialization == task.getType()) {
                return worker;
            }
        }
        
        // Fall back to general purpose worker
        for (Worker worker : workerPool) {
            if (!worker.busy && worker.canHandle(task.getType())) {
                return worker;
            }
        }
        
        return null;  // No available worker
    }
    
    /**
     * Schedule and execute pending tasks.
     */
    public void processQueue() {
        while (!taskQueue.isEmpty()) {
            TranscodeTask task = taskQueue.peek();
            Worker worker = findWorker(task);
            
            if (worker == null) {
                // No available worker, wait
                break;
            }
            
            // Remove from queue and assign to worker
            taskQueue.poll();
            assignTask(task, worker);
        }
    }
    
    /**
     * Assign task to worker and execute.
     */
    private void assignTask(TranscodeTask task, Worker worker) {
        worker.busy = true;
        task.markRunning();
        runningTasks.put(task.getTaskId(), task);
        
        System.out.println(String.format("  [ResourceManager] Assigned %s to worker %s", 
            task.getTaskId(), worker.workerId));
        
        // Simulate async execution
        CompletableFuture.runAsync(() -> {
            try {
                // Simulate processing time based on task type
                int sleepTime = getProcessingTime(task.getType());
                Thread.sleep(sleepTime);
                
                // Mark complete
                task.markCompleted();
                completedTasks.add(task.getTaskId());
                worker.busy = false;
                runningTasks.remove(task.getTaskId());
                
                System.out.println(String.format("  [ResourceManager] ✓ Task %s completed by %s", 
                    task.getTaskId(), worker.workerId));
                
                // Notify listeners
                for (TaskCompletionListener listener : listeners) {
                    listener.onTaskComplete(task);
                }
                
            } catch (Exception e) {
                task.markFailed(e.getMessage());
                worker.busy = false;
                runningTasks.remove(task.getTaskId());
                
                for (TaskCompletionListener listener : listeners) {
                    listener.onTaskFailed(task);
                }
            }
        });
    }
    
    private int getProcessingTime(TranscodeTask.TaskType type) {
        switch (type) {
            case VIDEO_ENCODE: return 500;  // Longest
            case AUDIO_ENCODE: return 100;
            case THUMBNAIL: return 50;
            case MERGE: return 200;
            default: return 100;
        }
    }
    
    /**
     * Add completion listener.
     */
    public void addListener(TaskCompletionListener listener) {
        listeners.add(listener);
    }
    
    /**
     * Get completed task IDs.
     */
    public Set<String> getCompletedTasks() {
        return new HashSet<>(completedTasks);
    }
    
    /**
     * Check if all tasks are complete.
     */
    public boolean allTasksComplete(int expectedCount) {
        return completedTasks.size() >= expectedCount && runningTasks.isEmpty();
    }
    
    /**
     * Get queue status.
     */
    public String getStatus() {
        long availableWorkers = workerPool.stream().filter(w -> !w.busy).count();
        return String.format("Queue: %d, Running: %d, Complete: %d, Workers: %d/%d available",
            taskQueue.size(), runningTasks.size(), completedTasks.size(), 
            availableWorkers, workerPool.size());
    }
}

