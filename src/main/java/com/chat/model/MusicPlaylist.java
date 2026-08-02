package com.chat.model;

import java.util.ArrayList;
import java.util.List;

public class MusicPlaylist {
    private String id;
    private String userId;
    private String name;
    private boolean favorite;
    private List<String> trackIds = new ArrayList<>();
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public List<String> getTrackIds() { return trackIds == null ? (trackIds = new ArrayList<>()) : trackIds; }
    public void setTrackIds(List<String> trackIds) { this.trackIds = trackIds; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
