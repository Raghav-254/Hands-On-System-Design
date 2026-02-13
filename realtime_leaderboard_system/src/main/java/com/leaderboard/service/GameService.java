package com.leaderboard.service;

import com.leaderboard.storage.PlayerDB;

/**
 * Simulates the Game Service — when a user wins a game,
 * it calls the Leaderboard Service to update the score.
 *
 * In a real system this might publish to Kafka instead of calling directly.
 */
public class GameService {

    private final LeaderboardService leaderboardService;
    private final PlayerDB playerDB;

    public GameService(LeaderboardService leaderboardService, PlayerDB playerDB) {
        this.leaderboardService = leaderboardService;
        this.playerDB = playerDB;
    }

    /** Simulate a user winning a game and earning points */
    public void winGame(String userId, String displayName, int points) {
        // Ensure player exists in MySQL
        playerDB.getOrCreate(userId, displayName);
        // Update leaderboard
        leaderboardService.scorePoint(userId, points);
    }
}
