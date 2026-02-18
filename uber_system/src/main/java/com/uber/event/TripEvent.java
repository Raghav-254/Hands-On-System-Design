package com.uber.event;

import java.time.Instant;

/**
 * Event published to Kafka topic "trip-events" on every state transition.
 * Consumed by Notification Service, Payment Service, Analytics.
 */
public class TripEvent {
    public enum Type {
        TRIP_MATCHED, TRIP_STARTED, TRIP_COMPLETED, TRIP_CANCELLED
    }

    private final Type type;
    private final String rideId;
    private final String riderId;
    private final String driverId;
    private final Instant timestamp;

    public TripEvent(Type type, String rideId, String riderId, String driverId) {
        this.type = type;
        this.rideId = rideId;
        this.riderId = riderId;
        this.driverId = driverId;
        this.timestamp = Instant.now();
    }

    public Type getType() { return type; }
    public String getRideId() { return rideId; }
    public String getRiderId() { return riderId; }
    public String getDriverId() { return driverId; }
    public Instant getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        String driver = driverId != null ? driverId : "none";
        return String.format("TripEvent[%s ride=%s rider=%s driver=%s]",
            type, rideId, riderId, driver);
    }
}
