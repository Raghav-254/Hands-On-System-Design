# 3-Hour Pre-Interview Revision Plan

> **Purpose**: Quick revision of cross-cutting system design patterns before an interview. Each file maps patterns to the specific systems where they're applied, with the reasoning for each choice.

---

## Reading Schedule

| Time | File | Focus | Minutes |
|------|------|-------|---------|
| 0:00 | **01_SCALING_PATTERNS.md** | Caching, CDN, Sharding, Fan-out, Geospatial | 30 |
| 0:30 | **02_CONSISTENCY_PATTERNS.md** | Locking, Idempotency, Outbox, Saga, State Machine, IDs, Ledger | 35 |
| 1:05 | **03_ASYNC_PATTERNS.md** | Kafka, Consumer Groups, Stream Processing, Exactly-once, Batch | 30 |
| 1:35 | **04_STORAGE_PATTERNS.md** | DB Selection, ES, S3, Hot/Warm/Cold, CQRS, Denormalization | 30 |
| 2:05 | **05_REALTIME_AND_API.md** | WebSocket/SSE/Polling, CDC, Pre-signed URLs, Heartbeat, Webhooks | 25 |
| 2:30 | **06_RELIABILITY_PATTERNS.md** | Retry, Circuit Breaker, Buffering, Compensating Transactions | 20 |
| 2:50 | Re-read weak areas | Whichever patterns you're least confident on | 10 |

---

## If Your Interview Is About...

| Interview Topic | Priority Files | Key Patterns to Nail |
|----------------|---------------|---------------------|
| **Chat / Messaging** | 01, 03, 05 | Fan-out, Kafka, WebSocket, Presence/Heartbeat |
| **E-commerce / Booking** | 01, 02, 06 | Pessimistic Locking, Idempotency, State Machine, Compensating Transactions |
| **Social Media / Feed** | 01, 03, 04 | Fan-out (hybrid), Caching, Denormalization, Batch Pre-computation |
| **Financial / Payments** | 02, 06 | Double-entry Ledger, Idempotency, Saga, Reconciliation, Exactly-once |
| **Search / Discovery** | 01, 04 | Elasticsearch, Caching, Sharding, Geospatial |
| **Streaming / Media** | 01, 04, 05 | CDN, Pre-signed URLs, S3, Adaptive Bitrate, Hot/Warm/Cold |
| **Infrastructure / Logging** | 03, 04, 06 | Kafka Buffer, Stream Processing, ES, Backpressure, Local Buffering |
| **Real-time / Location** | 01, 05 | Geospatial, WebSocket, Heartbeat, Redis Pub/Sub |

---

## How to Read Each File

Every pattern follows the same format:

1. **What** — 2-3 line explanation
2. **When to use / When NOT to use**
3. **Applied in our systems** — table with 3 columns:
   - System (with link)
   - Specific use case
   - **Why this pattern and not the alternative** (the interview gold)
4. **Theory deep-dive** — link to `database_fundamentals/` for background

The most important column is always **"Why this pattern and not the alternative"** — that's what interviewers ask.

---

## System Reference

| # | System | Cheatsheet |
|---|--------|------------|
| 1 | Chat | [View](../chat_system/INTERVIEW_CHEATSHEET.md) |
| 2 | News Feed | [View](../news_feed_system/INTERVIEW_CHEATSHEET.md) |
| 3 | Autocomplete | [View](../autocomplete_system/INTERVIEW_CHEATSHEET.md) |
| 4 | Video Streaming | [View](../video_streaming_system/INTERVIEW_CHEATSHEET.md) |
| 5 | Google Drive | [View](../google_drive_system/INTERVIEW_CHEATSHEET.md) |
| 6 | Proximity Service | [View](../proximity_service/INTERVIEW_CHEATSHEET.md) |
| 7 | Nearby Friends | [View](../nearby_friends_system/INTERVIEW_CHEATSHEET.md) |
| 8 | Google Maps | [View](../google_maps_system/INTERVIEW_CHEATSHEET.md) |
| 9 | Distributed MQ | [View](../distributed_message_queue/INTERVIEW_CHEATSHEET.md) |
| 10 | Ad Click Aggregation | [View](../ad_click_aggregation_system/INTERVIEW_CHEATSHEET.md) |
| 11 | Hotel Reservation | [View](../hotel_reservation_system/INTERVIEW_CHEATSHEET.md) |
| 12 | Distributed Email | [View](../distributed_email_service/INTERVIEW_CHEATSHEET.md) |
| 13 | S3 Object Storage | [View](../s3_object_storage_system/INTERVIEW_CHEATSHEET.md) |
| 14 | Real-time Leaderboard | [View](../realtime_leaderboard_system/INTERVIEW_CHEATSHEET.md) |
| 15 | Payment System | [View](../payment_system/INTERVIEW_CHEATSHEET.md) |
| 16 | Digital Wallet | [View](../digital_wallet_system/INTERVIEW_CHEATSHEET.md) |
| 17 | URL Shortener | [View](../url_shortener_system/INTERVIEW_CHEATSHEET.md) |
| 18 | BookMyShow | [View](../bookmyshow_system/INTERVIEW_CHEATSHEET.md) |
| 19 | Uber | [View](../uber_system/INTERVIEW_CHEATSHEET.md) |
| 20 | Splitwise | [View](../splitwise_system/INTERVIEW_CHEATSHEET.md) |
| 21 | Spotify | [View](../spotify_system/INTERVIEW_CHEATSHEET.md) |
| 22 | Log Aggregation | [View](../log_aggregation_system/INTERVIEW_CHEATSHEET.md) |
