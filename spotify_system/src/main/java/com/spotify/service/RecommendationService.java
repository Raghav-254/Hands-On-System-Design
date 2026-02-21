package com.spotify.service;

import com.spotify.model.Song;
import com.spotify.storage.MusicMetadataDB;
import com.spotify.storage.PlayHistoryStore;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simplified collaborative filtering for recommendations.
 * In production, this runs as an offline Spark batch job and writes
 * precomputed playlists to Redis. Here we simulate it in-memory.
 *
 * Algorithm:
 * 1. Find users who listened to similar songs as the target user.
 * 2. Collect songs those similar users listened to but the target user hasn't.
 * 3. Rank by frequency (how many similar users listened to each song).
 */
public class RecommendationService {
    private final PlayHistoryStore playHistory;
    private final MusicMetadataDB metadataDB;
    private final Map<String, List<String>> cachedRecommendations = new HashMap<>();

    public RecommendationService(PlayHistoryStore playHistory, MusicMetadataDB metadataDB) {
        this.playHistory = playHistory;
        this.metadataDB = metadataDB;
    }

    /**
     * Generate recommendations for a user (simulates offline Spark pipeline).
     */
    public List<Song> generateDailyMix(String userId, int limit) {
        List<String> cached = cachedRecommendations.get(userId);
        if (cached != null) {
            return cached.stream()
                    .map(metadataDB::getSong)
                    .filter(Objects::nonNull)
                    .limit(limit)
                    .collect(Collectors.toList());
        }

        Set<String> userSongs = playHistory.getUserListenedSongs(userId);
        if (userSongs.isEmpty()) {
            return getPopularSongs(limit);
        }

        Map<String, Set<String>> allProfiles = playHistory.getAllUserListeningProfiles();
        Map<String, Integer> candidateScores = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : allProfiles.entrySet()) {
            if (entry.getKey().equals(userId)) continue;

            Set<String> otherSongs = entry.getValue();
            long overlap = userSongs.stream().filter(otherSongs::contains).count();
            if (overlap == 0) continue;

            double similarity = (double) overlap / Math.max(userSongs.size(), otherSongs.size());
            if (similarity < 0.1) continue;

            for (String songId : otherSongs) {
                if (!userSongs.contains(songId)) {
                    candidateScores.merge(songId, 1, Integer::sum);
                }
            }
        }

        List<String> recommendations = candidateScores.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .map(Map.Entry::getKey)
                .limit(limit)
                .collect(Collectors.toList());

        if (recommendations.isEmpty()) {
            return getPopularSongs(limit);
        }

        cachedRecommendations.put(userId, recommendations);

        return recommendations.stream()
                .map(metadataDB::getSong)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Fallback: return most popular songs (cold-start problem).
     */
    private List<Song> getPopularSongs(int limit) {
        return metadataDB.getAllSongs().stream()
                .sorted((a, b) -> Long.compare(b.getPlayCount(), a.getPlayCount()))
                .limit(limit)
                .collect(Collectors.toList());
    }

    public void clearCache() { cachedRecommendations.clear(); }

    /**
     * Simulate batch pipeline run: clear cache to force fresh computation.
     */
    public void runBatchPipeline() {
        cachedRecommendations.clear();
    }
}
