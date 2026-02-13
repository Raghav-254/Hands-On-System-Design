package com.leaderboard.storage;

import com.leaderboard.model.Player;
import java.util.*;

/**
 * Simulates the MySQL "point" table — the source of truth for player data.
 *
 * In production: MySQL with columns (user_id, total_score, display_name, …).
 * Redis is the fast leaderboard layer; MySQL is the durable store.
 */
public class PlayerDB {

    private final Map<String, Player> players = new LinkedHashMap<>();

    /** Insert or retrieve a player */
    public Player getOrCreate(String userId, String displayName) {
        return players.computeIfAbsent(userId, id -> new Player(id, displayName));
    }

    public Player get(String userId) {
        return players.get(userId);
    }

    /** Record a score event (source of truth) */
    public void addScore(String userId, int points) {
        Player p = players.get(userId);
        if (p != null) {
            p.addScore(points);
        }
    }

    public Collection<Player> allPlayers() {
        return players.values();
    }
}
