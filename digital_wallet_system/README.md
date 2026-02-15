# Digital Wallet System

Based on Alex Xu's System Design Interview Volume 2 - Chapter 12

## Overview

A high-performance distributed digital wallet system supporting balance transfers between accounts at 1,000,000 TPS. Demonstrates the evolution from naive approaches (Redis, 2PC) through Event Sourcing, CQRS, and Raft consensus.

## Key Concepts Demonstrated

- **In-memory vs Durable Storage**: Why Redis alone isn't enough
- **Distributed Transactions**: 2PC, TCC, Saga — and their limitations
- **Event Sourcing**: Commands → Events → State (append-only, reproducible)
- **CQRS**: Separate write path and read path for performance
- **Raft Consensus**: Replicating events for fault tolerance
- **State Machine**: Deterministic processing for reproducibility
- **Reverse Proxy**: Converting async Event Sourcing to sync API

## Running the Demo

```bash
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `DigitalWalletDemo.java` | Main demo showcasing features |
| `model/` | Data models (WalletAccount, TransferCommand, TransferEvent) |
| `storage/` | Storage simulation (EventStore, StateStore, SnapshotStore) |
| `service/` | Business logic (WalletService, EventSourcingEngine, RaftNode) |
