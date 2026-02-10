package com.emailservice.model;

/**
 * Represents an email attachment.
 * Actual file stored in Object Storage (S3); metadata stored in DB.
 */
public class Attachment {
    private final String attachmentId;
    private final String emailId;
    private final String fileName;
    private final String contentType;   // "image/png", "application/pdf"
    private final long sizeBytes;
    private final String objectStorageUrl;  // S3 URL

    public Attachment(String attachmentId, String emailId, String fileName,
                      String contentType, long sizeBytes) {
        this.attachmentId = attachmentId;
        this.emailId = emailId;
        this.fileName = fileName;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.objectStorageUrl = "s3://email-attachments/" + attachmentId + "/" + fileName;
    }

    public String getAttachmentId() { return attachmentId; }
    public String getEmailId() { return emailId; }
    public String getFileName() { return fileName; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public String getObjectStorageUrl() { return objectStorageUrl; }

    @Override
    public String toString() {
        return String.format("Attachment{id=%s, file='%s', size=%dKB, url=%s}",
                attachmentId, fileName, sizeBytes / 1024, objectStorageUrl);
    }
}
