package com.googlemaps.model;

/**
 * Represents a pre-computed map tile image.
 *
 * Map tiles are square images (256x256 pixels) that make up the visual map.
 * The world is divided into tiles at different zoom levels:
 * - Zoom 0: 1 tile covers the entire world
 * - Zoom 1: 4 tiles (2x2 grid)
 * - Zoom 21: ~4.4 trillion tiles (most detailed)
 *
 * Tile URL pattern: /tiles/{zoom}/{x}/{y}.png
 * These are pre-computed and served via CDN for fast delivery.
 */
public class MapTile {
    private final int zoom;
    private final int x;
    private final int y;
    private final String imageUrl;
    private final long sizeBytes;

    public MapTile(int zoom, int x, int y) {
        this.zoom = zoom;
        this.x = x;
        this.y = y;
        this.imageUrl = String.format("https://cdn.maps.example.com/tiles/%d/%d/%d.png", zoom, x, y);
        this.sizeBytes = 256 * 256; // ~100KB compressed PNG per tile
    }

    public int getZoom() { return zoom; }
    public int getX() { return x; }
    public int getY() { return y; }
    public String getImageUrl() { return imageUrl; }
    public long getSizeBytes() { return sizeBytes; }

    @Override
    public String toString() {
        return String.format("Tile(z=%d, x=%d, y=%d) → %s", zoom, x, y, imageUrl);
    }
}
