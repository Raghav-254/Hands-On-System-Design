package com.googlemaps.storage;

import com.googlemaps.model.RoutingTile;
import java.util.*;

/**
 * Simulates Object Storage (S3) for routing tiles.
 *
 * Routing tiles store the road network as a graph:
 * - Nodes = intersections
 * - Edges = road segments with distance, speed limit, one-way info
 *
 * In production:
 * - Stored in S3/Object Storage (not a traditional database)
 * - Loaded into memory by Shortest Path Service as needed
 * - Updated periodically by Routing Tile Processing Service (via Kafka)
 * - Billions of edges across millions of tiles
 */
public class RoutingTileStore {
    private final Map<String, RoutingTile> tiles = new HashMap<>();

    public RoutingTileStore() {
        seed();
    }

    private void seed() {
        // Tile covering San Francisco Bay Area
        RoutingTile bayArea = new RoutingTile("tile_bay_area");
        bayArea.addEdge("sf_downtown", "sf_mission", 3.2, 40, false);
        bayArea.addEdge("sf_downtown", "sf_soma", 1.5, 35, false);
        bayArea.addEdge("sf_soma", "sf_mission", 2.1, 35, false);
        bayArea.addEdge("sf_downtown", "oakland_bridge", 8.5, 80, false);
        bayArea.addEdge("oakland_bridge", "oakland_dt", 3.0, 60, false);
        bayArea.addEdge("sf_downtown", "daly_city", 12.0, 65, false);
        bayArea.addEdge("daly_city", "san_mateo", 18.0, 100, false);
        bayArea.addEdge("san_mateo", "mountain_view", 22.0, 100, false);
        bayArea.addEdge("mountain_view", "cupertino", 8.0, 50, false);
        tiles.put("tile_bay_area", bayArea);

        // Tile covering Mountain View / South Bay
        RoutingTile southBay = new RoutingTile("tile_south_bay");
        southBay.addEdge("mountain_view", "sunnyvale", 6.0, 55, false);
        southBay.addEdge("sunnyvale", "santa_clara", 5.0, 50, false);
        southBay.addEdge("santa_clara", "san_jose", 10.0, 65, false);
        southBay.addEdge("mountain_view", "palo_alto", 7.0, 45, false);
        southBay.addEdge("palo_alto", "menlo_park", 5.0, 40, false);
        tiles.put("tile_south_bay", southBay);

        // Highway-only tile (for faster long-distance routing)
        RoutingTile highway = new RoutingTile("tile_highway_101");
        highway.addEdge("sf_downtown", "mountain_view", 55.0, 100, false);
        highway.addEdge("mountain_view", "san_jose", 25.0, 100, false);
        highway.addEdge("sf_downtown", "san_jose", 77.0, 100, false);
        tiles.put("tile_highway_101", highway);
    }

    public Optional<RoutingTile> getTile(String tileId) {
        return Optional.ofNullable(tiles.get(tileId));
    }

    public List<String> getAllTileIds() {
        return new ArrayList<>(tiles.keySet());
    }

    /** Find tiles that contain a specific node */
    public List<RoutingTile> getTilesForNode(String node) {
        List<RoutingTile> result = new ArrayList<>();
        for (RoutingTile tile : tiles.values()) {
            if (tile.getNodes().contains(node)) {
                result.add(tile);
            }
        }
        return result;
    }
}
