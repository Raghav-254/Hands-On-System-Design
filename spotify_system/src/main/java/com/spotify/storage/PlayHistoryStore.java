package com.spotify.storage;

import com.spotify.model.PlayEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simulates Cassandra for play history storage.
 * Partition key: user_id, Clustering key: played_at DESC.
 * Write-optimized, append-only.
 */
public class PlayHistoryStore {
    private final Map<String, List<PlayEvent>> userPlayHistory = new ConcurrentHashMap<>();

    public void recordPlay(PlayEvent event) {
        userPlayHistory.computeIfAbsent(event.getUserId(), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(event);
    }

    public List<PlayEvent> getRecentPlays(String userId, int limit) {
        List<PlayEvent> history = userPlayHistory.getOrDefault(userId, Collections.emptyList());
        synchronized (history) {
            return history.stream()
                    .sorted((a, b) -> b.getPlayedAt().compareTo(a.getPlayedAt()))
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }

    /**
     * Aggregate play counts across all users for batch update.
     * In production, this would be a Spark job reading from Cassandra.
     */
    public Map<String, Long> aggregatePlayCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (List<PlayEvent> events : userPlayHistory.values()) {
            synchronized (events) {
                for (PlayEvent e : events) {
                    if (e.countsAsPlay()) {
                        counts.merge(e.getSongId(), 1L, Long::sum);
                    }
                }
            }
        }
        return counts;
    }

    /**
     * Get all unique song IDs a user has listened to (for recommendations).
     */
    public Set<String> getUserListenedSongs(String userId) {
        List<PlayEvent> history = userPlayHistory.getOrDefault(userId, Collections.emptyList());
        synchronized (history) {
            return history.stream()
                    .filter(PlayEvent::countsAsPlay)
                    .map(PlayEvent::getSongId)
                    .collect(Collectors.toSet());
        }
    }

    public Map<String, Set<String>> getAllUserListeningProfiles() {
        Map<String, Set<String>> profiles = new HashMap<>();
        for (String userId : userPlayHistory.keySet()) {
            profiles.put(userId, getUserListenedSongs(userId));
        }
        return profiles;
    }

    public long getTotalEvents() {
        return userPlayHistory.values().stream().mapToLong(List::size).sum();
    }
}
