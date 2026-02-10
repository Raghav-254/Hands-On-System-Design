package com.emailservice.service;

import com.emailservice.model.Email;
import com.emailservice.storage.MetadataDB;
import com.emailservice.storage.SearchStore;

import java.util.List;

/**
 * Email Receiving Service.
 *
 * Flow: SMTP Server (accept) → Incoming Queue → Mail Processing
 *       (spam/virus check) → Store in Metadata DB → Index in Search Store
 *       → Notify via WebSocket (if user online)
 */
public class ReceiveService {
    private final MetadataDB metadataDB;
    private final SearchStore searchStore;
    private int nextEmailId = 1000; // offset to avoid collision with send IDs in demo

    public ReceiveService(MetadataDB metadataDB, SearchStore searchStore) {
        this.metadataDB = metadataDB;
        this.searchStore = searchStore;
    }

    /**
     * Receive an incoming email.
     * Steps:
     * 1. SMTP server accepts (email acceptance policy)
     * 2. Enqueue to incoming message queue
     * 3. Mail processing: spam + virus check
     * 4. Store email metadata in DB (in recipient's Inbox)
     * 5. Index in search store
     * 6. Push notification via WebSocket (if user online)
     * 7. If user offline → store, they'll fetch via HTTP on next login
     */
    public Email receiveEmail(String fromUser, String toUser,
                               String subject, String body) {
        System.out.println("\n--- Receiving email ---");
        String emailId = "EMAIL-" + (nextEmailId++);
        Email email = new Email(emailId, fromUser, List.of(toUser), subject, body);

        // Step 1: SMTP acceptance policy
        System.out.println("  [SMTP] Accepted from " + fromUser);

        // Step 2: Enqueue (simulated)
        System.out.println("  [QUEUED] Added to incoming message queue");

        // Step 3: Spam/virus check
        boolean isSpam = spamCheck(email);
        virusCheck(email);

        if (isSpam) {
            email.markAsSpam();
            System.out.println("  [SPAM] Email marked as spam → Spam folder");
        } else {
            email.moveToFolder("inbox");
        }

        // Step 4: Store in recipient's mailbox
        metadataDB.storeEmail(toUser, email);
        System.out.println("  [STORED] In " + toUser + "'s " + email.getFolderId());

        // Step 5: Index for search
        searchStore.indexEmail(toUser, email);
        System.out.println("  [INDEXED] Available for search");

        // Step 6: Push notification (simulated)
        System.out.println("  [WEBSOCKET] Push notification sent to " + toUser);

        return email;
    }

    /** Simulate receiving multiple emails for a user */
    public void receiveMultiple(String fromUser, String toUser, int count) {
        for (int i = 1; i <= count; i++) {
            String subject = "Email #" + i + " from " + fromUser;
            String body = "Body of email #" + i;
            receiveEmail(fromUser, toUser, subject, body);
        }
    }

    private boolean spamCheck(Email email) {
        // Simulate: emails with "lottery" in subject are spam
        boolean isSpam = email.getSubject().toLowerCase().contains("lottery")
                || email.getSubject().toLowerCase().contains("free money");
        System.out.println("  [SPAM CHECK] " + (isSpam ? "SPAM DETECTED" : "Clean"));
        return isSpam;
    }

    private void virusCheck(Email email) {
        System.out.println("  [VIRUS CHECK] Clean");
    }
}
