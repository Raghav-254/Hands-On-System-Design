# S3-like Object Storage

Based on Alex Xu's System Design Interview Volume 2 - Chapter 9

## Overview

A simplified implementation of an S3-like object storage system. Supports bucket creation, object upload/download, versioning, listing, multipart uploads, and demonstrates data durability concepts (replication, erasure coding).

## Key Concepts Demonstrated

- **Two-Part Architecture**: Metadata Store (what/where) + Data Store (actual bytes)
- **Upload Flow**: API Service → Auth → Data Store (persist bytes) → Metadata Store (record location)
- **Download Flow**: API Service → Auth → Metadata Store (find location) → Data Store (fetch bytes)
- **Data Store Internals**: Data Routing Service, Placement Service, Data Nodes (Primary/Secondary)
- **Data Organization**: Small objects packed into large files (WAL-like append)
- **Durability**: Replication (3 copies) vs Erasure Coding (storage-efficient)
- **Versioning**: Multiple versions of same object, each with unique UUID
- **Multipart Upload**: Large files split into parts, uploaded in parallel
- **Garbage Collection**: Compaction of deleted/orphaned objects

## Running the Demo

```bash
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `ObjectStorageDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Bucket, ObjectMetadata, ObjectVersion, DataNode) |
| `storage/` | Storage simulation (MetadataStore, DataStore, PlacementService) |
| `service/` | Business logic (BucketService, ObjectService) |
