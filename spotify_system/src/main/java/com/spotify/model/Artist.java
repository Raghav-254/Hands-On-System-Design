package com.spotify.model;

public class Artist {
    private final String artistId;
    private final String name;
    private final String imageUrl;
    private final String bio;

    public Artist(String artistId, String name, String imageUrl, String bio) {
        this.artistId = artistId;
        this.name = name;
        this.imageUrl = imageUrl;
        this.bio = bio;
    }

    public String getArtistId() { return artistId; }
    public String getName() { return name; }
    public String getImageUrl() { return imageUrl; }
    public String getBio() { return bio; }

    @Override
    public String toString() {
        return String.format("Artist[%s: %s]", artistId, name);
    }
}
