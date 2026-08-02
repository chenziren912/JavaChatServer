package com.chat.model;

public class CloudEntry {
    private String id;
    private String ownerId;
    private String parentPath;
    private String name;
    private String type;
    private String storedName;
    private String contentType;
    private long size;
    private long createdAt;
    private long updatedAt;
    private boolean deleted;
    private long deletedAt;
    private String sourceModule;
    private int messageRefCount;
    private boolean favorite;
    private boolean safebox;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getParentPath() { return parentPath; }
    public void setParentPath(String parentPath) { this.parentPath = parentPath; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
    public boolean isDeleted() { return deleted; }
    public void setDeleted(boolean deleted) { this.deleted = deleted; }
    public long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(long deletedAt) { this.deletedAt = deletedAt; }
    public String getSourceModule() { return sourceModule; }
    public void setSourceModule(String sourceModule) { this.sourceModule = sourceModule; }
    public int getMessageRefCount() { return messageRefCount; }
    public void setMessageRefCount(int messageRefCount) { this.messageRefCount = messageRefCount; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public boolean isSafebox() { return safebox; }
    public void setSafebox(boolean safebox) { this.safebox = safebox; }

    public boolean isFolder() {
        return "folder".equals(type);
    }
}
