package com.chat.server;

import com.chat.model.*;
import com.chat.service.CloudService;
import com.chat.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

final class CloudRequestHandler extends RequestHandlerSupport {
    void handleGetCloudList(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        CloudService.getInstance().ensureUserCloud(me);
        String path = parseQuery(query).getOrDefault("path", "/");
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("path", path);
        res.put("usedBytes", CloudService.getInstance().getUsedBytes(me.getUserId()));
        res.put("quotaBytes", me.getCloudQuotaByLevel() == Long.MAX_VALUE ? -1 : me.getCloudQuotaByLevel());
        res.put("deletePolicy", me.getCloudDeletePolicy());
        res.put("entries", CloudService.getInstance().listEntries(me.getUserId(), path).stream().map(CloudEntryMapper::cloudEntryToMap).collect(Collectors.toList()));
        sendJson(ex, 200, res);
    }

    void handleGetCloudRecycle(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listRecycleBin(me.getUserId()).stream().map(CloudEntryMapper::cloudRecycleEntryToMap).collect(Collectors.toList()));
    }

    void handleGetCloudShares(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listShares(me.getUserId()).stream().map(CloudEntryMapper::cloudShareToMap).collect(Collectors.toList()));
    }

    void handleGetCloudDownloads(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listDownloads(me.getUserId()));
    }

    void handleGetCloudTasks(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listTasks(me.getUserId()));
    }

    void handleGetCloudZipTree(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String entryId = parseQuery(query).get("entryId");
        try {
            sendJson(ex, 200, CloudService.getInstance().previewZipTree(me.getUserId(), entryId));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleCreateCloudFolder(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().createFolder(me.getUserId(), p.get("parentPath"), p.get("name"))));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleCreateCloudFile(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().createTextFile(me, p.get("parentPath"), p.get("name"), p.get("content"), "cloud")));
        } catch (Exception e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleRenameCloudEntry(HttpExchange ex, String body, User me) throws IOException { handleCloudEntryMutation(ex, me, body, "rename"); }

    void handleDeleteCloudEntry(HttpExchange ex, String body, User me) throws IOException { handleCloudEntryMutation(ex, me, body, "delete"); }

    void handleRestoreCloudEntry(HttpExchange ex, String body, User me) throws IOException { handleCloudEntryMutation(ex, me, body, "restore"); }

    void handlePurgeCloudEntry(HttpExchange ex, String body, User me) throws IOException { handleCloudEntryMutation(ex, me, body, "purge"); }

    void handleMoveCloudEntry(HttpExchange ex, String body, User me) throws IOException { handleCloudEntryMutation(ex, me, body, "move"); }

    void handleCopyCloudEntry(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String entryId = p.get("entryId");
        if (entryId == null || entryId.isBlank()) {
            sendJson(ex, 400, map("error", "缺少条目ID"));
            return;
        }
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(
                    CloudService.getInstance().copyEntry(me, entryId, p.get("parentPath"))));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    private void handleCloudEntryMutation(HttpExchange ex, User me, String body, String op) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String entryId = p.get("entryId");
        if (entryId == null || entryId.trim().isEmpty()) { sendJson(ex, 400, map("error", "缺少条目ID")); return; }
        try {
            Object res;
            switch (op) {
                case "rename": res = CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().renameEntry(me.getUserId(), entryId, p.get("name"))); break;
                case "delete": CloudService.getInstance().deleteEntry(me.getUserId(), entryId); res = map("success", "true"); break;
                case "restore": CloudService.getInstance().restoreEntry(me.getUserId(), entryId); res = map("success", "true"); break;
                case "purge": CloudService.getInstance().permanentlyDelete(me.getUserId(), entryId); res = map("success", "true"); break;
                default: res = CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().moveEntry(me.getUserId(), entryId, p.get("parentPath"))); break;
            }
            sendJson(ex, 200, res);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleImportStoredCloudFile(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String filePath = p.get("filePath");
        StoredFileMetadata metadata = metadataFromPath(filePath, me);
        if (metadata == null) { sendJson(ex, 404, map("error", "源文件不存在")); return; }
        if (!CloudService.getInstance().canStore(me, metadata.getSize())) {
            sendJson(ex, 400, map("error", "云盘空间不足"));
            return;
        }
        String taskId = "cloud_upload_" + UUID.randomUUID().toString().replace("-", "");
        CloudService.getInstance().upsertTask(me.getUserId(), taskId, "upload", "上传文件到云盘", "running",
                metadata.getSize(), 0, 0, "正在写入云盘");
        try {
            CloudEntry entry = CloudService.getInstance().storeUserFile(me, metadata, p.get("parentPath"),
                    p.get("fileName") != null ? p.get("fileName") : metadata.getOriginalFileName(), "cloud");
            CloudService.getInstance().upsertTask(me.getUserId(), taskId, "upload", "上传文件到云盘", "done",
                    metadata.getSize(), metadata.getSize(), metadata.getSize(), "上传完成");
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(entry));
        } catch (Exception e) {
            CloudService.getInstance().upsertTask(me.getUserId(), taskId, "upload", "上传文件到云盘", "failed",
                    metadata.getSize(), 0, 0, e.getMessage());
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleShareCloudEntry(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String entryId = parseJson(body).get("entryId");
        try {
            CloudShareLink share = CloudService.getInstance().createShare(me.getUserId(), entryId, null, "cloud");
            sendJson(ex, 200, CloudEntryMapper.cloudShareToMap(share));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleSaveCloudShare(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().copySharedEntryToUser(p.get("shareId"), me, p.get("parentPath"))));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleClearCloudDownloads(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        CloudService.getInstance().clearDownloads(me.getUserId());
        sendJson(ex, 200, map("success", "true"));
    }

    void handleUnzipCloudEntry(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        try { sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().unzipEntry(me, parseJson(body).get("entryId")))); }
        catch (IllegalArgumentException e) { sendJson(ex, 400, map("error", e.getMessage())); }
    }

    void handleCompressCloudEntry(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String entryId = parseJson(body).get("entryId");
        if (entryId == null || entryId.trim().isEmpty()) { sendJson(ex, 400, map("error", "entryId不能为空")); return; }
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().compressEntry(me, entryId)));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleGetCloudFavorites(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listFavorites(me.getUserId()).stream()
                .map(CloudEntryMapper::cloudEntryToMap).collect(Collectors.toList()));
    }

    void handleGetCloudSafebox(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, CloudService.getInstance().listSafebox(me.getUserId()).stream()
                .map(CloudEntryMapper::cloudEntryToMap).collect(Collectors.toList()));
    }

    void handleToggleCloudFavorite(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String entryId = parseJson(body).get("entryId");
        if (entryId == null || entryId.trim().isEmpty()) { sendJson(ex, 400, map("error", "entryId不能为空")); return; }
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().toggleFavorite(me.getUserId(), entryId)));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleToggleCloudSafebox(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String entryId = parseJson(body).get("entryId");
        if (entryId == null || entryId.trim().isEmpty()) { sendJson(ex, 400, map("error", "entryId不能为空")); return; }
        try {
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().toggleSafebox(me.getUserId(), entryId)));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleCompressCloudBatch(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String entryIdsStr = p.get("entryIds");
        String zipName = p.get("zipName");
        if (entryIdsStr == null || entryIdsStr.trim().isEmpty()) { sendJson(ex, 400, map("error", "entryIds不能为空")); return; }
        try {
            List<String> entryIds = JsonUtil.fromJson(entryIdsStr, new com.google.gson.reflect.TypeToken<List<String>>() {}.getType());
            if (entryIds == null || entryIds.isEmpty()) { sendJson(ex, 400, map("error", "entryIds不能为空")); return; }
            sendJson(ex, 200, CloudEntryMapper.cloudEntryToMap(CloudService.getInstance().compressEntries(me, entryIds, zipName)));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }
}
