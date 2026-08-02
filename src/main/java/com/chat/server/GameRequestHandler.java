package com.chat.server;

import com.chat.model.*;
import com.chat.service.*;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.util.*;

final class GameRequestHandler extends RequestHandlerSupport {
    void handleGetGames(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        List<Map<String, Object>> games = GameService.getInstance().listGamesFor(me, UserRoles.isSuperAdmin(me.getUserId()));
        for (Map<String, Object> game : games) {
            game.put("developerIsDeveloper", UserRoles.isDeveloper(String.valueOf(game.getOrDefault("developerId", ""))));
        }
        sendJson(ex, 200, games);
    }

    void handleGetPendingGames(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        sendJson(ex, 200, GameService.getInstance().listPendingGames(me));
    }

    void handleUploadGameBinary(HttpExchange ex, InputStream bodyStream, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.isFeatureBanned("upload")) {
            sendJson(ex, 403, map("error", "上传功能已被封禁"));
            return;
        }
        Map<String, String> params = parseQuery(query);
        String fileName = params.get("fileName");
        if (fileName == null || !fileName.toLowerCase().endsWith(".html")) {
            sendJson(ex, 400, map("error", "只允许上传.html文件"));
            return;
        }
        try {
            StoredFileMetadata storedFile = storeStreamFile(ex, bodyStream, fileName, 6L * 1024 * 1024 * 1024,
                    "文件不能超过6GB", me.getUserId());
            Map<String, Object> res = new LinkedHashMap<>();
            res.put("success", "true");
            res.put("filePath", storedFile.getAccessPath());
            res.put("fileName", storedFile.getOriginalFileName());
            res.put("fileSize", storedFile.getSize());
            sendJson(ex, 200, res);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败: " + e.getMessage()));
        }
    }

    void handleCreateGame(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String title = p.get("title");
        String desc = p.getOrDefault("desc", "");
        String category = p.get("category");
        String version = p.get("version");
        String announcement = p.getOrDefault("announcement", "");
        String filePath = p.get("filePath");
        if (title == null || title.trim().isEmpty() || version == null || version.trim().isEmpty()
                || filePath == null || !GameService.isSupportedCategory(category)) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        StoredFileMetadata metadata = metadataFromPath(filePath, me);
        if (metadata == null) {
            sendJson(ex, 404, map("error", "上传文件不存在"));
            return;
        }
        boolean uploadReserved = !UserRoles.isSuperAdmin(me.getUserId()) && reserveGameUpload(me);
        if (!UserRoles.isSuperAdmin(me.getUserId()) && !uploadReserved) {
            int limit = me.getDailyGameUploadLimitByLevel();
            sendJson(ex, 403, map("error", "今日小程序上传数量已达上限(" + limit + "个)，升级可提升限额"));
            return;
        }
        Map<String, Object> result;
        try {
            result = GameService.getInstance().createGameFromBinary(
                    me, title, category, desc, version, announcement, metadata);
        } catch (IOException | RuntimeException e) {
            if (uploadReserved) refundGameUpload(me);
            throw e;
        }
        Object createdGameId = result.get("id");
        if (createdGameId != null) {
            result = GameService.getInstance().ensureGeneratedCover(
                    String.valueOf(createdGameId), me, UserRoles.isSuperAdmin(me.getUserId()), metadata);
        }
        sendJson(ex, 200, result);
    }

    void handlePublishGameVersion(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String gameId = p.get("gameId");
        String version = p.get("version");
        String announcement = p.getOrDefault("announcement", "");
        String filePath = p.get("filePath");
        if (gameId == null || version == null || version.trim().isEmpty() || filePath == null) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        StoredFileMetadata metadata = metadataFromPath(filePath, me);
        if (metadata == null) {
            sendJson(ex, 404, map("error", "上传文件不存在"));
            return;
        }
        try {
            Map<String, Object> result = GameService.getInstance().publishBinaryVersion(
                    gameId, me, UserRoles.isSuperAdmin(me.getUserId()), version, announcement, metadata);
            sendJson(ex, 200, result);
        } catch (SecurityException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleUpdateGameMeta(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String gameId = p.get("gameId");
        String title = p.get("title");
        String desc = p.getOrDefault("desc", "");
        String category = p.get("category");
        if (gameId == null || gameId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少小程序ID"));
            return;
        }
        String coverPath = p.get("coverPath");
        String previewVideoPath = p.get("previewVideoPath");
        String assetError = validateGameAsset(coverPath, me, "image/", 3L * 1024 * 1024, "封面图片");
        if (assetError == null) {
            assetError = validateGameAsset(previewVideoPath, me, "video/", 10L * 1024 * 1024, "预览视频");
        }
        if (assetError != null) {
            sendJson(ex, 400, map("error", assetError));
            return;
        }
        try {
            Map<String, Object> result = GameService.getInstance().updateMeta(
                    gameId, me, UserRoles.isSuperAdmin(me.getUserId()), title, category, desc);
            if (coverPath != null || previewVideoPath != null) {
                result = GameService.getInstance().updateAssets(
                        gameId, me, UserRoles.isSuperAdmin(me.getUserId()), coverPath, previewVideoPath);
            }
            sendJson(ex, 200, result);
        } catch (SecurityException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleUploadGameAsset(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String gameId = p.get("gameId");
        if (gameId == null || gameId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少小程序ID"));
            return;
        }
        String coverPath = p.get("coverPath");
        String previewVideoPath = p.get("previewVideoPath");
        if (coverPath != null && !coverPath.trim().isEmpty()) {
            StoredFileMetadata coverMeta = metadataFromPath(coverPath, me);
            if (coverMeta == null) {
                sendJson(ex, 404, map("error", "封面文件不存在"));
                return;
            }
            if (coverMeta.getSize() > 3L * 1024 * 1024) {
                sendJson(ex, 400, map("error", "封面图片不能超过3MB"));
                return;
            }
            if (coverMeta.getContentType() == null || !coverMeta.getContentType().startsWith("image/")) {
                sendJson(ex, 400, map("error", "封面必须是图片文件"));
                return;
            }
        }
        if (previewVideoPath != null && !previewVideoPath.trim().isEmpty()) {
            StoredFileMetadata videoMeta = metadataFromPath(previewVideoPath, me);
            if (videoMeta == null) {
                sendJson(ex, 404, map("error", "预览视频不存在"));
                return;
            }
            if (videoMeta.getSize() > 10L * 1024 * 1024) {
                sendJson(ex, 400, map("error", "预览视频不能超过10MB"));
                return;
            }
            if (videoMeta.getContentType() == null || !videoMeta.getContentType().startsWith("video/")) {
                sendJson(ex, 400, map("error", "预览视频必须是视频文件"));
                return;
            }
        }
        try {
            Map<String, Object> result = GameService.getInstance().updateAssets(
                    gameId, me, UserRoles.isSuperAdmin(me.getUserId()),
                    coverPath, previewVideoPath);
            sendJson(ex, 200, result);
        } catch (SecurityException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleUploadGame(HttpExchange ex, String body, User me) throws IOException {
        handleCreateGame(ex, body, me);
    }

    void handleApproveGame(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> values = parseJson(body);
        String gameId = values.get("gameId");
        if (gameId == null || gameId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少小程序ID"));
            return;
        }
        boolean approved = !"reject".equalsIgnoreCase(values.get("action"))
                && !"rejected".equalsIgnoreCase(values.get("status"))
                && !"false".equalsIgnoreCase(values.get("approved"));
        try {
            sendJson(ex, 200, GameService.getInstance().reviewGame(gameId.trim(), me, approved));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    void handleRecordGameVisit(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String gameId = parseJson(body).get("gameId");
        if (gameId == null || gameId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少小程序ID"));
            return;
        }
        try {
            double heatScore = GameService.getInstance().recordVisit(gameId, me.getUserId());
            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            boolean expAwarded = false;
            synchronized (me) {
                if (!today.equals(me.getGamePlayExpDay())) {
                    me.setGamePlayExpDay(today);
                    me.setDailyGamePlayExpCount(0);
                }
                if (me.getDailyGamePlayExpCount() < 3) {
                    me.setDailyGamePlayExpCount(me.getDailyGamePlayExpCount() + 1);
                    me.addExp(1);
                    expAwarded = true;
                }
            }
            if (expAwarded) UserService.getInstance().save();
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("heatScore", heatScore);
            response.put("heatDisplay", (int) Math.floor(heatScore));
            response.put("expAwarded", expAwarded);
            response.put("expGained", expAwarded ? 1 : 0);
            response.put("dailyGamePlayExpCount", me.getDailyGamePlayExpCount());
            response.put("dailyGamePlayExpLimit", 3);
            response.put("totalExp", me.getExp());
            response.put("level", me.getEffectiveLevel());
            response.put("levelDisplay", me.getLevelDisplay());
            response.put("nextLevelExp", getNextLevelExp(me));
            sendJson(ex, 200, response);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    private boolean reserveGameUpload(User user) {
        synchronized (user) {
            String today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
            if (!today.equals(user.getGameUploadDay())) {
                user.setGameUploadDay(today);
                user.setDailyGameUploads(0);
            }
            int limit = user.getDailyGameUploadLimitByLevel();
            if (limit != Integer.MAX_VALUE && user.getDailyGameUploads() >= limit) return false;
            user.setDailyGameUploads(user.getDailyGameUploads() + 1);
            UserService.getInstance().save();
            return true;
        }
    }

    private void refundGameUpload(User user) {
        synchronized (user) {
            user.setDailyGameUploads(Math.max(0, user.getDailyGameUploads() - 1));
            UserService.getInstance().save();
        }
    }

    private String validateGameAsset(String path, User user, String contentTypePrefix, long maxBytes, String label) {
        if (path == null || path.isBlank()) return null;
        StoredFileMetadata metadata = metadataFromPath(path, user);
        if (metadata == null) return label + "不存在或无权访问";
        if (metadata.getSize() > maxBytes) return label + "不能超过" + (maxBytes / 1024 / 1024) + "MB";
        if (metadata.getContentType() == null || !metadata.getContentType().startsWith(contentTypePrefix)) {
            return label + "格式不正确";
        }
        return null;
    }
}
