package com.chat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameEntry {
    public static final String CATEGORY_TOOL = "工具";
    public static final String CATEGORY_GAME = "游戏";
    public static final String CATEGORY_STUDY = "学习";
    public static final String CATEGORY_LIFE = "生活";
    public static final String CATEGORY_ENTERTAINMENT = "娱乐";
    public static final String CATEGORY_OTHER = "其他";

    public static final List<String> SUPPORTED_CATEGORIES = List.of(
            CATEGORY_TOOL,
            CATEGORY_GAME,
            CATEGORY_STUDY,
            CATEGORY_LIFE,
            CATEGORY_ENTERTAINMENT,
            CATEGORY_OTHER
    );

    private String id;
    private String title;
    private String category = CATEGORY_GAME;
    private String desc;
    private String coverPath;
    private String previewVideoPath;
    private String developerUserId;
    private String status = "approved"; // approved / pending / rejected
    private long createdAt;
    private List<GameVersion> versions = new ArrayList<>();
    private long visitCount;
    private long heat;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDesc() {
        return desc;
    }

    public void setDesc(String desc) {
        this.desc = desc;
    }

    public String getCoverPath() {
        return coverPath;
    }

    public void setCoverPath(String coverPath) {
        this.coverPath = coverPath;
    }

    public String getPreviewVideoPath() {
        return previewVideoPath;
    }

    public void setPreviewVideoPath(String previewVideoPath) {
        this.previewVideoPath = previewVideoPath;
    }

    public String getDeveloperUserId() {
        return developerUserId;
    }

    public void setDeveloperUserId(String developerUserId) {
        this.developerUserId = developerUserId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public List<GameVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<GameVersion> versions) {
        this.versions = versions;
    }

    public long getVisitCount() {
        return visitCount;
    }

    public void setVisitCount(long visitCount) {
        this.visitCount = visitCount;
    }

    public long getHeat() {
        return heat;
    }

    public void setHeat(long heat) {
        this.heat = heat;
    }

    public GameVersion getLatestVersion() {
        List<GameVersion> currentVersions = getVersions();
        if (currentVersions == null || currentVersions.isEmpty()) {
            return null;
        }
        GameVersion latest = currentVersions.get(0);
        for (int i = 1; i < currentVersions.size(); i++) {
            GameVersion candidate = currentVersions.get(i);
            if (candidate.getUploadTime() > latest.getUploadTime()) {
                latest = candidate;
            }
        }
        return latest;
    }
}
