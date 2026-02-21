# Splitwise - System Design

Design a system where users in a group record who paid for what; the system tracks who owes whom and **simplifies debts** so the minimum number of payments settles everyone.

## Key concepts

- **Expense and balances**: Who paid, amount, split (equal/custom); net balance per user; store or compute "A owes B $X."
- **Debt simplification**: Net each person → greedy (largest creditor + largest debtor) → minimal transactions.
- **Eager vs lazy**: Compute simplification on every expense or on demand when user views "simplify."

## Cheatsheet

→ **[INTERVIEW_CHEATSHEET.md](./INTERVIEW_CHEATSHEET.md)** — requirements, data model, simplification algorithm, APIs, architecture, and interview talking points.
