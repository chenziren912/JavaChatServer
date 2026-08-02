package com.chat.model;

public class VideoDanmaku {
    private String id;
    private String videoId;
    private String userId;
    private String nickname;
    private String content;
    private String color;
    private String position;
    private double timeSec;
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }
    public double getTimeSec() { return timeSec; }
    public void setTimeSec(double timeSec) { this.timeSec = timeSec; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
