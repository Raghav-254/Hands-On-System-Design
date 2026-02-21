package com.splitwise.service;

import com.splitwise.event.EventBus;
import com.splitwise.event.SplitwiseEvent;
import com.splitwise.model.*;
import com.splitwise.storage.GroupDB;
import java.util.*;

/**
 * Handles expense creation with validation, idempotency, and balance updates.
 * Publishes events to Kafka (EventBus) for notifications.
 */
public class ExpenseService {

    private final GroupDB db;
    private final EventBus eventBus;

    public ExpenseService(GroupDB db, EventBus eventBus) {
        this.db = db;
        this.eventBus = eventBus;
    }

    public Expense addExpense(String groupId, String payerId, double amount,
                               String description, List<String> participantIds,
                               String idempotencyKey) {
        // 1. Validate
        Group group = db.getGroup(groupId);
        if (group == null) {
            System.out.println("  [EXPENSE] Group " + groupId + " not found");
            return null;
        }
        if (!group.hasMember(payerId)) {
            System.out.println("  [EXPENSE] Payer " + payerId + " not in group");
            return null;
        }
        for (String pid : participantIds) {
            if (!group.hasMember(pid)) {
                System.out.println("  [EXPENSE] Participant " + pid + " not in group");
                return null;
            }
        }

        // 2. Idempotency check
        if (idempotencyKey != null && db.hasIdempotencyKey(idempotencyKey)) {
            System.out.println("  [IDEMPOTENT] Key '" + idempotencyKey + "' already used → skip");
            return null;
        }

        // 3. Create expense (atomic: INSERT expense + UPDATE balances)
        Expense expense = db.addExpense(groupId, payerId, amount, description,
            Expense.SplitType.EQUAL, participantIds, idempotencyKey);

        if (expense == null) {
            System.out.println("  [EXPENSE] Failed (duplicate or error)");
            return null;
        }

        System.out.println("  [EXPENSE] " + expense);
        System.out.println("    Splits: " + expense.getSplits());

        // 4. Publish event (async)
        eventBus.publish(new SplitwiseEvent(SplitwiseEvent.Type.EXPENSE_ADDED,
            groupId, payerId, expense.getExpenseId(), null,
            String.format("%s paid $%.2f for '%s'", payerId, amount, description)));

        return expense;
    }
}
