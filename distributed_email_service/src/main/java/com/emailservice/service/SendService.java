package com.emailservice.service;

import com.emailservice.model.Attachment;
import com.emailservice.model.Email;
import com.emailservice.storage.AttachmentStore;
import com.emailservice.storage.MetadataDB;
import com.emailservice.storage.SearchStore;

import java.util.List;

/**
 * Email Sending Service.
 *
 * Flow: Web Server → Validate → Store in Sent folder → Outgoing Queue
 *       → SMTP Outgoing (spam/virus check) → Recipient's mail server
 */
public class SendService {
    private final MetadataDB metadataDB;
    private final AttachmentStore attachmentStore;
    private final SearchStore searchStore;
    private int nextEmailId = 1;
    private int nextAttachmentId = 1;

    public SendService(MetadataDB metadataDB, AttachmentStore attachmentStore,
                       SearchStore searchStore) {
        this.metadataDB = metadataDB;
        this.attachmentStore = attachmentStore;
        this.searchStore = searchStore;
    }

    /**
     * Send an email.
     * Steps:
     * 1. Validate (size, recipients, rate limit)
     * 2. Spam/virus check on outgoing
     * 3. Store in sender's "Sent" folder
     * 4. Enqueue to outgoing message queue
     * 5. SMTP outgoing sends to recipient mail servers
     */
    public Email sendEmail(String fromUser, List<String> toUsers,
                           String subject, String body) {
        System.out.println("\n--- Sending email ---");
        String emailId = "EMAIL-" + (nextEmailId++);
        Email email = new Email(emailId, fromUser, toUsers, subject, body);

        // Step 1: Validate
        System.out.println("  [VALIDATE] Checking size limits, recipient count...");
        if (toUsers.isEmpty()) {
            System.out.println("  [REJECTED] No recipients");
            return null;
        }

        // Step 2: Spam/virus check
        boolean passedChecks = spamAndVirusCheck(email);
        if (!passedChecks) {
            System.out.println("  [REJECTED] Failed spam/virus check");
            return null;
        }

        // Step 3: Store in sender's Sent folder
        email.moveToFolder("sent");
        email.markAsRead(); // sent emails are read by default
        metadataDB.storeEmail(fromUser, email);
        searchStore.indexEmail(fromUser, email);
        System.out.println("  [STORED] In sender's Sent folder");

        // Step 4: Enqueue to outgoing queue (simulated)
        System.out.println("  [QUEUED] Added to outgoing message queue");

        // Step 5: SMTP outgoing sends to recipients (simulated)
        System.out.println("  [SMTP] Sending to recipients: " + toUsers);

        return email;
    }

    /** Send email with attachment */
    public Email sendEmailWithAttachment(String fromUser, List<String> toUsers,
                                          String subject, String body,
                                          String fileName, String contentType,
                                          long sizeBytes) {
        Email email = sendEmail(fromUser, toUsers, subject, body);
        if (email != null) {
            String attId = "ATT-" + (nextAttachmentId++);
            Attachment attachment = new Attachment(attId, email.getEmailId(),
                    fileName, contentType, sizeBytes);
            attachmentStore.store(attachment);
            email.addAttachment(attId);
        }
        return email;
    }

    private boolean spamAndVirusCheck(Email email) {
        // Simulated — always passes in demo
        System.out.println("  [SPAM CHECK] Passed");
        System.out.println("  [VIRUS CHECK] Passed");
        return true;
    }
}
