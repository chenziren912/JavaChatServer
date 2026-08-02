package com.chat.server;

import com.chat.model.*;
import com.chat.service.*;
import com.chat.util.SessionManager;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.*;
import java.util.stream.Collectors;

final class AdminRequestHandler extends RequestHandlerSupport {
    private final SuperAdminService superAdminService = SuperAdminService.getInstance();

    void handleAdminDeleteUser(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetId = p.get("userId");
        if (targetId == null || targetId.isEmpty()) {
            sendJson(ex, 400, map("error", "缺少目标用户ID"));
            return;
        }
        if (targetId.equals(SuperAdminService.getInstance().getPrimarySuperAdminId())) {
            sendJson(ex, 403, map("error", "服主账号禁止删除"));
            return;
        }
        if (targetId.equals(me.getUserId())) {
            sendJson(ex, 403, map("error", "不能在管理面板删除自己"));
            return;
        }
        if (UserRoles.isSuperAdmin(targetId)) {
            sendJson(ex, 403, map("error", "请先移除该用户的超级管理员身份"));
            return;
        }
        String err = UserService.getInstance().deleteUser(targetId);
        if ("ok".equals(err)) {
            sendJson(ex, 200, map("success", "true"));
        } else {
            sendJson(ex, 500, map("error", err != null ? err : "删除失败"));
        }
    }

    void handleGetPasswordRecoveryRequests(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        sendJson(ex, 200, PasswordRecoveryService.getInstance().list());
    }

    void handleUpdatePasswordRecoveryStatus(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> values = parseJson(body);
        boolean updated = PasswordRecoveryService.getInstance()
                .updateStatus(values.get("id"), values.get("status"));
        sendJson(ex, updated ? 200 : 400,
                updated ? map("success", "true") : map("error", "请求不存在或状态无效"));
    }

    void handleGetAdminOverview(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Runtime runtime = Runtime.getRuntime();
        long max = runtime.maxMemory();
        long total = runtime.totalMemory();
        long free = runtime.freeMemory();
        long used = total - free;
        ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("primarySuperAdminId", superAdminService.getPrimarySuperAdminId());
        res.put("superAdminCount", superAdminService.listSuperAdminIds().size());
        res.put("userCount", UserService.getInstance().getAllUsers().size());
        res.put("groupCount", GroupService.getInstance().getAllGroups().size());
        res.put("messageCount", MessageService.getInstance().getTotalMessageCount());
        res.put("roomCount", MessageService.getInstance().getRoomCount());
        res.put("fileCount", FileStore.getInstance().getStoredFileCount());
        res.put("activeSessionCount", SessionManager.getInstance().getTotalActiveSessions());
        res.put("processorCount", runtime.availableProcessors());
        res.put("heapUsed", used);
        res.put("heapCommitted", total);
        res.put("heapMax", max);
        res.put("heapFree", free);
        res.put("threadCount", threadBean.getThreadCount());
        res.put("daemonThreadCount", threadBean.getDaemonThreadCount());
        res.put("peakThreadCount", threadBean.getPeakThreadCount());
        res.put("uptimeMs", ManagementFactory.getRuntimeMXBean().getUptime());
        sendJson(ex, 200, res);
    }

    void handleGetAdminUsers(HttpExchange ex, String query, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> qp = parseQuery(query);
        int offset = 0, limit = Integer.MAX_VALUE;
        try { offset = Math.max(0, Integer.parseInt(qp.getOrDefault("offset", "0"))); } catch (Exception e) {}
        try { limit = Math.max(1, Math.min(200, Integer.parseInt(qp.getOrDefault("limit", "200")))); } catch (Exception e) {}
        List<User> allUsers = UserService.getInstance().getAllUsers();
        int total = allUsers.size();
        List<Map<String, Object>> users = allUsers.stream().skip(offset).limit(limit).map(user -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String userId = user.getUserId();
            row.put("userId", userId);
            row.put("nickname", user.getNickname());
            row.put("username", user.getUsername());
            row.put("avatarPath", user.getAvatarPath());
            row.put("isSuperAdmin", UserRoles.isSuperAdmin(userId));
            row.put("isPrimarySuperAdmin", UserRoles.isPrimarySuperAdmin(userId));
            row.put("isDeveloper", UserRoles.isDeveloper(userId));
            row.put("isCurrentUser", userId.equals(me.getUserId()));
            row.put("activeSessions", SessionManager.getInstance().getActiveSessionCount(userId));
            row.put("banned", user.isCurrentlyBanned());
            if (user.isCurrentlyBanned()) {
                row.put("banExpiresAt", user.getBanExpiresAt());
                row.put("banRemainingMillis", user.getBanRemainingMillis());
                row.put("banReason", user.getBanReason() != null ? user.getBanReason() : "");
            }
            row.put("level", user.getEffectiveLevel());
            row.put("levelDisplay", user.getLevelDisplay());
            row.put("exp", user.getExp());
            long cqb = user.getCloudQuotaBytes() == Long.MAX_VALUE ? -1 : user.getCloudQuotaBytes();
            row.put("cloudQuotaBytes", cqb);
            row.put("cloudUsedBytes", CloudService.getInstance().getUsedBytes(userId));
            row.put("cloudDeletePolicy", user.getCloudDeletePolicy());
            row.put("aiUsedTokensToday", user.getAiUsedTokensToday());
            row.put("aiRemainingTokens", AiService.getInstance().getRemainingTokens(user) == Double.MAX_VALUE ? -1 : AiService.getInstance().getRemainingTokens(user));
            row.put("featureBans", UserService.getInstance().getAllFeatureBanInfo(userId));
            return row;
        }).collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("offset", offset);
        result.put("users", users);
        sendJson(ex, 200, result);
    }

    void handleGetAdminGroups(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        List<Map<String, Object>> groups = GroupService.getInstance().getAllGroups().stream().map(group -> {
            Map<String, Object> row = new LinkedHashMap<>();
            User owner = UserService.getInstance().getByUserId(group.getOwnerId());
            row.put("groupId", group.getGroupId());
            row.put("groupName", group.getGroupName());
            row.put("ownerId", group.getOwnerId());
            row.put("ownerNickname", owner != null ? owner.getNickname() : group.getOwnerId());
            row.put("ownerIsSuperAdmin", UserRoles.isSuperAdmin(group.getOwnerId()));
            row.put("ownerIsPrimarySuperAdmin", UserRoles.isPrimarySuperAdmin(group.getOwnerId()));
            row.put("ownerIsDeveloper", UserRoles.isDeveloper(group.getOwnerId()));
            row.put("memberCount", group.getMembers() != null ? group.getMembers().size() : 0);
            row.put("adminCount", group.getAdmins() != null ? group.getAdmins().size() : 0);
            row.put("createdAt", group.getCreatedAt());
            row.put("iconPath", group.getIconPath());
            return row;
        }).collect(Collectors.toList());
        sendJson(ex, 200, groups);
    }

    void handleGetSuperAdmins(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        List<Map<String, Object>> admins = superAdminService.listSuperAdminIds().stream().map(uid -> {
            Map<String, Object> row = new LinkedHashMap<>();
            User user = UserService.getInstance().getByUserId(uid);
            row.put("userId", uid);
            row.put("nickname", user != null ? user.getNickname() : uid);
            row.put("avatarPath", user != null ? user.getAvatarPath() : null);
            row.put("isDeveloper", UserRoles.isDeveloper(uid));
            row.put("isPrimary", UserRoles.isPrimarySuperAdmin(uid));
            row.put("isCoOwner", superAdminService.isCoOwner(uid));
            row.put("activeSessions", SessionManager.getInstance().getActiveSessionCount(uid));
            return row;
        }).collect(Collectors.toList());
        sendJson(ex, 200, admins);
    }

    void handleAddSuperAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String role = p.getOrDefault("role", "admin"); // "coowner" or "admin"
        
        boolean iAmPrimary = UserRoles.isPrimarySuperAdmin(me.getUserId());
        boolean iAmCoOwner = superAdminService.isCoOwner(me.getUserId());
        
        if (!iAmPrimary && !iAmCoOwner) {
            sendJson(ex, 403, map("error", "无权限添加管理员"));
            return;
        }
        
        if ("coowner".equals(role) && !iAmPrimary) {
            sendJson(ex, 403, map("error", "仅服主可添加副服主"));
            return;
        }

        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请输入用户ID"));
            return;
        }
        if (UserService.getInstance().getByUserId(targetUserId) == null) {
            sendJson(ex, 404, map("error", "用户不存在"));
            return;
        }
        
        boolean ok = false;
        if ("coowner".equals(role)) {
            ok = superAdminService.addCoOwner(targetUserId.trim());
        } else {
            ok = superAdminService.addSuperAdmin(targetUserId.trim());
        }
        
        if (ok) UserService.getInstance().ensurePrimarySuperAdminLevel();
        sendJson(ex, ok ? 200 : 400, ok ? map("success", "true") : map("error", "该用户已是目标身份"));
    }

    void handleRemoveSuperAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        
        boolean iAmPrimary = UserRoles.isPrimarySuperAdmin(me.getUserId());
        boolean iAmCoOwner = superAdminService.isCoOwner(me.getUserId());
        
        if (!iAmPrimary && !iAmCoOwner) {
            sendJson(ex, 403, map("error", "无权限删除管理员"));
            return;
        }
        
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请输入用户ID"));
            return;
        }
        
        boolean targetIsCoOwner = superAdminService.isCoOwner(targetUserId);
        
        if (targetIsCoOwner && !iAmPrimary) {
            sendJson(ex, 403, map("error", "仅服主可删除副服主"));
            return;
        }
        
        boolean ok = false;
        if (targetIsCoOwner) {
            ok = superAdminService.removeCoOwner(targetUserId);
        } else {
            ok = superAdminService.removeSuperAdmin(targetUserId);
        }
        
        sendJson(ex, ok ? 200 : 400, ok ? map("success", "true") : map("error", "操作失败或该用户不是管理员"));
    }

    void handleSetUserTags(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        boolean iAmPrimary = UserRoles.isPrimarySuperAdmin(me.getUserId());
        boolean iAmCoOwner = superAdminService.isCoOwner(me.getUserId());
        
        if (!iAmPrimary && !iAmCoOwner) {
            sendJson(ex, 403, map("error", "仅服主/副服主可设置自定义标签"));
            return;
        }
        
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String tagsStr = p.get("tags"); // comma separated
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        
        User target = UserService.getInstance().getByUserId(targetUserId.trim());
        if (target == null) {
            sendJson(ex, 404, map("error", "用户不存在"));
            return;
        }
        
        List<String> tagList = new java.util.ArrayList<>();
        if (tagsStr != null && !tagsStr.trim().isEmpty()) {
            for (String t : tagsStr.split(",")) {
                if (!t.trim().isEmpty()) tagList.add(t.trim());
            }
        }
        
        target.setCustomTags(tagList);
        UserService.getInstance().save();
        
        sendJson(ex, 200, map("success", "true"));
    }

    void handleQuitSuperAdmin(HttpExchange ex, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        boolean ok = superAdminService.removeSuperAdmin(me.getUserId());
        sendJson(ex, ok ? 200 : 400, ok ? map("success", "true") : map("error", "至少要保留一名超级管理员"));
    }

    void handleDeleteGroupByAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        String groupId = parseJson(body).get("groupId");
        if (groupId == null || groupId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少群ID"));
            return;
        }
        boolean ok = GroupService.getInstance().deleteGroup(groupId.trim());
        sendJson(ex, ok ? 200 : 404, ok ? map("success", "true") : map("error", "群聊不存在"));
    }

    void handleForceLogoutByAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        String targetUserId = parseJson(body).get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        if (UserRoles.isSuperAdmin(targetUserId) && !targetUserId.equals(me.getUserId())) {
            sendJson(ex, 400, map("error", "超级管理员之间不能互相强退"));
            return;
        }
        SessionManager.getInstance().removeSessionsForUser(targetUserId);
        sendJson(ex, 200, map("success", "true"));
    }

    void handleSetUserPasswordByAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String newPassword = p.get("newPassword");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        String normalizedTargetId = targetUserId.trim();
        if (UserRoles.isPrimarySuperAdmin(normalizedTargetId)) {
            sendJson(ex, 403, map("error", "服主密码不能通过管理接口重置"));
            return;
        }
        if (UserRoles.isSuperAdmin(normalizedTargetId)
                && !UserRoles.isPrimarySuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "仅服主可重置其他超级管理员的密码"));
            return;
        }
        String result = UserService.getInstance().adminSetPassword(normalizedTargetId, newPassword);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleBanUser(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String durationStr = p.getOrDefault("durationSeconds", "0");
        String reason = p.get("reason");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        if (UserRoles.isSuperAdmin(targetUserId.trim())) {
            sendJson(ex, 400, map("error", "不能封禁超级管理员"));
            return;
        }
        long durationSeconds;
        try {
            durationSeconds = Long.parseLong(durationStr);
        } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "封禁时间格式错误"));
            return;
        }
        String result = UserService.getInstance().banUser(targetUserId.trim(), durationSeconds, reason);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleUnbanUser(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        String result = UserService.getInstance().unbanUser(targetUserId.trim());
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleSetFeatureBan(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String feature = p.get("feature");
        String durationStr = p.getOrDefault("durationSeconds", "0");
        String reason = p.get("reason");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        if (feature == null || feature.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少功能名称"));
            return;
        }
        if (UserRoles.isSuperAdmin(targetUserId.trim())) {
            sendJson(ex, 400, map("error", "不能封禁超级管理员的功能"));
            return;
        }
        long durationSeconds;
        try {
            durationSeconds = Long.parseLong(durationStr);
        } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "时间格式错误"));
            return;
        }
        String result = UserService.getInstance().setFeatureBan(targetUserId.trim(), feature.trim(), durationSeconds, reason);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleCheckFeatureBan(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> qp = parseQuery(query);
        String feature = qp.get("feature");
        if (feature == null || feature.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少功能名称"));
            return;
        }
        Map<String, Object> info = UserService.getInstance().getFeatureBanInfo(me.getUserId(), feature.trim());
        if (info == null) {
            sendJson(ex, 200, map("banned", "false"));
        } else {
            sendJson(ex, 200, info);
        }
    }

    void handleAdminGrantExp(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isPrimarySuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "仅服主可发放经验值"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String amountStr = p.get("amount");
        if (targetUserId == null || targetUserId.trim().isEmpty() || amountStr == null) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        long amount;
        try { amount = Long.parseLong(amountStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "经验值格式错误")); return;
        }
        String result = UserService.getInstance().addExp(targetUserId.trim(), amount);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleAdminSetLevel(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String levelStr = p.get("level");
        if (targetUserId == null || targetUserId.trim().isEmpty() || levelStr == null) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        int level;
        try { level = Integer.parseInt(levelStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "等级格式错误")); return;
        }
        if (level == 7) {
            // Lv6⚡ 仅限超管提拔
            String result = UserService.getInstance().adminSetLevel(targetUserId.trim(), 7);
            sendJson(ex, "ok".equals(result) ? 200 : 400,
                    "ok".equals(result) ? map("success", "true") : map("error", result));
        } else {
            String result = UserService.getInstance().adminSetLevel(targetUserId.trim(), level);
            sendJson(ex, "ok".equals(result) ? 200 : 400,
                    "ok".equals(result) ? map("success", "true") : map("error", result));
        }
    }

    void handleSetUserQuota(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String quotaStr = p.get("quotaBytes");
        if (targetUserId == null || targetUserId.trim().isEmpty() || quotaStr == null) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        long quotaBytes;
        try { quotaBytes = Long.parseLong(quotaStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "配额格式错误")); return;
        }
        if (quotaBytes <= 0 && quotaBytes != -1) {
            sendJson(ex, 400, map("error", "配额必须大于0或为-1（不限）"));
            return;
        }
        String result = UserService.getInstance().adminSetQuota(targetUserId.trim(), quotaBytes);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }

    void handleSetAiTokens(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !UserRoles.isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "\u65E0\u6743\u9650"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String targetUserId = p.get("targetUserId");
        String tokensStr = p.get("aiTokens");
        if (targetUserId == null || targetUserId.trim().isEmpty() || tokensStr == null) {
            sendJson(ex, 400, map("error", "\u53C2\u6570\u4E0D\u5B8C\u6574"));
            return;
        }
        double tokens;
        try { tokens = Double.parseDouble(tokensStr); } catch (NumberFormatException e) {
            sendJson(ex, 400, map("error", "\u70B9\u6570\u683C\u5F0F\u9519\u8BEF")); return;
        }
        if (!Double.isFinite(tokens) || tokens < -1 || tokens > 999999) {
            sendJson(ex, 400, map("error", "\u503C\u8303\u56F4: -1~999999")); return;
        }
        String result = UserService.getInstance().adminSetAiTokens(targetUserId.trim(), tokens);
        sendJson(ex, "ok".equals(result) ? 200 : 400,
                "ok".equals(result) ? map("success", "true") : map("error", result));
    }
}
