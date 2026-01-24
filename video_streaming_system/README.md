# Video Streaming System (YouTube-like)

A comprehensive Java implementation of a video streaming system based on Alex Xu's System Design Interview book (Chapter 14).

## Overview

This project implements a YouTube-like video streaming system focusing on:
- **Fast video uploads** via pre-signed URLs
- **Parallel transcoding** using DAG-based pipeline
- **Adaptive bitrate streaming** with multiple resolutions
- **CDN integration** for low-latency delivery

## Architecture

```
┌──────────┐     ┌────────────┐     ┌─────────────────┐
│  Client  │ ──→ │ API Server │ ──→ │ Original Storage│
└──────────┘     └────────────┘     └────────┬────────┘
     ↑                                       │
     │                                       ▼
     │                            ┌──────────────────────┐
     │                            │ TRANSCODING PIPELINE │
     │                            │  Preprocessor → DAG  │
     │                            │  → Resource Manager  │
     │                            │  → Task Workers      │
     │                            └──────────┬───────────┘
     │                                       │
     │                                       ▼
┌────┴────┐                       ┌──────────────────┐
│   CDN   │ ◀──────────────────── │Transcoded Storage│
└─────────┘                       └──────────────────┘
```

## Key Highlights

| Feature | Implementation |
|---------|---------------|
| **Pre-signed URL Upload** | Direct S3 upload, bypasses API servers |
| **Parallel GOP Upload** | Split large videos for faster uploads |
| **DAG Scheduler** | Optimizes task parallelism |
| **Resource Manager** | Task/worker queue management |
| **Multiple Encodings** | 360p, 480p, 720p, 1080p, 4K |
| **CDN vs Origin** | Smart routing based on popularity |
| **Adaptive Streaming** | HLS/DASH manifest generation |

## Project Structure

```
video_streaming_system/
├── src/main/java/com/videostreaming/
│   ├── model/
│   │   ├── Video.java              # Video entity with lifecycle
│   │   ├── VideoMetadata.java      # Metadata for search/display
│   │   ├── TranscodeTask.java      # Task in transcoding pipeline
│   │   └── GOP.java                # Group of Pictures for parallel upload
│   ├── storage/
│   │   ├── OriginalStorage.java    # S3-like temp storage
│   │   ├── TranscodedStorage.java  # S3-like permanent storage
│   │   └── MetadataDB.java         # MySQL-like metadata store
│   ├── cache/
│   │   └── MetadataCache.java      # Redis-like cache
│   ├── pipeline/
│   │   ├── Preprocessor.java       # Video splitting
│   │   ├── DAGScheduler.java       # Task dependency graph
│   │   └── ResourceManager.java    # Worker pool management
│   ├── queue/
│   │   └── CompletionQueue.java    # Kafka-like completion events
│   ├── service/
│   │   ├── UploadService.java      # Upload flow handling
│   │   ├── TranscodingService.java # Orchestrates transcoding
│   │   ├── StreamingService.java   # Video playback
│   │   └── ApiService.java         # REST API endpoints
│   └── VideoStreamingDemo.java     # Main demo
├── INTERVIEW_CHEATSHEET.md         # Interview preparation guide
├── README.md
└── pom.xml
```

## Run the Demo

```bash
cd video_streaming_system
mvn compile exec:java
```

## Demo Flows

The demo demonstrates:

1. **Video Upload Flow** - Pre-signed URL, direct S3 upload, transcoding trigger
2. **Transcoding Pipeline** - DAG-based parallel encoding
3. **Video Streaming** - CDN vs origin routing, HLS manifest
4. **Metadata Update** - Cache invalidation
5. **Parallel GOP Upload** - Large video optimization

## Key Concepts for Interviews

### Pre-signed URL Upload
Videos are large (up to 1GB). Uploading through API servers would overload them. Pre-signed URLs let clients upload directly to S3.

### DAG-based Transcoding
Tasks are modeled as a Directed Acyclic Graph. Independent tasks (encoding different resolutions) run in parallel. Dependent tasks (merge) wait for prerequisites.

### CDN vs Origin
- **Popular videos** → CDN (low latency, expensive)
- **Less popular** → Origin servers (higher latency, cheaper)

### Adaptive Bitrate Streaming
Videos encoded at multiple resolutions. Manifest file lists all qualities. Player switches based on network conditions.

## Scale Estimation

| Metric | Value |
|--------|-------|
| DAU | 5 million |
| Daily storage | 150 TB |
| CDN cost | ~$150,000/day |
| Video size | Up to 1GB |
| Resolutions | 360p to 4K |

## Further Reading

- See [INTERVIEW_CHEATSHEET.md](./INTERVIEW_CHEATSHEET.md) for comprehensive interview preparation
- Alex Xu's System Design Interview - Chapter 14

