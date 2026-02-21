package com.spotify.model;

import java.util.*;

public class Playlist {
    private final String playlistId;
    private final String ownerId;
    private String name;
    private final boolean collaborative;
    private int version;
    private final List<PlaylistEntry> songs;

    public Playlist(String playlistId, String ownerId, String name, boolean collaborative) {
        this.playlistId = playlistId;
        this.ownerId = ownerId;
        this.name = name;
        this.collaborative = collaborative;
        this.version = 1;
        this.songs = new ArrayList<>();
    }

    public String getPlaylistId() { return playlistId; }
    public String getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public boolean isCollaborative() { return collaborative; }
    public int getVersion() { return version; }
    public List<PlaylistEntry> getSongs() { return Collections.unmodifiableList(songs); }

    public void setName(String name) { this.name = name; }
    public void setVersion(int version) { this.version = version; }

    public void addSong(String songId, int position) {
        songs.removeIf(e -> e.getSongId().equals(songId));
        if (position < 0 || position > songs.size()) position = songs.size();
        songs.add(position, new PlaylistEntry(songId, position));
        reindex();
    }

    public boolean removeSong(String songId) {
        boolean removed = songs.removeIf(e -> e.getSongId().equals(songId));
        if (removed) reindex();
        return removed;
    }

    public void reorderSong(String songId, int newPosition) {
        PlaylistEntry entry = songs.stream()
                .filter(e -> e.getSongId().equals(songId))
                .findFirst().orElse(null);
        if (entry == null) return;
        songs.remove(entry);
        if (newPosition > songs.size()) newPosition = songs.size();
        songs.add(newPosition, entry);
        reindex();
    }

    private void reindex() {
        for (int i = 0; i < songs.size(); i++) {
            songs.get(i).setPosition(i);
        }
    }

    @Override
    public String toString() {
        return String.format("Playlist[%s: \"%s\" (%d songs, v%d, %s)]",
                playlistId, name, songs.size(), version,
                collaborative ? "collaborative" : "private");
    }

    public static class PlaylistEntry {
        private final String songId;
        private int position;

        public PlaylistEntry(String songId, int position) {
            this.songId = songId;
            this.position = position;
        }

        public String getSongId() { return songId; }
        public int getPosition() { return position; }
        public void setPosition(int position) { this.position = position; }
    }
}
