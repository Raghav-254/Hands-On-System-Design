package com.videostreaming.model;

/**
 * Group of Pictures (GOP) - fundamental unit for video encoding and streaming.
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════════╗
 * ║  WHAT IS A GOP?                                                              ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  A GOP (Group of Pictures) is a sequence of frames that starts with an      ║
 * ║  I-frame (keyframe) and includes subsequent P-frames and B-frames.          ║
 * ║                                                                               ║
 * ║  Frame Types:                                                                ║
 * ║  ─────────────                                                               ║
 * ║  I-frame: Complete image (keyframe) - can be decoded independently          ║
 * ║  P-frame: Predicted from previous frame - smaller size                      ║
 * ║  B-frame: Bidirectional - predicted from both past and future frames        ║
 * ║                                                                               ║
 * ║  Example GOP (size 8):                                                       ║
 * ║  I → B → B → P → B → B → P → B → [I → next GOP]                             ║
 * ║                                                                               ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║  WHY GOP MATTERS FOR STREAMING                                               ║
 * ╠═══════════════════════════════════════════════════════════════════════════════╣
 * ║                                                                               ║
 * ║  1. PARALLEL UPLOAD: Each GOP can be uploaded independently (Figure 14-23)  ║
 * ║     Client splits video into GOPs → uploads in parallel → faster upload     ║
 * ║                                                                               ║
 * ║  2. SEEKING: To seek to a timestamp, find the nearest I-frame (GOP start)   ║
 * ║     Smaller GOP = faster seeking, but larger file size                      ║
 * ║                                                                               ║
 * ║  3. ADAPTIVE STREAMING: Resolution can only change at GOP boundaries        ║
 * ║                                                                               ║
 * ╚═══════════════════════════════════════════════════════════════════════════════╝
 */
public class GOP {
    
    private final int gopIndex;         // 0, 1, 2, ... (sequence number)
    private final String videoId;
    private final long startTimeMs;     // Start timestamp in the video
    private final long endTimeMs;       // End timestamp
    private final int frameCount;       // Number of frames in this GOP
    private byte[] data;                // Raw GOP data (for demo purposes)
    private boolean uploaded;
    
    public GOP(int gopIndex, String videoId, long startTimeMs, long endTimeMs, int frameCount) {
        this.gopIndex = gopIndex;
        this.videoId = videoId;
        this.startTimeMs = startTimeMs;
        this.endTimeMs = endTimeMs;
        this.frameCount = frameCount;
        this.uploaded = false;
    }
    
    // Getters
    public int getGopIndex() { return gopIndex; }
    public String getVideoId() { return videoId; }
    public long getStartTimeMs() { return startTimeMs; }
    public long getEndTimeMs() { return endTimeMs; }
    public int getFrameCount() { return frameCount; }
    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }
    public boolean isUploaded() { return uploaded; }
    public void setUploaded(boolean uploaded) { this.uploaded = uploaded; }
    
    public long getDurationMs() {
        return endTimeMs - startTimeMs;
    }
    
    @Override
    public String toString() {
        return String.format("GOP[index=%d, video=%s, time=%d-%dms, frames=%d, uploaded=%s]",
            gopIndex, videoId, startTimeMs, endTimeMs, frameCount, uploaded);
    }
}

