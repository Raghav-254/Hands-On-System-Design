package com.nearbyfriends.websocket;

import com.nearbyfriends.model.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket Server - manages persistent connections with clients
 * In production: Stateful servers with sticky load balancing
 * 
 * Responsibilities:
 * - Maintain WebSocket connections
 * - Route messages to connected clients
 * - Handle connection lifecycle (connect/disconnect)
 */
public class WebSocketServer {
    private final String serverId;
    private final Map<Long, WebSocketConnection> connections;
    
    public WebSocketServer(String serverId) {
        this.serverId = serverId;
        this.connections = new ConcurrentHashMap<>();
    }
    
    public void connect(long userId, WebSocketConnection connection) {
        connections.put(userId, connection);
        System.out.println("[WS-" + serverId + "] User " + userId + " connected");
    }
    
    public void disconnect(long userId) {
        connections.remove(userId);
        System.out.println("[WS-" + serverId + "] User " + userId + " disconnected");
    }
    
    public boolean isConnected(long userId) {
        return connections.containsKey(userId);
    }
    
    public void sendLocationUpdate(long userId, FriendLocation friendLocation) {
        WebSocketConnection conn = connections.get(userId);
        if (conn != null) {
            conn.send("LOCATION_UPDATE", friendLocation);
        }
    }
    
    public void sendNearbyFriends(long userId, List<FriendLocation> nearbyFriends) {
        WebSocketConnection conn = connections.get(userId);
        if (conn != null) {
            conn.send("NEARBY_FRIENDS", nearbyFriends);
        }
    }
    
    public String getServerId() {
        return serverId;
    }
    
    public int getConnectionCount() {
        return connections.size();
    }
}
