# Google Drive System - Interview Cheat Sheet (Senior Engineer Deep-Dive)

Based on Alex Xu's System Design Interview - Chapter 15

---

## Quick Reference Card

| Component | Purpose | Storage | Key Points |
|-----------|---------|---------|------------|
| **Block Server** | Split, compress, encrypt files | Stateless | Delta sync - only upload changed blocks |
| **Cloud Storage** | Store file blocks | S3/Blob | Immutable blocks, content-addressed |
| **Cold Storage** | Old file versions | Glacier | Cost optimization, infrequent access |
| **Metadata DB** | File/folder structure | MySQL | User, workspace, file, file_version, block |
| **Metadata Cache** | Fast metadata lookups | Redis | Cache-aside pattern |
| **Notification Service** | Real-time sync | Long polling | Notify devices of changes |
| **Offline Backup Queue** | Offline device sync | Persistent | Deliver when device comes online |

---

## 1. Requirements Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FUNCTIONAL REQUIREMENTS                                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Upload and download files                                                 ║
║  • Sync files across multiple devices automatically                          ║
║  • Offline access: Work on files without internet (desktop/mobile apps)     ║
║  • See file revisions (version history)                                      ║
║  • Share files with friends, family, coworkers                               ║
║  • Send notifications when files are edited, deleted, or shared              ║
║                                                                               ║
║  SUPPORTED:                                                                  ║
║  • Mobile app, web app, and desktop app (with local sync)                   ║
║  • Any file type                                                             ║
║  • File size up to 10 GB                                                     ║
║  • Files must be encrypted in storage                                        ║
║                                                                               ║
║  OUT OF SCOPE:                                                               ║
║  • Google Docs real-time collaborative editing                               ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  NON-FUNCTIONAL REQUIREMENTS                                                 ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  • Reliability: Data loss is UNACCEPTABLE                                    ║
║  • Fast sync speed: Users won't wait for slow syncs                         ║
║  • Bandwidth efficiency: Minimize network usage (mobile data plans!)        ║
║  • Scalability: Handle high traffic volumes                                  ║
║  • High availability: Work even when some servers are down                  ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SCALE ESTIMATION                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  USERS:                                                                      ║
║  • 50M signed up users, 10M DAU                                             ║
║  • 10 GB free space per user                                                ║
║                                                                               ║
║  UPLOADS:                                                                    ║
║  • 2 files uploaded per user per day                                        ║
║  • Average file size: 500 KB                                                ║
║  • Total uploads: 10M × 2 × 500KB = 10 TB/day                              ║
║                                                                               ║
║  STORAGE:                                                                    ║
║  • If all users use full quota: 50M × 10GB = 500 PB                        ║
║  • Realistic (10% usage): 50 PB                                             ║
║                                                                               ║
║  READ/WRITE RATIO: 1:1 (file sync is bidirectional)                        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 2. API Endpoints

### API Summary (Quick Reference)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  UPLOAD (3-Step Flow - See Section 4 for details)                           │
├─────────────────────────────────────────────────────────────────────────────┤
│  Step 1: POST /api/files/upload-init     → Get file_id + upload_url        │
│  Step 2: POST {upload_url}               → Upload to Block Server          │
│  Step 3: POST /api/files/{id}/complete   → Finalize metadata               │
├─────────────────────────────────────────────────────────────────────────────┤
│  DOWNLOAD                                                                   │
│  GET /api/files/{id}/download            → Get block URLs                  │
│  GET /blocks/{hash}                      → Download block                  │
├─────────────────────────────────────────────────────────────────────────────┤
│  SYNC                                                                       │
│  GET /api/sync/changes?cursor=X          → Long polling for changes        │
└─────────────────────────────────────────────────────────────────────────────┘
```

### METADATA APIs (API Server)

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  FILE OPERATIONS                                                             ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                              ║
║  GET /api/files/{file_id}                                                    ║
║  ─────────────────────────                                                   ║
║  Description: Get file metadata                                              ║
║  Response: { file_id, name, path, size, checksum, versions: [...] }          ║
║                                                                              ║
║  GET /api/files/{file_id}/download                                           ║
║  ─────────────────────────────────                                           ║
║  Description: Get download URLs for file blocks                              ║
║  Response: {                                                                 ║
║    "blocks": [                                                               ║
║      { "hash": "abc", "url": "https://storage.../abc", "order": 0 }          ║
║    ]                                                                         ║
║  }                                                                           ║
║                                                                               ║
║  DELETE /api/files/{file_id}                                                 ║
║  ───────────────────────────                                                 ║
║  Description: Delete file (move to trash, not permanent)                   ║
║  Response: 204 No Content                                                    ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  BLOCK DOWNLOAD (used by both approaches)                                    ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /blocks/{block_hash}                                                    ║
║  ─────────────────────────                                                   ║
║  Description: Download a block (for file download/sync)                     ║
║  Response: Raw binary data (encrypted block)                                ║
║  Client must: Decrypt → Decompress → Reconstruct file from blocks           ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SYNC APIs                                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  GET /api/sync/changes                                                       ║
║  ──────────────────────                                                      ║
║  Description: Get changes since last sync (long polling)                   ║
║  Query Params:                                                               ║
║  • cursor: last_sync_timestamp or version_id                                ║
║  • timeout: max wait time in seconds (default 30)                           ║
║                                                                               ║
║  Response: {                                                                 ║
║    "changes": [                                                              ║
║      { "file_id": "123", "action": "modified", "version": 5 },             ║
║      { "file_id": "456", "action": "deleted" }                             ║
║    ],                                                                        ║
║    "next_cursor": "cursor_abc123"                                           ║
║  }                                                                           ║
║                                                                               ║
║  POST /api/sync/register                                                     ║
║  ────────────────────────                                                    ║
║  Description: Register device for push notifications                       ║
║  Request: { "device_id": "...", "device_type": "ios|android|desktop" }     ║
║  Response: 200 OK                                                            ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  SHARING APIs                                                                ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  POST /api/files/{file_id}/share                                             ║
║  ────────────────────────────────                                            ║
║  Description: Share file with another user                                  ║
║  Request: { "user_email": "bob@example.com", "permission": "view|edit" }   ║
║  Response: { "share_id": "share_123" }                                      ║
║                                                                               ║
║  GET /api/files/{file_id}/versions                                           ║
║  ──────────────────────────────────                                          ║
║  Description: Get version history                                           ║
║  Response: { "versions": [{ version: 3, modified_at, modified_by }, ...] } ║
║                                                                               ║
║  POST /api/files/{file_id}/restore/{version}                                 ║
║  ────────────────────────────────────────────                                ║
║  Description: Restore file to a previous version                           ║
║  Response: { "file_id": "123", "version": 4 }                               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 3. High-Level Architecture (Figure 15-10)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  GOOGLE DRIVE ARCHITECTURE                                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║          ┌─────────────────────────────────────────────────────────────┐    ║
║          │                         USER                                │    ║
║          │                  ┌───────┐  ┌───────┐                       │    ║
║          │                  │  Web  │  │Mobile │                       │    ║
║          │                  │Browser│  │  App  │                       │    ║
║          │                  └───┬───┘  └───┬───┘                       │    ║
║          └──────────────────────┼──────────┼───────────────────────────┘    ║
║                                 │          │                                 ║
║                                 ▼          ▼                                 ║
║                          ┌─────────────────────┐                            ║
║                          │   Load Balancer     │                            ║
║                          └──────────┬──────────┘                            ║
║                                     │                                        ║
║         ┌───────────────────────────┼───────────────────────────┐           ║
║         │                           │                           │           ║
║         ▼                           ▼                           ▼           ║
║  ┌─────────────┐            ┌─────────────┐           ┌──────────────┐     ║
║  │   Block     │            │    API      │           │ Notification │     ║
║  │   Servers   │            │   Servers   │           │   Service    │     ║
║  │             │            │             │           │              │     ║
║  │ • Split     │            │ • Metadata  │           │ • Long poll  │     ║
║  │ • Compress  │            │ • Auth      │           │ • Push sync  │     ║
║  │ • Encrypt   │            │ • Sharing   │           │              │     ║
║  └──────┬──────┘            └──────┬──────┘           └──────┬───────┘     ║
║         │                          │                          │             ║
║         │                          │                          │             ║
║         ▼                          ▼                          ▼             ║
║  ┌─────────────┐     ┌───────────────────────┐     ┌──────────────────┐    ║
║  │   Cloud     │     │     Metadata DB       │     │  Offline Backup  │    ║
║  │   Storage   │     │      (MySQL)          │     │      Queue       │    ║
║  │    (S3)     │     ├───────────────────────┤     └──────────────────┘    ║
║  └──────┬──────┘     │    Metadata Cache     │                             ║
║         │            │       (Redis)         │                             ║
║         ▼            └───────────────────────┘                             ║
║  ┌─────────────┐                                                            ║
║  │    Cold     │                                                            ║
║  │   Storage   │  ← Old versions moved here                                ║
║  │  (Glacier)  │                                                            ║
║  └─────────────┘                                                            ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### ⚠️ WHO DOES WHAT (Clear Separation - Per Diagram)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  BLOCK SERVER                    │  API SERVER                             │
│  (Handles file data ONLY)        │  (Handles metadata + everything else)   │
│  ❌ NO DB access                 │  ✅ HAS DB access                       │
├──────────────────────────────────┼─────────────────────────────────────────┤
│  ✅ Receive raw file from client │  ✅ Authentication/Authorization        │
│  ✅ Split into blocks            │  ✅ Update Metadata DB                  │
│  ✅ Compress blocks              │  ✅ List files/folders                  │
│  ✅ Encrypt blocks               │  ✅ Share files                         │
│  ✅ Hash blocks                  │  ✅ Get version history                 │
│  ✅ Upload to S3                 │  ✅ Restore versions                    │
│  ✅ Download blocks from S3      │  ✅ Sync changes (long polling)         │
│  ✅ Return block hashes to client│  ✅ Move/rename/delete                  │
│  ❌ NO Metadata DB access        │  ✅ Trigger Notification Service        │
├──────────────────────────────────┴─────────────────────────────────────────┤
│  KEY INSIGHT: Block Server is STATELESS (no DB connection)                 │
│  • Block Server only handles data: split, compress, encrypt, S3            │
│  • API Server handles all state/metadata                                    │
│  • This allows Block Servers to scale independently                        │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 4. Block Server & Upload Flow Deep-Dive (Figure 15-11, 15-12)

### Why Block-Based Storage?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  BLOCK SERVER - The Heart of File Sync                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  WHY BLOCK-BASED STORAGE?                                                   ║
║  ─────────────────────────                                                   ║
║  Instead of storing entire files, we split files into blocks (4MB each)    ║
║                                                                               ║
║  Benefits:                                                                  ║
║  • Delta sync: Only upload/download CHANGED blocks                         ║
║  • Deduplication: Same block stored once, shared by many files            ║
║  • Resume: Failed upload? Resume from last successful block                ║
║  • Parallel: Upload/download multiple blocks simultaneously               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### 3-Step Upload Flow (Core Interview Topic!)

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  3-STEP UPLOAD FLOW (Block Server is STATELESS - no DB access)               ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                              ║
║  STEP 1: Client → API Server (Initialize)                         [SYNC]    ║
║  ─────────────────────────────────────────                                   ║
║  POST /api/files/upload-init                                                 ║
║  Request: { "file_name": "doc.pdf", "size": 10485760, "path": "/Work/" }     ║
║  Response: {                                                                 ║
║    "file_id": "123",                                                         ║
║    "upload_url": "https://block-server-3.example.com/upload?token=xyz"       ║
║  }                                                                           ║
║                                                                              ║
║  STEP 2: Client → Block Server (Upload raw file)                  [SYNC]    ║
║  ─────────────────────────────────────────────                               ║
║  POST {upload_url}   ← Use the URL from Step 1!                              ║
║  Headers: Content-Type: multipart/form-data                                  ║
║  Body: <raw binary file data>                                                ║
║                                                                              ║
║  Block Server does:                                                          ║
║    1. Split into 4MB blocks                                                  ║
║    2. Compress (gzip/lz4)                                                    ║
║    3. Encrypt (AES-256)                                                      ║
║    4. Compute hash (SHA-256)                                                 ║
║    5. Upload to S3 (key = hash, dedup automatic)                             ║
║                                                                              ║
║  Response: { "blocks": ["hash_1", "hash_2", "hash_3"] }                      ║
║  ⚠️ Block Server does NOT touch Metadata DB!                                 ║
║                                                                              ║
║  STEP 3: Client → API Server (Finalize)                           [SYNC]    ║
║  ─────────────────────────────────────────                                   ║
║  POST /api/files/{file_id}/complete                                          ║
║  Request: { "blocks": ["hash_1", "hash_2", "hash_3"] }                       ║
║  API Server: Updates Metadata DB, triggers notifications [ASYNC]             ║
║  Response: { "status": "success", "version": 1 }                             ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

### What is `upload_url`?

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  upload_url EXPLAINED                                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  Example: "https://block-server-3.example.com/upload?token=xyz123"          │
│            ─────────────────────────────────────── ───────────────          │
│                        │                                │                   │
│            Specific Block Server               Authentication Token        │
│            (load balanced)                     (short-lived, ~1 hour)       │
│                                                                             │
│  WHY INCLUDE A SPECIFIC SERVER?                                             │
│  • Load Balancing: API Server picks least-loaded Block Server              │
│  • Session Affinity: For resumable uploads, same server handles retry      │
│  • Geographic: Pick Block Server closest to user                           │
│                                                                             │
│  WHY INCLUDE A TOKEN?                                                       │
│  • Authentication: Block Server doesn't have DB access to verify users     │
│  • Authorization: Token includes user_id, file_id, max_size, expiry        │
│  • Security: Prevents unauthorized uploads to Block Server                  │
│                                                                             │
│  Token payload (JWT-style):                                                 │
│  { "user_id": "user_123", "file_id": "file_456",                           │
│    "max_size": 10737418240, "expires": 1706234567 }                        │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Why 3 Steps? (Tradeoffs)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  WHY CAN'T CLIENT DIRECTLY CALL BLOCK SERVER?                               │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ❌ REJECTED: Client → Block Server directly                                │
│  Problems:                                                                  │
│  1. Authentication: Block Server has NO DB access → can't verify user      │
│  2. File ID: Who generates the unique file_id?                             │
│  3. Path validation: Quota check? Permissions?                             │
│  4. Rate limiting: Can't enforce without DB                                │
│  5. Security: Block Server would need to be publicly exposed               │
│                                                                             │
│  ✅ CHOSEN: API Server first, then Block Server                            │
│  Benefits:                                                                  │
│  1. Separation of concerns: API = metadata, Block = data                   │
│  2. Security: Block Server only accepts valid tokens                       │
│  3. Flexibility: Can add quota checks in Step 1                            │
│  4. Stateless Block Server: Easy to scale horizontally                     │
│                                                                             │
├─────────────────────────────────────────────────────────────────────────────┤
│  ALTERNATIVE: Pre-signed URL (like YouTube) - WHY NOT?                      │
│  • S3 can't process files - it's just storage                              │
│  • Client can't encrypt (don't trust client with encryption keys)          │
│  • Need Block Server in the middle for split/compress/encrypt              │
│                                                                             │
│  ENCRYPTION COMPARISON:                                                     │
│  │ Aspect          │ YouTube              │ Google Drive           │       │
│  │─────────────────┼──────────────────────┼────────────────────────│       │
│  │ Content         │ Public videos        │ Private documents      │       │
│  │ Encryption      │ S3 server-side (SSE) │ Block Server (CSE)     │       │
│  │ Can cloud read? │ Technically yes      │ No! Only encrypted     │       │
│  │ When encrypted? │ After S3 receives    │ Before S3 receives     │       │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Sync vs Async (Client Perspective)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Step │ Operation              │ Sync/Async │ Duration                      │
│  ─────┼────────────────────────┼────────────┼───────────────────────────────│
│   1   │ Init (get upload_url)  │ SYNC       │ ~50ms                         │
│   2   │ Upload to Block Server │ SYNC       │ ~80sec for 100MB              │
│   3   │ Finalize metadata      │ SYNC       │ ~100ms                        │
│  Post │ Notify other devices   │ ASYNC      │ Background                    │
├─────────────────────────────────────────────────────────────────────────────┤
│  User clicks "Upload" → [Step 1] → [=====Step 2=====] → [Step 3] → Done!   │
│                          ~50ms       ~80 seconds         ~100ms             │
│                                          ↑                                  │
│                                  Progress bar here                          │
├─────────────────────────────────────────────────────────────────────────────┤
│  WHY SYNC (not async like YouTube)?                                         │
│  • Google Drive: split/compress/encrypt = fast (seconds)                   │
│  • YouTube: transcoding = 30+ minutes (must be async)                      │
│  • User expects file IMMEDIATELY available after upload                    │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Upload Failure Handling

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  UPLOAD FAILURE HANDLING (3-Step Flow):                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  UPLOAD FAILURE HANDLING (3-Step Flow):                                     ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Step │ Failure Point            │ Impact            │ Solution              ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   1   │ API Server init fails    │ Nothing started   │ Client retries        ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   2   │ Client→Block Server fails│ No data uploaded  │ Client retries Step 2 ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   2   │ Block Server→S3 fails    │ Partial upload    │ Client retries;       ║
║       │                          │                   │ resumable upload      ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   2   │ S3 OK, response fails    │ Blocks in S3,     │ Client retries Step 2;║
║       │                          │ client doesn't    │ Block Server returns  ║
║       │                          │ know              │ same hashes (dedup)   ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   3   │ Finalize to API fails    │ Orphan blocks     │ Client retries Step 3;║
║       │                          │ (S3 has blocks,   │ Garbage collection    ║
║       │                          │ no metadata)      │ cleans orphans later  ║
║  ─────┼──────────────────────────┼───────────────────┼───────────────────────║
║   3   │ API saves, response fails│ File saved but    │ Client retries Step 3;║
║       │                          │ client unsure     │ API returns "exists"  ║
║  ─────┴──────────────────────────┴───────────────────┴───────────────────────║
║                                                                               ║
║  KEY INSIGHT: Steps are IDEMPOTENT                                          ║
║  • Step 2 retry: Same file → same hashes → S3 dedup (no duplicate storage)  ║
║  • Step 3 retry: Same file_id + blocks → API returns success                ║
║                                                                               ║
║  Garbage Collection:                                                        ║
║  • Periodic job scans S3 for blocks with ref_count = 0                     ║
║  • Orphan blocks (not referenced by any file) for >7 days → delete         ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### S3 Storage Format (Content-Addressed)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  S3 STORAGE FORMAT                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  S3 is a KEY-VALUE store:                                                   ║
║  • Key = SHA-256 hash of block content                                      ║
║  • Value = Compressed + Encrypted block data (binary)                      ║
║                                                                               ║
║  EXAMPLE:                                                                   ║
║  ─────────                                                                   ║
║  Alice uploads "report.pdf" (10MB) → splits into 3 blocks                  ║
║                                                                               ║
║  S3 Bucket after upload:                                                    ║
║  ┌────────────────────────────────────────────────────────────────────────┐ ║
║  │ KEY (hash)                              │ VALUE (data)                  │ ║
║  ├────────────────────────────────────────────────────────────────────────┤ ║
║  │ s3://blocks/a3f2b8c9e1d4f7a2b3c8...    │ [2.1MB encrypted binary]     │ ║
║  │ s3://blocks/7e9d2a1b5c8f3d4e6a7b...    │ [2.0MB encrypted binary]     │ ║
║  │ s3://blocks/4b6e8a2d9c3f1a5b7d8e...    │ [1.2MB encrypted binary]     │ ║
║  └────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  Metadata DB (MySQL) stores file structure:                                 ║
║  ┌────────────────────────────────────────────────────────────────────────┐ ║
║  │ file_id: "file_123"                                                     │ ║
║  │ name: "report.pdf"                                                      │ ║
║  │ user: "alice"                                                           │ ║
║  │ blocks: [                                                               │ ║
║  │   { order: 0, hash: "a3f2b8c9e1d4f7a2b3c8..." },                       │ ║
║  │   { order: 1, hash: "7e9d2a1b5c8f3d4e6a7b..." },                       │ ║
║  │   { order: 2, hash: "4b6e8a2d9c3f1a5b7d8e..." }                        │ ║
║  │ ]                                                                       │ ║
║  └────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Upload Sequence Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FILE UPLOAD SEQUENCE DIAGRAM                                                ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Client 1   Client 2   Block     Cloud      API      Metadata   Notification ║
║     │          │      Servers   Storage   Servers      DB        Service    ║
║     │          │         │         │         │          │           │        ║
║     │──────────┼─────────┼─────────┼────────►│          │           │        ║
║     │          │         │         │    1. Init upload  │           │        ║
║     │          │         │         │    (get file_id)   │           │        ║
║     │          │         │         │         │         │           │        ║
║     │◄─────────┼─────────┼─────────┼─────────│         │           │        ║
║     │          │         │         │  { file_id, upload_url }      │        ║
║     │          │         │         │         │         │           │        ║
║     │─────────────────► │         │         │         │           │        ║
║     │   2. Upload file  │         │         │         │           │        ║
║     │      (raw bytes)  │         │         │         │           │        ║
║     │          │         │         │         │         │           │        ║
║     │          │         │────────►│         │         │           │        ║
║     │          │         │ 2.1 Upload blocks (compressed + encrypted)      ║
║     │          │         │         │         │         │           │        ║
║     │◄─────────┼─────────│         │         │         │           │        ║
║     │          │  { blocks: [hash1, hash2, hash3] }    │           │        ║
║     │          │         │         │         │         │           │        ║
║     │──────────┼─────────┼─────────┼────────►│         │           │        ║
║     │          │         │         │  3. Complete      │           │        ║
║     │          │         │         │  { blocks: [...] }│           │        ║
║     │          │         │         │         │         │           │        ║
║     │          │         │         │         │────────►│           │        ║
║     │          │         │         │         │ 3.1 Save file→blocks│        ║
║     │          │         │         │         │         │           │        ║
║     │          │         │         │         │         │──────────►│        ║
║     │          │         │         │         │         │ 3.2 Notify│        ║
║     │          │         │         │         │         │   change  │        ║
║     │          │         │         │         │         │           │        ║
║     │          │◄────────┼─────────┼─────────┼─────────┼───────────│        ║
║     │          │         │         │  4. Notify changes (to Client 2)     ║
║     │          │         │         │         │         │           │        ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

**Note:** Deduplication and Delta Sync (client-side hashing) are covered in Section 14 (Optimization).

---

## 5. Metadata Database Schema (Figure 15-13)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DATABASE SCHEMA                                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ┌─────────────────┐       ┌─────────────────────┐                          ║
║  │      user       │       │      workspace      │                          ║
║  ├─────────────────┤       ├─────────────────────┤                          ║
║  │ user_id    (PK) │──┐    │ id           (PK)   │                          ║
║  │ user_name       │  │    │ owner_id     (FK)   │──┐                       ║
║  │ email           │  └───→│ is_shared           │  │                       ║
║  │ created_at      │       │ created_at          │  │                       ║
║  └─────────────────┘       └─────────────────────┘  │                       ║
║                                     │               │                       ║
║                                     │ 1:N           │                       ║
║                                     ▼               │                       ║
║  ┌─────────────────┐       ┌─────────────────────┐  │                       ║
║  │     device      │       │        file         │  │                       ║
║  ├─────────────────┤       ├─────────────────────┤  │                       ║
║  │ device_id  (PK) │       │ id           (PK)   │  │                       ║
║  │ user_id    (FK) │───────│ file_name           │  │                       ║
║  │ device_name     │       │ relative_path       │  │                       ║
║  │ last_logged_in  │       │ is_directory        │  │                       ║
║  └─────────────────┘       │ latest_version (FK) │  │                       ║
║                            │ checksum            │  │                       ║
║                            │ workspace_id  (FK)  │←─┘                       ║
║                            │ created_at          │                          ║
║                            │ last_modified       │                          ║
║                            └─────────────────────┘                          ║
║                                     │                                        ║
║                                     │ 1:N                                    ║
║                                     ▼                                        ║
║                            ┌─────────────────────┐                          ║
║                            │    file_version     │                          ║
║                            ├─────────────────────┤                          ║
║                            │ id           (PK)   │                          ║
║                            │ file_id      (FK)   │                          ║
║                            │ device_id    (FK)   │ ← Which device modified ║
║                            │ version_number      │                          ║
║                            │ last_modified       │                          ║
║                            └─────────────────────┘                          ║
║                                     │                                        ║
║                                     │ 1:N                                    ║
║                                     ▼                                        ║
║                            ┌─────────────────────┐                          ║
║                            │       block         │                          ║
║                            ├─────────────────────┤                          ║
║                            │ block_id     (PK)   │ ← SHA256 hash           ║
║                            │ file_version_id(FK) │                          ║
║                            │ block_order         │ ← Position in file      ║
║                            │ block_size          │                          ║
║                            └─────────────────────┘                          ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 6. Download Flow Deep-Dive (Figure 15-15)

### When Does Download Trigger?

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DOWNLOAD SCENARIOS                                                          ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. NEW DEVICE SYNC                                                          ║
║     User installs Google Drive on new laptop                                 ║
║     → Download all files from cloud to local                                 ║
║                                                                               ║
║  2. FILE SYNC FROM ANOTHER DEVICE                                            ║
║     Alice edits file on Phone → Laptop needs to sync                         ║
║     → Download changed blocks to laptop                                      ║
║                                                                               ║
║  3. EXPLICIT DOWNLOAD                                                        ║
║     User clicks "Download" on a shared file                                  ║
║     → Download file to local machine                                         ║
║                                                                               ║
║  4. WEB BROWSER ACCESS                                                       ║
║     User opens file in browser (no local sync)                               ║
║     → Download/stream file for viewing                                       ║
║                                                                               ║
║  5. RESTORE FROM VERSION                                                     ║
║     User restores old version of file                                        ║
║     → Download blocks from that version                                      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Download is Different from Upload

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  THE CHALLENGE: Reconstructing Files from Blocks                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  In S3, we DON'T store "report.pdf" as a single file!                        ║
║                                                                               ║
║  We store BLOCKS:                                                            ║
║    s3://blocks/abc123 → [encrypted block 1]                                  ║
║    s3://blocks/def456 → [encrypted block 2]                                  ║
║    s3://blocks/ghi789 → [encrypted block 3]                                  ║
║                                                                               ║
║  Metadata DB stores the mapping:                                             ║
║    file_id: 123                                                              ║
║    name: "report.pdf"                                                        ║
║    blocks: [abc123, def456, ghi789]  ← in order                              ║
║                                                                               ║
║  DOWNLOAD FLOW MUST:                                                         ║
║  ─────────────────────                                                       ║
║  1. Get block list from Metadata DB                                          ║
║  2. Download each block from S3 (can be parallel!)                           ║
║  3. Decrypt each block                                                       ║
║  4. Decompress each block                                                    ║
║  5. Reassemble blocks into original file                                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Why Do We Need Local Copies? (Sync Model)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  TWO DIFFERENT STORAGE MODELS                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  ❌ SIMPLE CLOUD STORAGE (like a website)                                    ║
║  ─────────────────────────────────────────                                   ║
║  • Files ONLY exist in cloud                                                 ║
║  • You always need internet to access                                        ║
║  • Example: Viewing a file on a website                                      ║
║                                                                               ║
║  ✅ GOOGLE DRIVE / DROPBOX (sync model)                                      ║
║  ─────────────────────────────────────────                                   ║
║  • Files exist in BOTH cloud AND your device                                 ║
║  • You can work OFFLINE                                                      ║
║  • Changes sync automatically                                                ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  THE SYNC MODEL:                                                             ║
║  ────────────────                                                            ║
║                                                                               ║
║                        ┌─────────────┐                                       ║
║                        │    Cloud    │                                       ║
║                        │  (Master)   │                                       ║
║                        └─────────────┘                                       ║
║                       ▲       │       ▲                                      ║
║                upload │       │       │ upload                               ║
║               changes │       │       │ changes                              ║
║                       │       ▼       │                                      ║
║            ┌──────────┴───┐       ┌───┴──────────┐                          ║
║            │   Laptop     │       │    Phone     │                          ║
║            │ (local copy) │       │ (local copy) │                          ║
║            └──────────────┘       └──────────────┘                          ║
║                       │               │                                      ║
║                       │    download   │                                      ║
║                       └───────────────┘                                      ║
║                          each other's                                        ║
║                           changes                                            ║
║                                                                               ║
║  Edit on any device → Upload to cloud → Download to other devices           ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  WHY KEEP LOCAL COPIES?                                                      ║
║  ───────────────────────                                                     ║
║  │ Benefit         │ Explanation                                │           ║
║  │─────────────────┼────────────────────────────────────────────│           ║
║  │ Offline Access  │ Work on a flight, in subway, no internet   │           ║
║  │ Speed           │ Opening local file = instant (no network)  │           ║
║  │ Native Apps     │ Open in Word, Photoshop, etc. (need local) │           ║
║  │ Reliability     │ If cloud is down, you still have your files│           ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Download Flow (Simplified)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DOWNLOAD FLOW STEPS                                                         ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Step 1: Client → API Server                                                 ║
║  ─────────────────────────────                                               ║
║  GET /api/files/{file_id}/download                                           ║
║  Response: {                                                                 ║
║    "blocks": [                                                               ║
║      { "hash": "abc123", "url": "https://s3.../abc123", "order": 0 },       ║
║      { "hash": "def456", "url": "https://s3.../def456", "order": 1 },       ║
║      { "hash": "ghi789", "url": "https://s3.../ghi789", "order": 2 }        ║
║    ]                                                                         ║
║  }                                                                           ║
║                                                                               ║
║  Step 2: Client → S3/Block Server (parallel downloads!)                      ║
║  ─────────────────────────────────────────────────────                       ║
║  GET https://s3.../abc123 → [encrypted block 1]                              ║
║  GET https://s3.../def456 → [encrypted block 2]  ← All in parallel!         ║
║  GET https://s3.../ghi789 → [encrypted block 3]                              ║
║                                                                               ║
║  Step 3: Client reconstructs file locally                                    ║
║  ─────────────────────────────────────────────                               ║
║  For each block (in order):                                                  ║
║    1. Decrypt (AES-256)                                                      ║
║    2. Decompress (gzip)                                                      ║
║    3. Append to file                                                         ║
║                                                                               ║
║  Result: Original "report.pdf" reconstructed!                                ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Upload vs Download Comparison

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Aspect          │ Upload                      │ Download                   │
│  ────────────────┼─────────────────────────────┼────────────────────────────│
│  Direction       │ File → Blocks → S3          │ S3 → Blocks → File         │
│  Processing      │ Split → Compress → Encrypt  │ Decrypt → Decompress → Merge│
│  Who processes?  │ Block Server                │ CLIENT (has decrypt key!)  │
│  Parallel?       │ Block Server → S3           │ Client downloads multiple  │
│                  │                             │ blocks in parallel         │
│  State change    │ Creates new version in DB   │ No DB change (read-only)   │
├─────────────────────────────────────────────────────────────────────────────┤
│  KEY INSIGHT: Download decryption happens on CLIENT because only client    │
│  has the decryption key (from Key Management Service during auth)          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Smart Sync: Only Download Changed Blocks

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  DELTA SYNC ON DOWNLOAD                                                      ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Laptop's local copy (version 4):                                            ║
║  ┌───────┬───────┬───────┬───────┬───────┐                                  ║
║  │Block 1│Block 2│Block 3│Block 4│Block 5│                                  ║
║  │hash_A │hash_B │hash_C │hash_D │hash_E │                                  ║
║  └───────┴───────┴───────┴───────┴───────┘                                  ║
║                                                                               ║
║  Cloud's new version (version 5):                                            ║
║  ┌───────┬───────┬───────┬───────┬───────┐                                  ║
║  │Block 1│Block 2│Block 3│Block 4│Block 5│                                  ║
║  │hash_A │hash_X⚡│hash_C │hash_D │hash_E │  ← Only Block 2 changed!         ║
║  └───────┴───────┴───────┴───────┴───────┘                                  ║
║                                                                               ║
║  SYNC PROCESS:                                                               ║
║  ─────────────                                                               ║
║  1. Laptop receives notification: "file 123 has version 5"                   ║
║                                                                               ║
║  2. Laptop fetches metadata:                                                 ║
║     GET /api/files/123/metadata?version=5                                    ║
║     Response: { blocks: [hash_A, hash_X, hash_C, hash_D, hash_E] }          ║
║                                                                               ║
║  3. Laptop compares with local cache:                                        ║
║     - hash_A → I have this ✓                                                 ║
║     - hash_X → NEW! Need to download                                         ║
║     - hash_C → I have this ✓                                                 ║
║     - hash_D → I have this ✓                                                 ║
║     - hash_E → I have this ✓                                                 ║
║                                                                               ║
║  4. Download ONLY hash_X (1 block instead of 5!)                             ║
║                                                                               ║
║  5. Rebuild file locally with new block order                                ║
║                                                                               ║
║  RESULT: Downloaded 4MB instead of 20MB! 🎉                                  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Download Sequence Diagram

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  FILE DOWNLOAD SEQUENCE DIAGRAM                                              ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Client 2    Block     Cloud      API      Metadata   Notification          ║
║     │       Servers   Storage   Servers      DB        Service              ║
║     │          │         │         │          │           │                  ║
║     │◄─────────┼─────────┼─────────┼──────────┼───────────│                  ║
║     │          │         │         │  1. Notify changes   │                  ║
║     │          │         │         │    (long polling)    │                  ║
║     │          │         │         │          │           │                  ║
║     │──────────┼─────────┼────────►│          │           │                  ║
║     │          │         │  2. Get file metadata          │                  ║
║     │          │         │         │          │           │                  ║
║     │          │         │         │─────────►│           │                  ║
║     │          │         │         │ 3. Query │           │                  ║
║     │          │         │         │   blocks │           │                  ║
║     │          │         │         │          │           │                  ║
║     │          │         │         │◄─────────│           │                  ║
║     │          │         │  4. Return block list          │                  ║
║     │          │         │         │          │           │                  ║
║     │◄─────────┼─────────┼─────────│          │           │                  ║
║     │  5. Return block URLs + hashes          │           │                  ║
║     │          │         │         │          │           │                  ║
║     │──────────┼────────►│         │          │           │                  ║
║     │  6. Download blocks (PARALLEL)          │           │                  ║
║     │          │         │         │          │           │                  ║
║     │◄─────────┼─────────│         │          │           │                  ║
║     │  7. Encrypted blocks                    │           │                  ║
║     │          │         │         │          │           │                  ║
║     │──────┐   │         │         │          │           │                  ║
║     │      │ 8. Decrypt + Decompress + Reassemble file   │                  ║
║     │◄─────┘   │         │         │          │           │                  ║
║     │          │         │         │          │           │                  ║
║     │  9. File ready locally! ✓               │           │                  ║
║     │          │         │         │          │           │                  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 7. Notification Service Deep-Dive (Long Polling)

### The Core Problem

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  HOW DOES LAPTOP KNOW CLOUD HAS CHANGES?                                    ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Phone uploads new version of report.pdf to cloud...                        ║
║                                                                               ║
║       ┌──────────┐          ┌──────────┐          ┌──────────┐             ║
║       │  Phone   │ ──────▶  │  Cloud   │    ???   │  Laptop  │             ║
║       │ (edited) │  upload  │(updated) │  ─────▶  │  (stale) │             ║
║       └──────────┘          └──────────┘          └──────────┘             ║
║                                                                               ║
║  How does Laptop find out? Three options:                                   ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Three Approaches Comparison

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  OPTION 1: REGULAR POLLING (❌ Not Ideal)                                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Laptop                              Server                                  ║
║     │                                   │                                    ║
║     │──── "Any changes?" ──────────────▶│                                    ║
║     │◀─── "No" ─────────────────────────│                                    ║
║     │                                   │                                    ║
║     │  (wait 5 seconds)                 │                                    ║
║     │                                   │                                    ║
║     │──── "Any changes?" ──────────────▶│                                    ║
║     │◀─── "No" ─────────────────────────│                                    ║
║     │                                   │                                    ║
║     │  (wait 5 seconds)                 │                                    ║
║     │                                   │                                    ║
║     │──── "Any changes?" ──────────────▶│                                    ║
║     │◀─── "Yes! report.pdf changed" ────│                                    ║
║     │                                   │                                    ║
║                                                                               ║
║  PROBLEMS:                                                                   ║
║  • Wastes bandwidth (most requests return "no")                             ║
║  • Delay = polling interval (could be 5+ seconds stale)                     ║
║  • Server handles tons of unnecessary requests                              ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  OPTION 2: LONG POLLING (✅ Used by Dropbox/Google Drive)                   ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Laptop                         Notification Service                        ║
║     │                                   │                                    ║
║     │── GET /sync/changes?cursor=X ────▶│                                    ║
║     │                                   │  (server HOLDS connection open)    ║
║     │         ... waiting ...           │  (no response yet)                 ║
║     │         ... waiting ...           │                                    ║
║     │         ... 30 seconds ...        │                                    ║
║     │                                   │                                    ║
║     │                                   │◀── API Server: "file 123 changed"  ║
║     │                                   │    (triggered after upload)        ║
║     │                                   │                                    ║
║     │◀─── { changes: [file_123] } ──────│  (NOW server responds!)            ║
║     │                                   │                                    ║
║     │── GET /sync/changes?cursor=Y ────▶│  (immediately reconnect)           ║
║     │         ... waiting ...           │                                    ║
║                                                                               ║
║  NOTE: Notification Service is SEPARATE from API Server!                    ║
║  • API Server: handles file upload/download, metadata CRUD                  ║
║  • Notification Service: handles long-poll connections, sync events         ║
║                                                                               ║
║  BENEFITS:                                                                   ║
║  ✓ Near real-time (notification as soon as change happens)                  ║
║  ✓ No wasted requests                                                        ║
║  ✓ Simple HTTP (works through firewalls)                                     ║
║                                                                               ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║  OPTION 3: WEBSOCKET (✅ Alternative)                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Laptop                              Server                                  ║
║     │                                   │                                    ║
║     │════ WebSocket Connection ════════▶│                                    ║
║     │◀═══════════════════════════════════│  (stays open)                     ║
║     │                                   │                                    ║
║     │         ... connection open ...   │                                    ║
║     │                                   │                                    ║
║     │                                   │◀── Phone uploads report.pdf        ║
║     │                                   │                                    ║
║     │◀─── PUSH: "report.pdf changed" ───│  (server pushes immediately)       ║
║     │                                   │                                    ║
║                                                                               ║
║  BENEFITS:                                                                   ║
║  ✓ True real-time                                                            ║
║  ✓ Bidirectional (can also push from client)                                 ║
║                                                                               ║
║  DRAWBACKS:                                                                   ║
║  ✗ More complex                                                               ║
║  ✗ Connection management overhead                                             ║
║  ✗ May have issues with some firewalls/proxies                               ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Comparison Summary

```
┌─────────────────────────────────────────────────────────────────────────────┐
│  Aspect       │ Polling        │ Long Polling     │ WebSocket             │
│  ─────────────┼────────────────┼──────────────────┼───────────────────────│
│  Latency      │ High (interval)│ Low (near real)  │ Lowest (instant)      │
│  Bandwidth    │ Wasteful       │ Efficient        │ Efficient             │
│  Complexity   │ Simple         │ Medium           │ Complex               │
│  Connection   │ New each time  │ Held 30-60s      │ Persistent            │
│  Firewall     │ ✅ Works       │ ✅ Works         │ ⚠️ May have issues    │
│  Used by      │ Legacy systems │ Dropbox, GDrive  │ Chat apps             │
├─────────────────────────────────────────────────────────────────────────────┤
│  GOOGLE DRIVE USES LONG POLLING because:                                   │
│  • Near real-time is good enough (not a chat app)                          │
│  • Works through corporate firewalls                                       │
│  • Simpler than WebSocket                                                  │
│  • HTTP-based (easy to implement)                                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Complete Sync Flow with Long Polling

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  COMPLETE SYNC FLOW WITH LONG POLLING                                       ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  Phone          Cloud           Notification        Laptop                  ║
║    │            Storage          Service              │                     ║
║    │               │                │                 │                     ║
║    │               │                │◄────────────────│                     ║
║    │               │                │  1. Long poll   │                     ║
║    │               │                │  "Any changes?" │                     ║
║    │               │                │                 │                     ║
║    │               │                │  (connection    │                     ║
║    │               │                │   held open)    │                     ║
║    │               │                │                 │                     ║
║    │──────────────▶│                │                 │                     ║
║    │ 2. Upload     │                │                 │                     ║
║    │    report.pdf │                │                 │                     ║
║    │               │                │                 │                     ║
║    │               │───────────────▶│                 │                     ║
║    │               │ 3. Notify:     │                 │                     ║
║    │               │ "file_id=123   │                 │                     ║
║    │               │  changed"      │                 │                     ║
║    │               │                │                 │                     ║
║    │               │                │────────────────▶│                     ║
║    │               │                │ 4. Response:    │                     ║
║    │               │                │ "file_id=123    │                     ║
║    │               │                │  has new ver"   │                     ║
║    │               │                │                 │                     ║
║    │               │                │                 │──────┐              ║
║    │               │                │                 │      │ 5. Fetch     ║
║    │               │                │                 │◀─────┘ metadata     ║
║    │               │                │                 │                     ║
║    │               │◀───────────────┼─────────────────│                     ║
║    │               │                │  6. Download    │                     ║
║    │               │                │     blocks      │                     ║
║    │               │                │                 │                     ║
║    │               │────────────────┼────────────────▶│                     ║
║    │               │                │  7. Blocks      │                     ║
║    │               │                │                 │                     ║
║    │               │                │                 │──────┐              ║
║    │               │                │                 │      │ 8. Decrypt   ║
║    │               │                │                 │◀─────┘ & rebuild    ║
║    │               │                │                 │        file         ║
║    │               │                │                 │                     ║
║    │               │                │◄────────────────│                     ║
║    │               │                │  9. Long poll   │                     ║
║    │               │                │  (reconnect)    │                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### How Notification Service Knows WHO to Notify

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  NOTIFICATION SERVICE INTERNALS                                             ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  1. DEVICE SUBSCRIPTION TABLE                                               ║
║  ─────────────────────────────                                               ║
║  ┌────────────┬──────────────┬─────────────────┬─────────────────┐          ║
║  │ user_id    │ device_id    │ connection_id   │ last_sync_time  │          ║
║  ├────────────┼──────────────┼─────────────────┼─────────────────┤          ║
║  │ alice      │ laptop_1     │ conn_abc123     │ 2024-01-15 10:00│          ║
║  │ alice      │ phone_1      │ conn_def456     │ 2024-01-15 10:02│          ║
║  │ alice      │ tablet_1     │ conn_ghi789     │ 2024-01-15 09:55│          ║
║  └────────────┴──────────────┴─────────────────┴─────────────────┘          ║
║                                                                               ║
║  2. WHEN FILE CHANGES:                                                       ║
║  ─────────────────────                                                       ║
║  • Look up: "Which devices belong to this user?"                            ║
║  • For each device with active connection:                                  ║
║      - If device_id != uploading_device → Send notification                 ║
║  • Devices without connection will sync when they reconnect                 ║
║                                                                               ║
║  3. NOTIFICATION MESSAGE:                                                   ║
║  ─────────────────────────                                                   ║
║  {                                                                           ║
║    "type": "FILE_CHANGED",                                                  ║
║    "file_id": "123",                                                        ║
║    "version": 5,                                                            ║
║    "action": "UPDATE",    // or CREATE, DELETE, RENAME                     ║
║    "timestamp": "2024-01-15T10:05:00Z"                                      ║
║  }                                                                           ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

### Offline Device Handling & Offline Backup Queue

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WHERE DOES OFFLINE BACKUP QUEUE FIT?                                       ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  The Offline Backup Queue from the HLD is used here!                        ║
║                                                                               ║
║  FLOW WHEN FILE CHANGES:                                                    ║
║  ────────────────────────                                                    ║
║                                                                               ║
║  1. Phone uploads report.pdf                                                ║
║                        │                                                     ║
║                        ▼                                                     ║
║  2. API Server updates Metadata DB + notifies Notification Service         ║
║                        │                                                     ║
║                        ▼                                                     ║
║  3. Notification Service checks: "Which devices need to know?"              ║
║                        │                                                     ║
║        ┌───────────────┼───────────────┐                                    ║
║        ▼               ▼               ▼                                    ║
║     Laptop          Tablet          Desktop                                 ║
║   (OFFLINE)        (ONLINE)        (ONLINE)                                 ║
║        │               │               │                                    ║
║        ▼               ▼               ▼                                    ║
║  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐                         ║
║  │   Offline   │  │ Long Poll   │  │ Long Poll   │                         ║
║  │   Backup    │  │ Response    │  │ Response    │                         ║
║  │   Queue     │  │ Immediately │  │ Immediately │                         ║
║  └─────────────┘  └─────────────┘  └─────────────┘                         ║
║                                                                               ║
║  DECISION LOGIC:                                                            ║
║  • Device has active long-poll connection? → Send immediately              ║
║  • Device is offline (no connection)? → Queue in Offline Backup Queue      ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  OFFLINE DEVICE SCENARIO                                                    ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  SCENARIO: User's laptop is offline for 2 days                              ║
║                                                                               ║
║  Day 1: Laptop offline (no long-poll connection)                            ║
║  ────────────────────────────────────────────────                            ║
║  • Phone uploads file_A → Notification Service queues for laptop           ║
║  • Phone uploads file_B → Notification Service queues for laptop           ║
║  • Phone deletes file_C → Notification Service queues for laptop           ║
║                                                                               ║
║  OFFLINE BACKUP QUEUE (per device, stored in Redis/Kafka):                  ║
║  ┌────────────────────────────────────────────────────────────────────────┐ ║
║  │ device_id: laptop_1                                                     │ ║
║  │ changes: [                                                              │ ║
║  │   { file: "A", action: "CREATE", version: 1, timestamp: "..." },       │ ║
║  │   { file: "B", action: "CREATE", version: 1, timestamp: "..." },       │ ║
║  │   { file: "C", action: "DELETE", version: 2, timestamp: "..." }        │ ║
║  │ ]                                                                       │ ║
║  └────────────────────────────────────────────────────────────────────────┘ ║
║                                                                               ║
║  Day 3: Laptop comes online                                                 ║
║  ───────────────────────────                                                ║
║  1. Laptop starts long poll: GET /sync/changes?cursor=old_cursor           ║
║  2. Notification Service:                                                   ║
║     a. Check Offline Backup Queue for this device                          ║
║     b. Return ALL queued changes immediately (don't wait!)                 ║
║  3. Laptop processes changes in order (download new files, delete, etc.)   ║
║  4. Laptop updates cursor and reconnects for new long poll                 ║
║  5. Queue cleared for this device                                          ║
║                                                                               ║
║  KEY PROPERTIES:                                                            ║
║  • Queue is PERSISTENT (survives server restarts) - stored in Redis/Kafka  ║
║  • Queue is PER-DEVICE (each device has its own backlog)                   ║
║  • Changes are ORDERED by timestamp (process in sequence)                  ║
║  • Compaction: If file_A created then deleted, queue can compact to empty  ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 8. Conflict Resolution

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  CONFLICT HANDLING                                                           ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  SCENARIO: Two users edit the same file simultaneously                      ║
║                                                                               ║
║   User 1 (Laptop)         Our System           User 2 (Phone)               ║
║        │                      │                      │                       ║
║        │                      │                      │                       ║
║        │    1. Edit file      │                      │                       ║
║        │─────────────────────►│                      │                       ║
║        │                      │                      │                       ║
║        │     2. Sync OK       │                      │                       ║
║        │◄─────────────────────│                      │                       ║
║        │                      │                      │                       ║
║        │                      │     3. Edit same file│                       ║
║        │                      │◄─────────────────────│                       ║
║        │                      │                      │                       ║
║        │                      │  4. CONFLICT!        │                       ║
║        │                      │  File already modified│                      ║
║        │                      │─────────────────────►│                       ║
║        │                      │                      │                       ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  RESOLUTION STRATEGIES:                                                     ║
║  ───────────────────────                                                     ║
║                                                                               ║
║  1. LAST WRITER WINS (Simple, lossy)                                        ║
║     • Latest timestamp wins                                                 ║
║     • Other changes are lost                                                ║
║     • Used for: Simple files, non-critical data                            ║
║                                                                               ║
║  2. CREATE CONFLICTED COPY (Dropbox approach) ✓ RECOMMENDED                ║
║     • Keep both versions                                                    ║
║     • Rename: "report.docx" → "report (User2's conflicted copy).docx"      ║
║     • User manually merges                                                  ║
║     • No data loss!                                                         ║
║                                                                               ║
║  3. VERSION BRANCHING (Git-like)                                            ║
║     • Create version branches                                               ║
║     • UI for merge/resolve                                                  ║
║     • Complex but powerful                                                  ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  CONFLICT DETECTION (Optimistic Locking):                                  ║
║  ─────────────────────────────────────────                                   ║
║                                                                               ║
║  When uploading:                                                            ║
║  1. Client sends: { file_id, base_version: 3, new_blocks: [...] }          ║
║  2. Server checks: current_version == base_version?                        ║
║  3. If NO → Conflict! Reject upload, return conflict info                  ║
║  4. If YES → Accept, increment version to 4                                ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  HOW IS THE CHECK "ATOMIC"? (Compare-and-Swap via SQL)                     ║
║  ─────────────────────────────────────────────────────                       ║
║                                                                               ║
║  The atomicity comes from the DATABASE, not checksums!                      ║
║  Version is just an INTEGER column:                                         ║
║                                                                               ║
║  ┌─────────┬──────────────┬─────────┬─────────────────────┐                ║
║  │ file_id │ file_name    │ version │ blocks              │                ║
║  ├─────────┼──────────────┼─────────┼─────────────────────┤                ║
║  │ 123     │ report.pdf   │ 3       │ [abc, def, ghi]     │                ║
║  └─────────┴──────────────┴─────────┴─────────────────────┘                ║
║                                                                               ║
║  THE SQL TRICK:                                                             ║
║  ──────────────                                                              ║
║                                                                               ║
║  UPDATE files                                                               ║
║  SET version = version + 1, blocks = ['new_abc', 'new_def']                ║
║  WHERE file_id = 123 AND version = 3;                                       ║
║        ───────────────────────────────                                       ║
║              ↑ This is the atomic check!                                    ║
║                                                                               ║
║  • If version IS 3     → rows_affected = 1 → SUCCESS!                      ║
║  • If version is NOT 3 → rows_affected = 0 → CONFLICT!                     ║
║                                                                               ║
║  WHY IS IT ATOMIC?                                                          ║
║  • Database LOCKS the row during UPDATE                                    ║
║  • WHERE check + UPDATE happen as ONE indivisible operation                ║
║  • No other transaction can sneak in between                               ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  VERSION vs CHECKSUM (Different Purposes!):                                ║
║  ───────────────────────────────────────────                                 ║
║                                                                               ║
║  │ Aspect    │ Version Number       │ Checksum (SHA-256)     │             ║
║  │───────────┼──────────────────────┼────────────────────────│             ║
║  │ Purpose   │ Conflict detection   │ Data integrity         │             ║
║  │ Value     │ Integer (1, 2, 3...) │ Hash of content        │             ║
║  │ Stored in │ Metadata DB (MySQL)  │ Block key (S3)         │             ║
║  │ Used for  │ "Was file modified?" │ "Is content correct?"  │             ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 9. Storage Replication (Figure 15-6)

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  S3 REPLICATION STRATEGIES                                                  ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  SAME-REGION REPLICATION (SRR):                                             ║
║  ───────────────────────────────                                             ║
║                                                                               ║
║      ┌───────────────────────────── Region A ──────────────────────────┐    ║
║      │                                                                  │    ║
║      │   ┌─────────┐                                                   │    ║
║      │   │         │──────── replication ──────►  ┌─────────┐         │    ║
║      │   │         │                               │ Replica │         │    ║
║      │   │ Primary │──────── replication ──────►  ├─────────┤         │    ║
║      │   │ Bucket  │                               │ Replica │         │    ║
║      │   │         │──────── replication ──────►  ├─────────┤         │    ║
║      │   │         │                               │ Replica │         │    ║
║      │   └─────────┘                               └─────────┘         │    ║
║      │                                                                  │    ║
║      └──────────────────────────────────────────────────────────────────┘    ║
║                                                                               ║
║  Purpose: Compliance, data residency, lower latency within region          ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  CROSS-REGION REPLICATION (CRR):                                            ║
║  ─────────────────────────────────                                           ║
║                                                                               ║
║      ┌─────── Region A ───────┐         ┌─────── Region B ───────┐         ║
║      │                        │         │                        │         ║
║      │   ┌─────────┐         │         │   ┌─────────┐         │         ║
║      │   │         │─────────┼─────────┼──►│ Replica │         │         ║
║      │   │ Primary │         │         │   ├─────────┤         │         ║
║      │   │ Bucket  │─────────┼─────────┼──►│ Replica │         │         ║
║      │   │         │         │         │   ├─────────┤         │         ║
║      │   │         │─────────┼─────────┼──►│ Replica │         │         ║
║      │   └─────────┘         │         │   └─────────┘         │         ║
║      │                        │         │                        │         ║
║      └────────────────────────┘         └────────────────────────┘         ║
║                                                                               ║
║  Purpose: Disaster recovery, global low-latency access                     ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  GOOGLE DRIVE APPROACH:                                                     ║
║  • Primary: 3 replicas in same region (11 9's durability)                  ║
║  • Cross-region: Async replication to 2+ regions                           ║
║  • Cold storage: Old versions to Glacier after 30 days                     ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 10. Database Choice Tradeoffs

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║  WHY MYSQL FOR METADATA (Not NoSQL)?                                        ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║  MYSQL IS A GOOD FIT BECAUSE:                                               ║
║  ─────────────────────────────                                               ║
║                                                                               ║
║  1. COMPLEX RELATIONSHIPS                                                   ║
║     • User → Workspace → File → Version → Block                            ║
║     • Sharing permissions (many-to-many)                                   ║
║     • JOINs are useful for building file tree                              ║
║                                                                               ║
║  2. STRONG CONSISTENCY NEEDED                                               ║
║     • File metadata must be consistent                                      ║
║     • Version numbers must be atomic                                        ║
║     • Sharing permissions must be immediate                                 ║
║                                                                               ║
║  3. MODERATE SCALE                                                          ║
║     • 10M DAU, not billions                                                 ║
║     • Metadata is small (KB per file)                                      ║
║     • MySQL can handle this with sharding                                  ║
║                                                                               ║
║  ═══════════════════════════════════════════════════════════════════════════ ║
║                                                                               ║
║  SHARDING STRATEGY:                                                         ║
║  ───────────────────                                                         ║
║  • Shard by user_id (all user's files on same shard)                       ║
║  • Avoids cross-shard queries for file tree operations                     ║
║  • Shared files: Metadata duplicated to both users' shards                 ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

## 11. Interview Quick Answers

**Q: Why use block-level storage instead of storing whole files?**
> "Four reasons: (1) Delta sync - only upload changed blocks, saving 80%+ bandwidth. (2) Deduplication - same block stored once, shared by many files. (3) Resume - failed upload resumes from last block, not start. (4) Parallel upload - multiple blocks upload simultaneously."

**Q: How does conflict resolution work?**
> "We use optimistic locking with compare-and-swap. When a user opens a file, the client remembers the version number. When saving, the client sends base_version along with changes. The server atomically checks if current_version == base_version using an SQL UPDATE with WHERE clause. If version matches, update succeeds (rows_affected=1). If not, it's a conflict (rows_affected=0). On conflict, we create a 'conflicted copy' file so no data is lost, and the user manually merges."

**Q: How is the conflict check "atomic"? Is it using checksums?**
> "No checksums! The atomicity comes from the database itself. We use SQL: `UPDATE files SET version = version + 1 WHERE file_id = ? AND version = ?`. The WHERE clause IS the check. If version changed, 0 rows updated = conflict detected. The database row-level locking ensures no other transaction can sneak in between the check and update. Version is just an integer counter, not a checksum. Checksums are used for data integrity and deduplication, not conflict detection."

**Q: How do you handle offline devices?**
> "Changes queue in the 'Offline Backup Queue' (per device, persistent). When device reconnects, it fetches all queued changes via long polling. Queue survives server restarts."

**Q: Why long polling instead of WebSockets?**
> "File sync isn't real-time like chat - seconds of delay is acceptable. Long polling is simpler, works through firewalls/proxies better, and uses fewer server resources than maintaining persistent WebSocket connections."

**Q: How is deduplication implemented?**
> "Content-addressed storage. Each block is identified by SHA256 hash of its contents. Before uploading, client checks if hash exists. If yes, skip upload - block already stored. Same file from different users = same blocks = stored once."

**Q: How do you ensure reliability for a storage system?**
> "Multiple layers: (1) S3 gives 11 9's durability with 3+ replicas per region. (2) Cross-region replication for disaster recovery. (3) Checksums on every block to detect corruption. (4) Version history so deleted files can be restored."

**Q: What happens when a large file is edited?**
> "Only changed blocks are uploaded (delta sync). 10MB file split into 4MB blocks. If user edits middle, only 1-2 blocks change. We upload just those blocks, not the whole file. Typically saves 70-90% bandwidth."

**Q: How do you handle encryption?**
> "Blocks are encrypted at rest using AES-256 before storage. Each user has a unique encryption key. Keys stored separately from data (key management service). Blocks are also compressed before encryption (compress then encrypt, not vice versa)."

**Q: Why encrypt at Block Server and not just use S3 server-side encryption?**
> "Privacy! Google Drive stores sensitive data - tax docs, contracts, medical records. With S3 server-side encryption (SSE), AWS manages keys and could theoretically access data. With Block Server encryption using separate Key Management Service (KMS), S3 only sees encrypted blobs. Even cloud provider employees can't read user files. This is different from YouTube where videos are meant to be public, so SSE is sufficient."

**Q: Why not use block servers for YouTube?**
> "Block servers add value when there's deduplication potential (Dropbox - same files shared by many users). For video streaming, every video is unique, so block servers just double bandwidth cost with no benefit. YouTube uses pre-signed URLs for direct-to-S3 uploads."

---

## 12. Scalability Strategies

| Component | Scaling Approach |
|-----------|-----------------|
| **Block Servers** | Horizontal scaling, stateless, load balanced |
| **API Servers** | Horizontal scaling, stateless |
| **Metadata DB** | Shard by user_id, read replicas |
| **Cloud Storage** | S3 auto-scales, multi-region |
| **Notification Service** | Stateless, scale by connection count |
| **Metadata Cache** | Redis cluster |

---

## 13. Optimization: Delta Sync & Deduplication (Figure 15-12)

### Why Delta Sync?

For large files that are frequently edited, uploading the entire file is wasteful:
- 100MB file → change 1 paragraph → upload 100MB again? ❌
- With Delta Sync → upload only the 4MB block that changed ✅ (96% savings!)

### How It Works (Client-Side Splitting)

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  DELTA SYNC: ONLY UPLOAD CHANGED BLOCKS                                     ║
╠══════════════════════════════════════════════════════════════════════════════╣
║                                                                              ║
║  SCENARIO: User edits a 40MB file (10 blocks × 4MB each)                     ║
║                                                                              ║
║  ═══════════════════════════════════════════════════════════════════════     ║
║  PREVIOUS VERSION (stored locally on desktop client):                        ║
║  ┌───────┬───────┬───────┬───────┬───────┬───────┬───────┬───────┬────────┐ ║
║  │Block 1│Block 2│Block 3│Block 4│Block 5│Block 6│Block 7│Block 8│...     │ ║
║  │hash_A │hash_B │hash_C │hash_D │hash_E │hash_F │hash_G │hash_H │        │ ║
║  └───────┴───────┴───────┴───────┴───────┴───────┴───────┴───────┴────────┘ ║
║                                                                              ║
║  User edits document (changes content in Block 2 and Block 5 areas)          ║
║                                                                              ║
║  ═══════════════════════════════════════════════════════════════════════     ║
║  NEW VERSION (after user saves):                                             ║
║  ┌───────┬───────┬───────┬───────┬───────┬───────┬───────┬───────┬────────┐ ║
║  │Block 1│Block 2│Block 3│Block 4│Block 5│Block 6│Block 7│Block 8│...     │ ║
║  │hash_A │hash_X⚡│hash_C │hash_D │hash_Y⚡│hash_F │hash_G │hash_H │        │ ║
║  │ same  │CHANGED│ same  │ same  │CHANGED│ same  │ same  │ same  │        │ ║
║  └───────┴───────┴───────┴───────┴───────┴───────┴───────┴───────┴────────┘ ║
║                                                                              ║
║  ═══════════════════════════════════════════════════════════════════════     ║
║  CLIENT DETECTION LOGIC:                                                     ║
║                                                                              ║
║  for each block in new_file:                                                 ║
║      new_hash = SHA256(block_content)                                        ║
║      old_hash = local_cache.get(block_index)                                 ║
║      if new_hash != old_hash:                                                ║
║          changed_blocks.add(block)                                           ║
║                                                                              ║
║  Result: Only Block 2 and Block 5 need upload (8MB instead of 40MB!)        ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

### Delta Sync API Flow

```
╔══════════════════════════════════════════════════════════════════════════════╗
║  STEP 1: Desktop Client → API Server                                        ║
║  ─────────────────────────────────────                                       ║
║  POST /api/files/upload-init                                                 ║
║  Request: {                                                                  ║
║    "file_id": "existing_file_123",    ← This is an EDIT, not new upload     ║
║    "blocks": [                                                               ║
║      { "order": 0, "hash": "hash_A" },  ← Client computed hashes             ║
║      { "order": 1, "hash": "hash_X" },  ← NEW hash (changed)                 ║
║      { "order": 2, "hash": "hash_C" },                                       ║
║      ...                                                                     ║
║    ]                                                                         ║
║  }                                                                           ║
║                                                                              ║
║  API Server checks Block Index DB:                                           ║
║  • hash_A exists? ✅ → skip                                                  ║
║  • hash_X exists? ❌ → upload needed                                         ║
║  • hash_Y exists? ❌ → upload needed                                         ║
║                                                                              ║
║  Response: {                                                                 ║
║    "blocks_to_upload": ["hash_X", "hash_Y"],   ← Only 2 blocks!             ║
║    "blocks_already_exist": ["hash_A", "hash_C", ...] ← Skip these           ║
║  }                                                                           ║
║                                                                              ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  STEP 2: Desktop Client → Block Server (upload only changed blocks)         ║
║  ─────────────────────────────────────                                       ║
║  POST /blocks/hash_X  → Body: <4MB block data>                              ║
║  POST /blocks/hash_Y  → Body: <4MB block data>                              ║
║                                                                              ║
║  Block Server: Compress → Encrypt → Upload to S3                            ║
║                                                                              ║
╠══════════════════════════════════════════════════════════════════════════════╣
║  STEP 3: Desktop Client → API Server (finalize)                              ║
║  ─────────────────────────────────────                                       ║
║  POST /api/files/{file_id}/complete                                          ║
║  Request: { "blocks": ["hash_A", "hash_X", "hash_C", ..., "hash_Y", ...] }   ║
║  Response: { "version": 2 }                                                  ║
║                                                                              ║
╚══════════════════════════════════════════════════════════════════════════════╝
```

### Why Desktop Only?

| Client Type | Can Do Delta Sync? | Reason |
|-------------|-------------------|--------|
| **Desktop App** | ✅ Yes | Has local storage to cache block hashes |
| **Mobile App** | ⚠️ Limited | Limited storage, battery concerns |
| **Web Browser** | ❌ No | No persistent local storage access |

### Bandwidth Savings Example

| Scenario | Without Delta Sync | With Delta Sync | Savings |
|----------|-------------------|-----------------|---------|
| Edit 100MB file, change 1 paragraph | Upload 100MB | Upload 4MB | **96%** |
| Edit 1GB video, change title screen | Upload 1GB | Upload 4MB | **99.6%** |
| Small typo fix in 50MB doc | Upload 50MB | Upload 4MB | **92%** |

---

## 14. Failure Scenarios

| Scenario | Impact | Mitigation |
|----------|--------|------------|
| Block server crash | Upload fails | Retry, stateless servers, client resume |
| Metadata DB down | Can't browse files | Read replicas, failover |
| S3 outage | Can't upload/download | Multi-region, queue uploads |
| Notification service down | Delayed sync | Manual refresh, eventual sync |
| Network partition | Split brain | Conflict resolution, version vectors |

---

## 15. Visual Architecture Summary

```
╔═══════════════════════════════════════════════════════════════════════════════╗
║                           GOOGLE DRIVE ARCHITECTURE                          ║
╠═══════════════════════════════════════════════════════════════════════════════╣
║                                                                               ║
║   ┌─────────────────────────────────────────────────────────────────────┐   ║
║   │                              CLIENTS                                 │   ║
║   │   ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐            │   ║
║   │   │   Web   │   │  iOS    │   │ Android │   │ Desktop │            │   ║
║   │   │ Browser │   │  App    │   │   App   │   │  Sync   │            │   ║
║   │   └────┬────┘   └────┬────┘   └────┬────┘   └────┬────┘            │   ║
║   └────────┼─────────────┼─────────────┼─────────────┼──────────────────┘   ║
║            │             │             │             │                       ║
║            └─────────────┴─────────────┴─────────────┘                       ║
║                                  │                                           ║
║                                  ▼                                           ║
║                         ┌───────────────┐                                   ║
║                         │ Load Balancer │                                   ║
║                         └───────┬───────┘                                   ║
║                                 │                                            ║
║         ┌───────────────────────┼───────────────────────┐                   ║
║         │                       │                       │                   ║
║         ▼                       ▼                       ▼                   ║
║   ┌───────────┐          ┌───────────┐          ┌───────────────┐          ║
║   │   Block   │          │    API    │          │ Notification  │          ║
║   │  Servers  │          │  Servers  │          │   Service     │          ║
║   │           │          │           │          │               │          ║
║   │ Split     │          │ Metadata  │          │ Long Polling  │          ║
║   │ Compress  │          │ CRUD      │          │ Sync Events   │          ║
║   │ Encrypt   │          │ Sharing   │          │               │          ║
║   └─────┬─────┘          └─────┬─────┘          └───────┬───────┘          ║
║         │                      │                        │                   ║
║         │                      │                        ▼                   ║
║         │                      │               ┌───────────────┐            ║
║         │                      │               │Offline Backup │            ║
║         │                      │               │    Queue      │            ║
║         │                      │               └───────────────┘            ║
║         │                      │                                            ║
║         ▼                      ▼                                            ║
║   ┌───────────┐    ┌────────────────────────┐                              ║
║   │  Cloud    │    │      Metadata DB       │                              ║
║   │ Storage   │    │       (MySQL)          │                              ║
║   │   (S3)    │    ├────────────────────────┤                              ║
║   └─────┬─────┘    │    Metadata Cache      │                              ║
║         │          │       (Redis)          │                              ║
║         │          └────────────────────────┘                              ║
║         ▼                                                                   ║
║   ┌───────────┐                                                            ║
║   │   Cold    │   ← Old versions (cost optimization)                       ║
║   │  Storage  │                                                            ║
║   │ (Glacier) │                                                            ║
║   └───────────┘                                                            ║
║                                                                               ║
║   KEY FLOWS:                                                                ║
║   ① Upload: Client → Block Server → S3 + API → Metadata DB                ║
║   ② Download: Client → API → Metadata → Block Server → S3                  ║
║   ③ Sync: Notification Service → Long Poll → Client                       ║
║                                                                               ║
╚═══════════════════════════════════════════════════════════════════════════════╝
```

---

**For hands-on code, run:** `mvn compile exec:java`

