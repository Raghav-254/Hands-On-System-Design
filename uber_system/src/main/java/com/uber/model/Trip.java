package com.uber.model;

import java.time.Instant;

/**
 * Trip entity — single source of truth for ride state.
 * State transitions enforced by TripService (state machine).
 */
public class Trip {
    public enum Status {
        SEARCHING, MATCHED, IN_PROGRESS, COMPLETED, CANCELLED
    }

    private final String rideId;
    private final String riderId;
    private final Location pickup;
    private final Location dropoff;
    private Status status;
    private String driverId;
    private Instant createdAt;
    private Instant matchedAt;
    private Instant startedAt;
    private Instant completedAt;

    public Trip(String rideId, String riderId, Location pickup, Location dropoff) {
        this.rideId = rideId;
        this.riderId = riderId;
        this.pickup = pickup;
        this.dropoff = dropoff;
        this.status = Status.SEARCHING;
        this.createdAt = Instant.now();
    }

    public String getRideId() { return rideId; }
    public String getRiderId() { return riderId; }
    public Location getPickup() { return pickup; }
    public Location getDropoff() { return dropoff; }
    public Status getStatus() { return status; }
    public String getDriverId() { return driverId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getMatchedAt() { return matchedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }

    public void setStatus(Status status) { this.status = status; }
    public void setDriverId(String driverId) { this.driverId = driverId; }
    public void setMatchedAt(Instant matchedAt) { this.matchedAt = matchedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    @Override
    public String toString() {
        String driver = driverId != null ? driverId : "none";
        return String.format("Trip[%s rider=%s driver=%s status=%s pickup=%s dropoff=%s]",
            rideId, riderId, driver, status, pickup, dropoff);
    }
}
