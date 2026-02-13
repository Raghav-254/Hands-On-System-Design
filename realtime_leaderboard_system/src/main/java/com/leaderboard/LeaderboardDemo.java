package com.leaderboard;

import com.leaderboard.model.LeaderboardEntry;
import com.leaderboard.service.GameService;
import com.leaderboard.service.LeaderboardService;
import com.leaderboard.storage.PlayerDB;
import com.leaderboard.storage.RedisSortedSet;

import java.util.List;

/**
 * End-to-end demo of the Real-time Game Leaderboard.
 *
 * Flow:
 *   1. Players win games → GameService calls LeaderboardService
 *   2. LeaderboardService updates Redis Sorted Set + MySQL
 *   3. Fetch top 10, player rank, and nearby players
 */
public class LeaderboardDemo {

    public static void main(String[] args) {
        // --- Bootstrap ---
        RedisSortedSet redis = new RedisSortedSet();
        PlayerDB playerDB = new PlayerDB();
        LeaderboardService lbService = new LeaderboardService(redis, playerDB);
        GameService gameService = new GameService(lbService, playerDB);

        System.out.println("=== Real-time Game Leaderboard ===\n");

        // --- Simulate players winning games ---
        System.out.println("--- Players winning games ---");
        gameService.winGame("u1", "Alice",   100);
        gameService.winGame("u2", "Bob",     250);
        gameService.winGame("u3", "Charlie", 180);
        gameService.winGame("u4", "Diana",   320);
        gameService.winGame("u5", "Eve",     210);
        gameService.winGame("u6", "Frank",    90);
        gameService.winGame("u7", "Grace",   275);
        gameService.winGame("u8", "Hank",    155);
        gameService.winGame("u9", "Ivy",     340);
        gameService.winGame("u10","Jack",    200);
        gameService.winGame("u11","Kate",    190);
        gameService.winGame("u12","Leo",     305);

        // Alice plays more games
        gameService.winGame("u1", "Alice", 150);  // Alice: 100+150 = 250
        gameService.winGame("u1", "Alice", 80);   // Alice: 250+80  = 330

        System.out.println("Scores recorded.\n");

        // --- Top 10 leaderboard ---
        System.out.println("--- Top 10 Leaderboard ---");
        List<LeaderboardEntry> top10 = lbService.getTopN(10);
        for (LeaderboardEntry e : top10) {
            System.out.println(e);
        }

        // --- Get a specific player's rank ---
        System.out.println("\n--- Player Rank ---");
        LeaderboardEntry aliceRank = lbService.getPlayerRank("u1");
        System.out.println("Alice → " + aliceRank);

        LeaderboardEntry frankRank = lbService.getPlayerRank("u6");
        System.out.println("Frank → " + frankRank);

        // --- Nearby players (4 above and below) ---
        System.out.println("\n--- Nearby Players for Eve (±4) ---");
        List<LeaderboardEntry> nearby = lbService.getNearbyPlayers("u5", 4);
        for (LeaderboardEntry e : nearby) {
            String marker = e.getUserId().equals("u5") ? "  <<<" : "";
            System.out.println(e + marker);
        }

        // --- Demonstrate incremental score update ---
        System.out.println("\n--- Frank wins big! (+500) ---");
        gameService.winGame("u6", "Frank", 500);  // 90 + 500 = 590
        System.out.println("Frank new rank → " + lbService.getPlayerRank("u6"));

        System.out.println("\n--- Updated Top 5 ---");
        for (LeaderboardEntry e : lbService.getTopN(5)) {
            System.out.println(e);
        }

        System.out.println("\n=== Demo complete ===");
    }
}
