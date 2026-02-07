# Distributed Message Queue

Based on Alex Xu's System Design Interview Volume 2 - Chapter 4

## Overview

A simplified implementation of a distributed message queue (like Apache Kafka). Supports topics, partitions, consumer groups, replication, and configurable delivery semantics.

## Key Concepts Demonstrated

- **Topics & Partitions**: Messages organized into topics, partitioned for parallelism
- **Producer Routing**: Buffer + routing layer to direct messages to correct broker/partition
- **Consumer Groups**: Multiple consumers sharing partitions with rebalancing
- **Replication**: Leader-follower replication with ISR (In-Sync Replicas)
- **Delivery Semantics**: At-most-once, at-least-once, exactly-once (configurable)
- **Coordination**: ZooKeeper/metadata service for broker discovery and leader election
- **Segment Files**: On-disk storage using append-only segment files

## Architecture

```
Producers → Buffer → Routing → Brokers (Data + State Storage) → Consumers (Consumer Groups)
                                    ↕
                              ZooKeeper / Coordination Service
                              (Metadata + State + Coordination)
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
| `MessageQueueDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Message, Topic, Partition, Segment) |
| `broker/` | Broker, partition management, replication |
| `producer/` | Producer with buffering and routing |
| `consumer/` | Consumer groups, offset management, rebalancing |
| `coordination/` | ZooKeeper-like coordination service |
