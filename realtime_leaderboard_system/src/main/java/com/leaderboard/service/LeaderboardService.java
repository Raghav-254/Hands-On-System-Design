package com.leaderboard.service;

import com.leaderboard.model.LeaderboardEntry;
import com.leaderboard.storage.PlayerDB;
import com.leaderboard.storage.RedisSortedSet;

import java.util.*;

/**
 * Core leaderboard operations backed by a Redis Sorted Set.
 *
 * Mirrors real commands:
 *   - Score a point  →  ZINCRBY  (O(log n))
 *   - Top 10         →  ZREVRANGE 0 9  (O(log n + 10))
 *   - Player rank    →  ZREVRANK  (O(log n))
 *   - Nearby players →  ZREVRANGE (rank-4, rank+4)  (O(log n + 9))
 */
public class LeaderboardService {

    private final RedisSortedSet leaderboard;
    private final PlayerDB playerDB;

    public LeaderboardService(RedisSortedSet leaderboard, PlayerDB playerDB) {
        this.leaderboard = leaderboard;
        this.playerDB = playerDB;
    }

    /** User wins a game — increment score in Redis + MySQL */
    public void scorePoint(String userId, int points) {
        // 1. Update Redis sorted set (real-time leaderboard)
        leaderboard.zincrby(userId, points);
        // 2. Update MySQL (source of truth)
        playerDB.addScore(userId, points);
    }

    /** Fetch top N leaderboard entries */
    public List<LeaderboardEntry> getTopN(int n) {
        var entries = leaderboard.zrevrange(0, n - 1);
        List<LeaderboardEntry> result = new ArrayList<>();
        int rank = 1;
        for (var e : entries) {
            String userId = e.getKey();
            var player = playerDB.get(userId);
            String name = player != null ? player.getDisplayName() : userId;
            result.add(new LeaderboardEntry(rank++, userId, name, e.getValue()));
        }
        return result;
    }

    /** Get a specific user's rank (1-based) and score */
    public LeaderboardEntry getPlayerRank(String userId) {
        int zeroBasedRank = leaderboard.zrevrank(userId);
        if (zeroBasedRank < 0) return null;
        Double score = leaderboard.zscore(userId);
        var player = playerDB.get(userId);
        String name = player != null ? player.getDisplayName() : userId;
        return new LeaderboardEntry(zeroBasedRank + 1, userId, name, score);
    }

    /** Get nearby players: 4 above, the user, 4 below */
    public List<LeaderboardEntry> getNearbyPlayers(String userId, int range) {
        int zeroBasedRank = leaderboard.zrevrank(userId);
        if (zeroBasedRank < 0) return Collections.emptyList();
        int start = Math.max(0, zeroBasedRank - range);
        int stop = zeroBasedRank + range;
        var entries = leaderboard.zrevrange(start, stop);
        List<LeaderboardEntry> result = new ArrayList<>();
        int rank = start + 1;
        for (var e : entries) {
            String uid = e.getKey();
            var player = playerDB.get(uid);
            String name = player != null ? player.getDisplayName() : uid;
            result.add(new LeaderboardEntry(rank++, uid, name, e.getValue()));
        }
        return result;
    }
}
