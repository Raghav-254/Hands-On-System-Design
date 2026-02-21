package com.spotify.model;

public class Album {
    private final String albumId;
    private final String artistId;
    private final String title;
    private final int releaseYear;
    private final String artUrl;

    public Album(String albumId, String artistId, String title, int releaseYear, String artUrl) {
        this.albumId = albumId;
        this.artistId = artistId;
        this.title = title;
        this.releaseYear = releaseYear;
        this.artUrl = artUrl;
    }

    public String getAlbumId() { return albumId; }
    public String getArtistId() { return artistId; }
    public String getTitle() { return title; }
    public int getReleaseYear() { return releaseYear; }
    public String getArtUrl() { return artUrl; }

    @Override
    public String toString() {
        return String.format("Album[%s: \"%s\" (%d)]", albumId, title, releaseYear);
    }
}
