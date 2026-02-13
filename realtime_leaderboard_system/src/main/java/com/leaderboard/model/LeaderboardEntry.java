package com.leaderboard.model;

/**
 * A single row in the leaderboard — player + rank + score.
 */
public class LeaderboardEntry {
    private final int rank;
    private final String userId;
    private final String displayName;
    private final double score;

    public LeaderboardEntry(int rank, String userId, String displayName, double score) {
        this.rank = rank;
        this.userId = userId;
        this.displayName = displayName;
        this.score = score;
    }

    public int getRank() { return rank; }
    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public double getScore() { return score; }

    @Override
    public String toString() {
        return String.format("#%d  %-15s  %.0f pts", rank, displayName, score);
    }
}
