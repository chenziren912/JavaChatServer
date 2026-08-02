package com.chat.model;

public class MusicTrack {
    private String id;
    private String title;
    private String artist;
    private String album;
    private String cover;
    private String lyrics;
    private String filePath;
    private String cloudEntryId;
    private String uploadedBy;
    private long createdAt;
    private long playCount;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getArtist() { return artist; }
    public void setArtist(String artist) { this.artist = artist; }
    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getLyrics() { return lyrics; }
    public void setLyrics(String lyrics) { this.lyrics = lyrics; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getCloudEntryId() { return cloudEntryId; }
    public void setCloudEntryId(String cloudEntryId) { this.cloudEntryId = cloudEntryId; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getPlayCount() { return playCount; }
    public void setPlayCount(long playCount) { this.playCount = playCount; }
}
