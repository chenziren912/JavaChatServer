package com.chat.model;

public class CloudDownloadRecord {
    private String id;
    private String ownerId;
    private String entryId;
    private String fileName;
    private long downloadedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public long getDownloadedAt() { return downloadedAt; }
    public void setDownloadedAt(long downloadedAt) { this.downloadedAt = downloadedAt; }
}
