package com.chat.model;

import java.util.ArrayList;
import java.util.List;

public class PublicRoomConfig {
    private String ownerId;
    private List<String> adminIds = new ArrayList<>();
    private boolean allMuted;
    private List<String> mutedUserIds = new ArrayList<>();
    private String description;
    private long updatedAt;

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }
    public List<String> getAdminIds() { return adminIds == null ? (adminIds = new ArrayList<>()) : adminIds; }
    public void setAdminIds(List<String> adminIds) { this.adminIds = adminIds; }
    public boolean isAllMuted() { return allMuted; }
    public void setAllMuted(boolean allMuted) { this.allMuted = allMuted; }
    public List<String> getMutedUserIds() { return mutedUserIds == null ? (mutedUserIds = new ArrayList<>()) : mutedUserIds; }
    public void setMutedUserIds(List<String> mutedUserIds) { this.mutedUserIds = mutedUserIds; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
