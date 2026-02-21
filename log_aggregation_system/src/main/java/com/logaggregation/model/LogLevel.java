package com.logaggregation.model;

public enum LogLevel {
    DEBUG, INFO, WARN, ERROR, FATAL;

    public boolean isAtLeast(LogLevel other) {
        return this.ordinal() >= other.ordinal();
    }
}
