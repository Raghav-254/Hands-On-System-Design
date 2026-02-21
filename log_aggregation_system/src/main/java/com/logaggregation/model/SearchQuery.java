package com.logaggregation.model;

import java.time.Instant;

public class SearchQuery {
    private final String text;
    private final String service;
    private final LogLevel level;
    private final Instant from;
    private final Instant to;
    private final int limit;

    public SearchQuery(String text, String service, LogLevel level,
                       Instant from, Instant to, int limit) {
        this.text = text;
        this.service = service;
        this.level = level;
        this.from = from;
        this.to = to;
        this.limit = limit > 0 ? limit : 50;
    }

    public String getText() { return text; }
    public String getService() { return service; }
    public LogLevel getLevel() { return level; }
    public Instant getFrom() { return from; }
    public Instant getTo() { return to; }
    public int getLimit() { return limit; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SearchQuery[");
        if (text != null) sb.append("q=\"").append(text).append("\" ");
        if (service != null) sb.append("service=").append(service).append(" ");
        if (level != null) sb.append("level>=").append(level).append(" ");
        if (from != null) sb.append("from=").append(from).append(" ");
        if (to != null) sb.append("to=").append(to).append(" ");
        sb.append("limit=").append(limit).append("]");
        return sb.toString();
    }
}
