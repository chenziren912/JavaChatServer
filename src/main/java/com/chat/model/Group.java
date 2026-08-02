package com.chat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Group {
    private String groupId;
    private String groupName;
    private String ownerId;
    private List<String> members;
    private List<String> admins;
    private long createdAt;
    private Map<String, Long> mutedUntil; // userId -> 禁言到期毫秒
    private String iconPath;              // 群图标 /files/xxx
    private String description;           // 群描述（支持Markdown）
    private boolean allMuted;
    private long allMutedAt;

    public Group() {
        this.members = Collections.synchronizedList(new ArrayList<>());
        this.admins = Collections.synchronizedList(new ArrayList<>());
        this.mutedUntil = new ConcurrentHashMap<>();
    }

    public Group(String groupId, String groupName, String ownerId) {
        this.groupId = groupId; this.groupName = groupName; this.ownerId = ownerId;
        this.members = Collections.synchronizedList(new ArrayList<>());
        this.admins = Collections.synchronizedList(new ArrayList<>());
        this.mutedUntil = new ConcurrentHashMap<>();
        this.createdAt = System.currentTimeMillis();
        this.members.add(ownerId);
    }

    public String getGroupId() { return groupId; }
    public void setGroupId(String v) { this.groupId = v; }
    public String getGroupName() { return groupName; }
    public void setGroupName(String v) { this.groupName = v; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String v) { this.ownerId = v; }
    public List<String> getMembers() {
        if (members == null) members = Collections.synchronizedList(new ArrayList<>());
        return members;
    }
    public void setMembers(List<String> v) {
        this.members = v != null ? Collections.synchronizedList(new ArrayList<>(v)) : Collections.synchronizedList(new ArrayList<>());
    }
    public List<String> getAdmins() {
        if (admins == null) admins = Collections.synchronizedList(new ArrayList<>());
        return admins;
    }
    public void setAdmins(List<String> v) {
        this.admins = v != null ? Collections.synchronizedList(new ArrayList<>(v)) : Collections.synchronizedList(new ArrayList<>());
    }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }
    public String getIconPath() { return iconPath; }
    public void setIconPath(String v) { this.iconPath = v; }
    public String getDescription() { return description; }
    public void setDescription(String v) { this.description = v; }
    public Map<String, Long> getMutedUntil() {
        if (mutedUntil == null) mutedUntil = new ConcurrentHashMap<>();
        return mutedUntil;
    }
    public void setMutedUntil(Map<String, Long> v) {
        this.mutedUntil = v != null ? new ConcurrentHashMap<>(v) : new ConcurrentHashMap<>();
    }
    public boolean isAllMuted() { return allMuted; }
    public void setAllMuted(boolean v) { this.allMuted = v; }
    public long getAllMutedAt() { return allMutedAt; }
    public void setAllMutedAt(long v) { this.allMutedAt = v; }

    public boolean isMember(String uid) { return members.contains(uid); }
    public boolean isAdmin(String uid) { return admins.contains(uid); }
    public boolean isOwner(String uid) { return ownerId != null && ownerId.equals(uid); }
    public boolean canManage(String uid) { return isOwner(uid) || isAdmin(uid); }

    public boolean isMuted(String uid) {
        if (mutedUntil == null) return false;
        Long until = mutedUntil.get(uid);
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) { mutedUntil.remove(uid); return false; }
        return true;
    }
    public long mutedSecondsLeft(String uid) {
        if (!isMuted(uid)) return 0;
        return (getMutedUntil().get(uid) - System.currentTimeMillis()) / 1000;
    }

    public boolean blocksSpeaking(String uid) {
        return isMuted(uid) || (allMuted && !canManage(uid));
    }
}
