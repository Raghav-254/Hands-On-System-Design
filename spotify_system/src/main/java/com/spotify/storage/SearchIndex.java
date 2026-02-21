package com.spotify.storage;

import com.spotify.model.Song;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates Elasticsearch for full-text search.
 * Implements fuzzy matching (edit distance), field weighting, and popularity re-ranking.
 */
public class SearchIndex {
    private final List<Song> indexedSongs = Collections.synchronizedList(new ArrayList<>());

    public void indexSong(Song song) {
        indexedSongs.removeIf(s -> s.getSongId().equals(song.getSongId()));
        indexedSongs.add(song);
    }

    public void indexAll(Collection<Song> songs) {
        songs.forEach(this::indexSong);
    }

    /**
     * Search with fuzzy matching and popularity re-ranking.
     * Simulates: multi_match with fuzziness + popularity boost.
     */
    public List<Song> search(String query, int limit) {
        String normalizedQuery = query.toLowerCase().trim();

        return indexedSongs.stream()
                .map(song -> new ScoredSong(song, computeScore(song, normalizedQuery)))
                .filter(ss -> ss.score > 0)
                .sorted((a, b) -> Double.compare(b.score, a.score))
                .limit(limit)
                .map(ss -> ss.song)
                .collect(Collectors.toList());
    }

    private double computeScore(Song song, String query) {
        double titleScore = fuzzyMatch(song.getTitle().toLowerCase(), query) * 3.0;
        double artistScore = fuzzyMatch(song.getArtistName().toLowerCase(), query) * 2.0;
        double albumScore = fuzzyMatch(song.getAlbumTitle().toLowerCase(), query) * 1.0;

        double textRelevance = Math.max(titleScore, Math.max(artistScore, albumScore));
        if (textRelevance == 0) return 0;

        double popularityScore = Math.log1p(song.getPlayCount());
        return 0.7 * textRelevance + 0.3 * popularityScore;
    }

    /**
     * Simple fuzzy match: substring match + prefix match + edit distance tolerance.
     */
    private double fuzzyMatch(String text, String query) {
        if (text.equals(query)) return 1.0;
        if (text.startsWith(query)) return 0.9;
        if (text.contains(query)) return 0.7;

        String[] queryWords = query.split("\\s+");
        int matches = 0;
        for (String word : queryWords) {
            if (text.contains(word)) {
                matches++;
            } else {
                for (String textWord : text.split("\\s+")) {
                    if (editDistance(textWord, word) <= 2) {
                        matches++;
                        break;
                    }
                }
            }
        }
        if (matches == 0) return 0;
        return 0.5 * ((double) matches / queryWords.length);
    }

    private int editDistance(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(dp[i - 1][j] + 1,
                        Math.min(dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost));
            }
        }
        return dp[a.length()][b.length()];
    }

    public int size() { return indexedSongs.size(); }

    private static class ScoredSong {
        final Song song;
        final double score;
        ScoredSong(Song song, double score) {
            this.song = song;
            this.score = score;
        }
    }
}
