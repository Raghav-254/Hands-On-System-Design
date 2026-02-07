package com.adclick.aggregation;

import com.adclick.model.AdClickEvent;

import java.util.*;

/**
 * Handles late-arriving events using watermarking.
 *
 * Problem: Events may arrive after their time window has closed
 * (due to network delays, device offline, etc.)
 *
 * Solution: Watermark = "I believe all events up to time T have arrived"
 * - Events before watermark → process normally
 * - Events after watermark but within window → accept (late but within tolerance)
 * - Events way past watermark → drop or send to dead letter queue
 */
public class WatermarkHandler {
    private final int watermarkDelayMinutes;  // how long to wait for late events
    private long currentWatermark;            // current watermark minute
    private final List<AdClickEvent> lateEvents = new ArrayList<>();
    private int droppedEvents = 0;

    public WatermarkHandler(int watermarkDelayMinutes) {
        this.watermarkDelayMinutes = watermarkDelayMinutes;
        this.currentWatermark = 0;
    }

    /**
     * Advance the watermark to a new time.
     * Any events with timestamp < (watermark - delay) are too late.
     */
    public void advanceWatermark(long newWatermarkMinute) {
        this.currentWatermark = newWatermarkMinute;
    }

    /**
     * Check if an event is on-time, late-but-acceptable, or too-late.
     */
    public EventStatus classifyEvent(AdClickEvent event) {
        long eventMinute = event.getMinuteBucket();

        if (eventMinute >= currentWatermark) {
            return EventStatus.ON_TIME;
        } else if (eventMinute >= currentWatermark - watermarkDelayMinutes) {
            lateEvents.add(event);
            return EventStatus.LATE_ACCEPTED;
        } else {
            droppedEvents++;
            return EventStatus.TOO_LATE_DROPPED;
        }
    }

    public List<AdClickEvent> getLateEvents() { return new ArrayList<>(lateEvents); }
    public void clearLateEvents() { lateEvents.clear(); }
    public int getDroppedCount() { return droppedEvents; }
    public long getCurrentWatermark() { return currentWatermark; }

    public enum EventStatus {
        ON_TIME,            // Within current window
        LATE_ACCEPTED,      // Late but within watermark tolerance
        TOO_LATE_DROPPED    // Too late, dropped
    }
}
