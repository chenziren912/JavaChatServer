package com.chat.model;

public class CloudTask {
    private String id;
    private String ownerId;
    private String type;
    private String title;
    private String status;
    private long totalBytes;
    private long processedBytes;
    private double speedBytesPerSec;
    private String detail;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getTotalBytes() { return totalBytes; }
    public void setTotalBytes(long totalBytes) { this.totalBytes = totalBytes; }
    public long getProcessedBytes() { return processedBytes; }
    public void setProcessedBytes(long processedBytes) { this.processedBytes = processedBytes; }
    public double getSpeedBytesPerSec() { return speedBytesPerSec; }
    public void setSpeedBytesPerSec(double speedBytesPerSec) { this.speedBytesPerSec = speedBytesPerSec; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
