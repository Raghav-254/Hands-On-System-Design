package com.emailservice;

import com.emailservice.model.*;
import com.emailservice.service.*;
import com.emailservice.storage.*;

import java.util.*;

/**
 * Demo: Distributed Email Service
 *
 * Demonstrates:
 * 1. Sending emails (with spam/virus check)
 * 2. Receiving emails (with spam detection)
 * 3. Attachments (stored in Object Storage)
 * 4. Folder management (inbox, sent, spam)
 * 5. Read/unread filtering
 * 6. Full-text search (subject, body, sender)
 * 7. Back-of-envelope estimation
 */
public class DistributedEmailDemo {

    public static void main(String[] args) {
        System.out.println("╔══════════════════════════════════════════════════╗");
        System.out.println("║       Distributed Email Service Demo            ║");
        System.out.println("╚══════════════════════════════════════════════════╝");

        // Initialize storage layer
        MetadataDB metadataDB = new MetadataDB();
        AttachmentStore attachmentStore = new AttachmentStore();
        SearchStore searchStore = new SearchStore();

        // Initialize services
        SendService sendService = new SendService(metadataDB, attachmentStore, searchStore);
        ReceiveService receiveService = new ReceiveService(metadataDB, searchStore);
        SearchService searchService = new SearchService(searchStore);

        // Initialize users
        metadataDB.initUser("alice@example.com");
        metadataDB.initUser("bob@example.com");

        // ============================================
        // Demo 1: Send Email
        // ============================================
        System.out.println("\n========== DEMO 1: Send Email ==========");
        Email sent1 = sendService.sendEmail(
                "alice@example.com",
                List.of("bob@example.com"),
                "Project Update",
                "Hi Bob, the project is on track for next week delivery."
        );
        System.out.println("  Result: " + sent1);

        // ============================================
        // Demo 2: Receive Email
        // ============================================
        System.out.println("\n========== DEMO 2: Receive Email ==========");
        Email received1 = receiveService.receiveEmail(
                "alice@example.com", "bob@example.com",
                "Project Update",
                "Hi Bob, the project is on track for next week delivery."
        );

        // Receive another email
        Email received2 = receiveService.receiveEmail(
                "charlie@example.com", "bob@example.com",
                "Meeting Tomorrow",
                "Don't forget our meeting at 10am."
        );

        // ============================================
        // Demo 3: Spam Detection
        // ============================================
        System.out.println("\n========== DEMO 3: Spam Detection ==========");
        Email spamEmail = receiveService.receiveEmail(
                "scammer@sketchy.com", "bob@example.com",
                "You won the LOTTERY!",
                "Click here to claim your free money!"
        );
        System.out.println("  Spam email folder: " + spamEmail.getFolderId());

        // ============================================
        // Demo 4: Send Email with Attachment
        // ============================================
        System.out.println("\n========== DEMO 4: Email with Attachment ==========");
        Email withAttachment = sendService.sendEmailWithAttachment(
                "alice@example.com",
                List.of("bob@example.com"),
                "Q4 Report",
                "Please find the Q4 report attached.",
                "q4_report.pdf", "application/pdf", 2_500_000 // 2.5 MB
        );
        System.out.println("  Attachments: " + withAttachment.getAttachmentIds());

        // ============================================
        // Demo 5: Folder & Read/Unread
        // ============================================
        System.out.println("\n========== DEMO 5: Folder & Read/Unread ==========");

        System.out.println("\n--- Bob's Inbox ---");
        List<Email> bobInbox = metadataDB.getEmailsByFolder("bob@example.com", "inbox");
        for (Email e : bobInbox) {
            System.out.println("  " + e);
        }

        System.out.println("\n--- Bob reads first email ---");
        bobInbox.get(0).markAsRead();

        System.out.println("\n--- Bob's unread emails ---");
        List<Email> unread = metadataDB.getEmailsByReadStatus("bob@example.com", "inbox", false);
        System.out.println("  Unread count: " + unread.size());
        for (Email e : unread) {
            System.out.println("  " + e);
        }

        System.out.println("\n--- Bob's Spam folder ---");
        List<Email> bobSpam = metadataDB.getEmailsByFolder("bob@example.com", "spam");
        for (Email e : bobSpam) {
            System.out.println("  " + e);
        }

        System.out.println("\n--- Alice's Sent folder ---");
        List<Email> aliceSent = metadataDB.getEmailsByFolder("alice@example.com", "sent");
        for (Email e : aliceSent) {
            System.out.println("  " + e);
        }

        // ============================================
        // Demo 6: Search
        // ============================================
        System.out.println("\n========== DEMO 6: Search ==========");

        searchService.search("bob@example.com", "project");
        searchService.search("bob@example.com", "meeting");
        searchService.searchBySender("bob@example.com", "charlie@example.com");

        // ============================================
        // Demo 7: Folders
        // ============================================
        System.out.println("\n========== DEMO 7: Folders ==========");
        System.out.println("Bob's folders:");
        for (Folder f : metadataDB.getFolders("bob@example.com")) {
            System.out.println("  " + f);
        }

        // Move an email to trash
        System.out.println("\n--- Move spam to trash ---");
        spamEmail.moveToFolder("trash");
        System.out.println("  Moved: " + spamEmail);

        // ============================================
        // Demo 8: Back-of-Envelope Estimation
        // ============================================
        System.out.println("\n========== DEMO 8: Back-of-Envelope Estimation ==========");
        System.out.println("┌──────────────────────────────────────────────────────────┐");
        System.out.println("│  Users:                   1 billion                      │");
        System.out.println("│  Emails sent/day/user:    10                             │");
        System.out.println("│  Emails received/day/user:40                             │");
        System.out.println("│                                                          │");
        System.out.println("│  Send QPS:  1B × 10 / 86400 = ~100,000 QPS              │");
        System.out.println("│  Recv QPS:  1B × 40 / 86400 = ~400,000 QPS              │");
        System.out.println("│                                                          │");
        System.out.println("│  Metadata size:    50 KB per email                       │");
        System.out.println("│  Metadata/year:    1B × 40 × 365 × 50KB = 730 PB        │");
        System.out.println("│                                                          │");
        System.out.println("│  20% emails have attachments, avg 500 KB                 │");
        System.out.println("│  Attachments/year: 1B × 40 × 365 × 0.2 × 500KB         │");
        System.out.println("│                  = 1,460 PB                              │");
        System.out.println("│                                                          │");
        System.out.println("│  → This is a STORAGE-HEAVY system!                       │");
        System.out.println("│  → Total ~2,190 PB/year = ~2.2 EB                       │");
        System.out.println("│  → Key challenge: massive storage + search at scale      │");
        System.out.println("└──────────────────────────────────────────────────────────┘");

        System.out.println("\n✓ Demo complete!");
    }
}
