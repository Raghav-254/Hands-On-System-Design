# Real-time Game Leaderboard System

Based on Alex Xu's System Design Interview Volume 2 - Chapter 10

## Overview

A simplified implementation of a real-time game leaderboard. Supports scoring points, fetching top-N leaderboard, getting player rank, nearby players, tie-breaking, and demonstrates scaling with Redis Sorted Sets.

## Key Concepts Demonstrated

- **Redis Sorted Sets**: ZINCRBY, ZREVRANGE, ZREVRANK, ZSCORE — O(log n) operations
- **Why Not MySQL**: ORDER BY + LIMIT doesn't scale for millions of players
- **Score Flow**: Game Service → Leaderboard Service → Redis + MySQL
- **Scaling**: Fixed partition (score ranges) vs Hash partition (Redis Cluster)
- **Tie-breaking**: Composite score with timestamp for deterministic ordering
- **Fault Tolerance**: Redis persistence (AOF/RDB) + MySQL as source of truth

## Running the Demo

```bash
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `LeaderboardDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Player, LeaderboardEntry) |
| `storage/` | Storage simulation (RedisSortedSet, PlayerDB) |
| `service/` | Business logic (LeaderboardService, GameService) |
