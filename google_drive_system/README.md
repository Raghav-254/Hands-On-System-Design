# Google Drive System Design

A hands-on implementation of a Google Drive-like file synchronization system, based on Alex Xu's System Design Interview - Chapter 15.

## Key Features Implemented

1. **Block Server** - Split, compress, encrypt files into blocks
2. **Delta Sync** - Only upload changed blocks (bandwidth optimization)
3. **Deduplication** - Same content stored once (content-addressed storage)
4. **Version History** - Track all file versions, enable restore
5. **Conflict Detection** - Optimistic locking for concurrent edits
6. **Cross-Device Sync** - Long polling notification service

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                          CLIENTS                                 │
│   Web Browser  │  iOS App  │  Android App  │  Desktop Sync      │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
                    ┌─────────────────┐
                    │  Load Balancer  │
                    └────────┬────────┘
                             │
        ┌────────────────────┼────────────────────┐
        │                    │                    │
        ▼                    ▼                    ▼
  ┌───────────┐       ┌───────────┐       ┌──────────────┐
  │   Block   │       │    API    │       │ Notification │
  │  Servers  │       │  Servers  │       │   Service    │
  └─────┬─────┘       └─────┬─────┘       └──────────────┘
        │                   │
        ▼                   ▼
  ┌───────────┐       ┌───────────┐
  │   Cloud   │       │ Metadata  │
  │  Storage  │       │    DB     │
  └───────────┘       └───────────┘
```

## Core Concepts

### Block-Based Storage
- Files split into 4MB blocks
- Each block compressed (gzip) then encrypted (AES-256)
- Block ID = SHA-256 hash of content (content-addressed)

### Delta Sync
- When file changes, only modified blocks are re-uploaded
- 10MB file with small edit = upload 1 block (4MB), not 10MB
- Typical bandwidth savings: 70-90%

### Deduplication
- Same content = same hash = stored only once
- Works across users (company documents deduplicated)
- "100 users upload same PDF" = stored once

### Conflict Resolution
- Optimistic locking via version numbers
- Client sends `base_version` with upload
- If `current_version != base_version` → Conflict!
- Resolution: Create "conflicted copy" (no data loss)

## Running the Demo

```bash
# Navigate to project
cd google_drive_system

# Build and run
mvn compile exec:java
```

## Demo Output

The demo demonstrates:
1. File upload with block processing
2. File download with reassembly
3. Delta sync (only changed blocks uploaded)
4. Deduplication (same file = no new storage)
5. Conflict detection
6. Version history
7. Cross-device sync via long polling

## Project Structure

```
google_drive_system/
├── src/main/java/com/googledrive/
│   ├── model/
│   │   ├── Block.java           # File chunk representation
│   │   ├── FileMetadata.java    # File metadata (stored in DB)
│   │   └── FileVersion.java     # Version history entry
│   ├── service/
│   │   ├── BlockServer.java     # Split, compress, encrypt
│   │   └── NotificationService.java  # Long polling sync
│   ├── storage/
│   │   ├── CloudStorage.java    # S3-like block storage
│   │   └── MetadataDB.java      # MySQL-like metadata store
│   └── GoogleDriveDemo.java     # Main demo
├── INTERVIEW_CHEATSHEET.md      # Complete interview guide
├── pom.xml
└── README.md
```

## Key Interview Points

1. **Why Block Servers?**
   - Delta sync (bandwidth optimization)
   - Deduplication (storage optimization)
   - Resume support (reliability)
   - Parallel upload (performance)

2. **Why MySQL for Metadata?**
   - Complex relationships (user → workspace → file → version)
   - Strong consistency for file structure
   - Moderate scale (sharding by user_id)

3. **Why Long Polling (not WebSocket)?**
   - File sync tolerates seconds of delay
   - Simpler, works through firewalls
   - Less server resources

4. **Block Server vs Pre-signed URL?**
   - Block Server: For file sync (Dropbox, Google Drive) - dedup matters
   - Pre-signed URL: For video streaming (YouTube) - unique content, no dedup value

## Reference

Based on: Alex Xu's System Design Interview, Chapter 15 - Design Google Drive

