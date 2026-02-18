# Uber (Ride-Sharing) - System Design

Design a system where **riders** request rides and **drivers** accept them; match riders to nearby drivers, track **location** in real time, and handle the full **trip lifecycle** from request to completion.

## Key concepts

- **Matching**: Geospatial (geohash, Redis GEO) to find nearby available drivers; push (assign) or pull (list); atomic accept to avoid double-assign.
- **Location updates**: High write volume; Redis/cache; serve to rider via polling or WebSocket.
- **Trip lifecycle**: Requested → Searching → Matched → In progress → Completed; single source of truth; idempotent state transitions.

## Cheatsheet

→ **[INTERVIEW_CHEATSHEET.md](./INTERVIEW_CHEATSHEET.md)** — requirements, matching, location, trip state machine, data model, architecture, and interview talking points.
