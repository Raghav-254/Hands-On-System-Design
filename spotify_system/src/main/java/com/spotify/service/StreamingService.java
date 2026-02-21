package com.spotify.service;

import com.spotify.model.*;
import com.spotify.storage.CDNCache;
import com.spotify.storage.MusicMetadataDB;
import com.spotify.event.EventBus;

import java.util.UUID;

/**
 * Generates pre-signed CDN URLs for audio streaming.
 * Never touches audio bytes — only issues URLs. CDN does the heavy lifting.
 */
public class StreamingService {
    private final MusicMetadataDB metadataDB;
    private final CDNCache cdn;
    private final EventBus eventBus;
    private static final String CDN_BASE = "https://cdn.spotify.internal";

    public StreamingService(MusicMetadataDB metadataDB, CDNCache cdn, EventBus eventBus) {
        this.metadataDB = metadataDB;
        this.cdn = cdn;
        this.eventBus = eventBus;
    }

    /**
     * Main streaming flow:
     * 1. Look up song metadata
     * 2. Select bitrate based on user subscription + network quality
     * 3. Generate pre-signed CDN URL
     * 4. Return URL to client (client fetches audio directly from CDN)
     */
    public StreamResponse playSong(String songId, String userId, String networkQuality) {
        Song song = metadataDB.getSong(songId);
        if (song == null) {
            return new StreamResponse(null, 0, null, "Song not found");
        }

        User user = metadataDB.getUser(userId);
        int bitrate = selectBitrate(user, networkQuality);

        String s3Path = song.getS3Path(bitrate);
        String token = generateToken(userId, songId);
        String streamUrl = CDN_BASE + s3Path + "?token=" + token + "&expires=3600";

        return new StreamResponse(streamUrl, bitrate, song, null);
    }

    /**
     * Simulate client fetching audio chunk from CDN.
     * In production, the client does this directly — our servers are not involved.
     */
    public String fetchChunkFromCDN(String s3Path) {
        return cdn.fetch(s3Path);
    }

    /**
     * Record a play event (called by client after 30s of playback).
     */
    public void recordPlay(String userId, String songId, long durationMs, int bitrate) {
        PlayEvent event = new PlayEvent(userId, songId, durationMs, bitrate);
        eventBus.publish(event);
    }

    private int selectBitrate(User user, String networkQuality) {
        int maxAllowed = (user != null) ? user.getMaxBitrate() : 128;

        int networkMax;
        switch (networkQuality != null ? networkQuality.toLowerCase() : "medium") {
            case "high": networkMax = 320; break;
            case "low": networkMax = 128; break;
            default: networkMax = 256; break;
        }

        return Math.min(maxAllowed, networkMax);
    }

    private String generateToken(String userId, String songId) {
        return UUID.randomUUID().toString().substring(0, 12);
    }

    public static class StreamResponse {
        public final String streamUrl;
        public final int bitrate;
        public final Song song;
        public final String error;

        public StreamResponse(String streamUrl, int bitrate, Song song, String error) {
            this.streamUrl = streamUrl;
            this.bitrate = bitrate;
            this.song = song;
            this.error = error;
        }

        @Override
        public String toString() {
            if (error != null) return "StreamResponse[ERROR: " + error + "]";
            return String.format("StreamResponse[url=%s, bitrate=%d, song=%s]",
                    streamUrl, bitrate, song.getTitle());
        }
    }
}
