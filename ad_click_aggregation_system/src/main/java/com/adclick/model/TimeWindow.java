package com.adclick.model;

/**
 * Represents a time window for aggregation.
 * Supports tumbling windows (fixed, non-overlapping) and
 * sliding windows (overlapping, for "last M minutes" queries).
 */
public class TimeWindow {
    public enum WindowType {
        TUMBLING,   // Fixed, non-overlapping: [0-1min], [1-2min], [2-3min]
        SLIDING     // Overlapping: every minute, aggregate last M minutes
    }

    private final long startMinute;
    private final long endMinute;
    private final WindowType type;

    public TimeWindow(long startMinute, long endMinute, WindowType type) {
        this.startMinute = startMinute;
        this.endMinute = endMinute;
        this.type = type;
    }

    /** Create a 1-minute tumbling window */
    public static TimeWindow tumblingWindow(long minuteBucket) {
        return new TimeWindow(minuteBucket, minuteBucket + 1, WindowType.TUMBLING);
    }

    /** Create a sliding window of last M minutes ending at given minute */
    public static TimeWindow slidingWindow(long endMinute, int windowSizeMinutes) {
        return new TimeWindow(endMinute - windowSizeMinutes, endMinute, WindowType.SLIDING);
    }

    public long getStartMinute() { return startMinute; }
    public long getEndMinute() { return endMinute; }
    public WindowType getType() { return type; }
    public long getDurationMinutes() { return endMinute - startMinute; }

    @Override
    public String toString() {
        return String.format("Window{%s, [%d, %d), size=%d min}",
                type, startMinute, endMinute, getDurationMinutes());
    }
}
