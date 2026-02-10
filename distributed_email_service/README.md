# Distributed Email Service

Based on Alex Xu's System Design Interview Volume 2 - Chapter 8

## Overview

A simplified implementation of a large-scale email service (like Gmail). Supports sending and receiving emails, folder management, search, attachments, spam filtering, and real-time notifications.

## Key Concepts Demonstrated

- **Email Protocols**: Traditional (SMTP, IMAP, POP) vs Modern (HTTP + WebSocket)
- **Send Flow**: Web Server → Outgoing Queue → SMTP Outgoing → Internet
- **Receive Flow**: SMTP Server → Incoming Queue → Mail Processing → Storage
- **Storage Layer**: Metadata DB, Attachment Store (S3), Search Store (Elasticsearch), Cache
- **Email Deliverability**: SPF, DKIM, DMARC, IP warm-up, dedicated IPs
- **Search**: Elasticsearch with inverted index for full-text email search
- **Real-time**: WebSocket for push notifications on new emails
- **Scalability**: Partitioning by user_id, denormalized read path

## Architecture

```
Sending: Webmail → HTTPS → Web Servers → Outgoing Queue → SMTP Outgoing → Internet
Receiving: Internet → SMTP Servers → Incoming Queue → Mail Processing → Storage
Real-time: Storage → Real-time Servers → WebSocket → Webmail

Storage Layer:
  Metadata DB (email headers, folders) → Distributed DB (Cassandra/Bigtable)
  Attachment Store → Object Storage (S3)
  Search Store → Elasticsearch
  Cache → Redis (recent emails)
```

## Running the Demo

```bash
# Option 1: Using Maven
mvn compile exec:java

# Option 2: Direct compilation
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `DistributedEmailDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Email, Folder, Attachment, User) |
| `storage/` | Database simulation (MetadataDB, AttachmentStore, SearchStore) |
| `service/` | Business logic (SendService, ReceiveService, SearchService) |
