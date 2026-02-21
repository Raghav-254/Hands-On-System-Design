package com.splitwise.storage;

import com.splitwise.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Simulates the database for groups, expenses, settlements, balances, and idempotency.
 * In production: MySQL/PostgreSQL, sharded by group_id.
 */
public class GroupDB {

    private final Map<String, Group> groups = new LinkedHashMap<>();
    private final Map<String, List<Expense>> expenses = new LinkedHashMap<>(); // group_id → expenses
    private final Map<String, List<Settlement>> settlements = new LinkedHashMap<>();
    private final Map<String, Map<String, Balance>> balances = new LinkedHashMap<>(); // group_id → key → Balance
    private final Set<String> idempotencyKeys = new HashSet<>();

    private int expenseCounter = 0;
    private int settlementCounter = 0;

    public void createGroup(Group group) {
        groups.put(group.getGroupId(), group);
        expenses.put(group.getGroupId(), new ArrayList<>());
        settlements.put(group.getGroupId(), new ArrayList<>());
        balances.put(group.getGroupId(), new LinkedHashMap<>());
    }

    public Group getGroup(String groupId) { return groups.get(groupId); }

    /** Check if idempotency key has been used. */
    public boolean hasIdempotencyKey(String key) { return idempotencyKeys.contains(key); }

    /**
     * Atomic transaction: INSERT expense + UPDATE balances + store idempotency key.
     * synchronized = simulates DB-level transaction lock per group.
     */
    public synchronized Expense addExpense(String groupId, String payerId, double amount,
                                            String description, Expense.SplitType splitType,
                                            List<String> participantIds, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKeys.contains(idempotencyKey)) {
            return null; // duplicate
        }

        // Compute splits
        Map<String, Double> splits = new LinkedHashMap<>();
        if (splitType == Expense.SplitType.EQUAL) {
            double share = Math.round(amount / participantIds.size() * 100.0) / 100.0;
            for (String uid : participantIds) {
                splits.put(uid, share);
            }
        }

        String expenseId = "e_" + String.format("%03d", ++expenseCounter);
        Expense expense = new Expense(expenseId, groupId, payerId, amount,
            description, splitType, splits);
        expenses.get(groupId).add(expense);

        // Update pairwise balances
        Map<String, Balance> groupBalances = balances.get(groupId);
        for (Map.Entry<String, Double> entry : splits.entrySet()) {
            String userId = entry.getKey();
            double share = entry.getValue();
            if (!userId.equals(payerId)) {
                // userId owes payerId this share
                String key = payerId + ":" + userId;
                String reverseKey = userId + ":" + payerId;

                if (groupBalances.containsKey(reverseKey)) {
                    // userId is currently owed by payerId → decrease that first
                    Balance reverseBalance = groupBalances.get(reverseKey);
                    if (reverseBalance.getAmount() >= share) {
                        reverseBalance.subtractAmount(share);
                    } else {
                        double overflow = share - reverseBalance.getAmount();
                        reverseBalance.subtractAmount(reverseBalance.getAmount());
                        groupBalances.computeIfAbsent(key,
                            k -> new Balance(groupId, payerId, userId)).addAmount(overflow);
                    }
                } else {
                    groupBalances.computeIfAbsent(key,
                        k -> new Balance(groupId, payerId, userId)).addAmount(share);
                }
            }
        }

        if (idempotencyKey != null) {
            idempotencyKeys.add(idempotencyKey);
        }
        return expense;
    }

    /**
     * Atomic: INSERT settlement + UPDATE balance.
     */
    public synchronized Settlement settle(String groupId, String fromUserId, String toUserId,
                                           double amount, String idempotencyKey) {
        if (idempotencyKey != null && idempotencyKeys.contains(idempotencyKey)) {
            return null;
        }

        Map<String, Balance> groupBalances = balances.get(groupId);
        String key = toUserId + ":" + fromUserId; // toUserId is creditor, fromUserId is debtor
        Balance balance = groupBalances.get(key);

        if (balance == null || balance.getAmount() < amount - 0.01) {
            return null; // fromUserId doesn't owe toUserId this much
        }

        balance.subtractAmount(amount);

        String settlementId = "s_" + String.format("%03d", ++settlementCounter);
        Settlement settlement = new Settlement(settlementId, groupId, fromUserId, toUserId, amount);
        settlements.get(groupId).add(settlement);

        if (idempotencyKey != null) {
            idempotencyKeys.add(idempotencyKey);
        }
        return settlement;
    }

    /** Get all non-zero pairwise balances for a group. */
    public List<Balance> getBalances(String groupId) {
        Map<String, Balance> groupBalances = balances.get(groupId);
        if (groupBalances == null) return Collections.emptyList();
        return groupBalances.values().stream()
            .filter(b -> Math.abs(b.getAmount()) > 0.01)
            .collect(Collectors.toList());
    }

    /** Get net balance per user in a group. */
    public Map<String, Double> getNetBalances(String groupId) {
        Map<String, Double> nets = new LinkedHashMap<>();
        Group group = groups.get(groupId);
        if (group == null) return nets;

        for (String member : group.getMemberIds()) {
            nets.put(member, 0.0);
        }

        for (Balance b : getBalances(groupId)) {
            nets.merge(b.getCreditorId(), b.getAmount(), Double::sum);
            nets.merge(b.getDebtorId(), -b.getAmount(), Double::sum);
        }
        return nets;
    }

    public List<Expense> getExpenses(String groupId) {
        return expenses.getOrDefault(groupId, Collections.emptyList());
    }

    public List<Settlement> getSettlements(String groupId) {
        return settlements.getOrDefault(groupId, Collections.emptyList());
    }
}
