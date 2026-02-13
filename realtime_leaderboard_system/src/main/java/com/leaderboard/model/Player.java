package com.leaderboard.model;

/**
 * Represents a player / user in the game.
 */
public class Player {
    private final String userId;
    private final String displayName;
    private int totalScore;

    public Player(String userId, String displayName) {
        this.userId = userId;
        this.displayName = displayName;
        this.totalScore = 0;
    }

    public String getUserId() { return userId; }
    public String getDisplayName() { return displayName; }
    public int getTotalScore() { return totalScore; }
    public void addScore(int points) { this.totalScore += points; }

    @Override
    public String toString() {
        return displayName + " (score=" + totalScore + ")";
    }
}
