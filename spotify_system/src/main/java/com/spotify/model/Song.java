package com.spotify.model;

import java.util.Map;
import java.util.HashMap;

public class Song {
    private final String songId;
    private final String title;
    private final String albumId;
    private final String artistId;
    private final String artistName;
    private final String albumTitle;
    private final String genre;
    private final long durationMs;
    private final Map<Integer, String> bitrateToS3Path;
    private long playCount;

    public Song(String songId, String title, String artistId, String artistName,
                String albumId, String albumTitle, String genre, long durationMs) {
        this.songId = songId;
        this.title = title;
        this.artistId = artistId;
        this.artistName = artistName;
        this.albumId = albumId;
        this.albumTitle = albumTitle;
        this.genre = genre;
        this.durationMs = durationMs;
        this.playCount = 0;

        this.bitrateToS3Path = new HashMap<>();
        bitrateToS3Path.put(128, "/songs/" + songId + "/128.mp3");
        bitrateToS3Path.put(256, "/songs/" + songId + "/256.mp3");
        bitrateToS3Path.put(320, "/songs/" + songId + "/320.mp3");
    }

    public String getSongId() { return songId; }
    public String getTitle() { return title; }
    public String getAlbumId() { return albumId; }
    public String getArtistId() { return artistId; }
    public String getArtistName() { return artistName; }
    public String getAlbumTitle() { return albumTitle; }
    public String getGenre() { return genre; }
    public long getDurationMs() { return durationMs; }
    public long getPlayCount() { return playCount; }
    public Map<Integer, String> getBitrateToS3Path() { return bitrateToS3Path; }

    public void incrementPlayCount() { this.playCount++; }
    public void setPlayCount(long count) { this.playCount = count; }

    public String getS3Path(int bitrate) {
        return bitrateToS3Path.getOrDefault(bitrate, bitrateToS3Path.get(128));
    }

    @Override
    public String toString() {
        return String.format("\"%s\" by %s [%s] (%d ms, %d plays)",
                title, artistName, genre, durationMs, playCount);
    }
}
