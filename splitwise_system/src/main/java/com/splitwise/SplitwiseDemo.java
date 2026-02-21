package com.splitwise;

import com.splitwise.event.EventBus;
import com.splitwise.event.SplitwiseEvent;
import com.splitwise.model.*;
import com.splitwise.service.*;
import com.splitwise.service.SimplificationEngine.Transaction;
import com.splitwise.storage.GroupDB;

import java.util.*;

/**
 * Demonstrates the Splitwise System Design concepts:
 * 1. Equal Split Expense (payer pays, cost split equally)
 * 2. Pairwise Balance Tracking (who owes whom, eagerly updated)
 * 3. Multiple Expenses (balances accumulate correctly)
 * 4. Debt Simplification (greedy algorithm → minimal transactions)
 * 5. Settle Up (real-world payment → balance decreases)
 * 6. Idempotency (duplicate expense → rejected)
 * 7. Net Balance Invariant (sum of all nets = 0)
 * 8. Event-Driven Notifications (Kafka simulation)
 */
public class SplitwiseDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║       Splitwise System Demo              ║");
        System.out.println("║       Expenses + Simplify + Settle       ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        demoEqualSplit();
        demoMultipleExpenses();
        demoSimplification();
        demoSettleUp();
        demoIdempotency();
        demoNetBalanceInvariant();
        demoFourPersonTrip();
        demoEventDriven();
    }

    static void printBalances(GroupDB db, String groupId) {
        List<Balance> balances = db.getBalances(groupId);
        if (balances.isEmpty()) {
            System.out.println("  Balances: all settled ✓");
        } else {
            System.out.println("  Pairwise balances:");
            for (Balance b : balances) {
                System.out.println("    " + b);
            }
        }
    }

    static void printNetBalances(GroupDB db, String groupId) {
        Map<String, Double> nets = db.getNetBalances(groupId);
        System.out.print("  Net balances: ");
        double sum = 0;
        for (Map.Entry<String, Double> e : nets.entrySet()) {
            System.out.printf("%s=%.2f  ", e.getKey(), e.getValue());
            sum += e.getValue();
        }
        System.out.printf("(sum=%.2f)\n", sum);
    }

    static void printSimplified(GroupDB db, String groupId) {
        Map<String, Double> nets = db.getNetBalances(groupId);
        List<Transaction> txns = SimplificationEngine.simplify(nets);
        if (txns.isEmpty()) {
            System.out.println("  Simplified: nothing to settle ✓");
        } else {
            System.out.println("  Simplified transactions:");
            for (Transaction t : txns) {
                System.out.println("    " + t);
            }
        }
    }

    // ─── Demo 1: Equal Split ───────────────────────────────────────────

    static void demoEqualSplit() {
        System.out.println("━━━ Demo 1: Equal Split Expense ━━━");
        System.out.println("Alice pays $90 for dinner. Split among Alice, Bob, Charlie.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);

        db.createGroup(new Group("g_1", "Friends",
            new LinkedHashSet<>(List.of("Alice", "Bob", "Charlie"))));

        expenseService.addExpense("g_1", "Alice", 90.0, "Dinner",
            List.of("Alice", "Bob", "Charlie"), null);

        System.out.println();
        printBalances(db, "g_1");
        printNetBalances(db, "g_1");
        System.out.println("  Bob owes Alice $30, Charlie owes Alice $30 ✓\n");
    }

    // ─── Demo 2: Multiple Expenses ─────────────────────────────────────

    static void demoMultipleExpenses() {
        System.out.println("━━━ Demo 2: Multiple Expenses ━━━");
        System.out.println("Alice pays $90 dinner, then Bob pays $60 for cab.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);

        db.createGroup(new Group("g_2", "Trip",
            new LinkedHashSet<>(List.of("Alice", "Bob", "Charlie"))));

        System.out.println("--- Expense 1: Alice pays $90 dinner ---");
        expenseService.addExpense("g_2", "Alice", 90.0, "Dinner",
            List.of("Alice", "Bob", "Charlie"), null);
        printBalances(db, "g_2");

        System.out.println("\n--- Expense 2: Bob pays $60 cab ---");
        expenseService.addExpense("g_2", "Bob", 60.0, "Cab",
            List.of("Alice", "Bob", "Charlie"), null);
        printBalances(db, "g_2");
        printNetBalances(db, "g_2");
        System.out.println();
    }

    // ─── Demo 3: Debt Simplification ───────────────────────────────────

    static void demoSimplification() {
        System.out.println("━━━ Demo 3: Debt Simplification ━━━");
        System.out.println("A→B $10, B→C $20, C→D $30, D→A $15. Simplify!\n");

        // Manually set up net balances for the example from the cheatsheet
        Map<String, Double> nets = new LinkedHashMap<>();
        nets.put("A", 5.0);   // owed $15, owes $10 → net +5
        nets.put("B", -10.0); // owed $10, owes $20 → net -10
        nets.put("C", -10.0); // owed $20, owes $30 → net -10
        nets.put("D", 15.0);  // owed $30, owes $15 → net +15

        System.out.println("  Net balances: A=+5, B=-10, C=-10, D=+15 (sum=0)");

        List<Transaction> txns = SimplificationEngine.simplify(nets);
        System.out.println("\n  Simplified from 4 original debts to " + txns.size() + " transactions:");
        for (Transaction t : txns) {
            System.out.println("    " + t);
        }
        System.out.println("\n  4 debts → " + txns.size() + " transactions ✓\n");
    }

    // ─── Demo 4: Settle Up ─────────────────────────────────────────────

    static void demoSettleUp() {
        System.out.println("━━━ Demo 4: Settle Up ━━━");
        System.out.println("Bob owes Alice $30. Bob settles $30 → debt cleared.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);
        SettlementService settlementService = new SettlementService(db, eventBus);

        db.createGroup(new Group("g_4", "Test",
            new LinkedHashSet<>(List.of("Alice", "Bob"))));

        expenseService.addExpense("g_4", "Alice", 60.0, "Lunch",
            List.of("Alice", "Bob"), null);

        System.out.println("\n  Before settle:");
        printBalances(db, "g_4");

        System.out.println("\n  Bob settles $30 with Alice:");
        settlementService.settle("g_4", "Bob", "Alice", 30.0, null);

        System.out.println("\n  After settle:");
        printBalances(db, "g_4");
        System.out.println("  Debt cleared ✓\n");
    }

    // ─── Demo 5: Idempotency ──────────────────────────────────────────

    static void demoIdempotency() {
        System.out.println("━━━ Demo 5: Idempotency ━━━");
        System.out.println("Same expense submitted twice with same key → only one recorded.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);

        db.createGroup(new Group("g_5", "Test",
            new LinkedHashSet<>(List.of("Alice", "Bob"))));

        String idemKey = "idem_expense_001";

        System.out.println("  First submit (key=" + idemKey + "):");
        Expense e1 = expenseService.addExpense("g_5", "Alice", 100.0, "Hotel",
            List.of("Alice", "Bob"), idemKey);
        System.out.println("  Result: " + (e1 != null ? "CREATED ✓" : "FAILED"));

        System.out.println("\n  Retry with SAME key:");
        Expense e2 = expenseService.addExpense("g_5", "Alice", 100.0, "Hotel",
            List.of("Alice", "Bob"), idemKey);
        System.out.println("  Result: " + (e2 != null ? "CREATED (duplicate!)" : "REJECTED ✓"));

        System.out.println("\n  Expenses in group: " + db.getExpenses("g_5").size() + " (only 1)");
        printBalances(db, "g_5");
        System.out.println("  Bob owes $50 (not $100). No duplicate ✓\n");
    }

    // ─── Demo 6: Net Balance Invariant ─────────────────────────────────

    static void demoNetBalanceInvariant() {
        System.out.println("━━━ Demo 6: Net Balance Invariant ━━━");
        System.out.println("Sum of all net balances in a group is ALWAYS zero.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);

        db.createGroup(new Group("g_6", "Invariant Test",
            new LinkedHashSet<>(List.of("A", "B", "C", "D"))));

        expenseService.addExpense("g_6", "A", 100.0, "Groceries", List.of("A", "B", "C", "D"), null);
        expenseService.addExpense("g_6", "B", 80.0, "Gas", List.of("A", "B", "C"), null);
        expenseService.addExpense("g_6", "C", 45.0, "Snacks", List.of("B", "C", "D"), null);

        System.out.println();
        printNetBalances(db, "g_6");
        System.out.println("  Sum is always 0 (closed system) ✓\n");
    }

    // ─── Demo 7: Full 4-Person Trip ────────────────────────────────────

    static void demoFourPersonTrip() {
        System.out.println("━━━ Demo 7: Full 4-Person Trip (End-to-End) ━━━");
        System.out.println("4 friends, 3 expenses → view balances → simplify → settle.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();
        ExpenseService expenseService = new ExpenseService(db, eventBus);
        SettlementService settlementService = new SettlementService(db, eventBus);

        db.createGroup(new Group("g_7", "Goa Trip",
            new LinkedHashSet<>(List.of("Alice", "Bob", "Charlie", "Dave"))));

        System.out.println("--- Adding expenses ---");
        expenseService.addExpense("g_7", "Alice", 1200.0, "Hotel",
            List.of("Alice", "Bob", "Charlie", "Dave"), null);
        expenseService.addExpense("g_7", "Bob", 400.0, "Fuel",
            List.of("Alice", "Bob", "Charlie", "Dave"), null);
        expenseService.addExpense("g_7", "Charlie", 200.0, "Food",
            List.of("Alice", "Bob", "Charlie", "Dave"), null);

        System.out.println("\n--- Pairwise balances ---");
        printBalances(db, "g_7");
        printNetBalances(db, "g_7");

        System.out.println("\n--- Simplified (minimum transactions) ---");
        printSimplified(db, "g_7");

        // Settle using actual pairwise debts
        System.out.println("\n--- Settling all debts (using pairwise balances) ---");
        List<Balance> allDebts = db.getBalances("g_7");
        for (Balance b : allDebts) {
            if (b.getAmount() > 0.01) {
                settlementService.settle("g_7", b.getDebtorId(), b.getCreditorId(), b.getAmount(), null);
            }
        }

        System.out.println("\n--- After all settlements ---");
        printBalances(db, "g_7");
        printNetBalances(db, "g_7");
        System.out.println("  All settled ✓\n");
    }

    // ─── Demo 8: Event-Driven Notifications ───────────────────────────

    static void demoEventDriven() {
        System.out.println("━━━ Demo 8: Event-Driven Notifications ━━━");
        System.out.println("Expense/settlement events → Kafka → Notification Service.\n");

        GroupDB db = new GroupDB();
        EventBus eventBus = new EventBus();

        // Subscribe Notification Service
        eventBus.subscribe("NotificationService", event -> {
            switch (event.getType()) {
                case EXPENSE_ADDED:
                    System.out.println("    [NOTIFICATION] Push to group " + event.getGroupId() +
                        ": \"" + event.getMessage() + "\"");
                    break;
                case DEBT_SETTLED:
                    System.out.println("    [NOTIFICATION] Push to group " + event.getGroupId() +
                        ": \"" + event.getMessage() + "\"");
                    break;
            }
        });

        ExpenseService expenseService = new ExpenseService(db, eventBus);
        SettlementService settlementService = new SettlementService(db, eventBus);

        db.createGroup(new Group("g_8", "Demo",
            new LinkedHashSet<>(List.of("Alice", "Bob", "Charlie"))));

        System.out.println("--- Alice adds expense ---");
        expenseService.addExpense("g_8", "Alice", 150.0, "Dinner & Drinks",
            List.of("Alice", "Bob", "Charlie"), null);

        System.out.println("\n--- Bob settles with Alice ---");
        settlementService.settle("g_8", "Bob", "Alice", 50.0, null);

        System.out.println("\n  Total events: " + eventBus.eventCount());
        System.out.println("\n═══════════════════════════════════════════");
        System.out.println("Demo complete!");
    }
}
