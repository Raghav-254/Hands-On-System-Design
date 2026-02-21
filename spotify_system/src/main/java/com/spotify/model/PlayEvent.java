package com.spotify.model;

import java.time.Instant;

public class PlayEvent {
    private final String userId;
    private final String songId;
    private final long durationMs;
    private final int bitrate;
    private final Instant playedAt;

    public PlayEvent(String userId, String songId, long durationMs, int bitrate) {
        this.userId = userId;
        this.songId = songId;
        this.durationMs = durationMs;
        this.bitrate = bitrate;
        this.playedAt = Instant.now();
    }

    public String getUserId() { return userId; }
    public String getSongId() { return songId; }
    public long getDurationMs() { return durationMs; }
    public int getBitrate() { return bitrate; }
    public Instant getPlayedAt() { return playedAt; }

    public boolean countsAsPlay() {
        return durationMs >= 30_000;
    }

    @Override
    public String toString() {
        return String.format("PlayEvent[user=%s, song=%s, %dms, %dkbps, %s, counts=%s]",
                userId, songId, durationMs, bitrate, playedAt, countsAsPlay());
    }
}
