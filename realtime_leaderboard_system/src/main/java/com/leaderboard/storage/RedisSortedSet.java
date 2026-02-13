package com.leaderboard.storage;

import java.util.*;

/**
 * Simulates a Redis Sorted Set (ZADD, ZINCRBY, ZREVRANGE, ZREVRANK, ZSCORE).
 *
 * In production this would be a real Redis instance.
 * Internally uses a TreeMap for O(log n) rank operations.
 */
public class RedisSortedSet {

    // member -> score
    private final Map<String, Double> scores = new HashMap<>();

    // Sorted view: (score, member) — highest first
    private final TreeSet<double[]> sortedView = new TreeSet<>((a, b) -> {
        // Compare by score descending, then by member hash for determinism
        if (a[0] != b[0]) return Double.compare(b[0], a[0]);
        return Double.compare(a[1], b[1]);
    });

    // member -> insertion hash (for stable ordering)
    private final Map<String, Double> memberHash = new HashMap<>();

    /** ZINCRBY key increment member — O(log n) */
    public double zincrby(String member, double increment) {
        Double oldScore = scores.get(member);
        if (oldScore != null) {
            sortedView.remove(new double[]{oldScore, memberHash.get(member)});
        }
        double newScore = (oldScore == null ? 0 : oldScore) + increment;
        scores.put(member, newScore);
        if (!memberHash.containsKey(member)) {
            memberHash.put(member, (double) member.hashCode());
        }
        sortedView.add(new double[]{newScore, memberHash.get(member)});
        return newScore;
    }

    /** ZADD key score member — O(log n) */
    public void zadd(String member, double score) {
        Double oldScore = scores.get(member);
        if (oldScore != null) {
            sortedView.remove(new double[]{oldScore, memberHash.get(member)});
        }
        scores.put(member, score);
        if (!memberHash.containsKey(member)) {
            memberHash.put(member, (double) member.hashCode());
        }
        sortedView.add(new double[]{score, memberHash.get(member)});
    }

    /** ZSCORE key member — O(1) */
    public Double zscore(String member) {
        return scores.get(member);
    }

    /** ZREVRANGE key start stop — top players (0-indexed, inclusive) — O(log n + m) */
    public List<Map.Entry<String, Double>> zrevrange(int start, int stop) {
        List<Map.Entry<String, Double>> result = new ArrayList<>();
        int idx = 0;
        // Build reverse map for lookup
        Map<Double, List<String>> hashToMembers = new HashMap<>();
        for (var e : memberHash.entrySet()) {
            hashToMembers.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
        }
        for (double[] entry : sortedView) {
            if (idx > stop) break;
            if (idx >= start) {
                // find member by hash
                List<String> candidates = hashToMembers.get(entry[1]);
                if (candidates != null) {
                    for (String m : candidates) {
                        if (scores.get(m) == entry[0]) {
                            result.add(new AbstractMap.SimpleEntry<>(m, entry[0]));
                            break;
                        }
                    }
                }
            }
            idx++;
        }
        return result;
    }

    /** ZREVRANK key member — 0-based rank (highest score = rank 0) — O(log n) */
    public int zrevrank(String member) {
        Double score = scores.get(member);
        if (score == null) return -1;
        int rank = 0;
        for (double[] entry : sortedView) {
            // find member by matching score + hash
            if (entry[0] == score && entry[1] == memberHash.get(member)) {
                return rank;
            }
            rank++;
        }
        return -1;
    }

    /** ZCARD — total members — O(1) */
    public int zcard() {
        return scores.size();
    }
}
