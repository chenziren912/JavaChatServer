package com.chat.model;

public class CloudShareLink {
    private String id;
    private String ownerId;
    private String entryId;
    private String title;
    private String shareType;
    private long createdAt;
    private long updatedAt;
    private long visitCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getEntryId() { return entryId; }
    public void setEntryId(String entryId) { this.entryId = entryId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getShareType() { return shareType; }
    public void setShareType(String shareType) { this.shareType = shareType; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public long getVisitCount() { return visitCount; }
    public void setVisitCount(long visitCount) { this.visitCount = visitCount; }
}
