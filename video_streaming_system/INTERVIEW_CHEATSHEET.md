# Video Streaming System (YouTube) - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview - Chapter 14

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Original Storage** | Raw uploaded videos | S3/Blob (temp) | Pre-signed URL upload, deleted after transcoding |
| **Transcoded Storage** | Encoded videos | S3/Blob (permanent) | Multiple resolutions, source for CDN |
| **Metadata DB** | Video info | MySQL/PostgreSQL | Title, description, view counts |
| **Metadata Cache** | Fast reads | Redis | Cache-aside, 1hr TTL |
| **Preprocessor** | Split video | CPU-intensive | Video/audio/metadata streams |
| **DAG Scheduler** | Task orchestration | In-memory | Parallel task execution |
| **Task Workers** | Encoding | GPU/CPU clusters | Encoder, thumbnail, watermark, merger |
| **CDN** | Video delivery | Edge locations | Popular videos cached globally |

---

## 1. Requirements Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Ability to upload videos fast                                            ║
║  • Smooth video streaming                                                   ║
║  • Ability to change video quality (adaptive bitrate)                       ║
║  • Support for mobile apps, web browsers, and smart TV                      ║
║                                                                               ║
║  OUT OF SCOPE:                                                              ║
║  • Comments, likes, shares                                                  ║
║  • Recommendations                                                          ║
║  • Live streaming                                                           ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Low infrastructure cost                                                  ║
║  • High availability                                                        ║
║  • Scalable                                                                 ║
║  • Reliable                                                                 ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  USERS:                                                                     ║
║  • 5 million DAU                                                            ║
║  • 30 minutes average daily time                                            ║
║                                                                               ║
║  UPLOADS:                                                                   ║
║  • 10% of users upload 1 video/day                                         ║
║  • Average video size: 300 MB                                               ║
║  • Max video size: 1 GB                                                     ║
║  • Daily storage: 5M × 10% × 300MB = 150 TB/day                            ║
║                                                                               ║
║  CDN COST:                                                                  ║
║  • 5M users × 5 videos × 0.3GB × $0.02/GB = $150,000/day                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. High-Level Architecture

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  VIDEO STREAMING SYSTEM ARCHITECTURE                                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────────────────────── UPLOAD PATH ──────────────────────────────┐║
║  │                                                                           │║
║  │  User ──→ POST /upload ──→ API Server ──→ Pre-signed URL                │║
║  │    │                                           │                          │║
║  │    │                                           ▼                          │║
║  │    └─────────── Direct Upload ──────────→ Original Storage (S3)          │║
║  │                                                │                          │║
║  │                           S3 Event Trigger ────┘                          │║
║  │                                   │                                       │║
║  │                                   ▼                                       │║
║  │  ┌──────────── TRANSCODING PIPELINE (Figures 14-8, 14-10) ─────────────┐│║
║  │  │                                                                      ││║
║  │  │  Preprocessor ──→ DAG Scheduler ──→ Resource Manager               ││║
║  │  │       │                                    │                         ││║
║  │  │       ▼                                    ▼                         ││║
║  │  │  Split into:                      Task Workers:                     ││║
║  │  │  • Video stream                   • Encoders (360p→4K)              ││║
║  │  │  • Audio stream                   • Thumbnail generator             ││║
║  │  │  • Metadata                       • Watermark                       ││║
║  │  │                                   • Merger                          ││║
║  │  │                                          │                           ││║
║  │  │                                          ▼                           ││║
║  │  │                              Transcoded Storage (S3)                ││║
║  │  │                                          │                           ││║
║  │  │                                          ▼                           ││║
║  │  │                          Completion Queue (Kafka)                   ││║
║  │  │                                          │                           ││║
║  │  │                                          ▼                           ││║
║  │  │                          Completion Handler                         ││║
║  │  │                          • Update Metadata DB                       ││║
║  │  │                          • Invalidate Cache                         ││║
║  │  │                          • Push to CDN                              ││║
║  │  │                                                                      ││║
║  │  └──────────────────────────────────────────────────────────────────────┘│║
║  │                                                                           │║
║  └───────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
║  ┌────────────────────────────── STREAMING PATH ────────────────────────────┐║
║  │                                                                           │║
║  │  User ──→ GET /video/{id}/stream ──→ API Server                         │║
║  │                                           │                               │║
║  │               ┌───────────────────────────┴───────────────────────────┐  │║
║  │               │                                                        │  │║
║  │               ▼                                                        ▼  │║
║  │  Popular videos (>100 views)                   Less popular videos       │║
║  │        CDN (Edge)                              Origin servers            │║
║  │        ~50ms latency                           Higher latency            │║
║  │        $$$ expensive                           $ cheaper                 │║
║  │                                                                           │║
║  └───────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 3. Video Upload Flow (Figure 14-5, 14-27)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  PRE-SIGNED URL UPLOAD (Why?)                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  PROBLEM: Videos are LARGE (100MB - 1GB)                                      ║
║  • Can't upload through API servers (bottleneck)                              ║
║  • Would overload API servers                                                 ║
║  • High latency for users                                                     ║
║                                                                               ║
║  SOLUTION: Pre-signed URL                                                     ║
║                                                                               ║
║  1. Client (JS in browser) ──→ POST /upload (metadata only) ──→ API Server    ║
║  2. API Server generates pre-signed S3 URL (temporary, ~1 hour valid)         ║
║  3. Client (browser) uploads DIRECTLY to S3 (bypasses API servers!)           ║
║  4. S3 triggers event on upload complete                                      ║
║                                                                               ║
║  ┌─────────┐    1. POST /upload    ┌─────────────┐                            ║
║  │  User   │ ───────────────────→  │ API Server  │                            ║
║  │         │ ←───────────────────  │             │                            ║
║  └────┬────┘    2. Pre-signed URL  └──────┬──────┘                            ║
║       │                                    │                                  ║
║       │ 3. Direct upload                   │ Generate URL                     ║
║       │                                    │                                  ║
║       ▼                                    ▼                                ║
║  ┌─────────────────────────────────────────────────────────────────────┐   ║
║  │                    Original Storage (S3)                             │   ║
║  │                                                                       │   ║
║  │    URL: https://s3.amazonaws.com/videos/vid123?signature=xyz&exp=1hr │   ║
║  │                                                                       │   ║
║  └───────────────────────────────┬─────────────────────────────────────┘   ║
║                                  │                                          ║
║                    4. S3 Event Notification                                 ║
║                                  │                                          ║
║                                  ▼                                          ║
║                         ┌──────────────┐                                    ║
║                         │    KAFKA     │  ← NOT API Server!                ║
║                         └──────┬───────┘                                    ║
║                                │                                            ║
║                                ▼                                            ║
║                    5. Transcoding Service (polls queue)                    ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### S3 Event → Kafka Message Format

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  S3 EVENT MESSAGE (Published to Kafka topic: "video-uploaded")              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  When S3 upload completes, this event is published to Kafka:               ║
║                                                                               ║
║  {                                                                           ║
║    "eventType": "ObjectCreated:Put",                                        ║
║    "eventTime": "2024-01-15T12:30:45.123Z",                                 ║
║                                                                               ║
║    "bucket": "video-uploads-temp",                                          ║
║    "objectKey": "users/123/videos/vid_abc123/original.mp4",                ║
║    "objectSize": 1073741824,           // 1GB in bytes                      ║
║    "contentType": "video/mp4",                                              ║
║    "eTag": "d41d8cd98f00b204e9800998ecf8427e",                             ║
║                                                                               ║
║    // Metadata set during pre-signed URL generation                         ║
║    "metadata": {                                                             ║
║      "videoId": "vid_abc123",                                               ║
║      "userId": "123",                                                        ║
║      "title": "My Awesome Video",                                           ║
║      "uploadedAt": "2024-01-15T12:30:45.123Z"                              ║
║    }                                                                         ║
║  }                                                                           ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  FOR GOP-BASED UPLOADS (one message per GOP):                               ║
║  ─────────────────────────────────────────────                               ║
║                                                                               ║
║  {                                                                           ║
║    "eventType": "ObjectCreated:Put",                                        ║
║    "bucket": "video-uploads-temp",                                          ║
║    "objectKey": "users/123/videos/vid_abc123/gop_003.mp4",                 ║
║    "objectSize": 10485760,             // ~10MB per GOP                     ║
║                                                                               ║
║    "metadata": {                                                             ║
║      "videoId": "vid_abc123",                                               ║
║      "gopIndex": 3,                    // Which GOP (0-indexed)             ║
║      "totalGops": 10,                  // Total GOPs in video               ║
║      "isLastGop": false                // True for final GOP                ║
║    }                                                                         ║
║  }                                                                           ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  CONSUMER LOGIC:                                                            ║
║  ────────────────                                                            ║
║                                                                               ║
║  // Transcoding worker poll() loop                                          ║
║  while (true) {                                                              ║
║      records = consumer.poll(Duration.ofSeconds(1));                        ║
║      for (record : records) {                                               ║
║          S3Event event = parse(record.value());                             ║
║                                                                               ║
║          // Download from S3                                                 ║
║          byte[] video = s3.getObject(event.bucket, event.objectKey);       ║
║                                                                               ║
║          // Transcode to multiple resolutions                               ║
║          transcode(video, ["1080p", "720p", "480p", "360p"]);              ║
║                                                                               ║
║          // Upload transcoded versions to permanent storage                 ║
║          s3.putObject("video-transcoded", outputPath, transcodedVideo);    ║
║                                                                               ║
║          // Commit offset (mark as processed)                               ║
║          consumer.commitSync();                                              ║
║      }                                                                       ║
║  }                                                                           ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### AWS Concepts Explained

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  PRE-SIGNED URL - What & Why?                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHAT IS IT?                                                                ║
║  ───────────                                                                 ║
║  A pre-signed URL is a temporary URL with embedded authentication           ║
║  that allows anyone with the URL to upload/download from S3 directly.       ║
║                                                                               ║
║  Example URL:                                                               ║
║  https://my-bucket.s3.amazonaws.com/video123.mp4                            ║
║    ?X-Amz-Algorithm=AWS4-HMAC-SHA256                                        ║
║    &X-Amz-Credential=AKIA.../20240115/us-east-1/s3/aws4_request            ║
║    &X-Amz-Date=20240115T120000Z                                             ║
║    &X-Amz-Expires=3600              ← Valid for 1 hour                      ║
║    &X-Amz-SignedHeaders=host                                                ║
║    &X-Amz-Signature=abc123...       ← Cryptographic signature               ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WHY USE IT?                                                                ║
║  ────────────                                                                ║
║                                                                               ║
║  ❌ WITHOUT Pre-signed URL (Bad):                                           ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║  Client (browser) ─── 1GB video ───→ API Server ───────→ S3                ║
║                                          ↑                                   ║
║                                     BOTTLENECK!                              ║
║                                     (API server handles all bytes)           ║
║                                                                               ║
║  Problems:                                                                  ║
║  • API server memory exhausted (1GB video in memory!)                       ║
║  • Network bandwidth consumed twice (client→API, API→S3)                   ║
║  • High latency for user                                                    ║
║  • API servers can't scale (tied up handling uploads)                       ║
║                                                                               ║
║  ───────────────────────────────────────────────────────────────────────── ║
║                                                                               ║
║  ✓ WITH Pre-signed URL (Good):                                              ║
║  ──────────────────────────────                                              ║
║                                                                               ║
║  Client (JS in browser) ──→ API Server ──→ Generate URL (tiny, fast)      ║
║     │                                                                        ║
║     └────────── 1GB video ───────────────────────→ S3 (direct!)            ║
║                                                                               ║
║  Benefits:                                                                  ║
║  • API server only generates URL (milliseconds)                            ║
║  • Client (browser) uploads directly to S3 (no middleman)                  ║
║  • S3 handles the heavy lifting (designed for this!)                       ║
║  • API servers stay lean and fast                                          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  FRONTEND CODE EXAMPLE (runs in user's browser):                           ║
║  ─────────────────────────────────────────────────                          ║
║                                                                               ║
║  // File: youtube-web/src/components/Upload.js                             ║
║                                                                               ║
║  async function uploadVideo(videoFile, title) {                            ║
║      // Step 1: Call YOUR backend API (small request)                      ║
║      const response = await fetch('/api/upload/init', {                    ║
║          method: 'POST',                                                    ║
║          body: JSON.stringify({ title })                                   ║
║      });                                                                    ║
║      const { presignedUrl, videoId } = await response.json();              ║
║                                                                               ║
║      // Step 2: Upload DIRECTLY to S3 (NOT your server!)                   ║
║      await fetch(presignedUrl, {                                           ║
║          method: 'PUT',                                                     ║
║          body: videoFile  // 1GB goes directly to S3                       ║
║      });                                                                    ║
║  }                                                                          ║
║                                                                               ║
║  KEY INSIGHT:                                                               ║
║  • This JS code is SERVED by Web Server (Nginx/CDN)                        ║
║  • This JS code RUNS in user's browser                                     ║
║  • /api/upload/init is handled by API Server (Spring Boot/Node.js)        ║
║  • presignedUrl points directly to S3 (bypasses all your servers!)        ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SECURITY:                                                                  ║
║  ──────────                                                                  ║
║  • URL is TIME-LIMITED (expires in 1 hour typically)                       ║
║  • Cryptographic signature prevents tampering                              ║
║  • Only allows access to specific object (not entire bucket)               ║
║  • Can restrict to specific operations (PUT only, GET only)                ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  KAFKA (Message Queue) - What & Why?                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHAT IS IT?                                                                ║
║  ───────────                                                                 ║
║  Kafka is a distributed message queue / event streaming platform.           ║
║  • Producer sends messages to a TOPIC (partitioned log)                    ║
║  • Consumers poll() messages at their own pace                             ║
║  • Messages are durable (stored on disk, replicated, retained)             ║
║                                                                               ║
║  ┌──────────┐   publish   ┌───────────────┐    poll()   ┌──────────┐       ║
║  │ Producer │ ──────────→ │ Kafka Topic   │ ←────────── │ Consumer │       ║
║  └──────────┘             │ (partitions)  │             └──────────┘       ║
║                           └───────────────┘                                 ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WHY USE IT FOR VIDEO PROCESSING?                                           ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║  1. DECOUPLING                                                              ║
║     • Upload and transcoding are independent                               ║
║     • Upload succeeds even if transcoding is slow                          ║
║                                                                               ║
║  2. DURABILITY                                                              ║
║     • If transcoding worker crashes, message stays in Kafka                ║
║     • Consumer offset not committed → message is retried                   ║
║                                                                               ║
║  3. SCALING                                                                 ║
║     • Partition by video_id for parallelism                                ║
║     • Add more consumers to consumer group as load grows                   ║
║                                                                               ║
║  4. RATE LIMITING (Backpressure)                                           ║
║     • Workers poll() at their own pace                                     ║
║     • No thundering herd if 1000 videos upload at once                     ║
║                                                                               ║
║  5. MULTIPLE CONSUMERS                                                      ║
║     • Same event can trigger: Transcoding, Thumbnail, Notification         ║
║     • Different consumer groups process independently                      ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  KAFKA TOPIC DESIGN FOR VIDEO:                                              ║
║  ──────────────────────────────                                              ║
║                                                                               ║
║  Topic: "video-uploaded"                                                    ║
║  ┌─────────────────────────────────────────────────────────────────────────┐║
║  │ Partition 0: [vid001, vid004, vid007...]                                │║
║  │ Partition 1: [vid002, vid005, vid008...]                                │║
║  │ Partition 2: [vid003, vid006, vid009...]                                │║
║  └─────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
║  Consumer Groups:                                                           ║
║  • "transcoding-workers" → transcode videos                                ║
║  • "thumbnail-workers"   → generate thumbnails                             ║
║  • "notification-workers" → notify user when complete                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  AWS LAMBDA - What & Why?                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHAT IS IT?                                                                ║
║  ───────────                                                                 ║
║  Lambda is SERVERLESS compute. You write a function, AWS runs it           ║
║  automatically when triggered. No servers to manage!                       ║
║                                                                               ║
║  // Lambda function (pseudo-code)                                           ║
║  function handleS3Upload(event) {                                          ║
║      videoId = event.s3.object.key;                                        ║
║      kafka.publish("video-uploads", {videoId: videoId});                   ║
║  }                                                                          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  TRIGGER TYPES:                                                             ║
║  ───────────────                                                             ║
║  • S3 Event (object created, deleted)                                      ║
║  • HTTP (API Gateway)                                                      ║
║  • SQS Message                                                             ║
║  • Scheduled (cron)                                                        ║
║  • And many more...                                                        ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WHY USE IT FOR VIDEO PROCESSING?                                           ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║  ┌────┐    S3 Event    ┌────────┐    Publish    ┌───────┐                  ║
║  │ S3 │ ──────────────→│ Lambda │ ────────────→ │ Kafka │                  ║
║  └────┘   "new object" └────────┘   "transcode" └───────┘                  ║
║                             │                                               ║
║                    Runs automatically!                                      ║
║                    No server to manage!                                     ║
║                                                                               ║
║  Benefits:                                                                  ║
║  • Zero infrastructure to manage                                           ║
║  • Scales automatically (1000 uploads = 1000 Lambdas)                     ║
║  • Pay only when triggered (not idle servers)                              ║
║  • Perfect for "glue" between services                                     ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  LAMBDA LIMITATIONS:                                                        ║
║  ────────────────────                                                        ║
║  • Max 15 minutes runtime (can't run long transcoding)                     ║
║  • Limited memory (10GB max)                                               ║
║  • Stateless (no persistent connections)                                   ║
║                                                                               ║
║  So Lambda is used to TRIGGER transcoding, not DO transcoding.             ║
║  Lambda publishes to Kafka/SQS, workers do the actual encoding.            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Parallel GOP Upload (Figure 14-23)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  GOP = Group of Pictures (video segment starting with keyframe)             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  For LARGE videos, client splits into GOPs and uploads in parallel:        ║
║                                                                               ║
║  ┌─────────┐     ┌─────┐                                                    ║
║  │         │ ──→ │GOP 1│ ──→ S3                                            ║
║  │         │     └─────┘                                                    ║
║  │ Client  │     ┌─────┐                                                    ║
║  │         │ ──→ │GOP 2│ ──→ S3  (parallel uploads!)                       ║
║  │         │     └─────┘                                                    ║
║  │         │     ┌─────┐                                                    ║
║  │         │ ──→ │GOP 3│ ──→ S3                                            ║
║  └─────────┘     └─────┘                                                    ║
║                                                                               ║
║  BENEFITS:                                                                  ║
║  • Faster upload (parallel)                                                ║
║  • Resumable (re-upload only failed GOPs)                                  ║
║  • Better network utilization                                              ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 4. Transcoding Pipeline (Figures 14-8, 14-10)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TRANSCODING = Converting video to multiple formats/resolutions             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHY TRANSCODE?                                                             ║
║  ───────────────                                                             ║
║  • Different devices need different resolutions                             ║
║  • Adaptive bitrate streaming (switch quality based on network)             ║
║  • Standardize codecs (H.264, H.265, VP9)                                  ║
║                                                                               ║
║  ENCODING OUTPUTS (Figure 14-9):                                            ║
║  ──────────────────────────────                                             ║
║  Original ──┬──→ 360p.mp4  (low quality, mobile data)                      ║
║             ├──→ 480p.mp4                                                   ║
║             ├──→ 720p.mp4  (HD)                                            ║
║             ├──→ 1080p.mp4 (Full HD)                                       ║
║             └──→ 4k.mp4    (Ultra HD)                                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### DAG Scheduler (Figure 14-15)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DAG = Directed Acyclic Graph (task dependencies)                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHY DAG?                                                                   ║
║  • Some tasks can run in parallel (video encode, audio encode)             ║
║  • Some tasks depend on others (merge depends on video + audio)            ║
║  • DAG scheduler optimizes execution order                                 ║
║                                                                               ║
║  EXAMPLE DAG:                                                               ║
║  ─────────────                                                               ║
║                                                                               ║
║  STAGE 1 (Split):                                                          ║
║  Original ──→ [VIDEO_SPLIT] ──┬──→ video.raw                              ║
║                                ├──→ audio.raw                              ║
║                                └──→ metadata.json                          ║
║                                                                               ║
║  STAGE 2 (Parallel encoding):                                              ║
║                   ┌──→ [ENCODE 360p] ────────────────────┐                 ║
║                   ├──→ [ENCODE 720p] ────────────────────┤                 ║
║  video.raw ───────┼──→ [ENCODE 1080p] ───────────────────┼──→ STAGE 3     ║
║                   └──→ [THUMBNAIL] ──────────────────────┤                 ║
║  audio.raw ───────────→ [AUDIO ENCODE] ──────────────────┘                 ║
║                                                                               ║
║  STAGE 3 (Merge):                                                          ║
║  [MERGE 360p] ──→ 360p.mp4                                                 ║
║  [MERGE 720p] ──→ 720p.mp4                                                 ║
║  [MERGE 1080p] ──→ 1080p.mp4                                               ║
║                                                                               ║
║  PARALLELISM: Stage 2 tasks run in PARALLEL!                               ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  TECH STACK:                                                                 ║
║  • Workflow: Apache Airflow, AWS Step Functions, Temporal, Netflix Conductor║
║  • Encoding: FFmpeg (industry standard for video transcoding)               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Resource Manager (Figure 14-17)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  RESOURCE MANAGER - Manages workers and task queues                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  COMPONENTS:                                                                ║
║  ────────────                                                                ║
║                                                                               ║
║  1. TASK QUEUE (priority queue)                                            ║
║     • High-priority videos (premium users, trending)                       ║
║     • FIFO for same priority                                               ║
║     → Message: {taskId, videoId, taskType: "encode_720p", priority: 1}    ║
║                                                                               ║
║  2. WORKER QUEUE (available workers)                                       ║
║     • Specialized workers (encoder, thumbnail, merger)                     ║
║     • General-purpose workers                                              ║
║     → Message: {workerId, type: "encoder", status: "idle", capacity: 2}   ║
║                                                                               ║
║  3. RUNNING QUEUE (in-progress tasks)                                      ║
║     • Track progress, handle failures                                      ║
║     → Message: {taskId, workerId, startTime, progress: 45%, heartbeat}    ║
║                                                                               ║
║  4. TASK SCHEDULER                                                         ║
║     • Matches tasks to optimal workers                                     ║
║     • Considers worker specialization and load                             ║
║                                                                               ║
║  FLOW:                                                                      ║
║  ──────                                                                      ║
║  ┌───────────┐    get highest     ┌──────────────┐    run task    ┌───────┐║
║  │Task Queue │ ──→ priority ──→  │Task Scheduler│ ────────────→ │Workers│║
║  └───────────┘                    └──────────────┘                └───────┘║
║                                          ↑                                  ║
║  ┌────────────┐    get optimal    ┌──────┴──────┐                          ║
║  │Worker Queue│ ───────────────→ │             │                          ║
║  └────────────┘      worker       └─────────────┘                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Metadata Update Flow (Important!)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WHEN IS METADATA DB UPDATED?                                                ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Metadata is updated at 3 KEY POINTS in the video lifecycle:                 ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 1: INITIAL CREATION (when user clicks "Upload")                       ║
║  ──────────────────────────────────────────────────────                       ║
║  When: API Server receives POST /api/upload/init                             ║
║  Who: API Server writes to Metadata DB                                       ║
║                                                                               ║
║  Client ──→ POST /api/upload/init ──→ API Server ──→ Metadata DB            ║
║                                                                               ║
║  INSERT INTO videos (                                                        ║
║      video_id = "vid_abc123",                                                ║
║      user_id = "123",                                                        ║
║      title = "My Awesome Video",                                             ║
║      status = "UPLOADING",           ← Initial status                       ║
║      created_at = NOW(),                                                     ║
║      s3_original_key = "users/123/videos/vid_abc123/original.mp4"           ║
║  );                                                                          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 2: UPLOAD COMPLETE (when S3 upload finishes)                          ║
║  ──────────────────────────────────────────────────                          ║
║  When: S3 event notification triggers (via Lambda/Kafka)                    ║
║  Who: Lambda function OR Transcoding Service (first thing it does)          ║
║                                                                               ║
║  S3 Event ──→ Lambda ──→ Metadata DB                                        ║
║                                                                               ║
║  UPDATE videos SET                                                           ║
║      status = "PROCESSING",          ← Upload done, transcoding started     ║
║      file_size = 1073741824,         ← Actual file size from S3 event      ║
║      upload_completed_at = NOW()                                             ║
║  WHERE video_id = "vid_abc123";                                              ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  STEP 3: TRANSCODING COMPLETE (when all resolutions are ready)              ║
║  ─────────────────────────────────────────────────────────────               ║
║  When: DAG scheduler marks all tasks as complete                            ║
║  Who: Transcoding Service / Completion Handler                              ║
║                                                                               ║
║  Transcoding ──→ "All tasks done" ──→ Metadata DB                           ║
║                                                                               ║
║  UPDATE videos SET                                                           ║
║      status = "READY",               ← Video is playable!                   ║
║      duration = 125,                 ← Extracted during transcoding         ║
║      thumbnail_url = "https://cdn.example.com/vid_abc123/thumb.jpg",        ║
║      processing_completed_at = NOW()                                         ║
║  WHERE video_id = "vid_abc123";                                              ║
║                                                                               ║
║  -- Also insert available resolutions                                        ║
║  INSERT INTO video_renditions (video_id, resolution, url, bitrate) VALUES   ║
║      ("vid_abc123", "360p", "https://cdn.../vid_abc123/360p.m3u8", 500),    ║
║      ("vid_abc123", "720p", "https://cdn.../vid_abc123/720p.m3u8", 2500),   ║
║      ("vid_abc123", "1080p", "https://cdn.../vid_abc123/1080p.m3u8", 5000); ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Client Perspective: Sync vs Async

| Part | Sync/Async | Client Behavior |
|------|------------|-----------------|
| **Get pre-signed URL** | Sync | `await fetch('/api/upload/init')` - client waits for URL |
| **Upload to S3** | Sync | `await fetch(presignedUrl, {body: file})` - client waits for 1GB to upload |
| **Transcoding** | **Async** | Client shows "Processing..." and polls `GET /api/videos/{id}/status` or waits for push notification |

**Key Insight**: Upload is synchronous (client blocks until complete), but transcoding is asynchronous (client doesn't wait, checks status later).

---

## 5. Video Streaming (Figure 14-7, 14-28)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  CDN vs ORIGIN (Figure 14-28)                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  POPULAR VIDEOS → CDN (edge servers)                                        ║
║  ─────────────────────────────────────                                       ║
║  • Cached at 200+ edge locations globally                                  ║
║  • Low latency (~50ms)                                                     ║
║  • Expensive (pay per GB transferred)                                      ║
║  • Used for: viral videos, trending content                                ║
║                                                                               ║
║  LESS POPULAR VIDEOS → Origin servers                                       ║
║  ──────────────────────────────────────                                      ║
║  • Fetched from Transcoded Storage                                         ║
║  • Higher latency                                                          ║
║  • Cheaper                                                                 ║
║  • Used for: old videos, niche content                                     ║
║                                                                               ║
║  DECISION LOGIC:                                                            ║
║  ─────────────────                                                           ║
║  if (views > threshold || trending):                                        ║
║      serve from CDN                                                         ║
║  else:                                                                      ║
║      serve from origin                                                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Adaptive Bitrate Streaming

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  ADAPTIVE BITRATE STREAMING (HLS/DASH)                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHAT IS IT?                                                                ║
║  ────────────                                                                ║
║  Video quality that AUTO-ADJUSTS based on your internet speed!             ║
║  • Fast internet → 1080p (crisp)                                           ║
║  • Slow internet → 360p (no buffering, just lower quality)                 ║
║  • Prevents the "buffering" spinner!                                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WHO DOES WHAT?                                                             ║
║  ───────────────                                                             ║
║                                                                               ║
║  YOUR BACKEND (Transcoding Service):                                        ║
║  ┌─────────────────────────────────────────────────────────────────────────┐║
║  │ 1. Creates MULTIPLE versions of same video:                             │║
║  │    original.mp4 → 360p.mp4, 480p.mp4, 720p.mp4, 1080p.mp4              │║
║  │                                                                          │║
║  │ 2. Creates MANIFEST file (.m3u8) listing all versions                   │║
║  │                                                                          │║
║  │ 3. Uploads all to CDN/S3                                                │║
║  └─────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
║  VIDEO PLAYER (Client - browser/app):                                       ║
║  ┌─────────────────────────────────────────────────────────────────────────┐║
║  │ 1. Fetches manifest file from CDN                                       │║
║  │ 2. Monitors network bandwidth in real-time                              │║
║  │ 3. Picks appropriate quality (no server involvement!)                   │║
║  │ 4. If bandwidth drops mid-video → switches to lower quality instantly  │║
║  │                                                                          │║
║  │ Libraries: hls.js, video.js, Shaka Player, ExoPlayer (Android)         │║
║  └─────────────────────────────────────────────────────────────────────────┘║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  FLOW:                                                                       ║
║  ──────                                                                      ║
║                                                                               ║
║  1. User clicks "Play"                                                      ║
║     │                                                                        ║
║  2. Player fetches: CDN/videos/vid123/manifest.m3u8                        ║
║     │                                                                        ║
║  3. Manifest says: "Here are your options: 360p, 480p, 720p, 1080p"        ║
║     │                                                                        ║
║  4. Player measures: "My bandwidth is 3 Mbps"                              ║
║     │                                                                        ║
║  5. Player decides: "I'll request 720p chunks" (2.8 Mbps needed)           ║
║     │                                                                        ║
║  6. Player fetches: CDN/videos/vid123/720p/segment_001.ts                  ║
║     │                                                                        ║
║  7. [Later] Bandwidth drops to 1 Mbps → Switch to 480p mid-video!          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  MANIFEST FILE EXAMPLE (HLS - .m3u8):                                       ║
║  ──────────────────────────────────────                                      ║
║  #EXTM3U                                                                    ║
║  #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360                     ║
║  360p.m3u8                                                                  ║
║  #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=854x480                    ║
║  480p.m3u8                                                                  ║
║  #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720                   ║
║  720p.m3u8                                                                  ║
║  #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080                  ║
║  1080p.m3u8                                                                 ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  PROTOCOLS:                                                                 ║
║  ───────────                                                                 ║
║  • HLS (HTTP Live Streaming) - Apple, most compatible, used by YouTube    ║
║  • DASH (Dynamic Adaptive Streaming over HTTP) - Open standard, Netflix   ║
║  • RTMP - Legacy, low latency (for live streaming)                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 6. Database Deep-Dive

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATABASES USED                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. METADATA DB (MySQL/PostgreSQL)                                          ║
║     ────────────────────────────────                                         ║
║     Purpose: Store video metadata                                           ║
║     Schema:                                                                 ║
║       videos:                                                               ║
║         - video_id (PK)                                                    ║
║         - user_id (FK)                                                     ║
║         - title, description                                               ║
║         - duration, thumbnail_url                                          ║
║         - created_at, updated_at                                           ║
║                                                                               ║
║       video_stats:                                                          ║
║         - video_id (PK)                                                    ║
║         - view_count, like_count, dislike_count                           ║
║         (Separate table for high-write volume)                             ║
║                                                                               ║
║       video_encodings:                                                      ║
║         - video_id, resolution, cdn_url, file_size                        ║
║                                                                               ║
║  2. METADATA CACHE (Redis)                                                  ║
║     ─────────────────────────                                                ║
║     Purpose: Cache hot metadata                                             ║
║     Pattern: Cache-aside                                                    ║
║     TTL: 1 hour                                                            ║
║     Keys:                                                                   ║
║       video:metadata:{videoId} → VideoMetadata JSON                        ║
║       video:viewcount:{videoId} → Counter (buffered)                       ║
║       trending:daily → Sorted set                                          ║
║                                                                               ║
║  3. ORIGINAL STORAGE (S3 / Blob Storage)                                   ║
║     ─────────────────────────────────────                                   ║
║     Purpose: Temporary storage for raw uploads                             ║
║     Lifecycle: Deleted after transcoding (cost optimization)               ║
║                                                                               ║
║  4. TRANSCODED STORAGE (S3 / Blob Storage)                                 ║
║     ─────────────────────────────────────────                               ║
║     Purpose: Permanent storage for encoded videos                          ║
║     Structure:                                                              ║
║       s3://transcoded-videos/{video_id}/                                   ║
║         ├── 360p.mp4                                                       ║
║         ├── 720p.mp4                                                       ║
║         ├── 1080p.mp4                                                      ║
║         ├── thumbnail.jpg                                                  ║
║         └── manifest.m3u8                                                  ║
║                                                                               ║
║  5. CDN (CloudFront / Akamai / Cloudflare)                                 ║
║     ──────────────────────────────────────                                  ║
║     Purpose: Edge caching for low-latency delivery                         ║
║     Cache policy: Popular videos, configurable TTL                         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 7. Cost Optimization Strategies

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  COST OPTIMIZATION                                                          ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. CDN COST ($150K/day in example)                                        ║
║     ───────────────────────────────                                          ║
║     • Only cache popular videos (>N views)                                 ║
║     • Less popular videos → origin servers                                 ║
║     • Multi-CDN strategy for negotiating prices                            ║
║                                                                               ║
║  2. STORAGE COST                                                            ║
║     ─────────────                                                            ║
║     • Delete original videos after transcoding                             ║
║     • Only generate resolutions <= original                                ║
║     • Use storage tiers (hot/warm/cold)                                    ║
║                                                                               ║
║  3. TRANSCODING COST                                                        ║
║     ──────────────────                                                       ║
║     • Use spot/preemptible instances                                       ║
║     • Batch low-priority videos during off-peak                            ║
║     • Re-use encoded segments for similar videos                           ║
║                                                                               ║
║  4. BANDWIDTH                                                               ║
║     ───────────                                                              ║
║     • Compression (H.265/HEVC is 50% smaller than H.264)                   ║
║     • Adaptive bitrate (don't over-serve quality)                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 8. Database Choice Tradeoffs

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WHY MySQL FOR METADATA (Not NoSQL)?                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  DECISION: MySQL/PostgreSQL for video metadata                              ║
║                                                                               ║
║  REASONS:                                                                   ║
║  • Complex queries (search by title, filter by category, date range)       ║
║  • Relationships (user → videos → comments)                                ║
║  • Familiarity (easier to debug, more tooling)                             ║
║  • ACID not critical here (eventual consistency OK)                        ║
║                                                                               ║
║  TRADEOFF:                                                                  ║
║  • Video metadata is READ-HEAVY (millions of reads, few writes)            ║
║  • Sharding by user_id or video_id works fine                              ║
║  • Cache absorbs most read traffic (Redis)                                 ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  WHY S3 FOR VIDEO STORAGE (Not Database)?                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  DECISION: S3/Blob storage for video files                                  ║
║                                                                               ║
║  REASONS:                                                                   ║
║  • Designed for large binary objects                                       ║
║  • Unlimited scale                                                         ║
║  • Integrates with CDN (CloudFront pulls from S3)                          ║
║  • Pay per GB (cost-effective for petabytes)                              ║
║                                                                               ║
║  ALTERNATIVE CONSIDERED:                                                    ║
║  • MongoDB GridFS - doesn't scale as well                                  ║
║  • HDFS - good for batch processing, not serving                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 9. Interview Quick Answers

**Q: Why use pre-signed URLs for upload?**
> "Video files are large (up to 1GB). Uploading through API servers would overload them. Pre-signed URLs let clients upload directly to S3, bypassing API servers. The URL is temporary (1 hour) and includes authentication signature."

**Q: Who receives the S3 event after upload - API server or something else?**
> "NOT the API server! API servers are stateless and designed for HTTP request/response only. S3 events go to SQS or trigger a Lambda function. Transcoding workers poll from SQS at their own pace. This decouples the upload path from the transcoding path and allows independent scaling."

**Q: What exactly is a pre-signed URL and why is it critical for video upload?**
> "A pre-signed URL is a temporary S3 URL with an embedded cryptographic signature that allows direct upload without going through our servers. It expires (typically 1 hour) and is scoped to a specific object. Without it, a 1GB video would flow through our API server, exhausting memory and bandwidth. With pre-signed URL, the API server just generates the URL (milliseconds), and the client uploads directly to S3."

**Q: Why use SQS between S3 and transcoding workers?**
> "Three reasons: (1) Decoupling - upload succeeds even if transcoding is slow. (2) Durability - if a worker crashes, the message stays in queue and gets retried. (3) Rate limiting - workers poll at their own pace, so 1000 simultaneous uploads won't overwhelm the system."

**Q: What's the difference between SQS and Lambda in this architecture?**
> "Lambda is a 'glue' function that runs when S3 triggers an event - it typically publishes to Kafka/SQS and returns. Lambda has a 15-minute limit, so it can't do transcoding itself. SQS is a message queue that stores work items until workers are ready. Workers poll SQS, do the actual transcoding (which can take minutes/hours), and ack the message when done."

**Q: How does the DAG scheduler optimize transcoding?**
> "It models task dependencies as a DAG. Independent tasks (encoding 360p, 720p, audio) run in parallel on different workers. Dependent tasks (merge) wait for prerequisites. This maximizes parallelism and reduces total transcoding time."

**Q: Why delete original videos after transcoding?**
> "Cost optimization. Original 1GB video is only needed until transcoding completes. Keeping it wastes storage. If re-transcoding is needed, we can re-upload or keep for a short retention period."

**Q: How does adaptive bitrate streaming work?**
> "Videos are encoded at multiple resolutions. A manifest file lists all available qualities with their bandwidths. The player monitors network conditions and switches quality seamlessly. Better network = higher quality, poor network = lower quality."

**Q: CDN vs origin servers - how do you decide?**
> "Popular videos (above view threshold, trending) go to CDN for low latency globally. Less popular videos are served from origin (cheaper). This hybrid approach balances cost and performance."

**Q: How do you handle transcoding failures?**
> "Each task is tracked in the running queue. If a worker fails, the task is retried on another worker. After N retries, mark as failed and notify user. DAG ensures dependent tasks don't start until prerequisites complete."

**Q: When is the Metadata DB updated during video upload?**
> "At 3 key points: (1) Initial creation when user clicks upload - we INSERT with status='UPLOADING' and return pre-signed URL; (2) After S3 upload completes - Lambda/event handler updates status='PROCESSING'; (3) After all transcoding tasks complete - we update status='READY', add duration, thumbnail_url, and insert all available renditions (360p, 720p, 1080p URLs). The video is only playable after step 3."

**Q: What's a GOP and why does it matter?**
> "Group of Pictures - a video segment starting with a keyframe (I-frame). GOPs enable parallel upload (each GOP uploads independently) and seeking (jump to nearest keyframe). Smaller GOP = faster seeking but larger file size."

**Q: How do you reduce CDN costs?**
> "Only cache popular videos on CDN. Use multi-CDN strategy for price negotiation. Compress videos (H.265 is 50% smaller than H.264). Adaptive bitrate prevents over-serving quality. Consider geographic placement of origin servers."

---

## 10. Scalability Strategies

| Component | Scaling Approach |
|-----------|-----------------|
| **API Servers** | Horizontal scaling, stateless, load balancer |
| **Transcoding** | Auto-scale workers based on queue depth, spot instances |
| **Storage** | S3 auto-scales, multi-region for durability |
| **Metadata DB** | Read replicas, sharding by video_id |
| **Cache** | Redis cluster, consistent hashing |
| **CDN** | Inherently distributed, 200+ edge locations |

---

## 11. Failure Scenarios

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| Transcoding worker crash | Task stuck | Retry on different worker, timeout detection |
| S3 upload fails | Video lost | Resumable upload (retry failed GOPs) |
| CDN outage | Slow streaming | Multi-CDN failover, serve from origin |
| Metadata DB down | Can't play videos | Read replicas, cache continues serving |
| DAG scheduler crash | Pipeline stuck | Checkpoint state, resume from last stage |

---

## 12. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                                                                               ║
║    ┌──────────┐                                                              ║
║    │  Client  │                                                              ║
║    └────┬─────┘                                                              ║
║         │                                                                     ║
║         ├──────────────── POST /upload ──────────────────┐                   ║
║         │                                                 │                   ║
║         │                                                 ▼                   ║
║         │                                          ┌────────────┐            ║
║         │     GET /video/{id}/stream              │ API Server │            ║
║         │◀────────────────────────────────────────│            │            ║
║         │                                          └─────┬──────┘            ║
║         │                                                │                   ║
║         │                                    ┌───────────┼───────────┐       ║
║         │                                    │           │           │       ║
║         │                              Pre-signed    ①INSERT    Read/Query  ║
║         │                                 URL     "UPLOADING"       │       ║
║         │                                    │           │           │       ║
║    ┌────┴────┐                              ▼           ▼           │       ║
║    │         │    Direct Upload      ┌─────────────────────────┐   │       ║
║    │  CDN    │◀──────────────────────│ Original Storage (S3)   │   │       ║
║    │         │                       └───────────┬─────────────┘   │       ║
║    └─────────┘                                   │                  │       ║
║         ▲                                        │ S3 Event         │       ║
║         │                                        ▼                  │       ║
║         │                              ┌─────────────────┐          │       ║
║         │                              │ Lambda + Kafka  │ ②UPDATE │       ║
║         │                              │                 │"PROCESSING"     ║
║         │                              └────────┬────────┘     │    │       ║
║         │                                       │              │    │       ║
║         │                                       ▼              │    │       ║
║         │                        ┌───────────────────────────┐ │    │       ║
║         │                        │  TRANSCODING PIPELINE     │ │    │       ║
║         │                        │                           │ │    │       ║
║         │                        │ Preprocessor → DAG        │ │    │       ║
║         │                        │ → Resource Manager        │ │    │       ║
║         │                        │ → Task Workers            │ │    │       ║
║         │                        │                           │ │    │       ║
║         │                        └─────────────┬─────────────┘ │    │       ║
║         │                                      │               │    │       ║
║         │ Push                 ③UPDATE "READY"│◀──────────────┘    │       ║
║         │                      + renditions    │                    │       ║
║         │                                      ▼                    │       ║
║         │                           ┌──────────────────┐           │       ║
║         │                           │Transcoded Storage│           │       ║
║         └───────────────────────────│     (S3)         │           │       ║
║                                     └──────────────────┘           │       ║
║                                                                     │       ║
║                                     ┌──────────────────┐           │       ║
║                                     │  METADATA DB     │◀──────────┘       ║
║                                     │  (MySQL/Postgres)│                   ║
║                                     │                  │                   ║
║                                     │  videos:         │                   ║
║                                     │  - video_id      │                   ║
║                                     │  - status        │                   ║
║                                     │  - duration      │                   ║
║                                     │  - thumbnail_url │                   ║
║                                     │                  │                   ║
║                                     │  renditions:     │                   ║
║                                     │  - 360p, 720p... │                   ║
║                                     └──────────────────┘                   ║
║                                                                               ║
║  METADATA DB WRITES:                                                        ║
║  ① API Server: INSERT video (status = "UPLOADING")                         ║
║  ② Lambda: UPDATE status = "PROCESSING"                                    ║
║  ③ Transcoding: UPDATE status = "READY" + insert renditions               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

**For hands-on code, run:** `mvn compile exec:java`

