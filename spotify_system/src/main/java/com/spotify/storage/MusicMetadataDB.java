package com.spotify.storage;

import com.spotify.model.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Simulates PostgreSQL for song/artist/album/playlist metadata.
 * Uses synchronized for playlist operations to simulate row-level locking.
 */
public class MusicMetadataDB {
    private final Map<String, Song> songs = new ConcurrentHashMap<>();
    private final Map<String, Artist> artists = new ConcurrentHashMap<>();
    private final Map<String, Album> albums = new ConcurrentHashMap<>();
    private final Map<String, Playlist> playlists = new ConcurrentHashMap<>();
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Set<String> idempotencyKeys = ConcurrentHashMap.newKeySet();

    public void addSong(Song song) { songs.put(song.getSongId(), song); }
    public void addArtist(Artist artist) { artists.put(artist.getArtistId(), artist); }
    public void addAlbum(Album album) { albums.put(album.getAlbumId(), album); }
    public void addUser(User user) { users.put(user.getUserId(), user); }

    public Song getSong(String songId) { return songs.get(songId); }
    public Artist getArtist(String artistId) { return artists.get(artistId); }
    public Album getAlbum(String albumId) { return albums.get(albumId); }
    public User getUser(String userId) { return users.get(userId); }
    public Playlist getPlaylist(String playlistId) { return playlists.get(playlistId); }

    public List<Song> getAllSongs() { return new ArrayList<>(songs.values()); }
    public List<Song> getSongsByArtist(String artistId) {
        return songs.values().stream()
                .filter(s -> s.getArtistId().equals(artistId))
                .collect(Collectors.toList());
    }
    public List<Song> getSongsByAlbum(String albumId) {
        return songs.values().stream()
                .filter(s -> s.getAlbumId().equals(albumId))
                .collect(Collectors.toList());
    }

    public Playlist createPlaylist(String playlistId, String ownerId, String name, boolean collaborative) {
        Playlist p = new Playlist(playlistId, ownerId, name, collaborative);
        playlists.put(playlistId, p);
        return p;
    }

    /**
     * Add song to playlist with optimistic locking.
     * Returns true if successful, false if version conflict (another user edited first).
     */
    public synchronized boolean addSongToPlaylist(String playlistId, String songId,
                                                   int position, int expectedVersion,
                                                   String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKeys.add(idempotencyKey)) {
            return true;
        }

        Playlist p = playlists.get(playlistId);
        if (p == null) return false;

        if (p.getVersion() != expectedVersion) {
            return false;
        }

        p.addSong(songId, position);
        p.setVersion(expectedVersion + 1);
        return true;
    }

    public synchronized boolean removeSongFromPlaylist(String playlistId, String songId,
                                                        int expectedVersion) {
        Playlist p = playlists.get(playlistId);
        if (p == null) return false;
        if (p.getVersion() != expectedVersion) return false;

        boolean removed = p.removeSong(songId);
        if (removed) p.setVersion(expectedVersion + 1);
        return removed;
    }

    public synchronized boolean reorderSong(String playlistId, String songId,
                                             int newPosition, int expectedVersion) {
        Playlist p = playlists.get(playlistId);
        if (p == null) return false;
        if (p.getVersion() != expectedVersion) return false;

        p.reorderSong(songId, newPosition);
        p.setVersion(expectedVersion + 1);
        return true;
    }

    public void updatePlayCounts(Map<String, Long> songIdToCounts) {
        songIdToCounts.forEach((songId, count) -> {
            Song s = songs.get(songId);
            if (s != null) s.setPlayCount(s.getPlayCount() + count);
        });
    }
}
