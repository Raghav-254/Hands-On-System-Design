package com.uber.model;

public class Driver {
    public enum Status { ONLINE, OFFLINE, BUSY }

    private final String driverId;
    private final String name;
    private Status status;
    private Location currentLocation;
    private long lastLocationUpdate;

    public Driver(String driverId, String name, Location location) {
        this.driverId = driverId;
        this.name = name;
        this.currentLocation = location;
        this.status = Status.ONLINE;
        this.lastLocationUpdate = System.currentTimeMillis();
    }

    public String getDriverId() { return driverId; }
    public String getName() { return name; }
    public Status getStatus() { return status; }
    public Location getCurrentLocation() { return currentLocation; }
    public long getLastLocationUpdate() { return lastLocationUpdate; }

    public void setStatus(Status status) { this.status = status; }

    public void updateLocation(Location location) {
        this.currentLocation = location;
        this.lastLocationUpdate = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Driver[%s '%s' %s at %s]", driverId, name, status, currentLocation);
    }
}
