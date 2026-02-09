# Hotel Reservation System

Based on Alex Xu's System Design Interview Volume 2 - Chapter 7

## Overview

A simplified implementation of a hotel reservation system (like Booking.com). Supports hotel/room browsing, room reservation with concurrency control, overbooking, dynamic pricing, idempotent reservation API, and admin management.

## Key Concepts Demonstrated

- **Reservation Flow**: Search → Reserve → Pay → Confirm (with concurrency handling)
- **Concurrency Control**: Pessimistic locking (SELECT FOR UPDATE) and optimistic locking (version column)
- **Overbooking**: Allow 110% booking capacity to account for cancellations
- **Dynamic Pricing**: Room prices vary by date based on demand/occupancy
- **Idempotent APIs**: Reservation idempotency key to prevent double bookings
- **Data Consistency**: ACID transactions for reservation + payment
- **Caching**: Hotel/room data cached (read-heavy), reservation data NOT cached (consistency)
- **Microservices**: Hotel, Rate, Reservation, Payment as separate services

## Architecture

```
User → CDN (static) → Public API Gateway → Hotel Service (+ Cache)
                                          → Rate Service
                                          → Reservation Service
                                          → Payment Service

Admin → Internal API → Hotel Management Service
```

## Running the Demo

```bash
# Option 1: Using Maven
mvn compile exec:java

# Option 2: Direct compilation
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `HotelReservationDemo.java` | Main demo showcasing all features |
| `model/` | Data models (Hotel, Room, RoomType, Reservation, RoomInventory) |
| `storage/` | Database simulation (HotelDB, ReservationDB, RateDB) |
| `service/` | Business logic (HotelService, ReservationService, RateService) |
