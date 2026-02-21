package com.spotify.service;

import com.spotify.model.Playlist;
import com.spotify.storage.MusicMetadataDB;

/**
 * Manages playlist operations with optimistic locking for collaborative edits.
 * Demonstrates the many-to-many relationship between playlists and songs
 * via a join table (playlist_songs) with a position column.
 */
public class PlaylistService {
    private final MusicMetadataDB db;

    public PlaylistService(MusicMetadataDB db) {
        this.db = db;
    }

    public Playlist createPlaylist(String playlistId, String ownerId, String name, boolean collaborative) {
        return db.createPlaylist(playlistId, ownerId, name, collaborative);
    }

    /**
     * Add song with optimistic locking.
     * If another user edited the playlist since we read it, returns CONFLICT.
     */
    public EditResult addSong(String playlistId, String songId, int position,
                               int expectedVersion, String idempotencyKey) {
        if (db.getSong(songId) == null) {
            return new EditResult(false, "Song not found", -1);
        }

        boolean success = db.addSongToPlaylist(playlistId, songId, position,
                expectedVersion, idempotencyKey);
        if (!success) {
            Playlist current = db.getPlaylist(playlistId);
            if (current == null) return new EditResult(false, "Playlist not found", -1);
            return new EditResult(false,
                    "Version conflict: expected v" + expectedVersion + " but playlist is v" + current.getVersion(),
                    current.getVersion());
        }

        Playlist updated = db.getPlaylist(playlistId);
        return new EditResult(true, "Song added", updated.getVersion());
    }

    public EditResult removeSong(String playlistId, String songId, int expectedVersion) {
        boolean success = db.removeSongFromPlaylist(playlistId, songId, expectedVersion);
        if (!success) {
            Playlist current = db.getPlaylist(playlistId);
            if (current == null) return new EditResult(false, "Playlist not found", -1);
            return new EditResult(false,
                    "Version conflict: expected v" + expectedVersion + " but playlist is v" + current.getVersion(),
                    current.getVersion());
        }
        Playlist updated = db.getPlaylist(playlistId);
        return new EditResult(true, "Song removed", updated.getVersion());
    }

    public EditResult reorderSong(String playlistId, String songId,
                                   int newPosition, int expectedVersion) {
        boolean success = db.reorderSong(playlistId, songId, newPosition, expectedVersion);
        if (!success) {
            Playlist current = db.getPlaylist(playlistId);
            if (current == null) return new EditResult(false, "Playlist not found", -1);
            return new EditResult(false,
                    "Version conflict: expected v" + expectedVersion + " but playlist is v" + current.getVersion(),
                    current.getVersion());
        }
        Playlist updated = db.getPlaylist(playlistId);
        return new EditResult(true, "Song reordered", updated.getVersion());
    }

    public Playlist getPlaylist(String playlistId) {
        return db.getPlaylist(playlistId);
    }

    public static class EditResult {
        public final boolean success;
        public final String message;
        public final int currentVersion;

        public EditResult(boolean success, String message, int currentVersion) {
            this.success = success;
            this.message = message;
            this.currentVersion = currentVersion;
        }

        @Override
        public String toString() {
            return success ? "OK (v" + currentVersion + "): " + message
                           : "CONFLICT (v" + currentVersion + "): " + message;
        }
    }
}
