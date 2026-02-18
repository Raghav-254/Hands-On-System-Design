# BookMyShow - System Design

Design a **ticket booking system**: users browse movies and shows, select seats, **hold** them briefly, then pay and confirm. The system must prevent **double booking** and handle high concurrency for popular releases.

## Key concepts

- **Seat states**: Available → Held (TTL) → Confirmed / Expired.
- **Atomic reserve**: Single source of truth (DB or Redis); row lock, optimistic lock, or Redis Lua for "hold this seat."
- **Idempotency**: Confirm (payment + booking) with idempotency key to avoid double charge and double book.

## Cheatsheet

→ **[INTERVIEW_CHEATSHEET.md](./INTERVIEW_CHEATSHEET.md)** — requirements, concurrency, hold/release, API, data model, architecture, and interview talking points.
