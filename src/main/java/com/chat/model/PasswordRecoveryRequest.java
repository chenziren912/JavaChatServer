package com.chat.model;

public class PasswordRecoveryRequest {
    private String id;
    private String username;
    private String reason;
    private boolean accountExists;
    private String status;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String value) { id = value; }
    public String getUsername() { return username; }
    public void setUsername(String value) { username = value; }
    public String getReason() { return reason; }
    public void setReason(String value) { reason = value; }
    public boolean isAccountExists() { return accountExists; }
    public void setAccountExists(boolean value) { accountExists = value; }
    public String getStatus() { return status == null || status.isBlank() ? "open" : status; }
    public void setStatus(String value) { status = value; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long value) { createdAt = value; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long value) { updatedAt = value; }
}
