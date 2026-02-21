package com.splitwise.service;

import com.splitwise.event.EventBus;
import com.splitwise.event.SplitwiseEvent;
import com.splitwise.model.*;
import com.splitwise.storage.GroupDB;

/**
 * Handles settle-up: records real-world payment and decreases pairwise balance.
 */
public class SettlementService {

    private final GroupDB db;
    private final EventBus eventBus;

    public SettlementService(GroupDB db, EventBus eventBus) {
        this.db = db;
        this.eventBus = eventBus;
    }

    public Settlement settle(String groupId, String fromUserId, String toUserId,
                              double amount, String idempotencyKey) {
        // Validate
        Group group = db.getGroup(groupId);
        if (group == null || !group.hasMember(fromUserId) || !group.hasMember(toUserId)) {
            System.out.println("  [SETTLE] Invalid group or members");
            return null;
        }

        // Idempotency
        if (idempotencyKey != null && db.hasIdempotencyKey(idempotencyKey)) {
            System.out.println("  [IDEMPOTENT] Key '" + idempotencyKey + "' already used → skip");
            return null;
        }

        // Settle (atomic: INSERT settlement + UPDATE balance)
        Settlement settlement = db.settle(groupId, fromUserId, toUserId, amount, idempotencyKey);
        if (settlement == null) {
            System.out.println("  [SETTLE] Failed: " + fromUserId +
                " doesn't owe " + toUserId + " $" + String.format("%.2f", amount));
            return null;
        }

        System.out.println("  [SETTLE] " + settlement);

        eventBus.publish(new SplitwiseEvent(SplitwiseEvent.Type.DEBT_SETTLED,
            groupId, fromUserId, null, settlement.getSettlementId(),
            String.format("%s settled $%.2f with %s", fromUserId, amount, toUserId)));

        return settlement;
    }
}
