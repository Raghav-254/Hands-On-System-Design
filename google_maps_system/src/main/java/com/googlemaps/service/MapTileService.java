package com.googlemaps.service;

import com.googlemaps.cache.MapTileCDN;
import com.googlemaps.model.MapTile;
import java.util.*;

/**
 * Map Tile Service - constructs tile URLs for the client.
 *
 * Flow (Figure 12 from the book):
 * ① Client sends current viewport (lat/lng bounds + zoom) to Map Tile Service
 * ② Load Balancer forwards to Map Tile Service
 * ③ Map Tile Service constructs tile URLs based on zoom/x/y
 * ④ Client downloads tiles directly from CDN using those URLs
 *
 * The client does NOT download tiles through our servers!
 * We only tell the client WHICH tiles to fetch. CDN does the heavy lifting.
 */
public class MapTileService {
    private final MapTileCDN cdn;

    public MapTileService(MapTileCDN cdn) {
        this.cdn = cdn;
    }

    /**
     * Given a location and zoom level, compute which tiles are needed.
     * Returns tile URLs that the client fetches directly from CDN.
     *
     * At each zoom level, the number of tiles = 4^zoom
     * Zoom 0: 1 tile (whole world)
     * Zoom 12: ~16M tiles (city-level detail)
     * Zoom 21: ~4.4 trillion tiles (building-level)
     */
    public List<String> getTileUrlsForViewport(double lat, double lng, int zoom) {
        // Convert lat/lng to tile coordinates
        int tileX = lonToTileX(lng, zoom);
        int tileY = latToTileY(lat, zoom);

        // Fetch a 3x3 grid of tiles around the center (9 tiles per viewport)
        List<String> urls = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                urls.add(String.format("https://cdn.maps.example.com/tiles/%d/%d/%d.png",
                        zoom, tileX + dx, tileY + dy));
            }
        }
        return urls;
    }

    /** Fetch a specific tile (simulates client downloading from CDN) */
    public MapTile fetchTile(int zoom, int x, int y) {
        return cdn.getTile(zoom, x, y);
    }

    /** Convert longitude to tile X coordinate */
    private int lonToTileX(double lon, int zoom) {
        return (int) Math.floor((lon + 180.0) / 360.0 * Math.pow(2, zoom));
    }

    /** Convert latitude to tile Y coordinate */
    private int latToTileY(double lat, int zoom) {
        double latRad = Math.toRadians(lat);
        return (int) Math.floor((1.0 - Math.log(Math.tan(latRad) + 1.0 / Math.cos(latRad))
                / Math.PI) / 2.0 * Math.pow(2, zoom));
    }
}
