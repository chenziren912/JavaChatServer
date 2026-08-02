package com.chat.model;

import java.util.ArrayList;
import java.util.List;

public class StoredFileMetadata {
    private String storedName;
    private String originalFileName;
    private String sha256;
    private String contentType;
    private long size;
    private long createdAt;
    private long lastAccessAt;
    private List<String> ownerUserIds = new ArrayList<>();

    public String getStoredName() {
        return storedName;
    }

    public void setStoredName(String storedName) {
        this.storedName = storedName;
    }

    public String getOriginalFileName() {
        return originalFileName;
    }

    public void setOriginalFileName(String originalFileName) {
        this.originalFileName = originalFileName;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getLastAccessAt() {
        return lastAccessAt;
    }

    public void setLastAccessAt(long lastAccessAt) {
        this.lastAccessAt = lastAccessAt;
    }

    public List<String> getOwnerUserIds() {
        if (ownerUserIds == null) ownerUserIds = new ArrayList<>();
        return ownerUserIds;
    }

    public void setOwnerUserIds(List<String> ownerUserIds) {
        this.ownerUserIds = ownerUserIds != null ? new ArrayList<>(ownerUserIds) : new ArrayList<>();
    }

    public boolean addOwnerUserId(String userId) {
        if (userId == null || userId.isBlank() || getOwnerUserIds().contains(userId)) return false;
        getOwnerUserIds().add(userId);
        return true;
    }

    public boolean isOwnedBy(String userId) {
        return userId != null && getOwnerUserIds().contains(userId);
    }

    public String getAccessPath() {
        return "/files/" + storedName;
    }
}
