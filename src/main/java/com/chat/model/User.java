package com.chat.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class User {
    public static final long DEFAULT_CLOUD_QUOTA_BYTES = 2L * 1024 * 1024 * 1024;
    public static final int CURRENT_PASSWORD_POLICY_VERSION = 1;

    private String username;
    private String nickname;
    private String userId;
    private String password;
    private long createdAt;
    private int passwordPolicyVersion;
    private transient boolean requirePasswordChange;
    private boolean tutorialCompleted;
    private List<String> friends;
    private String avatarPath;   // 头像 /files/xxx
    private String bio;          // 个人简介
    private String birthday;     // 生日 yyyy-MM-dd
    private String gender;       // "male" | "female" | "other"
    private String bubbleSkin;   // 气泡皮肤：""/"pink"/"green"/"orange"/"blue"/"dark"
    private String messageFont;  // 消息字体：default/songti/heiti/kaiti/fangsong/dengxian/mono
    private String language = "zh-CN"; // 用户偏好语言
    private List<String> customTags; // 自定义标签
    private long cloudQuotaBytes;
    private String cloudDeletePolicy;
    private boolean cloudInitialized;
    private String aiUsageDay;
    private double aiUsedTokensToday;
    private boolean banned;

    private long banExpiresAt;
    private String banReason;
    private int level;          // 1~6, Lv6=6, Lv6⚡=7
    private long exp;           // 累计经验值
    private String lastCheckIn; // 上次签到日期 yyyy-MM-dd
    private int checkInStreak;  // 连续签到天数
    private int dailyGameUploads; // 今日已上传游戏数
    private String gameUploadDay; // 游戏上传计数日
    private int dailyGamePlayExpCount; // 每日游玩获得经验次数
    private String gamePlayExpDay; // 游玩获得经验的日期
    private Map<String, Long> featureBans;      // 功能封禁: feature -> expiresAt(0=永久)
    private Map<String, String> featureBanReasons; // 功能封禁原因

    // 等级配置: [所需累计经验, 云盘MB, AI日tokens万, 日游戏上传数, 每分钟消息数]
    public static final long[][] LEVEL_CONFIG = {
        {0,       100,  5,   3,   5},   // Lv1
        {30,      300,  10,  5,   8},   // Lv2
        {70,      500,  20,  8,   12},  // Lv3
        {170,     1024, 40,  12,  20},  // Lv4
        {370,     2048, 70,  20,  30},  // Lv5
        {670,     4096, 100, -1, 60},  // Lv6 (-1=无限)
        {0,       -1,   -1,  -1, -1},   // Lv6⚡ (全部无限)
    };

    public static int getLevelByExp(long exp) {
        // Lv6⚡(index 6)是超管专属，不通过经验获得，只遍历Lv1-6(index 0-5)
        for (int i = LEVEL_CONFIG.length - 2; i >= 0; i--) {
            if (exp >= LEVEL_CONFIG[i][0]) return i + 1;
        }
        return 1;
    }

    public long getCloudQuotaByLevel() {
        int lv = getEffectiveLevel();
        long mb = LEVEL_CONFIG[Math.min(lv - 1, LEVEL_CONFIG.length - 1)][1];
        return mb < 0 ? Long.MAX_VALUE : mb * 1024 * 1024;
    }

    public double getAiDailyLimitByLevel() {
        int lv = getEffectiveLevel();
        long val = LEVEL_CONFIG[Math.min(lv - 1, LEVEL_CONFIG.length - 1)][2];
        return val < 0 ? Double.MAX_VALUE : val * 10000D;
    }

    public int getDailyGameUploadLimitByLevel() {
        int lv = getEffectiveLevel();
        long val = LEVEL_CONFIG[Math.min(lv - 1, LEVEL_CONFIG.length - 1)][3];
        return val < 0 ? Integer.MAX_VALUE : (int) val;
    }

    public int getMsgsPerMinuteByLevel() {
        int lv = getEffectiveLevel();
        long val = LEVEL_CONFIG[Math.min(lv - 1, LEVEL_CONFIG.length - 1)][4];
        return val < 0 ? Integer.MAX_VALUE : (int) val;
    }

    public int getEffectiveLevel() {
        if (level >= 7) return 7; // Lv6⚡
        return Math.max(1, level);
    }

    public String getLevelDisplay() {
        if (level >= 7) return "Lv6⚡";
        return "Lv" + Math.max(1, level);
    }

    public User() {
        this.friends = Collections.synchronizedList(new ArrayList<>());
        this.customTags = Collections.synchronizedList(new ArrayList<>());
        this.bubbleSkin = "";
        this.messageFont = "default";
        this.cloudQuotaBytes = DEFAULT_CLOUD_QUOTA_BYTES;
        this.cloudDeletePolicy = "recycle";
        this.createdAt = System.currentTimeMillis();
        this.passwordPolicyVersion = CURRENT_PASSWORD_POLICY_VERSION;
        this.level = 1;
        this.exp = 0;
    }

    public User(String username, String nickname, String userId, String password) {
        this.username = username; this.nickname = nickname;
        this.userId = userId; this.password = password;
        this.friends = Collections.synchronizedList(new ArrayList<>());
        this.customTags = Collections.synchronizedList(new ArrayList<>());
        this.bubbleSkin = "";
        this.messageFont = "default";
        this.cloudQuotaBytes = DEFAULT_CLOUD_QUOTA_BYTES;
        this.cloudDeletePolicy = "recycle";
        this.createdAt = System.currentTimeMillis();
        this.passwordPolicyVersion = CURRENT_PASSWORD_POLICY_VERSION;
        this.level = 1; this.exp = 0;
    }

    public String getUsername() { return username; }
    public void setUsername(String v) { this.username = v; }
    public String getNickname() { return nickname; }
    public void setNickname(String v) { this.nickname = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { this.userId = v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { this.password = v; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }
    public int getPasswordPolicyVersion() { return passwordPolicyVersion; }
    public void setPasswordPolicyVersion(int v) { this.passwordPolicyVersion = v; }
    public boolean isRequirePasswordChange() { return requirePasswordChange; }
    public void setRequirePasswordChange(boolean v) { this.requirePasswordChange = v; }
    public boolean isTutorialCompleted() { return tutorialCompleted; }
    public void setTutorialCompleted(boolean v) { this.tutorialCompleted = v; }
    public List<String> getFriends() {
        if (friends == null) {
            friends = Collections.synchronizedList(new ArrayList<>());
        }
        return friends;
    }
    public void setFriends(List<String> v) { this.friends = v != null ? Collections.synchronizedList(new ArrayList<>(v)) : Collections.synchronizedList(new ArrayList<>()); }
    public List<String> snapshotFriends() {
        List<String> list = getFriends();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }
    public void addFriend(String uid) {
        List<String> list = getFriends();
        synchronized (list) {
            if (!list.contains(uid)) list.add(uid);
        }
    }
    public void removeFriend(String uid) {
        List<String> list = getFriends();
        synchronized (list) {
            list.remove(uid);
        }
    }
    public String getAvatarPath() { return avatarPath; }
    public void setAvatarPath(String v) { this.avatarPath = v; }
    public String getBio() { return bio; }
    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getBirthday() { return birthday; }
    public void setBirthday(String v) { this.birthday = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
    public String getBubbleSkin() { return bubbleSkin == null ? "" : bubbleSkin; }
    public void setBubbleSkin(String v) { this.bubbleSkin = v; }
    public String getMessageFont() { return messageFont == null || messageFont.isBlank() ? "default" : messageFont; }
    public void setMessageFont(String v) { this.messageFont = v; }
    public List<String> getCustomTags() {
        if (customTags == null) customTags = Collections.synchronizedList(new ArrayList<>());
        return customTags;
    }
    public void setCustomTags(List<String> v) { this.customTags = v != null ? Collections.synchronizedList(new ArrayList<>(v)) : Collections.synchronizedList(new ArrayList<>()); }
    public void addCustomTag(String tag) {
        List<String> list = getCustomTags();
        synchronized (list) {
            if (!list.contains(tag)) list.add(tag);
        }
    }
    public void removeCustomTag(String tag) {
        List<String> list = getCustomTags();
        synchronized (list) {
            list.remove(tag);
        }
    }
    public long getCloudQuotaBytes() { return cloudQuotaBytes > 0 ? cloudQuotaBytes : (cloudQuotaBytes == -1 ? Long.MAX_VALUE : DEFAULT_CLOUD_QUOTA_BYTES); }
    public void setCloudQuotaBytes(long v) { this.cloudQuotaBytes = v; }
    public String getCloudDeletePolicy() {
        return cloudDeletePolicy == null || cloudDeletePolicy.trim().isEmpty() ? "recycle" : cloudDeletePolicy;
    }
    public void setCloudDeletePolicy(String v) { this.cloudDeletePolicy = v; }
    public boolean isCloudInitialized() { return cloudInitialized; }
    public void setCloudInitialized(boolean v) { this.cloudInitialized = v; }
    public String getAiUsageDay() { return aiUsageDay; }
    public void setAiUsageDay(String v) { this.aiUsageDay = v; }
    public double getAiUsedTokensToday() { return aiUsedTokensToday; }
    public void setAiUsedTokensToday(double v) { this.aiUsedTokensToday = v; }
    public boolean isBanned() { return banned; }
    public void setBanned(boolean v) { this.banned = v; }
    public long getBanExpiresAt() { return banExpiresAt; }
    public void setBanExpiresAt(long v) { this.banExpiresAt = v; }
    public String getBanReason() { return banReason; }
    public void setBanReason(String v) { this.banReason = v; }

    public boolean isCurrentlyBanned() {
        if (!banned) return false;
        if (banExpiresAt <= 0) return true; // 永久封禁
        return System.currentTimeMillis() < banExpiresAt;
    }

    public long getBanRemainingMillis() {
        if (!banned) return 0;
        if (banExpiresAt <= 0) return Long.MAX_VALUE;
        return Math.max(0, banExpiresAt - System.currentTimeMillis());
    }

    public int getLevel() { return level; }
    public void setLevel(int v) { this.level = v; }
    public long getExp() { return exp; }
    public void setExp(long v) { this.exp = v; }
    public String getLastCheckIn() { return lastCheckIn; }
    public void setLastCheckIn(String v) { this.lastCheckIn = v; }
    public int getCheckInStreak() { return Math.max(0, checkInStreak); }
    public void setCheckInStreak(int v) { this.checkInStreak = Math.max(0, v); }
    public int getDailyGameUploads() { return dailyGameUploads; }
    public void setDailyGameUploads(int v) { this.dailyGameUploads = v; }
    public String getGameUploadDay() { return gameUploadDay; }
    public void setGameUploadDay(String v) { this.gameUploadDay = v; }
    public int getDailyGamePlayExpCount() { return dailyGamePlayExpCount; }
    public void setDailyGamePlayExpCount(int v) { this.dailyGamePlayExpCount = v; }
    public String getGamePlayExpDay() { return gamePlayExpDay; }
    public void setGamePlayExpDay(String v) { this.gamePlayExpDay = v; }

    public Map<String, Long> getFeatureBans() {
        if (featureBans == null) {
            featureBans = new java.util.concurrent.ConcurrentHashMap<>();
        }
        return featureBans;
    }
    public void setFeatureBans(Map<String, Long> v) { this.featureBans = v != null ? new java.util.concurrent.ConcurrentHashMap<>(v) : new java.util.concurrent.ConcurrentHashMap<>(); }
    public Map<String, String> getFeatureBanReasons() {
        if (featureBanReasons == null) {
            featureBanReasons = new java.util.concurrent.ConcurrentHashMap<>();
        }
        return featureBanReasons;
    }
    public void setFeatureBanReasons(Map<String, String> v) { this.featureBanReasons = v != null ? new java.util.concurrent.ConcurrentHashMap<>(v) : new java.util.concurrent.ConcurrentHashMap<>(); }

    public boolean isFeatureBanned(String feature) {
        if (featureBans == null) return false;
        Long expiresAt = featureBans.get(feature);
        if (expiresAt == null) return false;
        if (expiresAt == 0) return true;
        return System.currentTimeMillis() < expiresAt;
    }

    public long getFeatureBanRemainingMillis(String feature) {
        if (featureBans == null) return 0;
        Long expiresAt = featureBans.get(feature);
        if (expiresAt == null) return 0;
        if (expiresAt == 0) return Long.MAX_VALUE;
        return Math.max(0, expiresAt - System.currentTimeMillis());
    }


    public String getFeatureBanReason(String feature) {
        if (featureBanReasons == null) return null;
        return featureBanReasons.get(feature);
    }

    public synchronized void addExp(long amount) {
        this.exp += amount;
        int newLevel = getLevelByExp(this.exp);
        if (newLevel > this.level && this.level < 7) {
            this.level = newLevel;
        }
    }
}
