package com.spotify.model;

public class User {
    public enum Subscription { FREE, PREMIUM }

    private final String userId;
    private final String name;
    private final String email;
    private final Subscription subscription;

    public User(String userId, String name, String email, Subscription subscription) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.subscription = subscription;
    }

    public String getUserId() { return userId; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public Subscription getSubscription() { return subscription; }

    public int getMaxBitrate() {
        return subscription == Subscription.PREMIUM ? 320 : 128;
    }

    @Override
    public String toString() {
        return String.format("User[%s: %s (%s)]", userId, name, subscription);
    }
}
