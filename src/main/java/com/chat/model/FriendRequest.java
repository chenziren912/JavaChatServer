package com.chat.model;

public class FriendRequest {
    private String id;
    private String fromUserId;
    private String fromNickname;
    private String toUserId;
    private String message;
    private String status; // "pending", "accepted", "rejected"
    private long timestamp;

    public FriendRequest() {}

    public FriendRequest(String id, String fromUserId, String fromNickname,
                         String toUserId, String message) {
        this.id = id;
        this.fromUserId = fromUserId;
        this.fromNickname = fromNickname;
        this.toUserId = toUserId;
        this.message = message;
        this.status = "pending";
        this.timestamp = System.currentTimeMillis();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String fromUserId) { this.fromUserId = fromUserId; }

    public String getFromNickname() { return fromNickname; }
    public void setFromNickname(String fromNickname) { this.fromNickname = fromNickname; }

    public String getToUserId() { return toUserId; }
    public void setToUserId(String toUserId) { this.toUserId = toUserId; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
