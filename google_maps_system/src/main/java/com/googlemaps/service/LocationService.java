package com.googlemaps.service;

import com.googlemaps.model.Location;
import com.googlemaps.storage.UserLocationDB;
import java.util.*;

/**
 * Location Service - handles user location updates.
 *
 * Key design: BATCH UPDATES (Figure 9 from the book)
 * - Client records GPS location every second
 * - Batches 15 seconds of location data into a single request
 * - Sends batch to Location Service every 15 seconds
 * - Reduces network calls from 1/sec to 1/15sec (15x reduction!)
 *
 * The Location Service:
 * 1. Saves batch to User Location DB
 * 2. Publishes to Kafka for downstream consumers:
 *    - Traffic Update Service → updates Traffic DB
 *    - Routing Tile Processing Service → updates routing tiles
 *    - ML Personalization Service → learns user patterns
 *    - Analytics Service → aggregated statistics
 */
public class LocationService {
    private final UserLocationDB userLocationDB;
    private final List<KafkaMessage> kafkaStream; // Simulates Kafka topic

    public LocationService(UserLocationDB userLocationDB) {
        this.userLocationDB = userLocationDB;
        this.kafkaStream = new ArrayList<>();
    }

    /**
     * Receive a batch of location updates from a user.
     * Batch = ~15 GPS points collected over 15 seconds.
     */
    public void receiveBatch(String userId, List<Location> batch) {
        System.out.printf("  [LocationService] Received batch from %s: %d locations%n", userId, batch.size());

        // Step 1: Save to User Location DB
        userLocationDB.saveBatch(userId, batch);
        System.out.printf("  [LocationService] Saved to User Location DB%n");

        // Step 2: Publish to Kafka for downstream consumers
        for (Location loc : batch) {
            kafkaStream.add(new KafkaMessage(userId, loc));
        }
        System.out.printf("  [LocationService] Published %d messages to Kafka%n", batch.size());
    }

    /** Get Kafka stream (for downstream consumers to process) */
    public List<KafkaMessage> getKafkaStream() {
        return Collections.unmodifiableList(kafkaStream);
    }

    /** Simulates a Kafka message */
    public static class KafkaMessage {
        private final String userId;
        private final Location location;
        private final long publishedAt;

        public KafkaMessage(String userId, Location location) {
            this.userId = userId;
            this.location = location;
            this.publishedAt = System.currentTimeMillis();
        }

        public String getUserId() { return userId; }
        public Location getLocation() { return location; }

        @Override
        public String toString() {
            return String.format("KafkaMsg[user=%s, loc=%s]", userId, location);
        }
    }
}
