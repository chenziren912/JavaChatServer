package com.chat.service;

import com.chat.model.GameEntry;
import com.chat.model.GameVersion;
import com.chat.model.StoredFileMetadata;
import com.chat.model.User;
import com.chat.util.JsonUtil;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class GameService {
    public static final String DEFAULT_CATEGORY = GameEntry.CATEGORY_GAME;
    public static final List<String> SUPPORTED_CATEGORIES = GameEntry.SUPPORTED_CATEGORIES;

    private static final String GAMES_DIR = "chatserver/games";
    private static final String INDEX_FILE = GAMES_DIR + "/games.json";
    private static final String ASSETS_DIR = GAMES_DIR + "/assets";

    private static final GameService INSTANCE = new GameService();
    private final Map<String, GameEntry> gameMap = new ConcurrentHashMap<>();

    private GameService() {
        initDirectories();
        loadGames();
    }

    public static GameService getInstance() {
        return INSTANCE;
    }

    public static void warmUp() {
        getInstance();
    }

    public static boolean isSupportedCategory(String category) {
        if (category == null) return false;
        String trimmed = category.trim();
        return SUPPORTED_CATEGORIES.contains(trimmed);
    }

    public static String normalizeCategory(String category) {
        if (category == null) return DEFAULT_CATEGORY;
        String trimmed = category.trim();
        if (trimmed.isEmpty() || !SUPPORTED_CATEGORIES.contains(trimmed)) {
            return DEFAULT_CATEGORY;
        }
        return trimmed;
    }

    private void initDirectories() {
        new File(GAMES_DIR).mkdirs();
        new File(ASSETS_DIR).mkdirs();
    }

    private synchronized void loadGames() {
        File file = new File(INDEX_FILE);
        if (!file.exists()) return;
        try {
            String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
            GameEntry[] entries = JsonUtil.fromJson(json, GameEntry[].class);
            if (entries != null) {
                gameMap.clear();
                for (GameEntry entry : entries) {
                    if (entry != null && entry.getId() != null) {
                        entry.setCategory(normalizeCategory(entry.getCategory()));
                        gameMap.put(entry.getId(), entry);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[GameService] Failed to load index: " + e.getMessage());
        }
    }

    public synchronized void saveGamesSync() {
        List<GameEntry> list = getAllGames();
        try {
            JsonUtil.saveJsonAtomic(Paths.get(INDEX_FILE), list);
        } catch (IOException e) {
            System.err.println("[GameService] Failed to save index: " + e.getMessage());
        }
    }

    public List<GameEntry> getAllGames() {
        List<GameEntry> list = new ArrayList<>(gameMap.values());
        list.sort((a, b) -> Long.compare(b.getCreatedAt(), a.getCreatedAt()));
        return list;
    }

    public List<GameEntry> listGames() {
        return getAllGames();
    }

    public List<Map<String, Object>> listGamesFor(User me, boolean isSuperAdmin) {
        return getAllGames().stream()
                .filter(game -> isSuperAdmin || "approved".equals(game.getStatus())
                        || (me != null && Objects.equals(game.getDeveloperUserId(), me.getUserId())))
                .sorted(Comparator.comparingDouble(this::effectiveHeatScore).reversed()
                        .thenComparing(Comparator.comparingLong(GameEntry::getCreatedAt).reversed()))
                .map(game -> toView(game, me, isSuperAdmin))
                .collect(Collectors.toList());
    }

    public double effectiveHeatScore(GameEntry game) {
        if (game == null) return 0.0;
        long visits = game.getVisitCount();
        long ageMs = Math.max(0L, System.currentTimeMillis() - game.getCreatedAt());
        double hours = ageMs / (1000.0 * 3600.0);
        double boost = 1.0 + (1.0 / (1.0 + hours / 48.0));
        return visits * boost;
    }

    public Map<String, Object> toView(GameEntry game, User me, boolean isSuperAdmin) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", game.getId());
        map.put("title", game.getTitle());
        map.put("category", normalizeCategory(game.getCategory()));
        map.put("desc", game.getDesc() != null ? game.getDesc() : "");
        map.put("developerUserId", game.getDeveloperUserId() != null ? game.getDeveloperUserId() : "");
        map.put("status", game.getStatus() != null ? game.getStatus() : "approved");
        map.put("visitCount", game.getVisitCount());
        map.put("createdAt", game.getCreatedAt());
        map.put("coverPath", game.getCoverPath() != null ? game.getCoverPath() : "");
        map.put("previewVideoPath", game.getPreviewVideoPath() != null ? game.getPreviewVideoPath() : "");
        map.put("versions", game.getVersions());

        boolean canEdit = me != null && (isSuperAdmin || canManage(game.getId(), me.getUserId(), isSuperAdmin));
        map.put("canEdit", canEdit);
        return map;
    }

    public boolean canManage(String gameId, String userId, boolean isSuperAdmin) {
        if (isSuperAdmin) return true;
        GameEntry g = getGameById(gameId);
        return g != null && userId != null && userId.equals(g.getDeveloperUserId());
    }

    public Map<String, Object> getGame(String gameId, User me, boolean isSuperAdmin) {
        GameEntry g = getGameById(gameId);
        if (g == null) return null;
        boolean owner = me != null && Objects.equals(g.getDeveloperUserId(), me.getUserId());
        return isSuperAdmin || owner || "approved".equals(g.getStatus()) ? toView(g, me, isSuperAdmin) : null;
    }

    public List<Map<String, Object>> listPendingGames(User me) {
        return getAllGames().stream()
                .filter(game -> "pending".equals(game.getStatus()))
                .map(game -> toView(game, me, true))
                .collect(Collectors.toList());
    }

    public GameEntry getGameById(String id) {
        if (id == null) return null;
        return gameMap.get(id);
    }

    public Map<String, Object> createGame(User developer, String title, String category, String desc, String versionName, String htmlContent, StoredFileMetadata customCover) throws IOException {
        GameEntry entry = newGameEntry(developer, title, category, desc, customCover);

        GameVersion version = publishVersionInternal(entry, htmlContent, versionName);
        entry.getVersions().add(version);

        gameMap.put(entry.getId(), entry);
        saveGamesSync();
        return toView(entry, developer, false);
    }

    public Map<String, Object> createGameFromBinary(User developer, String title, String category, String desc,
                                                     String versionName, String announcement,
                                                     StoredFileMetadata binaryFile) throws IOException {
        if (binaryFile == null || binaryFile.getStoredName() == null) {
            throw new IllegalArgumentException("上传文件不存在");
        }
        GameEntry entry = newGameEntry(developer, title, category, desc, null);
        GameVersion version = newVersion(versionName);
        applyBinaryVersion(version, binaryFile, announcement, developer);
        entry.getVersions().add(version);
        gameMap.put(entry.getId(), entry);
        saveGamesSync();
        return toView(entry, developer, false);
    }

    private GameEntry newGameEntry(User developer, String title, String category, String desc,
                                   StoredFileMetadata customCover) {
        GameEntry entry = new GameEntry();
        entry.setId("game_" + UUID.randomUUID().toString().replace("-", ""));
        entry.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : "未命名小程序");
        entry.setCategory(normalizeCategory(category));
        entry.setDesc(desc != null ? desc.trim() : "");
        entry.setDeveloperUserId(developer != null ? developer.getUserId() : "");
        boolean autoApprove = developer != null
                && SuperAdminService.getInstance().isSuperAdmin(developer.getUserId());
        entry.setStatus(autoApprove ? "approved" : "pending");
        entry.setCreatedAt(System.currentTimeMillis());
        if (customCover != null && customCover.getStoredName() != null) {
            entry.setCoverPath(customCover.getAccessPath());
        }
        return entry;
    }

    public Map<String, Object> createGame(User developer, String title, String category, String desc, String htmlContent) throws IOException {
        return createGame(developer, title, category, desc, "v1.0.0", htmlContent, null);
    }

    public GameVersion publishVersionInternal(GameEntry entry, String htmlContent, String versionName) throws IOException {
        GameVersion version = newVersion(versionName);
        String gameDir = GAMES_DIR + "/" + entry.getId();
        new File(gameDir).mkdirs();

        String filePath = gameDir + "/" + version.getId() + ".html";
        File htmlFile = new File(filePath);
        if (htmlContent != null) {
            Files.writeString(htmlFile.toPath(), htmlContent, StandardCharsets.UTF_8);
        }

        version.setFilePath(filePath);
        return version;
    }

    private GameVersion newVersion(String versionName) {
        GameVersion version = new GameVersion();
        version.setId("ver_" + UUID.randomUUID().toString().replace("-", ""));
        version.setVersion(versionName != null && !versionName.trim().isEmpty()
                ? versionName.trim() : "v1.0.0");
        version.setUploadTime(System.currentTimeMillis());
        return version;
    }

    private void applyBinaryVersion(GameVersion version, StoredFileMetadata binaryFile,
                                    String announcement, User uploader) {
        version.setAnnouncement(announcement != null ? announcement.trim() : "");
        version.setFilePath(binaryFile.getAccessPath());
        version.setFileName(binaryFile.getOriginalFileName());
        version.setFileSize(binaryFile.getSize());
        if (uploader != null) {
            version.setUploaderId(uploader.getUserId());
            version.setUploaderNickname(uploader.getNickname());
        }
    }

    public Map<String, Object> publishVersion(String gameId, User user, boolean isSuperAdmin, String versionName, String htmlContent, StoredFileMetadata binaryFile) throws IOException {
        GameEntry entry = getGameById(gameId);
        if (entry == null) {
            throw new IllegalArgumentException("小程序不存在");
        }
        if (user != null && !canManage(gameId, user.getUserId(), isSuperAdmin)) {
            throw new SecurityException("无权更新此小程序");
        }
        GameVersion version = publishVersionInternal(entry, htmlContent, versionName);
        if (binaryFile != null && binaryFile.getStoredName() != null) {
            version.setFilePath("/files/" + binaryFile.getStoredName());
            version.setFileName(binaryFile.getOriginalFileName());
            version.setFileSize(binaryFile.getSize());
        }
        if (user != null) {
            version.setUploaderId(user.getUserId());
            version.setUploaderNickname(user.getNickname());
        }
        entry.getVersions().add(version);
        saveGamesSync();
        return toView(entry, user, isSuperAdmin);
    }

    public Map<String, Object> publishBinaryVersion(String gameId, User user, boolean isSuperAdmin,
                                                     String versionName, String announcement,
                                                     StoredFileMetadata binaryFile) throws IOException {
        GameEntry entry = getGameById(gameId);
        if (entry == null) {
            throw new IllegalArgumentException("小程序不存在");
        }
        if (user != null && !canManage(gameId, user.getUserId(), isSuperAdmin)) {
            throw new SecurityException("无权更新此小程序");
        }
        if (binaryFile == null || binaryFile.getStoredName() == null) {
            throw new IllegalArgumentException("上传文件不存在");
        }
        GameVersion version = newVersion(versionName);
        applyBinaryVersion(version, binaryFile, announcement, user);
        entry.getVersions().add(version);
        saveGamesSync();
        return toView(entry, user, isSuperAdmin);
    }

    public GameVersion publishVersion(GameEntry entry, String htmlContent, String versionName) throws IOException {
        GameVersion version = publishVersionInternal(entry, htmlContent, versionName);
        entry.getVersions().add(version);
        saveGamesSync();
        return version;
    }

    public Map<String, Object> updateMeta(String gameId, User user, boolean isSuperAdmin, String title, String category, String desc) {
        GameEntry entry = getGameById(gameId);
        if (entry == null) {
            throw new IllegalArgumentException("小程序不存在");
        }
        if (user != null && !canManage(gameId, user.getUserId(), isSuperAdmin)) {
            throw new SecurityException("无权修改此小程序信息");
        }
        if (title != null && !title.trim().isEmpty()) {
            entry.setTitle(title.trim());
        }
        if (category != null) {
            entry.setCategory(normalizeCategory(category));
        }
        if (desc != null) {
            entry.setDesc(desc.trim());
        }
        saveGamesSync();
        return toView(entry, user, isSuperAdmin);
    }

    public Map<String, Object> updateAssets(String gameId, User user, boolean isSuperAdmin, String coverPath, String previewVideoPath) {
        GameEntry entry = getGameById(gameId);
        if (entry == null) {
            throw new IllegalArgumentException("小程序不存在");
        }
        if (user != null && !canManage(gameId, user.getUserId(), isSuperAdmin)) {
            throw new SecurityException("无权修改此小程序资产");
        }
        if (coverPath != null) {
            entry.setCoverPath(coverPath);
        }
        if (previewVideoPath != null) {
            entry.setPreviewVideoPath(previewVideoPath);
        }
        saveGamesSync();
        return toView(entry, user, isSuperAdmin);
    }

    public synchronized Map<String, Object> reviewGame(String gameId, User reviewer, boolean approved) {
        GameEntry entry = getGameById(gameId);
        if (entry == null) throw new IllegalArgumentException("小程序不存在");
        entry.setStatus(approved ? "approved" : "rejected");
        saveGamesSync();
        return toView(entry, reviewer, true);
    }

    public Map<String, Object> ensureGeneratedCover(String gameId, User user, boolean isSuperAdmin, StoredFileMetadata metadata) {
        GameEntry entry = getGameById(gameId);
        if (entry != null && (entry.getCoverPath() == null || entry.getCoverPath().isEmpty())) {
            if (metadata != null && metadata.getStoredName() != null) {
                String ownerId = user != null ? user.getUserId() : entry.getDeveloperUserId();
                String coverPath = GameCoverRenderer.renderAndStore(entry.getTitle(), ownerId);
                if (coverPath != null && !coverPath.isEmpty()) {
                    return updateAssets(gameId, user, isSuperAdmin, coverPath, null);
                }
            }
        }
        return toView(entry, user, isSuperAdmin);
    }

    public synchronized double recordVisit(String gameId) {
        return recordVisit(gameId, null);
    }

    public synchronized double recordVisit(String gameId, String userId) {
        GameEntry entry = getGameById(gameId);
        if (entry == null) throw new IllegalArgumentException("小程序不存在");
        if (!"approved".equals(entry.getStatus())) throw new IllegalArgumentException("小程序尚未通过审核");
        entry.setVisitCount(entry.getVisitCount() + 1);
        saveGamesSync();
        return effectiveHeatScore(entry);
    }
}
