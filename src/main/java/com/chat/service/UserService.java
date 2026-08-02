package com.chat.service;

import com.chat.model.User;
import com.chat.util.JsonUtil;
import com.chat.util.PasswordUtil;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class UserService {
    // 相对路径：相对于 jar 运行目录（即 chatserver/ 与 jar 同级）
    private static final String DATA_DIR  = "chatserver/users";
    private static final String DATA_FILE = "chatserver/users/users.json";
    private static final String ACCOUNTS_FILE = "chatserver/users/accounts.json";
    private static final String PROFILES_FILE = "chatserver/users/profiles.json";
    private static final String SETTINGS_FILE = "chatserver/users/settings.json";

    private final Map<String, User> usersByName = new ConcurrentHashMap<>();
    private final Map<String, User> usersById   = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock    = new ReentrantReadWriteLock();
    private volatile boolean dirty = false;

    private static final java.util.regex.Pattern USERNAME_PATTERN =
            java.util.regex.Pattern.compile("^[\\p{IsHan}A-Za-z0-9][\\p{IsHan}A-Za-z0-9_]{1,18}[\\p{IsHan}A-Za-z0-9]$");
    private static final Set<String> MESSAGE_FONTS = Set.of(
            "default", "songti", "heiti", "kaiti", "fangsong", "dengxian", "mono");

    // Keep the singleton after every non-constant static dependency used by load().
    // Otherwise real profiles trigger class-initialization reads of null sets/patterns.
    private static final UserService INSTANCE = new UserService();
    public static UserService getInstance() { return INSTANCE; }

    private UserService() {
        try { Files.createDirectories(Paths.get(DATA_DIR)); } catch (Exception ignored) {}
        load();
        
        Thread saveThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    if (dirty) {
                        saveSync();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        saveThread.setDaemon(true);
        saveThread.start();
        
        Runtime.getRuntime().addShutdownHook(new Thread(this::saveSync));
    }

    private List<User> readUsersFromFiles() {
        File accountsFile = new File(ACCOUNTS_FILE);
        File profilesFile = new File(PROFILES_FILE);
        File settingsFile = new File(SETTINGS_FILE);
        // Try new split files first
        if (accountsFile.exists() && profilesFile.exists() && settingsFile.exists()) {
            try {
                Type listType = new TypeToken<List<Map<String, Object>>>(){}.getType();
                List<Map<String, Object>> accounts = JsonUtil.fromJson(new String(Files.readAllBytes(accountsFile.toPath()), StandardCharsets.UTF_8), listType);
                List<Map<String, Object>> profiles = JsonUtil.fromJson(new String(Files.readAllBytes(profilesFile.toPath()), StandardCharsets.UTF_8), listType);
                List<Map<String, Object>> settings = JsonUtil.fromJson(new String(Files.readAllBytes(settingsFile.toPath()), StandardCharsets.UTF_8), listType);
                if (accounts != null) {
                    Map<String, User> legacyUsers = readLegacyUsersByIdOrName();
                    Map<String, Map<String, Object>> profileMap = new HashMap<>();
                    if (profiles != null) for (Map<String, Object> p : profiles) profileMap.put(asString(p.get("userId")), p);
                    Map<String, Map<String, Object>> settingsMap = new HashMap<>();
                    if (settings != null) for (Map<String, Object> s : settings) settingsMap.put(asString(s.get("userId")), s);
                    List<User> list = new ArrayList<>();
                    for (Map<String, Object> a : accounts) {
                        if (a.get("username") == null) continue;
                        User u = new User();
                        u.setUsername(asString(a.get("username")));
                        u.setPassword(asString(a.get("password")));
                        u.setUserId(asString(a.get("userId")));
                        u.setCreatedAt(toLong(a.get("createdAt"), 0));
                        u.setPasswordPolicyVersion((int) toLong(a.get("passwordPolicyVersion"), 0));
                        u.setBanned("true".equals(String.valueOf(a.get("banned"))) || Boolean.TRUE.equals(a.get("banned")));
                        u.setBanExpiresAt(toLong(a.get("banExpiresAt"), 0));
                        String uid = u.getUserId();
                        User legacyUser = legacyUsers.get(uid);
                        if (legacyUser == null) {
                            legacyUser = legacyUsers.get("username:" + u.getUsername().toLowerCase());
                        }
                        if (legacyUser != null) {
                            copyPersonalData(u, legacyUser);
                        }
                        Map<String, Object> p = profileMap.get(uid);
                        if (p != null) {
                            u.setNickname(asString(p.get("nickname")));
                            u.setAvatarPath(asString(p.get("avatarPath")));
                            u.setBio(asString(p.get("bio")));
                            u.setBirthday(asString(p.get("birthday")));
                            u.setGender(asString(p.get("gender")));
                            u.setBubbleSkin(asString(p.get("bubbleSkin")));
                            u.setMessageFont(asString(p.get("messageFont")));
                            u.setLanguage(asString(p.get("language")));
                            Object tagsObj = p.get("customTags");
                            if (tagsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<String> tags = (List<String>) tagsObj;
                                u.setCustomTags(tags);
                            }
                            Object friendsObj = p.get("friends");
                            if (friendsObj instanceof List) {
                                @SuppressWarnings("unchecked")
                                List<String> friendsList = (List<String>) friendsObj;
                                u.setFriends(friendsList);
                            }
                        }
                        Map<String, Object> s = settingsMap.get(uid);
                        if (s != null) {
                            u.setCloudQuotaBytes(toLong(s.get("cloudQuotaBytes"), User.DEFAULT_CLOUD_QUOTA_BYTES));
                            u.setCloudDeletePolicy(asString(s.get("cloudDeletePolicy")));
                            u.setCloudInitialized("true".equals(String.valueOf(s.get("cloudInitialized"))) || Boolean.TRUE.equals(s.get("cloudInitialized")));
                            u.setLevel((int) toLong(s.get("level"), 1));
                            u.setExp(toLong(s.get("exp"), 0));
                            u.setLastCheckIn(asString(s.get("lastCheckIn")));
                            u.setCheckInStreak((int) toLong(s.get("checkInStreak"), 0));
                            u.setDailyGameUploads((int) toLong(s.get("dailyGameUploads"), 0));
                            u.setGameUploadDay(asString(s.get("gameUploadDay")));
                            u.setDailyGamePlayExpCount((int) toLong(s.get("dailyGamePlayExpCount"), 0));
                            u.setGamePlayExpDay(asString(s.get("gamePlayExpDay")));
                            u.setAiUsageDay(asString(s.get("aiUsageDay")));
                            u.setAiUsedTokensToday(toDouble(s.get("aiUsedTokensToday"), 0));
                            if (s.containsKey("tutorialCompleted")) {
                                u.setTutorialCompleted("true".equals(String.valueOf(s.get("tutorialCompleted"))) || Boolean.TRUE.equals(s.get("tutorialCompleted")));
                            } else {
                                // 老账号在教程功能上线前没有此字段，不应在每次登录时被当作新用户。
                                u.setTutorialCompleted(true);
                            }
                            @SuppressWarnings("unchecked")
                            Map<String, Object> rawBans = (Map<String, Object>) s.get("featureBans");
                            if (rawBans != null) {
                                Map<String, Long> bans = new HashMap<>();
                                Map<String, String> reasons = new HashMap<>();
                                for (Map.Entry<String, Object> e : rawBans.entrySet()) {
                                    bans.put(e.getKey(), toLong(e.getValue(), 0));
                                }
                                @SuppressWarnings("unchecked")
                                Map<String, Object> rawReasons = (Map<String, Object>) s.get("featureBanReasons");
                                if (rawReasons != null) {
                                    for (Map.Entry<String, Object> e : rawReasons.entrySet()) {
                                        reasons.put(e.getKey(), asString(e.getValue()));
                                    }
                                }
                                u.setFeatureBans(bans);
                                u.setFeatureBanReasons(reasons);
                            }
                        }
                        if (u.getFriends() == null) u.setFriends(new ArrayList<>());
                        if (u.getCloudQuotaBytes() <= 0 && u.getCloudQuotaBytes() != -1) u.setCloudQuotaBytes(User.DEFAULT_CLOUD_QUOTA_BYTES);
                        if (u.getCloudDeletePolicy() == null || u.getCloudDeletePolicy().trim().isEmpty()) u.setCloudDeletePolicy("recycle");
                        list.add(u);
                    }
                    return list;
                }
            } catch (Exception e) {
                System.err.println("[UserService] 拆分文件加载失败，尝试旧版 users.json: " + e.getMessage());
            }
        }
        // Fallback: read legacy users.json
        File legacy = new File(DATA_FILE);
        if (!legacy.exists()) return new ArrayList<>();
        try {
            String json = new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<User>>(){}.getType();
            List<User> list = JsonUtil.fromJson(json, type);
            if (list != null) return list;
        } catch (Exception e) {
            System.err.println("[UserService] 旧版 users.json 读取失败: " + e.getMessage());
            try {
                Path backup = Paths.get(DATA_FILE + ".bak");
                Files.copy(Paths.get(DATA_FILE), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[UserService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
        return new ArrayList<>();
    }

    private Map<String, User> readLegacyUsersByIdOrName() {
        Map<String, User> result = new HashMap<>();
        File legacy = new File(DATA_FILE);
        if (!legacy.exists()) {
            return result;
        }
        try {
            String json = new String(Files.readAllBytes(legacy.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<User>>(){}.getType();
            List<User> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                for (User user : list) {
                    if (user == null) continue;
                    if (user.getUserId() != null && !user.getUserId().trim().isEmpty()) {
                        result.put(user.getUserId(), user);
                    }
                    if (user.getUsername() != null && !user.getUsername().trim().isEmpty()) {
                        result.put("username:" + user.getUsername().toLowerCase(), user);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    private void copyPersonalData(User target, User source) {
        target.setNickname(source.getNickname());
        target.setAvatarPath(source.getAvatarPath());
        target.setBio(source.getBio());
        target.setBirthday(source.getBirthday());
        target.setGender(source.getGender());
        target.setBubbleSkin(source.getBubbleSkin());
        target.setMessageFont(source.getMessageFont());
        target.setLanguage(source.getLanguage());
        synchronized (source.getCustomTags()) {
            target.setCustomTags(new ArrayList<>(source.getCustomTags()));
        }
        synchronized (source.getFriends()) {
            target.setFriends(new ArrayList<>(source.getFriends()));
        }
        target.setCloudQuotaBytes(source.getCloudQuotaBytes());
        target.setCloudDeletePolicy(source.getCloudDeletePolicy());
        target.setCloudInitialized(source.isCloudInitialized());
        target.setLevel(source.getLevel());
        target.setExp(source.getExp());
        target.setLastCheckIn(source.getLastCheckIn());
        target.setCheckInStreak(source.getCheckInStreak());
        target.setDailyGameUploads(source.getDailyGameUploads());
        target.setGameUploadDay(source.getGameUploadDay());
        target.setDailyGamePlayExpCount(source.getDailyGamePlayExpCount());
        target.setGamePlayExpDay(source.getGamePlayExpDay());
        target.setAiUsageDay(source.getAiUsageDay());
        target.setAiUsedTokensToday(source.getAiUsedTokensToday());
        target.setTutorialCompleted(source.isTutorialCompleted());
        target.setBanReason(source.getBanReason());
        target.setFeatureBans(new HashMap<>(source.getFeatureBans()));
        target.setFeatureBanReasons(new HashMap<>(source.getFeatureBanReasons()));
    }

    private void load() {
        List<User> list = readUsersFromFiles();
        if (list == null || list.isEmpty()) {
            // Backward compat: also check old users.json
            File f = new File(DATA_FILE);
            if (f.exists()) {
                try {
                    String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                    Type type = new TypeToken<List<User>>(){}.getType();
                    list = JsonUtil.fromJson(json, type);
                } catch (Exception e) {
                    System.err.println("[UserService] 加载失败: " + e.getMessage());
                    e.printStackTrace();
                    return;
                }
            }
        }
        if (list == null) list = new ArrayList<>();
        try {
        boolean upgraded = false;
                long migrationBase = System.currentTimeMillis() - Math.max(1, list.size()) * 1000L;
                int migrationIndex = 0;
                for (User u : list) {
                    if (u.getFriends() == null) u.setFriends(new ArrayList<>());
                    if (u.getCreatedAt() <= 0) {
                        u.setCreatedAt(migrationBase + migrationIndex * 1000L);
                        u.setTutorialCompleted(true);
                        upgraded = true;
                    }
                    migrationIndex++;
                    if (u.getCloudQuotaBytes() <= 0 && u.getCloudQuotaBytes() != -1) u.setCloudQuotaBytes(User.DEFAULT_CLOUD_QUOTA_BYTES);
                    if (u.getCloudDeletePolicy() == null || u.getCloudDeletePolicy().trim().isEmpty()) {
                        u.setCloudDeletePolicy("recycle");
                    }
                    if (u.getPassword() != null && !u.getPassword().isEmpty()
                            && !PasswordUtil.looksHashed(u.getPassword())) {
                        u.setPassword(PasswordUtil.hashPassword(u.getPassword()));
                        upgraded = true;
                    }
                    if (u.getLevel() <= 0) { u.setLevel(1); upgraded = true; }
                    if (u.getExp() < 0) { u.setExp(0); upgraded = true; }
                    // 自动根据经验校正等级（Lv6⚡保持不变）
                    if (u.getLevel() < 7) {
                        int correctLevel = User.getLevelByExp(u.getExp());
                        if (correctLevel > u.getLevel()) { u.setLevel(correctLevel); upgraded = true; }
                    }
                    // 封禁过期自动解封
                    if (u.isBanned() && u.getBanExpiresAt() > 0
                            && u.getBanExpiresAt() < System.currentTimeMillis()) {
                        u.setBanned(false);
                        u.setBanExpiresAt(0);
                        upgraded = true;
                    }
                    // 功能封禁过期自动清理
                    if (u.getFeatureBans() != null && !u.getFeatureBans().isEmpty()) {
                        Map<String, Long> bans = u.getFeatureBans();
                        Map<String, String> reasons = u.getFeatureBanReasons();
                        boolean changed = false;
                        for (java.util.Iterator<Map.Entry<String, Long>> it = bans.entrySet().iterator(); it.hasNext();) {
                            Map.Entry<String, Long> e = it.next();
                            if (e.getValue() > 0 && e.getValue() < System.currentTimeMillis()) {
                                it.remove();
                                if (reasons != null) reasons.remove(e.getKey());
                                changed = true;
                            }
                        }
                        if (changed) upgraded = true;
                    }
                    // String 字段 null 安全
                    if (u.getLastCheckIn() == null) u.setLastCheckIn("");
                    if (u.getGameUploadDay() == null) u.setGameUploadDay("");
                    if (u.getGamePlayExpDay() == null) u.setGamePlayExpDay("");
                    if (u.getAiUsageDay() == null) u.setAiUsageDay("");
                    if (u.getBubbleSkin() == null) u.setBubbleSkin("");
                    if (!MESSAGE_FONTS.contains(u.getMessageFont())) { u.setMessageFont("default"); upgraded = true; }
                    if (u.getLanguage() == null || u.getLanguage().isBlank()) u.setLanguage("zh-CN");

                    usersByName.put(u.getUsername().toLowerCase(), u);
                    usersById.put(u.getUserId(), u);
                }
            User primaryByNewName = usersByName.get("陈梓仁".toLowerCase());
            User legacyPrimary = usersByName.get("chenziren");
            if (primaryByNewName == null && legacyPrimary != null) {
                usersByName.remove(legacyPrimary.getUsername().toLowerCase());
                legacyPrimary.setUsername("陈梓仁");
                if (legacyPrimary.getNickname() == null || legacyPrimary.getNickname().isBlank()
                        || "chenziren".equalsIgnoreCase(legacyPrimary.getNickname())) {
                    legacyPrimary.setNickname("陈梓仁");
                }
                usersByName.put(legacyPrimary.getUsername().toLowerCase(), legacyPrimary);
                upgraded = true;
            } else if (primaryByNewName != null && (primaryByNewName.getNickname() == null
                    || primaryByNewName.getNickname().isBlank()
                    || "chenziren".equalsIgnoreCase(primaryByNewName.getNickname()))) {
                primaryByNewName.setNickname("陈梓仁");
                upgraded = true;
            }
            if (upgraded) {
                save();
            }
            // 服主默认 Lv6⚡
            ensurePrimarySuperAdminLevel();
            System.out.println("[UserService] 已加载 " + usersByName.size() + " 个用户" + (upgraded ? " (含旧版数据迁移)" : ""));
        } catch (Exception e) {
            System.err.println("[UserService] 加载失败: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String asString(Object o) { return o == null ? "" : String.valueOf(o); }
    private static long toLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return def;
        try {
            return Long.parseLong(s);
        } catch (Exception ignored) {
            try {
                double value = Double.parseDouble(s);
                return Double.isFinite(value) ? (long) value : def;
            } catch (Exception e) {
                return def;
            }
        }
    }
    private static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number) {
            double value = ((Number) o).doubleValue();
            return Double.isFinite(value) ? value : def;
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) return def;
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) ? value : def;
        } catch (Exception e) { return def; }
    }

    public void save() {
        dirty = true;
    }

    private void saveSync() {
        if (!dirty) return;
        lock.writeLock().lock();
        try {
            List<User> users = new ArrayList<>(usersByName.values());
            List<User> userSnapshots = new ArrayList<>();
            for (User u : users) {
                User copy = new User();
                copy.setUsername(u.getUsername());
                copy.setPassword(u.getPassword());
                copy.setUserId(u.getUserId());
                copy.setCreatedAt(u.getCreatedAt());
                copy.setPasswordPolicyVersion(u.getPasswordPolicyVersion());
                copy.setRequirePasswordChange(u.isRequirePasswordChange());
                copy.setTutorialCompleted(u.isTutorialCompleted());
                copy.setNickname(u.getNickname());
                copy.setAvatarPath(u.getAvatarPath());
                copy.setBio(u.getBio());
                copy.setBirthday(u.getBirthday());
                copy.setGender(u.getGender());
                copy.setBubbleSkin(u.getBubbleSkin());
                copy.setMessageFont(u.getMessageFont());
                copy.setLanguage(u.getLanguage());
                synchronized (u.getFriends()) {
                    copy.setFriends(new ArrayList<>(u.getFriends()));
                }
                synchronized (u.getCustomTags()) {
                    copy.setCustomTags(new ArrayList<>(u.getCustomTags()));
                }
                copy.setCloudQuotaBytes(u.getCloudQuotaBytes());
                copy.setCloudDeletePolicy(u.getCloudDeletePolicy());
                copy.setCloudInitialized(u.isCloudInitialized());
                copy.setAiUsageDay(u.getAiUsageDay());
                copy.setAiUsedTokensToday(u.getAiUsedTokensToday());
                copy.setBanned(u.isBanned());
                copy.setBanExpiresAt(u.getBanExpiresAt());
                copy.setBanReason(u.getBanReason());
                copy.setLevel(u.getLevel());
                copy.setExp(u.getExp());
                copy.setLastCheckIn(u.getLastCheckIn());
                copy.setCheckInStreak(u.getCheckInStreak());
                copy.setDailyGameUploads(u.getDailyGameUploads());
                copy.setGameUploadDay(u.getGameUploadDay());
                copy.setDailyGamePlayExpCount(u.getDailyGamePlayExpCount());
                copy.setGamePlayExpDay(u.getGamePlayExpDay());
                copy.setFeatureBans(u.getFeatureBans());
                copy.setFeatureBanReasons(u.getFeatureBanReasons());
                userSnapshots.add(copy);
            }
            // Legacy: keep users.json for backward compat
            String fullJson = JsonUtil.toJson(userSnapshots);
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(DATA_FILE), fullJson.getBytes(StandardCharsets.UTF_8));
            // Split files
            List<Map<String, Object>> accounts = new ArrayList<>();
            List<Map<String, Object>> profiles = new ArrayList<>();
            List<Map<String, Object>> settings = new ArrayList<>();
            for (User u : users) {
                Map<String, Object> a = new java.util.LinkedHashMap<>();
                a.put("username", u.getUsername());
                a.put("password", u.getPassword());
                a.put("userId", u.getUserId());
                a.put("createdAt", u.getCreatedAt());
                a.put("passwordPolicyVersion", u.getPasswordPolicyVersion());
                a.put("banned", u.isBanned());
                a.put("banExpiresAt", u.getBanExpiresAt());
                accounts.add(a);
                Map<String, Object> p = new java.util.LinkedHashMap<>();
                p.put("userId", u.getUserId());
                p.put("nickname", u.getNickname());
                p.put("avatarPath", u.getAvatarPath());
                p.put("bio", u.getBio());
                p.put("birthday", u.getBirthday());
                p.put("gender", u.getGender());
                p.put("bubbleSkin", u.getBubbleSkin());
                p.put("messageFont", u.getMessageFont());
                p.put("language", u.getLanguage());
                synchronized (u.getFriends()) {
                    p.put("friends", new ArrayList<>(u.getFriends()));
                }
                synchronized (u.getCustomTags()) {
                    p.put("customTags", new ArrayList<>(u.getCustomTags()));
                }
                profiles.add(p);
                Map<String, Object> s = new java.util.LinkedHashMap<>();
                s.put("userId", u.getUserId());
                s.put("cloudQuotaBytes", u.getCloudQuotaBytes() == Long.MAX_VALUE ? -1 : u.getCloudQuotaBytes());
                s.put("cloudDeletePolicy", u.getCloudDeletePolicy());
                s.put("cloudInitialized", u.isCloudInitialized());
                s.put("level", u.getLevel());
                s.put("exp", u.getExp());
                s.put("lastCheckIn", u.getLastCheckIn());
                s.put("checkInStreak", u.getCheckInStreak());
                s.put("dailyGameUploads", u.getDailyGameUploads());
                s.put("gameUploadDay", u.getGameUploadDay());
                s.put("dailyGamePlayExpCount", u.getDailyGamePlayExpCount());
                s.put("gamePlayExpDay", u.getGamePlayExpDay());
                s.put("aiUsageDay", u.getAiUsageDay());
                s.put("aiUsedTokensToday", u.getAiUsedTokensToday());
                s.put("tutorialCompleted", u.isTutorialCompleted());
                s.put("featureBans", u.getFeatureBans());
                s.put("featureBanReasons", u.getFeatureBanReasons());
                settings.add(s);
            }
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(ACCOUNTS_FILE), JsonUtil.toJson(accounts).getBytes(StandardCharsets.UTF_8));
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(PROFILES_FILE), JsonUtil.toJson(profiles).getBytes(StandardCharsets.UTF_8));
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(SETTINGS_FILE), JsonUtil.toJson(settings).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[UserService] 保存失败: " + e.getMessage());
        } finally {
            dirty = false;
            lock.writeLock().unlock();
        }
    }

    public boolean existsByUsername(String username) {
        if (username == null) return false;
        return usersByName.containsKey(username.toLowerCase());
    }

    public boolean existsByUserId(String userId) {
        if (userId == null) return false;
        return usersById.containsKey(userId);
    }

    public long getUserCount() {
        return usersByName.size();
    }

    public boolean register(String username, String nickname, String userId, String password) {
        if (validateUsername(username) != null || validateNickname(nickname) != null
                || validatePassword(password) != null) return false;
        lock.writeLock().lock();
        try {
            if (existsByUsername(username) || existsByUserId(userId)) return false;
            User user = new User(username, nickname, userId, PasswordUtil.hashPassword(password));
            user.setPasswordPolicyVersion(User.CURRENT_PASSWORD_POLICY_VERSION);
            if (isPrimaryAccountName(username)) {
                user.setLevel(6);
            }
            user.setTutorialCompleted(false);
            usersByName.put(username.toLowerCase(), user);
            usersById.put(userId, user);
            save();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static boolean isPrimaryAccountName(String username) {
        return "陈梓仁".equalsIgnoreCase(username) || "chenziren".equalsIgnoreCase(username);
    }

    public User login(String username, String password) {
        if (username == null || password == null) return null;
        User user = usersByName.get(username.toLowerCase());
        if (user != null && PasswordUtil.verifyPassword(password, user.getPassword())) {
            boolean changed = false;
            boolean passwordMeetsPolicy = validatePassword(password) == null;
            user.setRequirePasswordChange(!passwordMeetsPolicy);
            if (!PasswordUtil.looksHashed(user.getPassword())) {
                user.setPassword(PasswordUtil.hashPassword(password));
                changed = true;
            }
            if (passwordMeetsPolicy
                    && user.getPasswordPolicyVersion() < User.CURRENT_PASSWORD_POLICY_VERSION) {
                user.setPasswordPolicyVersion(User.CURRENT_PASSWORD_POLICY_VERSION);
                changed = true;
            }
            if (changed) save();
            return user;
        }
        return null;
    }

    public User getByUsername(String username) {
        if (username == null) return null;
        return usersByName.get(username.toLowerCase());
    }

    public User getByUserId(String userId) {
        return usersById.get(userId);
    }

    public List<User> getAllUsers() {
        return new ArrayList<>(usersByName.values());
    }

    public boolean updateNickname(String userId, String newNickname) {
        User user = usersById.get(userId);
        if (user == null || validateNickname(newNickname) != null) return false;
        user.setNickname(newNickname.trim());
        save();
        return true;
    }

    public String updateUsername(String userId, String newUsername) {
        String validation = validateUsername(newUsername);
        if (validation != null) return validation;
        String trimmed = newUsername.trim().toLowerCase();
        lock.writeLock().lock();
        try {
            User user = usersById.get(userId);
            if (user == null) return "用户不存在";
            if (user.getUsername().equalsIgnoreCase(trimmed)) return "ok";
            if (existsByUsername(trimmed)) return "该用户名已被占用";
            usersByName.remove(user.getUsername().toLowerCase());
            user.setUsername(trimmed);
            usersByName.put(trimmed, user);
            save();
            return "ok";
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String updatePassword(String userId, String oldPassword, String newPassword) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        if (!PasswordUtil.verifyPassword(oldPassword, user.getPassword())) return "原密码错误";
        String policyError = validatePassword(newPassword);
        if (policyError != null) return policyError;
        user.setPassword(PasswordUtil.hashPassword(newPassword));
        user.setPasswordPolicyVersion(User.CURRENT_PASSWORD_POLICY_VERSION);
        user.setRequirePasswordChange(false);
        save();
        return "ok";
    }

    public String adminSetPassword(String userId, String newPassword) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        String policyError = validatePassword(newPassword);
        if (policyError != null) return policyError;
        user.setPassword(PasswordUtil.hashPassword(newPassword.trim()));
        user.setPasswordPolicyVersion(User.CURRENT_PASSWORD_POLICY_VERSION);
        user.setRequirePasswordChange(false);
        save();
        return "ok";
    }

    public String completeTutorial(String userId) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        user.setTutorialCompleted(true);
        save();
        return "ok";
    }

    public String validatePassword(String password) {
        if (password == null) return "密码不能为空";
        int len = password.length();
        if (len < 6 || len > 64) return "密码长度必须为 6~64 位";
        return null;
    }

    public static String validateUsername(String username) {
        if (username == null || username.trim().isEmpty()) return "用户名不能为空";
        String value = username.trim();
        int length = value.codePointCount(0, value.length());
        if (length < 3 || length > 20) return "用户名长度必须为 3~20 个字符";
        if (!USERNAME_PATTERN.matcher(value).matches()) {
            return "用户名仅允许中文、英文字母、数字和下划线，且不能以下划线开头或结尾";
        }
        return null;
    }

    public static String validateNickname(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) return "昵称不能为空";
        String value = nickname.trim();
        int length = value.codePointCount(0, value.length());
        if (length < 1 || length > 20) return "昵称长度必须为 1~20 个字符";
        String forbidden = "<>&\"'/\\`";
        for (int i = 0; i < value.length();) {
            int cp = value.codePointAt(i);
            if (Character.isISOControl(cp) || forbidden.indexOf(cp) >= 0) {
                return "昵称包含不允许的字符";
            }
            i += Character.charCount(cp);
        }
        return null;
    }

    public String updateMessageFont(String userId, String font) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        String normalized = font == null ? "default" : font.trim().toLowerCase(Locale.ROOT);
        if (!MESSAGE_FONTS.contains(normalized)) return "不支持的消息字体";
        user.setMessageFont(normalized);
        save();
        return "ok";
    }

    public boolean verifyPassword(String userId, String password) {
        User user = usersById.get(userId);
        return user != null && PasswordUtil.verifyPassword(password, user.getPassword());
    }

    public String adminSetQuota(String userId, long quotaBytes) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        if (quotaBytes <= 0 && quotaBytes != -1) return "容量必须大于0，-1表示不限";
        user.setCloudQuotaBytes(quotaBytes);
        save();
        return "ok";
    }

    public String adminSetAiTokens(String userId, double usedTokens) {
        User user = usersById.get(userId);
        if (user == null) return "\u7528\u6237\u4E0D\u5B58\u5728";
        if (!Double.isFinite(usedTokens)) return "\u70B9\u6570\u683C\u5F0F\u9519\u8BEF";
        if (usedTokens != -1 && usedTokens < 0) return "\u70B9\u6570\u4E0D\u80FD\u4E3A\u8D1F\u6570";
        user.setAiUsedTokensToday(usedTokens == -1 ? 0 : Math.max(0, usedTokens));
        save();
        return "ok";
    }

    public String banUser(String userId, long durationSeconds) {
        return banUser(userId, durationSeconds, null);
    }

    public String banUser(String userId, long durationSeconds, String reason) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        if (durationSeconds <= 0) {
            user.setBanned(true);
            user.setBanExpiresAt(0);
        } else {
            user.setBanned(true);
            user.setBanExpiresAt(System.currentTimeMillis() + durationSeconds * 1000L);
        }
        user.setBanReason(reason != null && !reason.trim().isEmpty() ? reason.trim() : null);
        save();
        return "ok";
    }

    public String unbanUser(String userId) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        user.setBanned(false);
        user.setBanExpiresAt(0);
        user.setBanReason(null);
        save();
        return "ok";
    }

    public String setFeatureBan(String userId, String feature, long durationSeconds, String reason) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        if (feature == null || feature.trim().isEmpty()) return "功能名称不能为空";
        String f = feature.trim();
        if (user.getFeatureBans() == null) user.setFeatureBans(new HashMap<>());
        if (user.getFeatureBanReasons() == null) user.setFeatureBanReasons(new HashMap<>());
        if (durationSeconds < 0) {
            user.getFeatureBans().remove(f);
            user.getFeatureBanReasons().remove(f);
        } else if (durationSeconds == 0) {
            user.getFeatureBans().put(f, 0L);
            user.getFeatureBanReasons().put(f, reason != null && !reason.trim().isEmpty() ? reason.trim() : null);
        } else {
            user.getFeatureBans().put(f, System.currentTimeMillis() + durationSeconds * 1000L);
            user.getFeatureBanReasons().put(f, reason != null && !reason.trim().isEmpty() ? reason.trim() : null);
        }
        save();
        return "ok";
    }

    public Map<String, Object> getFeatureBanInfo(String userId, String feature) {
        User user = usersById.get(userId);
        if (user == null) return null;
        if (!user.isFeatureBanned(feature)) return null;
        Map<String, Object> info = new HashMap<>();
        info.put("banned", true);
        info.put("feature", feature);
        info.put("expiresAt", user.getFeatureBans().get(feature));
        info.put("reason", user.getFeatureBanReason(feature));
        info.put("remainingMillis", user.getFeatureBanRemainingMillis(feature));
        return info;
    }

    public Map<String, Object> getAllFeatureBanInfo(String userId) {
        User user = usersById.get(userId);
        if (user == null) return new HashMap<>();
        Map<String, Object> result = new HashMap<>();
        Map<String, Long> bans = user.getFeatureBans();
        for (String feature : bans.keySet()) {
            if (user.isFeatureBanned(feature)) {
                Map<String, Object> info = new HashMap<>();
                info.put("banned", true);
                info.put("expiresAt", bans.get(feature));
                info.put("reason", user.getFeatureBanReason(feature));
                info.put("remainingMillis", user.getFeatureBanRemainingMillis(feature));
                result.put(feature, info);
            }
        }
        return result;
    }

    public String addExp(String userId, long amount) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        user.addExp(amount);
        save();
        return "ok";
    }

    public String adminSetLevel(String userId, int level) {
        User user = usersById.get(userId);
        if (user == null) return "用户不存在";
        if (level < 1 || level > 7) return "等级范围1~7";
        user.setLevel(level);
        save();
        return "ok";
    }

    public void ensurePrimarySuperAdminLevel() {
        String primaryId = SuperAdminService.getInstance().getPrimarySuperAdminId();
        if (primaryId == null) return;
        User user = usersById.get(primaryId);
        if (user != null && user.getLevel() < 7) {
            user.setLevel(7);
            save();
            System.out.println("[UserService] 服主 @" + primaryId + " 已设为 Lv6⚡");
        }
    }
    public String deleteUser(String userId) {
        if (userId == null || userId.trim().isEmpty()) return "用户ID不能为空";
        
        if (userId.equals(SuperAdminService.getInstance().getPrimarySuperAdminId())) {
            return "服主账号不可删除";
        }

        lock.writeLock().lock();
        try {
            User user = usersById.remove(userId);
            if (user == null) {
                return "用户不存在";
            }
            usersByName.remove(user.getUsername().toLowerCase());
            
            for (User u : usersById.values()) {
                if (u.getFriends() != null) {
                    synchronized (u.getFriends()) {
                        u.getFriends().remove(userId);
                    }
                }
            }
            
            save();

            SuperAdminService.getInstance().removeSuperAdmin(userId);
            SuperAdminService.getInstance().removeCoOwner(userId);

            com.chat.util.SessionManager.getInstance().removeSessionsForUser(userId);
            FriendService.getInstance().cleanupUserRequests(userId);
            try {
                CloudService.getInstance().deleteUserCloud(userId);
            } catch (Exception e) {
                System.err.println("[UserService] Failed to delete user cloud file: " + e.getMessage());
            }
            
            return "ok";
        } finally {
            lock.writeLock().unlock();
        }
    }
}
