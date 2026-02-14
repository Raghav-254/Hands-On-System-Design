# Payment System

Based on Alex Xu's System Design Interview Volume 2 - Chapter 11

## Overview

A simplified implementation of a payment system for an e-commerce platform. Covers pay-in (customer pays), pay-out (seller receives), PSP integration, double-entry ledger, reconciliation, and failure handling.

## Key Concepts Demonstrated

- **Pay-in / Pay-out Flows**: Complete money movement lifecycle
- **Payment Service Provider (PSP)**: Stripe/PayPal integration via hosted payment page
- **Double-entry Ledger**: Every transaction has equal debit and credit entries
- **Idempotency**: Exactly-once payment processing via idempotency keys
- **Reconciliation**: Cross-system consistency verification
- **Retry & Dead Letter Queue**: Handling failed and non-retryable payments

## Running the Demo

```bash
chmod +x compile-and-run.sh
./compile-and-run.sh
```

## Files

| File | Description |
|------|-------------|
| `INTERVIEW_CHEATSHEET.md` | Complete interview preparation guide |
| `PaymentSystemDemo.java` | Main demo showcasing all features |
| `model/` | Data models (PaymentOrder, LedgerEntry, PayoutOrder) |
| `storage/` | Storage simulation (PaymentDB, LedgerDB) |
| `service/` | Business logic (PaymentService, PaymentExecutor, LedgerService) |
