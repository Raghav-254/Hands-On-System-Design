package com.nearbyfriends.websocket;

/**
 * WebSocket Connection - represents a client connection
 * In production: Actual WebSocket connection (Netty/Spring WebSocket)
 */
public class WebSocketConnection {
    private final long userId;
    private final String connectionId;
    
    public WebSocketConnection(long userId) {
        this.userId = userId;
        this.connectionId = generateConnectionId();
    }
    
    public void send(String messageType, Object data) {
        // In production: actually send over WebSocket
        System.out.println("[WebSocket → User " + userId + "] " + messageType + ": " + data);
    }
    
    private String generateConnectionId() {
        return "conn-" + userId + "-" + System.currentTimeMillis();
    }
    
    public long getUserId() {
        return userId;
    }
}
