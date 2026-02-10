package com.emailservice.storage;

import com.emailservice.model.Attachment;

import java.util.*;

/**
 * Simulates Object Storage (S3) for email attachments.
 *
 * In production: Amazon S3 / Google Cloud Storage / Azure Blob Storage.
 * Attachments are stored as binary blobs, referenced by attachment_id.
 */
public class AttachmentStore {
    private final Map<String, Attachment> attachments = new LinkedHashMap<>();

    public void store(Attachment attachment) {
        attachments.put(attachment.getAttachmentId(), attachment);
        System.out.println("  [S3] Stored attachment: " + attachment.getFileName() +
                " → " + attachment.getObjectStorageUrl());
    }

    public Attachment get(String attachmentId) {
        return attachments.get(attachmentId);
    }

    public long getTotalStorageBytes() {
        return attachments.values().stream().mapToLong(Attachment::getSizeBytes).sum();
    }
}
