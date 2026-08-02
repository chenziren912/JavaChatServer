package com.chat.server;

import com.chat.model.*;
import com.chat.service.*;
import com.chat.util.JsonUtil;
import com.chat.util.SessionCookieSecurity;
import com.chat.util.SessionManager;
import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;

public class RequestHandler extends RequestHandlerSupport implements HttpHandler {
    private final AppPageRenderer pageRenderer = new AppPageRenderer();
    private final AiRequestHandler aiHandler = new AiRequestHandler();
    private final CloudRequestHandler cloudHandler = new CloudRequestHandler();
    private final GameRequestHandler gameHandler = new GameRequestHandler();
    private final AdminRequestHandler adminHandler = new AdminRequestHandler();
    private final MusicRequestHandler musicHandler = new MusicRequestHandler();
    private static final String LONGCAT_API_URL = "https://api.longcat.chat/openai/v1/chat/completions";
    private static final String LONGCAT_API_KEY = System.getenv("LONGCAT_API_KEY") != null ? System.getenv("LONGCAT_API_KEY") : System.getProperty("longcat.api.key", "");
    private static final java.nio.file.Path CODE_SOURCE_ROOT = resolveCodeSourceRoot();
    private static final long TEST_SEND_RESPONSE_DELAY_MS = readLongSetting(
            "chat.testSendResponseDelayMs",
            "CHATSERVER_TEST_SEND_RESPONSE_DELAY_MS",
            0L
    );
    private static final Set<String> DELAYED_SEND_RESPONSES = Collections.synchronizedSet(new HashSet<>());
    private static final java.net.http.HttpClient SHARED_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .build();

    static {
        try {
            java.net.URL loc = RequestHandler.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File jarDir = new java.io.File(loc.toURI()).getParentFile();
            if (jarDir != null && jarDir.exists()) {
                System.setProperty("user.dir", jarDir.getAbsolutePath());
            }
        } catch (Exception e) {
            System.err.println("[RequestHandler] 工作目录设置失败: " + e.getMessage());
        }
    }

    private static java.nio.file.Path resolveCodeSourceRoot() {
        try {
            java.net.URL loc = RequestHandler.class.getProtectionDomain().getCodeSource().getLocation();
            java.io.File source = new java.io.File(loc.toURI());
            java.io.File root = source.isDirectory() ? source : source.getParentFile();
            if (root != null) {
                return root.toPath().toAbsolutePath().normalize();
            }
        } catch (Exception ignored) {
        }
        return Paths.get(".").toAbsolutePath().normalize();
    }

    private static long readLongSetting(String propertyKey, String envKey, long defaultValue) {
        String raw = System.getProperty(propertyKey);
        if (raw == null || raw.trim().isEmpty()) {
            raw = System.getenv(envKey);
        }
        if (raw == null || raw.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private java.nio.file.Path resolveAssetFile(String name) {
        if (name == null || name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":") || java.nio.file.Paths.get(name).isAbsolute()) {
            return null;
        }
        List<Map.Entry<java.nio.file.Path, java.nio.file.Path>> pairs = new ArrayList<>();
        if (CODE_SOURCE_ROOT != null) {
            java.nio.file.Path base1 = CODE_SOURCE_ROOT.resolve("assets").toAbsolutePath().normalize();
            java.nio.file.Path base2 = CODE_SOURCE_ROOT.resolve("classes").resolve("assets").toAbsolutePath().normalize();
            java.nio.file.Path parent = CODE_SOURCE_ROOT.getParent();
            pairs.add(Map.entry(base1, base1.resolve(name).toAbsolutePath().normalize()));
            pairs.add(Map.entry(base2, base2.resolve(name).toAbsolutePath().normalize()));
            if (parent != null) {
                java.nio.file.Path base3 = parent.resolve("src").resolve("main").resolve("resources").resolve("assets").toAbsolutePath().normalize();
                pairs.add(Map.entry(base3, base3.resolve(name).toAbsolutePath().normalize()));
            }
        }
        java.nio.file.Path base4 = java.nio.file.Paths.get("target", "classes", "assets").toAbsolutePath().normalize();
        java.nio.file.Path base5 = java.nio.file.Paths.get("src", "main", "resources", "assets").toAbsolutePath().normalize();
        pairs.add(Map.entry(base4, base4.resolve(name).toAbsolutePath().normalize()));
        pairs.add(Map.entry(base5, base5.resolve(name).toAbsolutePath().normalize()));

        for (Map.Entry<java.nio.file.Path, java.nio.file.Path> entry : pairs) {
            java.nio.file.Path baseDir = entry.getKey();
            java.nio.file.Path candidate = entry.getValue();
            try {
                if (candidate.startsWith(baseDir) && java.nio.file.Files.isRegularFile(candidate)) {
                    java.nio.file.Path realPath = candidate.toRealPath();
                    java.nio.file.Path realBase = baseDir.toRealPath();
                    if (realPath.startsWith(realBase) && java.nio.file.Files.isRegularFile(realPath)) {
                        return realPath;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private final SuperAdminService superAdminService = SuperAdminService.getInstance();
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> msgRateLimit = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong lastRateLimitCleanupAt = new java.util.concurrent.atomic.AtomicLong(0L);

    private void cleanExpiredRateLimits(long now) {
        long previous = lastRateLimitCleanupAt.get();
        if (now - previous < 60000L || !lastRateLimitCleanupAt.compareAndSet(previous, now)) {
            return;
        }
        if (!msgRateLimit.isEmpty()) {
            msgRateLimit.entrySet().removeIf(entry -> {
                long[] b = entry.getValue();
                synchronized (b) {
                    return (now - b[1]) > 300000;
                }
            });
        }
    }

    private boolean isSuperAdmin(String uid) {
        return superAdminService.isSuperAdmin(uid);
    }

    private boolean isPrimarySuperAdmin(String uid) {
        return uid != null && uid.equals(superAdminService.getPrimarySuperAdminId());
    }

    private boolean isDeveloper(String uid) {
        if (uid == null) return false;
        User user = UserService.getInstance().getByUserId(uid);
        return user != null && ("陈梓仁".equalsIgnoreCase(user.getUsername())
                || "chenziren".equalsIgnoreCase(user.getUsername()));
    }

    private String featureForPath(String path) {
        if (path == null || "/api/check-feature-ban".equals(path)) return null;
        if (path.startsWith("/api/cloud/") || path.startsWith("/cloud-files/")) return "cloud";
        if (path.startsWith("/api/music/")) return "music";
        if (path.startsWith("/api/videos/")) return "video";
        if (path.startsWith("/api/games/")) return "games";
        if (path.startsWith("/api/moments/") || "/api/moments".equals(path)
                || "/api/user/moments".equals(path)) return "moments";
        if (path.startsWith("/api/note/") || "/api/notes".equals(path)
                || "/api/note".equals(path)) return "notes";
        if (path.startsWith("/api/ai/")) return "ai";
        if (path.equals("/api/users") || path.equals("/api/friends")
                || path.startsWith("/api/friend-requests/")
                || path.equals("/api/send-friend-request")
                || path.equals("/api/handle-friend-request")
                || path.equals("/api/search")) return "discover";
        if (path.equals("/api/messages") || path.equals("/api/events")
                || path.equals("/api/last-messages") || path.equals("/api/messages/paged")
                || path.equals("/api/unread") || path.equals("/api/send-message")
                || path.equals("/api/upload-file") || path.equals("/api/upload-file-stream")
                || path.equals("/api/forward-message") || path.equals("/api/recall-message")
                || path.equals("/api/share/send-card")) return "chat";
        return null;
    }

    private String blockedFeature(User user, String path) {
        if (user == null) return null;
        String feature = featureForPath(path);
        if (feature != null && user.isFeatureBanned(feature)) return feature;
        boolean uploadRoute = path != null && (path.startsWith("/api/upload-file")
                || path.equals("/api/store-file-stream") || path.equals("/api/games/upload-binary"));
        return uploadRoute && user.isFeatureBanned("upload") ? "upload" : null;
    }

    private void rejectInvalidSessionCookies(HttpExchange exchange, String path, String cookieHeader) throws IOException {
        SessionCookieSecurity.clearAllCookies(exchange, cookieHeader);
        exchange.getResponseHeaders().set("X-Session-Integrity", "failed");
        if (path != null && path.startsWith("/api/")) {
            sendJson(exchange, 401, map("error", "登录安全校验失败，需要重新登录",
                    "cookieIntegrityFailed", "true"));
        } else {
            redirect(exchange, "/login?reason=session-security");
        }
    }

    @Override
    public void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath();
            String query = ex.getRequestURI().getQuery();
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
            if ("OPTIONS".equalsIgnoreCase(method)) {
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With");
                ex.getResponseHeaders().add("Access-Control-Max-Age", "3600");
                ex.sendResponseHeaders(204, -1);
                return;
            }
            String cookieHeader = ex.getRequestHeaders().getFirst("Cookie");
            String sid = SessionManager.getInstance().getSessionIdFromCookie(cookieHeader);
            if (!SessionCookieSecurity.validate(cookieHeader, sid)) {
                if (sid != null) SessionManager.getInstance().removeSession(sid);
                rejectInvalidSessionCookies(ex, path, cookieHeader);
                return;
            }
            User me = SessionManager.getInstance().getUser(sid);
            if (sid != null && me == null) {
                rejectInvalidSessionCookies(ex, path, cookieHeader);
                return;
            }
            if (me != null && sid != null) {
                SessionManager.getInstance().refreshSession(sid);
            }
            I18n.CURRENT_LANG.set(me != null && me.getLanguage() != null ? me.getLanguage() : "zh-CN");

            // 封禁用户拦截判定（同时对 GET 与 POST 生效）
            if (me != null && me.isCurrentlyBanned()) {
                boolean allowedWhenBanned = path.equals("/login") || path.equals("/register")
                        || path.equals("/logout") || path.equals("/api/logout")
                        || path.equals("/api/login") || path.equals("/api/register")
                        || path.equals("/api/check-user") || path.equals("/forgot-password")
                        || path.equals("/api/password-recovery/request")
                        || path.startsWith("/assets/")
                        || path.equals("/favicon.ico");
                if (!allowedWhenBanned) {
                    if (path.startsWith("/api/")) {
                        sendJson(ex, 403, map("error", "账户已被封禁", "banned", String.valueOf(true),
                                "banExpiresAt", String.valueOf(me.getBanExpiresAt()),
                                "banRemainingMillis", String.valueOf(me.getBanRemainingMillis()),
                                "banReason", me.getBanReason() != null ? me.getBanReason() : ""));
                    } else {
                        sendText(ex, 403, "账户已被封禁");
                    }
                    return;
                }
            }

            String blockedFeature = blockedFeature(me, path);
            if (blockedFeature != null) {
                Map<String, Object> info = UserService.getInstance()
                        .getFeatureBanInfo(me.getUserId(), blockedFeature);
                sendJson(ex, 403, info != null ? info : map("error", "该功能已被封禁"));
                return;
            }
            
            // 全局未登录拦截（/files/ 将在 serveFile 中进一步做 canAccessFile 精准鉴权）
            if (me == null) {
                boolean isPublic = path.equals("/login") || path.equals("/register") || path.equals("/forgot-password")
                        || path.equals("/api/login") || path.equals("/api/register") || path.equals("/api/check-user")
                        || path.equals("/api/password-recovery/request")
                        || path.equals("/api/announcements") || path.equals("/api/announcements/latest")
                        || path.equals("/api/share/data")
                        || path.startsWith("/assets/")
                        || path.startsWith("/share/")
                        || path.startsWith("/shared-cloud-files/")
                        || path.equals("/favicon.ico");
                if (!isPublic) {
                    if (path.startsWith("/files/")) {
                        // 在 serveFile 中进行 canAccessFile 校验
                    } else if (path.startsWith("/api/")) {
                        sendJson(ex, 401, map("error", "未登录"));
                        return;
                    } else {
                        redirect(ex, "/login");
                        return;
                    }
                }
            }

            try {
                if ("GET".equals(method))
                    handleGet(ex, path, query, me, sid);
                else if ("POST".equals(method)) {
                    if (me != null && me.isFeatureBanned("upload") && path.startsWith("/api/upload-file")) {
                        sendJson(ex, 403, map("error", "上传功能已被封禁"));
                        return;
                    }
                    if ("/api/upload-file-stream".equals(path)) {
                        handleUploadFileStream(ex, ex.getRequestBody(), query, me);
                    } else if ("/api/store-file-stream".equals(path)) {
                        handleStoreFileStream(ex, ex.getRequestBody(), query, me);
                    } else if ("/api/games/upload-binary".equals(path)) {
                        gameHandler.handleUploadGameBinary(ex, ex.getRequestBody(), query, me);
                    } else if ("/api/upload-file-form".equals(path)) {
                        sendJson(ex, 410, map("error", "旧版表单上传已停用，请使用流式上传接口"));
                    } else {
                        handlePost(ex, path, readBody(ex), me, sid);
                    }
                } else
                    sendText(ex, 405, "Method Not Allowed");
            } catch (RequestBodyTooLargeException tooLarge) {
                if (path.startsWith("/api/")) {
                    sendJson(ex, 413, map("error", tooLarge.getMessage()));
                } else {
                    sendText(ex, 413, tooLarge.getMessage());
                }
            } catch (OutOfMemoryError oom) {
                System.err.println("[OOM] 内存不足: " + ex.getRequestURI().getPath());
                try { sendText(ex, 413, "文件过大，内存不足，请上传较小的文件"); } catch (Exception ignored) {}
            } catch (Exception e) {
                e.printStackTrace();
                if (path.startsWith("/api/")) {
                    sendJson(ex, 500, map("error", "服务器内部错误: " + e.getMessage()));
                } else {
                    sendText(ex, 500, "Error: " + e.getMessage());
                }
            }
        } finally {
            I18n.CURRENT_LANG.remove();
        }
    }

    // ======================== GET ========================
    private void handleGet(HttpExchange ex, String path, String query, User me, String sid) throws IOException {
        if (path.startsWith("/files/")) {
            serveFile(ex, path, query, me);
            return;
        }
        if (path.startsWith("/cloud-files/")) {
            serveCloudFile(ex, path, query, me);
            return;
        }
        if (path.startsWith("/shared-cloud-files/")) {
            serveSharedCloudFile(ex, path, query);
            return;
        }
        if (path.startsWith("/assets/")) {
            serveAsset(ex, path);
            return;
        }
        if ("/favicon.ico".equals(path)) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        if ("/".equals(path) || "/index".equals(path)) {
            redirect(ex, me != null ? "/chat" : "/login");
            return;
        }
        if ("/login".equals(path)) {
            if (me != null) {
                redirect(ex, "/chat");
                return;
            }
            send(ex, 200, "text/html;charset=utf-8", pageRenderer.buildLoginPage());
            return;
        }
        if ("/forgot-password".equals(path)) {
            send(ex, 200, "text/html;charset=utf-8", pageRenderer.buildForgotPasswordPage());
            return;
        }
        if ("/tools".equals(path)) {
            redirect(ex, "/games?app=builtin-qr");
            return;
        }
        if (isAppPagePath(path)) {
            if (me == null) {
                redirect(ex, "/login");
                return;
            }
            send(ex, 200, "text/html;charset=utf-8", pageRenderer.buildChatPage(me));
            return;
        }
        // 分享链接页面（无需登录即可访问）
        if (path.startsWith("/share/")) {
            send(ex, 200, "text/html;charset=utf-8", pageRenderer.buildSharePage(path, me));
            return;
        }
        switch (path) {
            case "/favicon.ico":
                ex.sendResponseHeaders(204, -1);
                break;
            case "/api/me":
                handleGetMe(ex, me);
                break;
            default:
                if (me != null && me.isCurrentlyBanned() && path.startsWith("/api/")) {
                    sendJson(ex, 403, map("error", "账户已被封禁", "banned", String.valueOf(true),
                            "banExpiresAt", String.valueOf(me.getBanExpiresAt()),
                            "banRemainingMillis", String.valueOf(me.getBanRemainingMillis()),
                            "banReason", me.getBanReason() != null ? me.getBanReason() : ""));
                    return;
                }
                handleGetApiRoute(ex, path, query, me, sid);
                break;
        }
    }

    private void handleGetApiRoute(HttpExchange ex, String path, String query, User me, String sid) throws IOException {
        switch (path) {
            case "/api/messages":
                handleGetMessages(ex, query, me);
                break;
            case "/api/events":
                handleGetEvents(ex, query, me);
                break;
            case "/api/last-messages":
                handleGetLastMessages(ex, me);
                break;
            case "/api/messages/paged":
                handleGetMessagesPaged(ex, query, me);
                break;
            case "/api/users":
                handleGetUsers(ex, me);
                break;
            case "/api/friend-requests/sent":
                handleGetSentReqs(ex, me);
                break;
            case "/api/friend-requests/received":
                handleGetRecvReqs(ex, me);
                break;
            case "/api/friends":
                handleGetFriends(ex, me);
                break;
            case "/api/moments":
                handleGetMoments(ex, query, me);
                break;
            case "/api/groups":
                handleGetGroups(ex, me);
                break;
            case "/api/group/info":
                handleGetGroupInfo(ex, query, me);
                break;
            case "/api/games":
                gameHandler.handleGetGames(ex, me);
                break;
            case "/api/games/pending":
                gameHandler.handleGetPendingGames(ex, me);
                break;
            case "/api/user/profile":
                handleGetUserProfile(ex, query, me);
                break;
            case "/api/user/moments":
                handleGetUserMoments(ex, query, me);
                break;
            case "/api/stickers":
                handleGetStickers(ex, me);
                break;
            case "/api/unread":
                handleGetUnread(ex, query, me);
                break;
            case "/api/notes":
                handleGetNotes(ex, me);
                break;
            case "/api/note":
                handleGetNote(ex, query, me);
                break;
            case "/api/feedback/list":
                handleGetFeedbackTickets(ex, me);
                break;
            case "/api/announcements":
                handleGetAnnouncements(ex);
                break;
            case "/api/announcements/latest":
                handleGetLatestAnnouncement(ex);
                break;
            case "/api/public-room/config":
                handleGetPublicRoomConfig(ex, me);
                break;
            case "/api/cloud/list":
                cloudHandler.handleGetCloudList(ex, query, me);
                break;
            case "/api/cloud/recycle":
                cloudHandler.handleGetCloudRecycle(ex, me);
                break;
            case "/api/cloud/shares":
                cloudHandler.handleGetCloudShares(ex, me);
                break;
            case "/api/cloud/downloads":
                cloudHandler.handleGetCloudDownloads(ex, me);
                break;
            case "/api/cloud/tasks":
                cloudHandler.handleGetCloudTasks(ex, me);
                break;
            case "/api/cloud/zip-tree":
                cloudHandler.handleGetCloudZipTree(ex, query, me);
                break;
            case "/api/cloud/favorites":
                cloudHandler.handleGetCloudFavorites(ex, me);
                break;
            case "/api/cloud/safebox":
                cloudHandler.handleGetCloudSafebox(ex, me);
                break;
            case "/api/music/tracks":
                musicHandler.handleGetTracks(ex, me);
                break;
            case "/api/music/playlists":
                musicHandler.handleGetPlaylists(ex, me);
                break;
            case "/api/music/recommend":
                musicHandler.handleGetRecommend(ex, me);
                break;
            case "/api/music/extract-meta":
                musicHandler.handleExtractMeta(ex, query, me);
                break;
            case "/api/music/comments":
                musicHandler.handleGetComments(ex, query, me);
                break;
            case "/api/videos/list":
                handleGetVideos(ex, me);
                break;
            case "/api/videos/categories":
                handleGetVideoCategories(ex, me);
                break;
            case "/api/videos/comments":
                handleGetVideoComments(ex, query, me);
                break;
            case "/api/videos/danmaku":
                handleGetVideoDanmaku(ex, query, me);
                break;
            case "/api/search":
                handleSearch(ex, query, me);
                break;
            case "/api/ai/models":
                aiHandler.handleGetAiModels(ex, me);
                break;
            case "/api/ai/conversations":
                aiHandler.handleGetAiConversations(ex, me);
                break;
            case "/api/ai/messages":
                aiHandler.handleGetAiMessages(ex, query, me);
                break;
            case "/api/ai/tasks":
                aiHandler.handleGetAiTasks(ex, me);
                break;
            case "/api/share/data":
                handleGetShareData(ex, query, me);
                break;
            case "/api/admin/overview":
                adminHandler.handleGetAdminOverview(ex, me);
                break;
            case "/api/admin/users":
                adminHandler.handleGetAdminUsers(ex, query, me);
                break;
            case "/api/admin/groups":
                adminHandler.handleGetAdminGroups(ex, me);
                break;
            case "/api/admin/super-admins":
                adminHandler.handleGetSuperAdmins(ex, me);
                break;
            case "/api/admin/password-recovery":
                adminHandler.handleGetPasswordRecoveryRequests(ex, me);
                break;
            case "/api/check-feature-ban":
                adminHandler.handleCheckFeatureBan(ex, query, me);
                break;
            case "/logout":
                doLogout(ex, sid, me);
                break;
            default:
                send(ex, 404, "text/html;charset=utf-8",
                        pageRenderer.buildStatusPage("404", "页面不存在", "你访问的页面不存在，可能已经移动、删除，或者地址写错了。", "/chat", "返回首页"));
        }
    }

    private boolean isAppPagePath(String path) {
        return "/chat".equals(path)
                || "/discover".equals(path)
                || "/moments".equals(path)
                || "/games".equals(path)
                || path.startsWith("/game/")
                || "/profile".equals(path)
                || "/notes".equals(path)
                || "/server-admin".equals(path)
                || "/cloud".equals(path)
                || "/ai".equals(path)
                || "/music".equals(path)
                || "/videos".equals(path)
                || "/feedback".equals(path)
                || path.startsWith("/chat/private/")
                || path.startsWith("/chat/group/")
                || path.startsWith("/users/");
    }

    // ======================== POST ========================
    private void handlePost(HttpExchange ex, String path, String body, User me, String sid) throws IOException {
        if (me != null && me.isCurrentlyBanned()
                && !"/api/check-user".equals(path) && !"/api/register".equals(path)
                && !"/api/login".equals(path) && !"/api/logout".equals(path)
                && !"/api/admin/ban-user".equals(path) && !"/api/admin/unban-user".equals(path)) {
            sendJson(ex, 403, map("error", "账户已被封禁", "banned", String.valueOf(true),
                    "banExpiresAt", String.valueOf(me.getBanExpiresAt()),
                    "banRemainingMillis", String.valueOf(me.getBanRemainingMillis()),
                    "banReason", me.getBanReason() != null ? me.getBanReason() : ""));
            return;
        }
        switch (path) {
            case "/api/check-user":
                handleCheckUser(ex, body);
                break;
            case "/api/register":
                handleRegister(ex, body);
                break;
            case "/api/password-recovery/request":
                handleCreatePasswordRecoveryRequest(ex, body);
                break;
            case "/api/login":
                handleLogin(ex, body);
                break;
            case "/api/send-message":
                handleSendMessage(ex, body, me);
                break;
            case "/api/upload-file":
                handleUploadFile(ex, body, me);
                break;
            case "/api/forward-message":
                handleForwardMessage(ex, body, me);
                break;
            case "/api/forward-to-moment":
                handleForwardToMoment(ex, body, me);
                break;
            case "/api/send-friend-request":
                handleSendFriendReq(ex, body, me);
                break;
            case "/api/handle-friend-request":
                handleHandleFriendReq(ex, body, me);
                break;
            case "/api/recall-message":
                handleRecallMessage(ex, body, me);
                break;
            case "/api/profile/delete-self":
                handleDeleteSelf(ex, body, me);
                break;
            case "/api/admin/delete-user":
                adminHandler.handleAdminDeleteUser(ex, body, me);
                break;
            case "/api/profile/update":
                handleUpdateProfile(ex, body, me);
                break;
            case "/api/profile/verify-password":
                handleVerifyPassword(ex, body, me);
                break;
            case "/api/profile/avatar":
                handleUpdateAvatar(ex, body, me);
                break;
            case "/api/profile/skin":
                handleUpdateSkin(ex, body, me);
                break;
            case "/api/moments/post":
                handlePostMoment(ex, body, me);
                break;
            case "/api/moments/like":
                handleLikeMoment(ex, body, me);
                break;
            case "/api/moments/comment":
                handleCommentMoment(ex, body, me);
                break;
            case "/api/moments/delete":
                handleDeleteMoment(ex, body, me);
                break;
            case "/api/group/create":
                handleCreateGroup(ex, body, me);
                break;
            case "/api/group/invite":
                handleGroupInvite(ex, body, me);
                break;
            case "/api/group/kick":
                handleGroupKick(ex, body, me);
                break;
            case "/api/group/set-admin":
                handleGroupSetAdmin(ex, body, me);
                break;
            case "/api/group/transfer-owner":
                handleGroupTransferOwner(ex, body, me);
                break;
            case "/api/group/join-as-admin":
                handleGroupJoinAsAdmin(ex, body, me);
                break;
            case "/api/group/force-add-member":
                handleGroupForceAddMember(ex, body, me);
                break;
            case "/api/group/leave":
                handleGroupLeave(ex, body, me);
                break;
            case "/api/group/rename":
                handleGroupRename(ex, body, me);
                break;
            case "/api/group/mute":
                handleGroupMute(ex, body, me);
                break;
            case "/api/group/mute-all":
                handleGroupMuteAll(ex, body, me);
                break;
            case "/api/group/delete-old-messages":
                handleGroupDeleteOldMessages(ex, body, me);
                break;
            case "/api/group/icon":
                handleGroupIcon(ex, body, me);
                break;
            case "/api/group/description":
                handleGroupDescription(ex, body, me);
                break;
            case "/api/stickers/add":
                handleAddSticker(ex, body, me);
                break;
            case "/api/games/upload":
                gameHandler.handleUploadGame(ex, body, me);
                break;
            case "/api/games/create":
                gameHandler.handleCreateGame(ex, body, me);
                break;
            case "/api/games/publish-version":
                gameHandler.handlePublishGameVersion(ex, body, me);
                break;
            case "/api/games/update-meta":
                gameHandler.handleUpdateGameMeta(ex, body, me);
                break;
            case "/api/games/upload-asset":
                gameHandler.handleUploadGameAsset(ex, body, me);
                break;
            case "/api/games/visit":
                gameHandler.handleRecordGameVisit(ex, body, me);
                break;
            case "/api/games/approve":
                gameHandler.handleApproveGame(ex, body, me);
                break;
            case "/api/logout":
                handleApiLogout(ex, sid, me);
                break;
            case "/api/note/create":
                handleCreateNote(ex, body, me);
                break;
            case "/api/note/update":
                handleUpdateNote(ex, body, me);
                break;
            case "/api/note/delete":
                handleDeleteNote(ex, body, me);
                break;
            case "/api/note/share":
                handleShareNote(ex, body, me);
                break;
            case "/api/feedback/create":
                handleCreateFeedbackTicket(ex, body, me);
                break;
            case "/api/feedback/status":
                handleUpdateFeedbackStatus(ex, body, me);
                break;
            case "/api/public-room/mute-all":
            case "/api/public-room/toggle-all-mute":
                handleSetPublicRoomMuteAll(ex, body, me);
                break;
            case "/api/public-room/add-admin":
                handleAddPublicRoomAdmin(ex, body, me);
                break;
            case "/api/public-room/remove-admin":
                handleRemovePublicRoomAdmin(ex, body, me);
                break;
            case "/api/public-room/description":
                handleSetPublicRoomDescription(ex, body, me);
                break;
            case "/api/public-room/delete-old-messages":
                handleDeleteOldPublicRoomMessages(ex, body, me);
                break;
            case "/api/public-room/mute-user":
                handleMutePublicRoomUser(ex, body, me);
                break;
            case "/api/public-room/unmute-user":
                handleUnmutePublicRoomUser(ex, body, me);
                break;
            case "/api/cloud/create-folder":
                cloudHandler.handleCreateCloudFolder(ex, body, me);
                break;
            case "/api/cloud/create-file":
                cloudHandler.handleCreateCloudFile(ex, body, me);
                break;
            case "/api/cloud/rename":
                cloudHandler.handleRenameCloudEntry(ex, body, me);
                break;
            case "/api/cloud/delete":
                cloudHandler.handleDeleteCloudEntry(ex, body, me);
                break;
            case "/api/cloud/restore":
                cloudHandler.handleRestoreCloudEntry(ex, body, me);
                break;
            case "/api/cloud/purge":
                cloudHandler.handlePurgeCloudEntry(ex, body, me);
                break;
            case "/api/cloud/share":
                cloudHandler.handleShareCloudEntry(ex, body, me);
                break;
            case "/api/cloud/save-share":
                cloudHandler.handleSaveCloudShare(ex, body, me);
                break;
            case "/api/cloud/clear-downloads":
                cloudHandler.handleClearCloudDownloads(ex, me);
                break;
            case "/api/cloud/move":
                cloudHandler.handleMoveCloudEntry(ex, body, me);
                break;
            case "/api/cloud/copy":
                cloudHandler.handleCopyCloudEntry(ex, body, me);
                break;
            case "/api/cloud/import-stored":
                cloudHandler.handleImportStoredCloudFile(ex, body, me);
                break;
            case "/api/cloud/unzip":
                cloudHandler.handleUnzipCloudEntry(ex, body, me);
                break;
            case "/api/cloud/compress":
                cloudHandler.handleCompressCloudEntry(ex, body, me);
                break;
            case "/api/cloud/toggle-favorite":
                cloudHandler.handleToggleCloudFavorite(ex, body, me);
                break;
            case "/api/cloud/toggle-safebox":
                cloudHandler.handleToggleCloudSafebox(ex, body, me);
                break;
            case "/api/cloud/compress-batch":
                cloudHandler.handleCompressCloudBatch(ex, body, me);
                break;
            case "/api/music/upload":
                musicHandler.handleUpload(ex, body, me);
                break;
            case "/api/music/create-playlist":
                musicHandler.handleCreatePlaylist(ex, body, me);
                break;
            case "/api/music/toggle-playlist":
                musicHandler.handleTogglePlaylist(ex, body, me);
                break;
            case "/api/music/play":
                musicHandler.handlePlay(ex, body, me);
                break;
            case "/api/music/comment":
                musicHandler.handleComment(ex, body, me);
                break;
            case "/api/music/update":
                musicHandler.handleUpdate(ex, body, me);
                break;
            case "/api/music/delete":
                musicHandler.handleDelete(ex, body, me);
                break;
            case "/api/music/import-zip":
                musicHandler.handleImportZip(ex, body, me);
                break;
            case "/api/videos/create-category":
                handleCreateVideoCategory(ex, body, me);
                break;
            case "/api/videos/upload":
                handleUploadVideo(ex, body, me);
                break;
            case "/api/videos/play":
                handlePlayVideo(ex, body, me);
                break;
            case "/api/videos/comment":
                handleCreateVideoComment(ex, body, me);
                break;
            case "/api/videos/danmaku":
                handleCreateVideoDanmaku(ex, body, me);
                break;
            case "/api/ai/conversation/create":
                aiHandler.handleCreateAiConversation(ex, body, me);
                break;
            case "/api/ai/conversation/update":
                aiHandler.handleUpdateAiConversation(ex, body, me);
                break;
            case "/api/ai/conversation/delete":
                aiHandler.handleDeleteAiConversation(ex, body, me);
                break;
            case "/api/ai/send":
                aiHandler.handleSendAiPrompt(ex, body, me);
                break;
            case "/api/ai/send-stream":
                aiHandler.handleSendAiPromptStream(ex, body, me);
                break;
            case "/api/share/send-card":
                handleSendShareCard(ex, body, me);
                break;
            case "/api/announcements/create":
                handleCreateAnnouncement(ex, body, me);
                break;
            case "/api/admin/add-super-admin":
                adminHandler.handleAddSuperAdmin(ex, body, me);
                break;
            case "/api/admin/remove-super-admin":
                adminHandler.handleRemoveSuperAdmin(ex, body, me);
                break;
            case "/api/admin/set-user-tags":
                adminHandler.handleSetUserTags(ex, body, me);
                break;
            case "/api/admin/password-recovery/status":
                adminHandler.handleUpdatePasswordRecoveryStatus(ex, body, me);
                break;
            case "/api/admin/quit-super-admin":
                adminHandler.handleQuitSuperAdmin(ex, me);
                break;
            case "/api/admin/delete-group":
                adminHandler.handleDeleteGroupByAdmin(ex, body, me);
                break;
            case "/api/admin/force-logout":
                adminHandler.handleForceLogoutByAdmin(ex, body, me);
                break;
            case "/api/admin/set-user-password":
                adminHandler.handleSetUserPasswordByAdmin(ex, body, me);
                break;
            case "/api/admin/ban-user":
                adminHandler.handleBanUser(ex, body, me);
                break;
            case "/api/admin/unban-user":
                adminHandler.handleUnbanUser(ex, body, me);
                break;
            case "/api/admin/set-feature-ban":
                adminHandler.handleSetFeatureBan(ex, body, me);
                break;
            case "/api/check-in":
                handleCheckIn(ex, me);
                break;
            case "/api/tutorial/complete":
                handleTutorialComplete(ex, me);
                break;
            case "/api/admin/grant-exp":
                adminHandler.handleAdminGrantExp(ex, body, me);
                break;
            case "/api/admin/set-level":
                adminHandler.handleAdminSetLevel(ex, body, me);
                break;
            case "/api/admin/set-user-quota":
                adminHandler.handleSetUserQuota(ex, body, me);
                break;
            case "/api/admin/set-ai-tokens":
                adminHandler.handleSetAiTokens(ex, body, me);
                break;
            case "/api/tools/decode-qr":
                handleDecodeQR(ex, body, me);
                break;
            case "/api/tools/encode-qr":
                handleEncodeQR(ex, body, me);
                break;
            default:
                sendText(ex, 404, "接口不存在");
        }
    }

    // ======================== 静态资源服务 ========================
    private void serveAsset(HttpExchange ex, String path) throws IOException {
        String name = path.substring("/assets/".length());
        if (name.contains("..") || name.startsWith("/") || name.startsWith("\\") || name.contains(":")) {
            sendText(ex, 400, "非法请求");
            return;
        }
        java.nio.file.Path assetFile = resolveAssetFile(name);
        if (assetFile != null) {
            byte[] data = Files.readAllBytes(assetFile);
            String mime = name.endsWith(".js") ? "application/javascript;charset=utf-8"
                    : name.endsWith(".css") ? "text/css;charset=utf-8"
                    : name.endsWith(".png") ? "image/png"
                    : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
            return;
        }
        try (InputStream is = getClass().getResourceAsStream("/assets/" + name)) {
            if (is == null) {
                sendText(ex, 404, "资源不存在");
                return;
            }
            byte[] data = is.readAllBytes();
            String mime = name.endsWith(".js") ? "application/javascript;charset=utf-8"
                    : name.endsWith(".css") ? "text/css;charset=utf-8"
                    : name.endsWith(".png") ? "image/png"
                    : "application/octet-stream";
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "max-age=3600");
            ex.sendResponseHeaders(200, data.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
    }

    // ======================== 文件服务 ========================
    private boolean canAccessFile(String storedName, User me) {
        return StoredFileAccess.canAccess(storedName, me);
    }

    private void serveFile(HttpExchange ex, String path, String query, User me) throws IOException {
        String stored = path.substring("/files/".length());
        if (stored.contains("..") || stored.contains("/") || stored.contains("\\") || stored.contains(":")) {
            sendText(ex, 400, "非法请求");
            return;
        }

        if (!canAccessFile(stored, me)) {
            if (me == null) {
                sendJson(ex, 401, map("error", "未登录或无权访问私密文件"));
            } else {
                sendJson(ex, 403, map("error", "无权访问该私密文件"));
            }
            return;
        }
        try {
            StoredFileMetadata metadata = FileStore.getInstance().getMetadata(stored);
            if (metadata == null) {
                sendText(ex, 404, "文件不存在");
                return;
            }
            String mime = metadata.getContentType() != null ? metadata.getContentType() : FileStore.guessMime(stored);
            String requestedName = parseQuery(query).get("name");
            String displayName = requestedName != null && !requestedName.trim().isEmpty()
                    ? FileStore.normalizeUploadedFileName(requestedName)
                    : FileStore.normalizeUploadedFileName(metadata.getOriginalFileName());
            boolean forceDownload = "1".equals(parseQuery(query).get("download"));
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
            applyActiveContentSandbox(ex, mime);
            boolean inline = mime.startsWith("image/") || mime.startsWith("video/")
                    || mime.startsWith("audio/") || mime.startsWith("text/html") || mime.startsWith("text/markdown");
            if (inline && !forceDownload) {
                ex.getResponseHeaders().set("Content-Disposition", "inline");
            } else {
                String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + asciiFileName(displayName) + "\"; filename*=UTF-8''" + encoded);
            }
            ex.sendResponseHeaders(200, metadata.getSize());
            try (InputStream in = FileStore.getInstance().openStream(stored);
                 OutputStream os = ex.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } catch (Exception ignored) {
            /* 客户端断开或IO错误，忽略 */ }
    }

    private void serveCloudFile(HttpExchange ex, String path, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String sub = path.substring("/cloud-files/".length());
        if (sub.contains("..") || sub.startsWith("/") || sub.startsWith("\\") || sub.contains(":")) {
            sendText(ex, 400, "非法请求");
            return;
        }
        java.nio.file.Path baseDir = Paths.get("chatserver", "cloud-files").toAbsolutePath().normalize();
        java.nio.file.Path filePath = baseDir.resolve(sub).toAbsolutePath().normalize();
        if (!filePath.startsWith(baseDir)) {
            sendText(ex, 400, "非法请求");
            return;
        }
        int firstSlash = sub.indexOf('/');
        if (firstSlash <= 0) {
            sendText(ex, 400, "非法路径格式");
            return;
        }
        String ownerId = sub.substring(0, firstSlash);
        boolean isOwner = me.getUserId().equals(ownerId);
        boolean isAdmin = SuperAdminService.getInstance().isSuperAdmin(me.getUserId()) ||
                          Objects.equals(SuperAdminService.getInstance().getPrimarySuperAdminId(), me.getUserId());
        if (!isOwner && !isAdmin) {
            sendText(ex, 403, "无权访问此私有文件");
            return;
        }
        if (!Files.isRegularFile(filePath)) {
            sendText(ex, 404, "文件不存在");
            return;
        }
        try {
            String mime = Files.probeContentType(filePath);
            if (mime == null) mime = "application/octet-stream";
            String requestedName = parseQuery(query).get("name");
            String displayName = requestedName != null && !requestedName.trim().isEmpty()
                    ? requestedName : filePath.getFileName().toString();
            boolean forceDownload = "1".equals(parseQuery(query).get("download"));
            ex.getResponseHeaders().set("Content-Type", mime);
            ex.getResponseHeaders().set("Cache-Control", "max-age=86400");
            applyActiveContentSandbox(ex, mime);
            boolean inline = mime.startsWith("image/") || mime.startsWith("video/")
                    || mime.startsWith("audio/") || mime.startsWith("text/html") || mime.startsWith("text/markdown");
            if (inline && !forceDownload) {
                ex.getResponseHeaders().set("Content-Disposition", "inline");
            } else {
                String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=\"" + asciiFileName(displayName) + "\"; filename*=UTF-8''" + encoded);
            }
            long fileSize = Files.size(filePath);
            ex.sendResponseHeaders(200, fileSize);
            try (InputStream in = Files.newInputStream(filePath);
                 OutputStream os = ex.getResponseBody()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
            }
        } catch (Exception ignored) {
            /* 客户端断开或IO错误，忽略 */ }
    }

    private void serveSharedCloudFile(HttpExchange ex, String path, String query) throws IOException {
        String shareId = path.substring("/shared-cloud-files/".length());
        if (shareId.isBlank() || shareId.contains("..") || shareId.contains("/")
                || shareId.contains("\\") || shareId.contains(":")) {
            sendText(ex, 400, "非法请求");
            return;
        }
        CloudShareLink share = CloudService.getInstance().getShare(shareId);
        CloudEntry entry = share != null ? CloudService.getInstance().getEntry(share.getEntryId()) : null;
        if (share == null || entry == null || entry.isDeleted() || entry.isFolder()
                || !Objects.equals(share.getOwnerId(), entry.getOwnerId())
                || entry.getStoredName() == null || entry.getStoredName().isBlank()) {
            sendText(ex, 404, "分享文件不存在");
            return;
        }
        java.nio.file.Path baseDir = Paths.get("chatserver", "cloud-files", entry.getOwnerId())
                .toAbsolutePath().normalize();
        java.nio.file.Path filePath = baseDir.resolve(entry.getStoredName()).toAbsolutePath().normalize();
        if (!filePath.startsWith(baseDir) || !Files.isRegularFile(filePath)) {
            sendText(ex, 404, "分享文件不存在");
            return;
        }
        String mime = entry.getContentType();
        if (mime == null || mime.isBlank()) mime = Files.probeContentType(filePath);
        if (mime == null) mime = "application/octet-stream";
        String displayName = entry.getName() != null && !entry.getName().isBlank()
                ? entry.getName() : filePath.getFileName().toString();
        boolean forceDownload = "1".equals(parseQuery(query).get("download"));
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.getResponseHeaders().set("Cache-Control", "private, max-age=300");
        applyActiveContentSandbox(ex, mime);
        boolean inline = mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/");
        if (inline && !forceDownload) {
            ex.getResponseHeaders().set("Content-Disposition", "inline");
        } else {
            String encoded = URLEncoder.encode(displayName, StandardCharsets.UTF_8).replace("+", "%20");
            ex.getResponseHeaders().set("Content-Disposition",
                    "attachment; filename=\"" + asciiFileName(displayName) + "\"; filename*=UTF-8''" + encoded);
        }
        ex.sendResponseHeaders(200, Files.size(filePath));
        try (InputStream input = Files.newInputStream(filePath); OutputStream output = ex.getResponseBody()) {
            input.transferTo(output);
        }
    }

    private void applyActiveContentSandbox(HttpExchange ex, String mime) {
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        String normalized = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("text/html") || normalized.startsWith("application/xhtml+xml")
                || normalized.startsWith("image/svg+xml")) {
            ex.getResponseHeaders().set("Content-Security-Policy",
                    "sandbox allow-scripts allow-forms allow-modals allow-pointer-lock allow-downloads");
        }
    }

    // ======================== API实现 ========================
    private void handleCheckUser(HttpExchange ex, String body) throws IOException {
        Map<String, String> p = parseJson(body);
        String u = p.get("username");
        if (u != null && UserService.getInstance().existsByUsername(u.trim())) {
            // 旧账号可能早于当前用户名规则创建，必须继续允许其进入登录流程。
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("exists", true);
            sendJson(ex, 200, result);
            return;
        }
        String validation = UserService.validateUsername(u);
        if (validation != null) {
            sendJson(ex, 400, map("error", validation));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exists", UserService.getInstance().existsByUsername(u.trim()));
        sendJson(ex, 200, result);
    }

    private void handleRegister(HttpExchange ex, String body) throws IOException {
        Map<String, String> p = parseJson(body);
        String username = p.get("username"), nickname = p.get("nickname"),
                password = p.get("password"), repeat = p.get("repeatPassword");
        if (username == null || nickname == null || password == null || repeat == null) {
            sendJson(ex, 400, map("error", "昵称和密码不能为空"));
            return;
        }
        if (!password.equals(repeat)) {
            sendJson(ex, 400, map("error", "两次密码不一致"));
            return;
        }
        if (UserService.getInstance().existsByUsername(username.trim())) {
            String message = ("陈梓仁".equalsIgnoreCase(username.trim()) || "chenziren".equalsIgnoreCase(username.trim()))
                    ? "陈梓仁账户已创建，请返回登录"
                    : "用户名已占用";
            sendJson(ex, 400, map("error", message));
            return;
        }
        String usernameError = UserService.validateUsername(username);
        String nicknameError = UserService.validateNickname(nickname);
        String passwordError = UserService.getInstance().validatePassword(password);
        if (usernameError != null || nicknameError != null || passwordError != null) {
            sendJson(ex, 400, map("error", usernameError != null ? usernameError
                    : nicknameError != null ? nicknameError : passwordError));
            return;
        }
        // 生成随机 userId：6位数字(100000-999999), 用完则8位(10000000-99999999)
        String autoUserId = generateRandomUserId();
        boolean ok = UserService.getInstance().register(username.trim(), nickname.trim(), autoUserId, password);
        if (ok) {
            User u = UserService.getInstance().getByUsername(username.trim());
            String s = SessionManager.getInstance().createSession(u);
            sendSessionCookie(ex, s);
            sendJson(ex, 200, map("success", "true", "nickname", u.getNickname()));
        } else
            sendJson(ex, 400, map("error", "注册失败，用户名已存在"));
    }

    private String generateRandomUserId() {
        // 先尝试 6 位随机数 (100000-999999)
        java.util.Set<String> allIds = UserService.getInstance().getAllUsers().stream()
                .map(u -> u.getUserId()).collect(java.util.stream.Collectors.toSet());
        Random rand = new Random();
        for (int attempt = 0; attempt < 200; attempt++) {
            int id = 100000 + rand.nextInt(900000);
            String sid = String.valueOf(id);
            if (!allIds.contains(sid)) return sid;
        }
        // 6 位用完，用 8 位随机数 (10000000-99999999)
        for (int attempt = 0; attempt < 200; attempt++) {
            int id = 10000000 + rand.nextInt(90000000);
            String sid = String.valueOf(id);
            if (!allIds.contains(sid)) return sid;
        }
        // 极端情况：时间戳结合循环自增，且必须通过 allIds 防重碰撞校验
        long now = System.currentTimeMillis();
        for (int offset = 0; offset < 10000; offset++) {
            String candidate = String.valueOf(now + offset);
            if (candidate.length() > 8) {
                candidate = candidate.substring(candidate.length() - 8);
            }
            if (!allIds.contains(candidate)) return candidate;
        }
        String uuidFallback;
        do {
            uuidFallback = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        } while (allIds.contains(uuidFallback));
        return uuidFallback;
    }

    private void handleLogin(HttpExchange ex, String body) throws IOException {
        Map<String, String> p = parseJson(body);
        User u = UserService.getInstance().login(p.get("username"), p.get("password"));
        if (u != null) {
            if (u.isCurrentlyBanned()) {
                Map<String, Object> banInfo = new LinkedHashMap<>();
                banInfo.put("error", "账户已被封禁");
                banInfo.put("banned", true);
                banInfo.put("banReason", u.getBanReason() != null ? u.getBanReason() : "");
                if (u.getBanExpiresAt() > 0) {
                    banInfo.put("banExpiresAt", u.getBanExpiresAt());
                    banInfo.put("banRemainingMillis", u.getBanRemainingMillis());
                } else {
                    banInfo.put("banExpiresAt", 0);
                    banInfo.put("permanent", true);
                }
                sendJson(ex, 403, banInfo);
                return;
            }
            String s = SessionManager.getInstance().createSession(u);
            sendSessionCookie(ex, s);
            sendJson(ex, 200, map("success", "true", "nickname", u.getNickname()));
        } else
            sendJson(ex, 401, map("error", "用户名或密码错误"));
    }

    private boolean canUserReadRoom(User me, String room) {
        if (me == null) return false;
        if (room == null || room.trim().isEmpty() || "public".equals(room)) return true;

        String normalized = room;
        if (!room.startsWith("public") && !room.startsWith("group_") && !room.startsWith("private_")) {
            if (UserService.getInstance().getByUserId(room) == null) return false;
            normalized = MessageService.normalizePrivateRoomId(me.getUserId(), room);
        }

        if (normalized.startsWith("private_")) {
            return MessageService.isPrivateRoomParticipant(normalized, me.getUserId());
        } else if (normalized.startsWith("group_")) {
            String groupId = normalized.substring("group_".length());
            Group g = GroupService.getInstance().getGroup(groupId);
            return g != null && (g.isMember(me.getUserId()) || isSuperAdmin(me.getUserId()));
        } else if (normalized.startsWith("g")) {
            Group g = GroupService.getInstance().getGroup(normalized);
            return g != null && (g.isMember(me.getUserId()) || isSuperAdmin(me.getUserId()));
        }
        return false;
    }

    private String normalizeWritableRoom(User me, String requestedRoom) {
        if (me == null) return null;
        String room = requestedRoom == null || requestedRoom.trim().isEmpty()
                ? "public" : requestedRoom.trim();
        if ("public".equals(room)) return room;
        if (room.startsWith("group_")) {
            Group group = GroupService.getInstance().getGroup(room.substring("group_".length()));
            return group != null && group.isMember(me.getUserId()) ? room : null;
        }
        if (room.startsWith("private_")) {
            String peerId = MessageService.getPrivateRoomPeer(room, me.getUserId());
            return peerId != null && UserService.getInstance().getByUserId(peerId) != null ? room : null;
        }
        if (room.startsWith("public") || room.startsWith("group_") || room.contains("/")) return null;
        User peer = UserService.getInstance().getByUserId(room);
        return peer != null ? MessageService.normalizePrivateRoomId(me.getUserId(), peer.getUserId()) : null;
    }

    private boolean ensureRoomWriteAllowed(HttpExchange ex, User me, String room) throws IOException {
        if (room == null) {
            sendJson(ex, 403, map("error", "无权向该聊天房间发送消息"));
            return false;
        }
        if (room.startsWith("private_")) {
            if (!MessageService.isPrivateRoomParticipant(room, me.getUserId())) {
                sendJson(ex, 403, map("error", "你不是该私聊的参与者"));
                return false;
            }
            return true;
        }
        if (room.startsWith("group_")) {
            Group group = GroupService.getInstance().getGroup(room.substring("group_".length()));
            if (group == null || !group.isMember(me.getUserId())) {
                sendJson(ex, 403, map("error", "你不在该群"));
                return false;
            }
            if (group.isMuted(me.getUserId())) {
                sendJson(ex, 403, map("error", "你已被禁言"));
                return false;
            }
            if (group.isAllMuted() && !group.isOwner(me.getUserId())
                    && (group.getAdmins() == null || !group.getAdmins().contains(me.getUserId()))) {
                sendJson(ex, 403, map("error", "全员禁言中，仅群主和管理员可发言"));
                return false;
            }
            return true;
        }
        PublicRoomConfig config = PublicRoomService.getInstance().getConfig();
        if (config.isAllMuted() && !PublicRoomService.getInstance().isAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "全员禁言中，仅管理员可发言"));
            return false;
        }
        if (config.getMutedUserIds().contains(me.getUserId())) {
            sendJson(ex, 403, map("error", "你已被禁言"));
            return false;
        }
        return true;
    }

    private void handleGetMessages(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseQuery(query);
        String room = p.getOrDefault("room", "public");
        if (!canUserReadRoom(me, room)) {
            sendJson(ex, 403, map("error", "无权访问该聊天房间"));
            return;
        }
        List<com.chat.model.Message> msgs = MessageService.getInstance().getMessagesSince(
                room, parseLong(p.get("since"), 0), me.getUserId());
        List<Map<String, Object>> result = msgs.stream().map(this::mapMessage).collect(Collectors.toList());
        sendJson(ex, 200, result);
    }

    // ===== 事件流 =====
    private static final java.util.Deque<Map<String, Object>> EVENT_QUEUE = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final java.util.concurrent.atomic.AtomicLong EVENT_ID = new java.util.concurrent.atomic.AtomicLong(
            0);
    private static final java.util.concurrent.atomic.AtomicInteger EVENT_QUEUE_SIZE = new java.util.concurrent.atomic.AtomicInteger(0);

    private static void pushEvent(String type, Map<String, Object> data) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("eid", String.valueOf(EVENT_ID.incrementAndGet()));
        ev.put("type", type);
        ev.putAll(data);
        EVENT_QUEUE.addLast(ev);
        if (EVENT_QUEUE_SIZE.incrementAndGet() > 1000) {
            while (EVENT_QUEUE_SIZE.get() > 1000) {
                if (EVENT_QUEUE.pollFirst() != null) {
                    EVENT_QUEUE_SIZE.decrementAndGet();
                } else {
                    break;
                }
            }
        }
    }

    private void handleGetEvents(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        long since = parseLong(parseQuery(query).get("since"), 0);
        List<Map<String, Object>> result = EVENT_QUEUE.stream()
                .filter(ev -> parseLong(String.valueOf(ev.get("eid")), 0) > since)
                .filter(ev -> isEventVisibleToUser(ev, me))
                .collect(Collectors.toList());
        sendJson(ex, 200, result);
    }

    private boolean isEventVisibleToUser(Map<String, Object> ev, User me) {
        if (ev == null || me == null) return false;
        String myUid = me.getUserId();
        
        Object roomObj = ev.get("room");
        if (roomObj == null) roomObj = ev.get("chatRoomId");
        if (roomObj != null) {
            String r = String.valueOf(roomObj);
            if (r.startsWith("private_")) {
                if (!MessageService.isPrivateRoomParticipant(r, myUid)) return false;
            } else if (r.startsWith("group_") || r.startsWith("g")) {
                com.chat.model.Group g = GroupService.getInstance().getGroup(r);
                if (g == null && r.startsWith("group_")) {
                    g = GroupService.getInstance().getGroup(r.substring(6));
                }
                if (g != null && !g.isMember(myUid)) {
                    return false;
                }
            }
        }
        
        Object targetObj = ev.get("targetUserId");
        if (targetObj != null && !targetObj.equals("null")) {
            String targetUid = String.valueOf(targetObj);
            Object fromObj = ev.get("fromUserId");
            String fromUid = fromObj != null ? String.valueOf(fromObj) : null;
            if (!targetUid.equals(myUid) && (fromUid == null || !fromUid.equals(myUid))) {
                return false;
            }
        }
        return true;
    }

    private void handleGetMe(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("userId", me.getUserId());
        res.put("username", me.getUsername());
        res.put("nickname", me.getNickname());
        res.put("avatarPath", me.getAvatarPath());
        res.put("bubbleSkin", me.getBubbleSkin());
        res.put("messageFont", me.getMessageFont());
        res.put("isSuperAdmin", isSuperAdmin(me.getUserId()));
        res.put("isPrimarySuperAdmin", isPrimarySuperAdmin(me.getUserId()));
        res.put("isCoOwner", superAdminService.isCoOwner(me.getUserId()));
        res.put("isDeveloper", isDeveloper(me.getUserId()));
        res.put("bio", me.getBio());
        res.put("birthday", me.getBirthday());
        res.put("gender", me.getGender());
        res.put("language", me.getLanguage() != null ? me.getLanguage() : "zh-CN");
        res.put("banned", me.isCurrentlyBanned());
        if (me.isCurrentlyBanned()) {
            res.put("banExpiresAt", me.getBanExpiresAt());
            res.put("banRemainingMillis", me.getBanRemainingMillis());
            res.put("banReason", me.getBanReason() != null ? me.getBanReason() : "");
        }
        res.put("level", me.getEffectiveLevel());
        res.put("levelDisplay", me.getLevelDisplay());
        res.put("exp", me.getExp());
        res.put("lastCheckIn", me.getLastCheckIn() != null ? me.getLastCheckIn() : "");
        res.put("checkInStreak", me.getCheckInStreak());
        String gameDay = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString();
        res.put("dailyGamePlayExpCount", gameDay.equals(me.getGamePlayExpDay()) ? me.getDailyGamePlayExpCount() : 0);
        res.put("dailyGamePlayExpLimit", 3);
        res.put("nextLevelExp", getNextLevelExp(me));
        long qb = me.getCloudQuotaByLevel() == Long.MAX_VALUE ? -1 : me.getCloudQuotaByLevel();
        res.put("cloudQuotaByLevel", qb);
        res.put("cloudQuotaBytes", qb);
        res.put("aiDailyLimitByLevel", me.getAiDailyLimitByLevel() == Double.MAX_VALUE ? -1 : (long) me.getAiDailyLimitByLevel());
        res.put("aiUsedTokensToday", me.getAiUsedTokensToday());
        res.put("aiRemainingTokens", AiService.getInstance().getRemainingTokens(me) == Double.MAX_VALUE ? -1 : AiService.getInstance().getRemainingTokens(me));
        res.put("dailyGameUploadLimitByLevel", me.getDailyGameUploadLimitByLevel());
        res.put("msgsPerMinuteByLevel", me.getMsgsPerMinuteByLevel());
        res.put("featureBans", UserService.getInstance().getAllFeatureBanInfo(me.getUserId()));
        sendJson(ex, 200, res);
    }

    /** 返回所有频道最后一条消息摘要，用于侧边栏实时更新 */
    private void handleGetLastMessages(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        com.chat.model.Message pubLast = MessageService.getInstance().getLastMessage("public", me.getUserId());
        if (pubLast != null)
            result.put("public", msgSummary(pubLast));
        for (String fid : me.snapshotFriends()) {
            com.chat.model.Message last = MessageService.getInstance().getLastMessage(fid, me.getUserId());
            if (last != null)
                result.put(fid, msgSummary(last));
        }
        for (com.chat.model.Group g : GroupService.getInstance().getGroupsOfUser(me.getUserId())) {
            com.chat.model.Message last = MessageService.getInstance().getLastMessage("group_" + g.getGroupId(),
                    me.getUserId());
            if (last != null)
                result.put("group_" + g.getGroupId(), msgSummary(last));
        }
        sendJson(ex, 200, result);
    }

    private Map<String, Object> msgSummary(com.chat.model.Message m) {
        Map<String, Object> s = new LinkedHashMap<>();
        User fromUser = UserService.getInstance().getByUserId(m.getFromUserId());
        s.put("id", m.getId());
        s.put("chatRoomId", m.getChatRoomId());
        s.put("fromUserId", m.getFromUserId());
        s.put("fromNickname", m.getFromNickname());
        s.put("avatarPath", fromUser != null ? fromUser.getAvatarPath() : null);
        s.put("msgType", m.getMsgType());
        s.put("fileName", m.getFileName());
        s.put("content", m.isRecalled() ? "【已撤回】" : (m.getContent() != null ? m.getContent() : ""));
        s.put("recalled", m.isRecalled());
        s.put("timestamp", m.getTimestamp());
        return s;
    }

    private void handleGetMessagesPaged(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseQuery(query);
        String room = p.getOrDefault("room", "public");
        if (!canUserReadRoom(me, room)) {
            sendJson(ex, 403, map("error", "无权访问该聊天房间"));
            return;
        }
        List<com.chat.model.Message> msgs = MessageService.getInstance().getMessagesPaged(
                room, parseLong(p.get("before"), 0), me.getUserId());
        List<Map<String, Object>> result = msgs.stream().map(this::mapMessage).collect(Collectors.toList());
        sendJson(ex, 200, result);
    }

    private Map<String, Object> mapMessage(com.chat.model.Message m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("id", m.getId());
        s.put("fromUserId", m.getFromUserId());
        s.put("fromNickname", m.getFromNickname());
        s.put("toUserId", m.getToUserId());
        s.put("content", m.getContent());
        s.put("timestamp", m.getTimestamp());
        s.put("chatRoomId", m.getChatRoomId());
        s.put("recalled", m.isRecalled());
        s.put("msgType", m.getMsgType());
        s.put("fileName", m.getFileName());
        s.put("filePath", m.getFilePath());
        s.put("forwardedFromNickname", m.getForwardedFromNickname());
        s.put("forwardedFromUserId", m.getForwardedFromUserId());
        s.put("mentions", m.getMentions());
        s.put("bubbleSkin", m.getBubbleSkin());
        s.put("messageFont", m.getMessageFont());
        s.put("cloudEntryId", m.getCloudEntryId());
        s.put("cardType", m.getCardType());
        s.put("cardPayload", m.getCardPayload());
        s.put("adminDeleted", m.isAdminDeleted());
        s.put("deletedByUserId", m.getDeletedByUserId());
        s.put("deletedAt", m.getDeletedAt());
        s.put("clientMsgId", m.getClientMsgId());
        s.put("aiProxy", m.isAiProxy());

        // 注入身份标签
        User fromUser = UserService.getInstance().getByUserId(m.getFromUserId());
        if (fromUser != null) {
            s.put("isPrimarySuperAdmin", isPrimarySuperAdmin(fromUser.getUserId()));
            s.put("isCoOwner", superAdminService.isCoOwner(fromUser.getUserId()));
            s.put("isSuperAdmin", isSuperAdmin(fromUser.getUserId()));
            s.put("isDeveloper", isDeveloper(fromUser.getUserId()));
            s.put("customTags", fromUser.getCustomTags());
        } else {
            s.put("isPrimarySuperAdmin", false);
            s.put("isCoOwner", false);
            s.put("isSuperAdmin", false);
            s.put("isDeveloper", false);
            s.put("customTags", new java.util.ArrayList<String>());
        }
        return s;
    }

    private void handleGetUsers(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        List<Map<String, Object>> res = UserService.getInstance().getAllUsers().stream()
                .filter(u -> !u.getUserId().equals(me.getUserId()))
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", u.getUserId());
                    m.put("nickname", u.getNickname());
                    m.put("username", u.getUsername());
                    m.put("avatarPath", u.getAvatarPath());
                    m.put("isSuperAdmin", isSuperAdmin(u.getUserId()));
                    m.put("isPrimarySuperAdmin", isPrimarySuperAdmin(u.getUserId()));
                    m.put("isDeveloper", isDeveloper(u.getUserId()));
                    return m;
                })
                .collect(Collectors.toList());
        sendJson(ex, 200, res);
    }

    private void handleGetFriends(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        List<Map<String, Object>> res = me.snapshotFriends().stream()
                .map(uid -> UserService.getInstance().getByUserId(uid)).filter(Objects::nonNull)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("userId", u.getUserId());
                    m.put("nickname", u.getNickname());
                    m.put("avatarPath", u.getAvatarPath());
                    m.put("isSuperAdmin", isSuperAdmin(u.getUserId()));
                    m.put("isPrimarySuperAdmin", isPrimarySuperAdmin(u.getUserId()));
                    m.put("isDeveloper", isDeveloper(u.getUserId()));
                    return m;
                })
                .collect(Collectors.toList());
        sendJson(ex, 200, res);
    }

    private void handleGetSentReqs(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, FriendService.getInstance().getSentRequests(me.getUserId()).stream()
                .map(this::friendRequestToMap).collect(Collectors.toList()));
    }

    private void handleGetRecvReqs(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, FriendService.getInstance().getReceivedRequests(me.getUserId()).stream()
                .map(this::friendRequestToMap).collect(Collectors.toList()));
    }

    private Map<String, Object> friendRequestToMap(FriendRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", request.getId());
        data.put("fromUserId", request.getFromUserId());
        data.put("fromNickname", request.getFromNickname());
        data.put("toUserId", request.getToUserId());
        User target = UserService.getInstance().getByUserId(request.getToUserId());
        data.put("toNickname", target != null ? target.getNickname() : request.getToUserId());
        data.put("message", request.getMessage());
        data.put("status", request.getStatus());
        data.put("timestamp", request.getTimestamp());
        data.put("fromIsDeveloper", isDeveloper(request.getFromUserId()));
        data.put("toIsDeveloper", isDeveloper(request.getToUserId()));
        return data;
    }

    private void handleGetUnread(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseQuery(query);
        long since = parseLong(p.get("since"), 0);
        Map<String, Object> cursors = parseJsonObj(p.get("sinceByRoom"));
        Map<String, Long> unread = new LinkedHashMap<>();
        long publicSince = parseLong(String.valueOf(cursors.getOrDefault("public", since)), since);
        long pub = MessageService.getInstance().getMessagesSince("public", publicSince, me.getUserId())
                .stream().filter(m -> !m.getFromUserId().equals(me.getUserId())).count();
        if (pub > 0)
            unread.put("public", pub);
        for (String fid : me.snapshotFriends()) {
            String room = MessageService.normalizePrivateRoomId(me.getUserId(), fid);
            long roomSince = parseLong(String.valueOf(cursors.getOrDefault(fid, since)), since);
            long cnt = MessageService.getInstance().getMessagesSince(room, roomSince, me.getUserId())
                    .stream().filter(m -> !m.getFromUserId().equals(me.getUserId())).count();
            if (cnt > 0)
                unread.put(fid, cnt);
        }
        for (Group g : GroupService.getInstance().getGroupsOfUser(me.getUserId())) {
            String key = "group_" + g.getGroupId();
            long roomSince = parseLong(String.valueOf(cursors.getOrDefault(key, since)), since);
            long cnt = MessageService.getInstance().getMessagesSince(key, roomSince, me.getUserId())
                    .stream().filter(m -> !m.getFromUserId().equals(me.getUserId())).count();
            if (cnt > 0)
                unread.put("group_" + g.getGroupId(), cnt);
        }
        sendJson(ex, 200, unread);
    }

    private void handleGetUserProfile(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        User target = UserService.getInstance().getByUserId(parseQuery(query).get("userId"));
        if (target == null) {
            sendJson(ex, 404, map("error", "用户不存在"));
            return;
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("userId", target.getUserId());
        res.put("nickname", target.getNickname());
        res.put("username", target.getUsername());
        res.put("avatarPath", target.getAvatarPath());
        res.put("bio", target.getBio());
        res.put("birthday", target.getBirthday());
        res.put("gender", target.getGender());
        res.put("isSuperAdmin", isSuperAdmin(target.getUserId()));
        res.put("isPrimarySuperAdmin", isPrimarySuperAdmin(target.getUserId()));
        res.put("isDeveloper", isDeveloper(target.getUserId()));
        res.put("isFriend", me.getFriends().contains(target.getUserId()));
        sendJson(ex, 200, res);
    }

    private void handleGetUserMoments(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseQuery(query);
        String targetId = p.get("userId");
        int offset = parseInt(p.get("offset"), 0);
        sendJson(ex, 200, MomentService.getInstance().getPagedByUser(targetId, offset, me.getUserId())
                .stream().map(this::momentToMap).collect(Collectors.toList()));
    }

    private Map<String, Object> momentToMap(Moment moment) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (moment == null) {
            return data;
        }
        User author = UserService.getInstance().getByUserId(moment.getFromUserId());
        data.put("id", moment.getId());
        data.put("fromUserId", moment.getFromUserId());
        data.put("fromNickname", author != null && author.getNickname() != null && !author.getNickname().trim().isEmpty()
                ? author.getNickname()
                : moment.getFromNickname());
        data.put("avatarPath", author != null ? author.getAvatarPath() : null);
        data.put("isDeveloper", author != null && isDeveloper(author.getUserId()));
        data.put("content", moment.getContent());
        data.put("timestamp", moment.getTimestamp());
        data.put("visibility", moment.getVisibility());
        data.put("allowedViewers",
                moment.getAllowedViewers() != null ? new ArrayList<>(moment.getAllowedViewers()) : new ArrayList<>());
        data.put("allowedViewerNicknames", (moment.getAllowedViewers() != null ? moment.getAllowedViewers() : Collections.<String>emptyList())
                .stream()
                .map(uid -> {
                    User user = UserService.getInstance().getByUserId(uid);
                    return user != null && user.getNickname() != null && !user.getNickname().trim().isEmpty()
                            ? user.getNickname()
                            : uid;
                })
                .collect(Collectors.toList()));
        data.put("likes", moment.getLikes() != null ? new ArrayList<>(moment.getLikes()) : new ArrayList<>());
        data.put("likeUsers", (moment.getLikes() != null ? moment.getLikes() : Collections.<String>emptyList())
                .stream()
                .map(uid -> {
                    User user = UserService.getInstance().getByUserId(uid);
                    Map<String, Object> likeUser = new LinkedHashMap<>();
                    likeUser.put("userId", uid);
                    likeUser.put("nickname",
                            user != null && user.getNickname() != null && !user.getNickname().trim().isEmpty()
                                    ? user.getNickname()
                                    : uid);
                    likeUser.put("avatarPath", user != null ? user.getAvatarPath() : null);
                    likeUser.put("isDeveloper", isDeveloper(uid));
                    return likeUser;
                })
                .collect(Collectors.toList()));
        data.put("comments", (moment.getComments() != null ? moment.getComments() : Collections.<Moment.Comment>emptyList())
                .stream()
                .map(comment -> {
                    User commentUser = UserService.getInstance().getByUserId(comment.getFromUserId());
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fromUserId", comment.getFromUserId());
                    row.put("fromNickname", commentUser != null && commentUser.getNickname() != null
                            && !commentUser.getNickname().trim().isEmpty()
                                    ? commentUser.getNickname()
                                    : comment.getFromNickname());
                    row.put("avatarPath", commentUser != null ? commentUser.getAvatarPath() : null);
                    row.put("isDeveloper", isDeveloper(comment.getFromUserId()));
                    row.put("content", comment.getContent());
                    row.put("timestamp", comment.getTimestamp());
                    return row;
                })
                .collect(Collectors.toList()));
        return data;
    }

    private static final String STICKERS_DIR = "chatserver/stickers";

    private void handleGetStickers(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, loadUserStickers(me.getUserId()));
    }

    private void handleAddSticker(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String existingPath = p.get("filePath");
        try {
            if (existingPath == null || existingPath.trim().isEmpty()) {
                sendJson(ex, 400, map("error", "请先上传文件"));
                return;
            }
            StoredFileMetadata metadata = metadataFromPath(existingPath, me);
            if (metadata == null) {
                sendJson(ex, 404, map("error", "上传文件不存在"));
                return;
            }
            String contentType = metadata.getContentType() != null ? metadata.getContentType() : "";
            if (!(contentType.startsWith("image/") || contentType.startsWith("video/"))) {
                sendJson(ex, 400, map("error", "表情包仅支持图片或视频"));
                return;
            }
            if (metadata.getSize() > 5L * 1024 * 1024) {
                sendJson(ex, 400, map("error", "表情包不能超过5MB"));
                return;
            }
            String path = metadata.getAccessPath();
            List<String> stickers = loadUserStickers(me.getUserId());
            if (!stickers.contains(path)) {
                stickers.add(path);
                saveUserStickers(me.getUserId(), stickers);
            }
            sendJson(ex, 200, map("success", "true", "path", path));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "失败: " + e.getMessage()));
        }
    }

    private List<String> loadUserStickers(String userId) {
        try {
            Files.createDirectories(Paths.get(STICKERS_DIR));
            java.io.File f = new java.io.File(STICKERS_DIR + "/" + userId + ".json");
            if (!f.exists())
                return new ArrayList<>();
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<String> list = JsonUtil.fromJson(json,
                    new com.google.gson.reflect.TypeToken<List<String>>() {
                    }.getType());
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private void saveUserStickers(String userId, List<String> stickers) {
        try {
            JsonUtil.saveJsonAtomic(Paths.get(STICKERS_DIR, userId + ".json"), stickers);
        } catch (Exception ignored) {
        }
    }

    private void handleSendMessage(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.isFeatureBanned("chat")) {
            Map<String, Object> fbInfo = UserService.getInstance().getFeatureBanInfo(me.getUserId(), "chat");
            sendJson(ex, 403, fbInfo != null ? fbInfo : map("error", "聊天功能已被封禁"));
            return;
        }
        // 消息限速检查
        if (!isSuperAdmin(me.getUserId())) {
            int limit = me.getMsgsPerMinuteByLevel();
            long now = System.currentTimeMillis();
            cleanExpiredRateLimits(now);
            long[] bucket = msgRateLimit.computeIfAbsent(me.getUserId(), k -> new long[]{0, now});
            synchronized (bucket) {
                if (now - bucket[1] > 60000) { bucket[0] = 0; bucket[1] = now; }
                bucket[0]++;
                if (bucket[0] > limit) {
                    long waitSec = Math.max(1, (60000 - (now - bucket[1])) / 1000);
                    sendJson(ex, 429, map("error", "发送过于频繁，请等待1分钟后再试", "rateLimited", "true", "waitSeconds", String.valueOf(waitSec)));
                    return;
                }
            }
        }
        Map<String, String> p = parseJson(body);
        String content = p.get("content");
        String room = normalizeWritableRoom(me, p.getOrDefault("room", "public"));
        if (!ensureRoomWriteAllowed(ex, me, room)) return;
        String toUserId = room.startsWith("private_")
                ? MessageService.getPrivateRoomPeer(room, me.getUserId()) : null;
        String stickerPath = p.get("stickerPath"), stickerName = p.get("stickerName");
        String type = p.getOrDefault("type", "text");
        String clientMsgId = p.get("clientMsgId"); // 客户端临时ID，用于去重
        if (room.startsWith("group_")) {
            Group g = GroupService.getInstance().getGroup(room.substring(6));
            if (g == null || !g.isMember(me.getUserId())) {
                sendJson(ex, 403, map("error", "你不在该群"));
                return;
            }
            if (g.isMuted(me.getUserId())) {
                sendJson(ex, 403, map("error", "你已被禁言，剩余" + fmtDur(g.mutedSecondsLeft(me.getUserId()))));
                return;
            }
            if (g.isAllMuted() && !g.isOwner(me.getUserId()) && !(g.getAdmins() != null && g.getAdmins().contains(me.getUserId()))) {
                sendJson(ex, 403, map("error", "全员禁言中，仅群主和管理员可发言"));
                return;
            }
        }
        if ("public".equals(room)) {
            PublicRoomConfig prConfig = PublicRoomService.getInstance().getConfig();
            if (prConfig.isAllMuted() && !PublicRoomService.getInstance().isAdmin(me.getUserId())) {
                sendJson(ex, 403, map("error", "全员禁言中，仅管理员可发言"));
                return;
            }
            if (prConfig.getMutedUserIds().contains(me.getUserId())) {
                sendJson(ex, 403, map("error", "你已被禁言"));
                return;
            }
        }
        if (clientMsgId != null && !clientMsgId.trim().isEmpty()) {
            Message existing = MessageService.getInstance().findMessageByClientMsgId(
                    me.getUserId(), room, clientMsgId.trim());
            if (existing != null) {
                sendJson(ex, 200, mapMessage(existing));
                return;
            }
        }
        if (stickerPath != null && !stickerPath.isEmpty()) {
            Message msg = MessageService.getInstance().sendMessage(
                    me.getUserId(), me.getNickname(), toUserId, stickerName != null ? stickerName : "[表情包]",
                    room, "sticker", stickerName, stickerPath, null, null, null, me.getBubbleSkin(),
                    clientMsgId);
            maybeDelaySendResponse(clientMsgId);
            sendJson(ex, 200, mapMessage(msg));
            return;
        }
        if (content == null || content.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "消息不能为空"));
            return;
        }
        String trimmedContent = content.trim();
        // AI Proxy Chat for super admins
        if (trimmedContent.startsWith("/ai ") && isSuperAdmin(me.getUserId())) {
            String proxyCmd = trimmedContent.substring(4).trim();
            if (proxyCmd.isEmpty()) {
                sendJson(ex, 400, map("error", "格式: /ai @用户昵称 指令"));
                return;
            }
            String targetUserId = null;
            String instruction;
            if (proxyCmd.startsWith("@")) {
                int spaceIdx = proxyCmd.indexOf(' ');
                if (spaceIdx < 0) {
                    sendJson(ex, 400, map("error", "格式: /ai @用户昵称 指令"));
                    return;
                }
                String mentionName = proxyCmd.substring(1, spaceIdx);
                instruction = proxyCmd.substring(spaceIdx + 1).trim();
                for (User u : UserService.getInstance().getAllUsers()) {
                    if (mentionName.equalsIgnoreCase(u.getNickname())
                            || mentionName.equalsIgnoreCase(u.getUsername())) {
                        targetUserId = u.getUserId();
                        break;
                    }
                }
            } else {
                int spaceIdx = proxyCmd.indexOf(' ');
                if (spaceIdx < 0) {
                    sendJson(ex, 400, map("error", "格式: /ai @用户昵称 指令"));
                    return;
                }
                targetUserId = proxyCmd.substring(0, spaceIdx);
                instruction = proxyCmd.substring(spaceIdx + 1).trim();
            }
            if (targetUserId == null || instruction.isEmpty()) {
                sendJson(ex, 400, map("error", "格式: /ai @用户昵称 指令"));
                return;
            }
            User targetUser = UserService.getInstance().getByUserId(targetUserId);
            if (targetUser == null) {
                sendJson(ex, 404, map("error", "目标用户不存在"));
                return;
            }
            String proxyRecipientId = room.startsWith("private_")
                    ? MessageService.getPrivateRoomPeer(room, targetUser.getUserId()) : null;
            if (room.startsWith("private_") && proxyRecipientId == null) {
                sendJson(ex, 403, map("error", "目标用户不是该私聊的参与者"));
                return;
            }
            // Fetch recent messages for context
            StringBuilder contextBuilder = new StringBuilder();
            try {
                List<Message> recentMsgs = MessageService.getInstance().getMessagesPaged(room, 0, me.getUserId());
                for (Message m : recentMsgs) {
                    String senderName = m.getFromNickname() != null ? m.getFromNickname() : m.getFromUserId();
                    contextBuilder.append(senderName).append(": ").append(m.getContent()).append("\n");
                }
            } catch (Exception ignored) {}
            String contextStr = contextBuilder.toString();
            // Call LongCat API
            String targetName = targetUser.getNickname() != null ? targetUser.getNickname() : targetUser.getUsername();
            String targetBio = targetUser.getBio() != null && !targetUser.getBio().isEmpty() ? targetUser.getBio() : "";
            String systemPrompt = "你是" + targetName + (targetBio.isEmpty() ? "" : "，" + targetBio) + "。根据聊天上下文，按照管理员指令回复。用第一人称，保持口吻自然，回复要简短自然不超过50字。";
            try {
                Map<String, Object> aiBody = new LinkedHashMap<>();
                aiBody.put("model", "LongCat-Flash-Lite");
                List<Map<String, Object>> msgs = new ArrayList<>();
                msgs.add(obj("role", "system", "content", systemPrompt));
                if (!contextStr.isEmpty()) {
                    msgs.add(obj("role", "user", "content", "聊天上下文：\n" + contextStr));
                }
                msgs.add(obj("role", "user", "content", "管理员指令：" + instruction));
                aiBody.put("messages", msgs);
                aiBody.put("stream", false);
                aiBody.put("max_tokens", 256);
                Map<String, Object> aiResp = postJson(LONGCAT_API_URL, LONGCAT_API_KEY, aiBody);
                List<Map<String, Object>> choices = asObjectList(aiResp.get("choices"));
                String reply = "";
                if (!choices.isEmpty()) {
                    reply = asString(asObjectMap(choices.get(0).get("message")).get("content")).trim();
                }
                if (reply.isEmpty()) {
                    sendJson(ex, 500, map("error", "AI 生成回复失败"));
                    return;
                }
                Message proxyMsg = MessageService.getInstance().sendMessage(
                        targetUser.getUserId(), targetName, proxyRecipientId, reply,
                        room, "text", null, null, null, null,
                        null, targetUser.getBubbleSkin(), null);
                proxyMsg.setAiProxy(true);
                UserService.getInstance().save();
                sendJson(ex, 200, mapMessage(proxyMsg));
            } catch (Exception e) {
                sendJson(ex, 500, map("error", "AI 代理回复失败: " + e.getMessage()));
            }
            return;
        }
        if (countLines(trimmedContent) > 4000) {
            byte[] bytes = trimmedContent.getBytes(StandardCharsets.UTF_8);
            try {
                if (!CloudService.getInstance().canStore(me, bytes.length)) {
                    sendJson(ex, 400, map("error", "空间已满，无法存储"));
                    return;
                }
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    StoredFileMetadata stored = FileStore.getInstance().store(input, "pasted.txt", "text/plain; charset=UTF-8", me.getUserId());
                    sendUploadedMessage(ex, me, room, "file", stored, "pasted.txt");
                    return;
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, map("error", e.getMessage()));
                return;
            } catch (Exception e) {
                sendJson(ex, 500, map("error", "保存 pasted.txt 失败: " + e.getMessage()));
                return;
            }
        }
        List<String> mentions = parseMentions(trimmedContent);
        Message msg = MessageService.getInstance().sendMessage(
                me.getUserId(), me.getNickname(), toUserId, trimmedContent,
                room, type, null, null, null, null, mentions, me.getBubbleSkin(), clientMsgId);
        UserService.getInstance().save();
        maybeDelaySendResponse(clientMsgId);
        sendJson(ex, 200, mapMessage(msg));
    }

    private void maybeDelaySendResponse(String clientMsgId) {
        if (TEST_SEND_RESPONSE_DELAY_MS <= 0) return;
        String key = clientMsgId == null ? "" : clientMsgId.trim();
        if (key.isEmpty() || !DELAYED_SEND_RESPONSES.add(key)) return;
        try {
            Thread.sleep(TEST_SEND_RESPONSE_DELAY_MS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private int countLines(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private void handleUploadFile(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String filePath = p.get("filePath"), room = p.getOrDefault("room", "public");
        String msgType = p.getOrDefault("type", "file");
        if (filePath == null || filePath.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请先上传文件"));
            return;
        }
        try {
            StoredFileMetadata metadata = metadataFromPath(filePath, me);
            if (metadata == null) {
                sendJson(ex, 404, map("error", "上传文件不存在"));
                return;
            }
            sendUploadedMessage(ex, me, room, msgType, metadata,
                    p.getOrDefault("fileName", metadata.getOriginalFileName()));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败: " + e.getMessage()));
        }
    }

    private void handleStoreFileStream(HttpExchange ex, InputStream bodyStream, String query, User me) throws IOException {
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
        if (fileName == null || fileName.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少文件名"));
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
            res.put("contentType", storedFile.getContentType());
            sendJson(ex, 200, res);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败: " + e.getMessage()));
        }
    }

    private void handleUploadFileStream(HttpExchange ex, InputStream bodyStream, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> params = parseQuery(query);
        String fileName = params.get("fileName");
        String room = params.getOrDefault("room", "public");
        String type = params.getOrDefault("type", "file");
        if (fileName == null || fileName.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少文件名"));
            return;
        }
        long contentLength = parseLong(ex.getRequestHeaders().getFirst("Content-Length"), -1);
        long sixGb = 6L * 1024 * 1024 * 1024;
        if (contentLength >= sixGb) {
            sendJson(ex, 400, map("error", "文件不能超过6GB"));
            return;
        }
        try {
            StoredFileMetadata storedFile = storeStreamFile(ex, bodyStream, fileName, sixGb, "文件不能超过6GB", me.getUserId());
            sendUploadedMessage(ex, me, room, type, storedFile, fileName);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败: " + e.getMessage()));
        }
    }

    private void sendUploadedMessage(HttpExchange ex, User me, String room, String type,
                                     StoredFileMetadata storedFile, String originalFileName) throws IOException {
        room = normalizeWritableRoom(me, room);
        if (!ensureRoomWriteAllowed(ex, me, room)) return;
        String toUserId = room.startsWith("private_")
                ? MessageService.getPrivateRoomPeer(room, me.getUserId()) : null;
        long fileSizeBytes = storedFile.getSize();
        long MB50 = 50L * 1024 * 1024;
        long MB300 = 300L * 1024 * 1024;
        long GB3 = 3L * 1024 * 1024 * 1024;
        Long expireAt = null;
        long now = System.currentTimeMillis();
        if (fileSizeBytes > GB3)
            expireAt = now + 1L * 24 * 3600 * 1000;
        else if (fileSizeBytes > MB300)
            expireAt = now + 7L * 24 * 3600 * 1000;
        else if (fileSizeBytes > MB50)
            expireAt = now + 60L * 24 * 3600 * 1000;

        Message msg = MessageService.getInstance().sendMessage(
                me.getUserId(), me.getNickname(), toUserId, null, room,
                type, FileStore.normalizeUploadedFileName(originalFileName),
                storedFile.getAccessPath(), null, null, null, me.getBubbleSkin());
        if (msg == null) {
            sendJson(ex, 400, map("error", "发送失败"));
            return;
        }
        // Auto-save uploaded files to cloud drive
        try {
            CloudService.getInstance().storeUserFile(me, storedFile, "/uploads", originalFileName, "chat");
        } catch (Exception ignored) {
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", msg.getId());
        res.put("fromUserId", msg.getFromUserId());
        res.put("fromNickname", msg.getFromNickname());
        res.put("chatRoomId", msg.getChatRoomId());
        res.put("msgType", msg.getMsgType());
        res.put("filePath", msg.getFilePath());
        res.put("fileName", msg.getFileName());
        res.put("content", msg.getContent());
        res.put("timestamp", msg.getTimestamp());
        res.put("recalled", msg.isRecalled());
        res.put("bubbleSkin", msg.getBubbleSkin());
        res.put("messageFont", msg.getMessageFont());
        res.put("isDeveloper", isDeveloper(me.getUserId()));
        res.put("avatarPath", me.getAvatarPath());
        if (expireAt != null)
            res.put("expireAt", expireAt);
        res.put("fileSize", fileSizeBytes);
        sendJson(ex, 200, res);
    }

    /** multipart/form-data 上传，支持真实进度（保留供外部调用兼容） */
    @SuppressWarnings("unused")
    private void handleUploadFileForm(HttpExchange ex, java.io.InputStream bodyStream, User me) throws IOException {
        sendJson(ex, 410, map("error", "旧版表单上传已停用，请使用流式上传接口"));
    }

    private boolean canUserReadMessage(User me, Message msg) {
        if (me == null || msg == null) return false;
        if (isSuperAdmin(me.getUserId())) return true;
        String room = msg.getChatRoomId();
        if (room == null || "public".equals(room)) return true;
        if (room.startsWith("group_")) {
            Group g = GroupService.getInstance().getGroup(room.substring(6));
            return g != null && g.isMember(me.getUserId());
        }
        if (room.startsWith("private_")) {
            return MessageService.isPrivateRoomParticipant(room, me.getUserId())
                    || me.getUserId().equals(msg.getFromUserId())
                    || me.getUserId().equals(msg.getToUserId());
        }
        return me.getUserId().equals(msg.getFromUserId()) || me.getUserId().equals(msg.getToUserId());
    }

    private void handleForwardMessage(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.isFeatureBanned("chat")) {
            sendJson(ex, 403, map("error", "聊天功能已被封禁"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String msgId = p.get("messageId"), targetRoom = p.get("targetRoom");
        if (msgId == null || targetRoom == null) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        targetRoom = normalizeWritableRoom(me, targetRoom);
        if (!ensureRoomWriteAllowed(ex, me, targetRoom)) return;
        String targetUserId = targetRoom.startsWith("private_")
                ? MessageService.getPrivateRoomPeer(targetRoom, me.getUserId()) : null;
        Message orig = MessageService.getInstance().getById(msgId);
        if (orig == null) {
            sendJson(ex, 404, map("error", "原消息不存在"));
            return;
        }
        if (!canUserReadMessage(me, orig)) {
            sendJson(ex, 403, map("error", "无权访问该原消息"));
            return;
        }
        if (orig.isRecalled()) {
            sendJson(ex, 400, map("error", "已撤回的消息不能转发"));
            return;
        }
        if (targetRoom.startsWith("group_")) {
            Group g = GroupService.getInstance().getGroup(targetRoom.substring(6));
            if (g == null || !g.isMember(me.getUserId())) {
                sendJson(ex, 403, map("error", "你不在该群"));
                return;
            }
            if (g.isMuted(me.getUserId())) {
                sendJson(ex, 403, map("error", "你已被禁言"));
                return;
            }
            if (g.isAllMuted() && !g.isOwner(me.getUserId()) && !(g.getAdmins() != null && g.getAdmins().contains(me.getUserId()))) {
                sendJson(ex, 403, map("error", "全员禁言中，仅群主和管理员可发言"));
                return;
            }
        }
        if ("public".equals(targetRoom)) {
            com.chat.model.PublicRoomConfig prConfig = PublicRoomService.getInstance().getConfig();
            if (prConfig.isAllMuted() && !PublicRoomService.getInstance().isAdmin(me.getUserId())) {
                sendJson(ex, 403, map("error", "全员禁言中，仅管理员可发言"));
                return;
            }
            if (prConfig.getMutedUserIds().contains(me.getUserId())) {
                sendJson(ex, 403, map("error", "你已被禁言"));
                return;
            }
        }
        Message fwd = MessageService.getInstance().sendMessage(
                me.getUserId(), me.getNickname(), targetUserId, orig.getContent(), targetRoom,
                orig.getMsgType(), orig.getFileName(), orig.getFilePath(),
                orig.getFromNickname(), orig.getFromUserId(), null, me.getBubbleSkin());
        sendJson(ex, 200, mapMessage(fwd));
    }

    private void handleForwardToMoment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.isFeatureBanned("moment") || me.isFeatureBanned("moments")) {
            sendJson(ex, 403, map("error", "动态功能已被封禁"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String msgId = p.get("messageId"), vis = p.getOrDefault("visibility", "friends");
        Message orig = MessageService.getInstance().getById(msgId);
        if (orig == null) {
            sendJson(ex, 404, map("error", "消息不存在"));
            return;
        }
        if (!canUserReadMessage(me, orig)) {
            sendJson(ex, 403, map("error", "无权访问该原消息"));
            return;
        }
        if (orig.isRecalled()) {
            sendJson(ex, 400, map("error", "已撤回的消息不能转发"));
            return;
        }
        String content = "[转发消息] " + orig.getFromNickname() + "：" +
                (orig.getFilePath() != null ? "[文件:" + orig.getFileName() + "]" : orig.getContent());
        sendJson(ex, 200, MomentService.getInstance().post(me.getUserId(), me.getNickname(), content, vis, null));
    }

    private void handleSendFriendReq(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String toId = p.get("toUserId");
        if (toId == null || toId.equals(me.getUserId())) {
            sendJson(ex, 400, map("error", "无效目标"));
            return;
        }
        User target = UserService.getInstance().getByUserId(toId);
        if (target == null) {
            sendJson(ex, 404, map("error", "用户不存在"));
            return;
        }
        if (isSuperAdmin(me.getUserId())) {
            if (me.getFriends().contains(toId)) {
                sendJson(ex, 400, map("error", "已是好友"));
                return;
            }
            me.getFriends().add(toId);
            target.getFriends().add(me.getUserId());
            UserService.getInstance().save();
            sendJson(ex, 200, map("success", "true", "direct", "true"));
            return;
        }
        boolean ok = FriendService.getInstance().sendRequest(me.getUserId(), me.getNickname(), toId,
                p.getOrDefault("message", ""));
        sendJson(ex, ok ? 200 : 400, ok ? map("success", "true") : map("error", "已发送过或已是好友"));
    }

    private void handleHandleFriendReq(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        boolean ok = FriendService.getInstance().handleRequest(p.get("requestId"), p.get("action"), me.getUserId());
        sendJson(ex, ok ? 200 : 400, ok ? map("success", "true") : map("error", "操作失败"));
    }

    private void handleRecallMessage(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String messageId = p.get("messageId");
        Message msg = MessageService.getInstance().getById(messageId);
        if (msg == null) {
            sendJson(ex, 200, map("success", "false", "error", "消息不存在"));
            return;
        }
        
        boolean isAdmin = isSuperAdmin(me.getUserId());
        if (!isAdmin && msg.getChatRoomId().startsWith("group_")) {
            Group g = GroupService.getInstance().getGroup(msg.getChatRoomId().substring(6));
            if (g != null && (g.getOwnerId().equals(me.getUserId()) || (g.getAdmins() != null && g.getAdmins().contains(me.getUserId())))) {
                isAdmin = true;
            }
        } else if (!isAdmin && "public".equals(msg.getChatRoomId())) {
            PublicRoomConfig pc = PublicRoomService.getInstance().getConfig();
            if (pc.getAdminIds() != null && pc.getAdminIds().contains(me.getUserId())) {
                isAdmin = true;
            }
        }
        
        String r;
        if (isAdmin && !msg.getFromUserId().equals(me.getUserId())) {
            Message deleted = MessageService.getInstance().adminDeleteMessage(messageId, me.getUserId());
            r = deleted != null ? "ok" : "not_found";
        } else {
            r = MessageService.getInstance().recallMessage(messageId, me.getUserId(), isAdmin);
        }
        
        switch (r) {
            case "ok": {
                Message recalled = MessageService.getInstance().getById(messageId);
                Map<String, Object> evData = new LinkedHashMap<>();
                evData.put("messageId", messageId);
                evData.put("room", recalled != null ? recalled.getChatRoomId() : "");
                pushEvent("recall", evData);
                sendJson(ex, 200, map("success", "true"));
                break;
            }
            case "not_owner":
                sendJson(ex, 200, map("success", "false", "error", "只能撤回自己的消息"));
                break;
            case "timeout":
                sendJson(ex, 200, map("success", "false", "error", "超过10分钟无法撤回"));
                break;
            case "save_failed":
                sendJson(ex, 500, map("success", "false", "error", "消息保存失败，请稍后重试"));
                break;
            default:
                sendJson(ex, 200, map("success", "false", "error", "消息不存在"));
                break;
        }
    }

    private void handleDeleteSelf(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.getUserId().equals(SuperAdminService.getInstance().getPrimarySuperAdminId())) {
            sendJson(ex, 403, map("error", "服主账号禁止删除"));
            return;
        }
        String err = UserService.getInstance().deleteUser(me.getUserId());
        if ("ok".equals(err)) {
            sendSessionCookie(ex, "");
            sendJson(ex, 200, map("success", "true"));
        } else {
            sendJson(ex, 500, map("error", err != null ? err : "删除失败"));
        }
    }

    private void handleUpdateProfile(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String type = p.get("type");
        UserService us = UserService.getInstance();
        if ("nickname".equals(type)) {
            String validation = UserService.validateNickname(p.get("value"));
            boolean ok = validation == null && us.updateNickname(me.getUserId(), p.get("value"));
            sendJson(ex, ok ? 200 : 400,
                    ok ? map("success", "true", "nickname", me.getNickname())
                            : map("error", validation != null ? validation : "昵称更新失败"));
        } else if ("username".equals(type)) {
            String r = us.updateUsername(me.getUserId(), p.get("value"));
            sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
        } else if ("password".equals(type)) {
            String r = us.updatePassword(me.getUserId(), p.get("oldPassword"), p.get("newPassword"));
            if ("ok".equals(r)) {
                SessionManager.getInstance().removeSessionsForUser(me.getUserId());
                SessionCookieSecurity.clearAllCookies(ex, ex.getRequestHeaders().getFirst("Cookie"));
                sendJson(ex, 200, map("success", "true", "loggedOut", "true"));
            } else {
                sendJson(ex, 400, map("error", r));
            }
        } else if ("messageFont".equals(type)) {
            String r = us.updateMessageFont(me.getUserId(), p.get("value"));
            sendJson(ex, "ok".equals(r) ? 200 : 400,
                    "ok".equals(r) ? map("success", "true", "messageFont", me.getMessageFont()) : map("error", r));
        } else if ("bio".equals(type)) {
            me.setBio(p.get("value"));
            us.save();
            sendJson(ex, 200, map("success", "true"));
        } else if ("birthday".equals(type)) {
            me.setBirthday(p.get("value"));
            us.save();
            sendJson(ex, 200, map("success", "true"));
        } else if ("gender".equals(type)) {
            me.setGender(p.get("value"));
            us.save();
            sendJson(ex, 200, map("success", "true"));
        } else if ("language".equals(type)) {
            me.setLanguage(p.get("value"));
            us.save();
            sendJson(ex, 200, map("success", "true"));
        } else
            sendJson(ex, 400, map("error", "未知类型"));
    }

    private void handleVerifyPassword(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String password = parseJson(body).get("password");
        if (!UserService.getInstance().verifyPassword(me.getUserId(), password)) {
            sendJson(ex, 400, map("error", "原密码错误"));
            return;
        }
        sendJson(ex, 200, map("success", "true"));
    }

    private void handleUpdateAvatar(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        try {
            StoredFileMetadata metadata = metadataFromPath(p.get("filePath"), me);
            if (metadata == null) {
                sendJson(ex, 404, map("error", "上传文件不存在"));
                return;
            }
            String contentType = metadata.getContentType() != null ? metadata.getContentType() : "";
            if (!contentType.startsWith("image/")) {
                sendJson(ex, 400, map("error", "头像必须是图片"));
                return;
            }
            if (metadata.getSize() > 5L * 1024 * 1024) {
                sendJson(ex, 400, map("error", "头像不能超过5MB"));
                return;
            }
            String path = metadata.getAccessPath();
            me.setAvatarPath(path);
            UserService.getInstance().save();
            sendJson(ex, 200, map("success", "true", "avatarPath", path));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败"));
        }
    }

    private void handleUpdateSkin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        me.setBubbleSkin(p.getOrDefault("skin", ""));
        UserService.getInstance().save();
        sendJson(ex, 200, map("success", "true"));
    }

    private void handleGetMoments(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseQuery(query);
        sendJson(ex, 200, MomentService.getInstance().getPaged(parseInt(p.get("offset"), 0), me.getUserId())
                .stream().map(this::momentToMap).collect(Collectors.toList()));
    }

    private void handlePostMoment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (me.isFeatureBanned("moment") || me.isFeatureBanned("moments")) {
            sendJson(ex, 403, map("error", "动态功能已被封禁"));
            return;
        }
        Map<String, Object> p = parseJsonObj(body);
        String content = (String) p.get("content"), vis = (String) p.getOrDefault("visibility", "friends");
        @SuppressWarnings("unchecked")
        List<String> allowed = (List<String>) p.get("allowedViewers");
        if (content == null || content.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "内容不能为空"));
            return;
        }
        String trimmedContent = content.trim();
        if (countLines(trimmedContent) > 4000) {
            byte[] bytes = trimmedContent.getBytes(StandardCharsets.UTF_8);
            try {
                if (!CloudService.getInstance().canStore(me, bytes.length)) {
                    sendJson(ex, 400, map("error", "云盘空间已满，无法存储"));
                    return;
                }
                try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
                    StoredFileMetadata stored = FileStore.getInstance().store(input, "pasted.txt", "text/plain; charset=UTF-8", me.getUserId());
                    String fileMsgContent = "📋 超长内容已自动保存为文件";
                    Moment.Attachment att = new Moment.Attachment();
                    att.setType("file");
                    att.setFilePath(stored.getAccessPath());
                    att.setFileName("pasted.txt");
                    Moment moment = MomentService.getInstance().post(me.getUserId(), me.getNickname(), fileMsgContent, vis, allowed, List.of(att));
                    sendJson(ex, 200, momentToMap(moment));
                    return;
                }
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, map("error", e.getMessage()));
                return;
            } catch (Exception e) {
                sendJson(ex, 500, map("error", "保存 pasted.txt 失败: " + e.getMessage()));
                return;
            }
        }
        sendJson(ex, 200,
                momentToMap(MomentService.getInstance().post(me.getUserId(), me.getNickname(), trimmedContent, vis,
                        allowed)));
    }

    private void handleLikeMoment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String momentId = p.get("momentId");
        MomentService service = MomentService.getInstance();
        service.toggleLike(momentId, me.getUserId());
        Moment updated = service.getVisibleById(momentId, me.getUserId());
        if (updated == null) {
            sendJson(ex, 404, map("error", "动态不存在"));
            return;
        }
        sendJson(ex, 200, momentToMap(updated));
    }

    private void handleCommentMoment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String c = p.get("content");
        if (c == null || c.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "评论不能为空"));
            return;
        }
        String momentId = p.get("momentId");
        MomentService service = MomentService.getInstance();
        boolean ok = service.addComment(momentId, me.getUserId(), me.getNickname(), c.trim());
        if (!ok) {
            sendJson(ex, 404, map("error", "动态不存在"));
            return;
        }
        Moment updated = service.getVisibleById(momentId, me.getUserId());
        if (updated == null) {
            sendJson(ex, 404, map("error", "动态不存在"));
            return;
        }
        sendJson(ex, 200, momentToMap(updated));
    }

    private void handleDeleteMoment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String momentId = parseJson(body).get("momentId");
        String result = MomentService.getInstance().deleteMoment(momentId, me.getUserId(), isSuperAdmin(me.getUserId()));
        if ("ok".equals(result)) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("momentId", momentId);
            pushEvent("moment_deleted", event);
            sendJson(ex, 200, map("success", "true"));
        } else if ("timeout".equals(result)) {
            sendJson(ex, 403, map("error", "动态发布超过 5 分钟，无法撤回"));
        } else if ("forbidden".equals(result)) {
            sendJson(ex, 403, map("error", "无权删除这条动态"));
        } else {
            sendJson(ex, 404, map("error", "动态不存在"));
        }
    }

    private void handleGetGroups(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, GroupService.getInstance().getGroupsOfUser(me.getUserId()).stream()
                .map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("groupId", g.getGroupId());
                    m.put("groupName", g.getGroupName());
                    m.put("ownerId", g.getOwnerId());
                    m.put("memberCount", g.getMembers().size());
                    m.put("iconPath", g.getIconPath());
                    m.put("isOwner", g.isOwner(me.getUserId()));
                    m.put("isAdmin", g.isAdmin(me.getUserId()));
                    return m;
                })
                .collect(Collectors.toList()));
    }

    private void handleGetGroupInfo(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Group g = GroupService.getInstance().getGroup(parseQuery(query).get("groupId"));
        if (g == null) {
            sendJson(ex, 404, map("error", "群不存在"));
            return;
        }
        if (!g.isMember(me.getUserId())) {
            sendJson(ex, 403, map("error", "你不在该群"));
            return;
        }
        List<Map<String, Object>> members = g.getMembers().stream().map(uid -> {
            User u = UserService.getInstance().getByUserId(uid);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", uid);
            m.put("nickname", u != null ? u.getNickname() : uid);
            m.put("avatarPath", u != null ? u.getAvatarPath() : null);
            m.put("isSuperAdmin", isSuperAdmin(uid));
            m.put("isPrimarySuperAdmin", isPrimarySuperAdmin(uid));
            m.put("isDeveloper", isDeveloper(uid));
            m.put("role", g.isOwner(uid) ? "owner" : g.isAdmin(uid) ? "admin" : "member");
            if (g.isMuted(uid))
                m.put("mutedSecondsLeft", g.mutedSecondsLeft(uid));
            return m;
        }).collect(Collectors.toList());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("groupId", g.getGroupId());
        res.put("groupName", g.getGroupName());
        res.put("ownerId", g.getOwnerId());
        res.put("iconPath", g.getIconPath());
        res.put("description", g.getDescription());
        res.put("createdAt", g.getCreatedAt());
        res.put("allMuted", g.isAllMuted());
        res.put("members", members);
        res.put("myRole", g.isOwner(me.getUserId()) ? "owner" : g.isAdmin(me.getUserId()) ? "admin" : "member");
        sendJson(ex, 200, res);
    }

    private void handleCreateGroup(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String name = p.get("groupName");
        if (name == null || name.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "群名不能为空"));
            return;
        }
        Group g = GroupService.getInstance().createGroup(name.trim(), me.getUserId());
        sendJson(ex, 200, map("success", "true", "groupId", g.getGroupId(), "groupName", g.getGroupName()));
    }

    private void handleGroupInvite(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().inviteMember(p.get("groupId"), me.getUserId(), p.get("targetUserId"));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupKick(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().kickMember(p.get("groupId"), me.getUserId(), p.get("targetUserId"));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupSetAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().setAdmin(p.get("groupId"), me.getUserId(), p.get("targetUserId"),
                "true".equals(p.get("isAdmin")));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupTransferOwner(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().transferOwner(p.get("groupId"), me.getUserId(), p.get("newOwnerId"));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupJoinAsAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (!isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无超级管理员权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String groupId = p.get("groupId");
        if (groupId == null || groupId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少群聊ID"));
            return;
        }
        String r = GroupService.getInstance().joinAsAdmin(groupId.trim(), me.getUserId());
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupForceAddMember(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        if (!isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "仅管理员可用"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String groupId = p.get("groupId");
        String targetUserId = p.get("targetUserId");
        if (groupId == null || groupId.trim().isEmpty() || targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        String r = GroupService.getInstance().forceAddMember(groupId.trim(), me.getUserId(), targetUserId.trim());
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupLeave(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().leaveGroup(p.get("groupId"), me.getUserId());
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupRename(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().renameGroup(p.get("groupId"), me.getUserId(), p.get("groupName"));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupMute(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String r = GroupService.getInstance().muteMember(p.get("groupId"), me.getUserId(), p.get("targetUserId"),
                parseLong(p.get("seconds"), 0));
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    private void handleGroupMuteAll(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String groupId = p.get("groupId");
        if (groupId == null || groupId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少群聊ID"));
            return;
        }
        Group g = GroupService.getInstance().getGroup(groupId);
        if (g == null) {
            sendJson(ex, 404, map("error", "群聊不存在"));
            return;
        }
        boolean newMuted = !g.isAllMuted();
        String r = GroupService.getInstance().setAllMuted(groupId, me.getUserId(), newMuted);
        if ("ok".equals(r)) {
            sendJson(ex, 200, map("success", "true", "allMuted", String.valueOf(newMuted)));
        } else {
            sendJson(ex, 400, map("error", r));
        }
    }

    private void handleGroupDeleteOldMessages(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String groupId = p.get("groupId");
        if (groupId == null || groupId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少群聊ID"));
            return;
        }
        Group g = GroupService.getInstance().getGroup(groupId);
        if (g == null) {
            sendJson(ex, 404, map("error", "群聊不存在"));
            return;
        }
        boolean isOwner = g.getOwnerId().equals(me.getUserId());
        boolean isAdmin = g.getAdmins() != null && g.getAdmins().contains(me.getUserId());
        if (!isOwner && !isAdmin && !isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        int days = Math.max(1, parseInt(p.get("days"), 1));
        long olderThanMs = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        String roomId = "group_" + groupId;
        int removed = MessageService.getInstance().deleteMessagesOlderThan(roomId, olderThanMs);
        sendJson(ex, 200, map("success", "true", "removed", String.valueOf(removed)));
    }

    private void handleGroupIcon(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        try {
            StoredFileMetadata metadata = metadataFromPath(p.get("filePath"), me);
            if (metadata == null) {
                sendJson(ex, 404, map("error", "上传文件不存在"));
                return;
            }
            String contentType = metadata.getContentType() != null ? metadata.getContentType() : "";
            if (!contentType.startsWith("image/")) {
                sendJson(ex, 400, map("error", "群图标必须是图片"));
                return;
            }
            if (metadata.getSize() > 5L * 1024 * 1024) {
                sendJson(ex, 400, map("error", "群图标不能超过5MB"));
                return;
            }
            String path = metadata.getAccessPath();
            String r = GroupService.getInstance().setGroupIcon(p.get("groupId"), me.getUserId(), path);
            sendJson(ex, "ok".equals(r) ? 200 : 400,
                    "ok".equals(r) ? map("success", "true", "iconPath", path) : map("error", r));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "上传失败"));
        }
    }

    private void handleGroupDescription(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "\u672A\u767B\u5F55"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String groupId = p.get("groupId");
        String description = p.get("description");
        if (groupId == null || groupId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "\u7F3A\u5C11\u7FA4ID"));
            return;
        }
        String r = GroupService.getInstance().setDescription(groupId, me.getUserId(), description);
        sendJson(ex, "ok".equals(r) ? 200 : 400, "ok".equals(r) ? map("success", "true") : map("error", r));
    }

    // ===== 小程序（保留 /api/games 路径兼容旧客户端） =====
    private void handleGetNotes(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, NoteService.getInstance().listNotes(me.getUserId()));
    }

    private void handleGetNote(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String noteId = parseQuery(query).get("noteId");
        if (noteId == null || noteId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少笔记ID"));
            return;
        }
        Note note = NoteService.getInstance().getNote(noteId, me.getUserId());
        if (note == null) {
            sendJson(ex, 404, map("error", "笔记不存在"));
            return;
        }
        sendJson(ex, 200, note);
    }

    private void handleCreateNote(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        Note note = NoteService.getInstance().createNote(me.getUserId(), p.get("title"));
        sendJson(ex, 200, note);
    }

    private void handleUpdateNote(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String noteId = p.get("noteId");
        if (noteId == null || noteId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少笔记ID"));
            return;
        }
        Note note = NoteService.getInstance().updateNote(me.getUserId(), noteId, p.get("title"), p.get("content"));
        if (note == null) {
            sendJson(ex, 404, map("error", "笔记不存在"));
            return;
        }
        sendJson(ex, 200, note);
    }

    private void handleDeleteNote(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String noteId = parseJson(body).get("noteId");
        if (noteId == null || noteId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少笔记ID"));
            return;
        }
        boolean ok = NoteService.getInstance().deleteNote(me.getUserId(), noteId);
        sendJson(ex, ok ? 200 : 404, ok ? map("success", "true") : map("error", "笔记不存在"));
    }

    private void handleShareNote(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String noteId = parseJson(body).get("noteId");
        if (noteId == null || noteId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少笔记ID"));
            return;
        }
        Note note = NoteService.getInstance().getNote(noteId, me.getUserId());
        if (note == null) {
            sendJson(ex, 404, map("error", "笔记不存在"));
            return;
        }
        String shareId = (note.getShareId() == null || note.getShareId().trim().isEmpty())
                ? "note_" + UUID.randomUUID().toString().replace("-", "")
                : note.getShareId();
        Note shared = NoteService.getInstance().updateShare(me.getUserId(), noteId, shareId);
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("id", shared.getShareId());
        res.put("url", "/share/note/" + shared.getShareId());
        sendJson(ex, 200, res);
    }

    private void handleGetFeedbackTickets(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        sendJson(ex, 200, isSuperAdmin(me.getUserId())
                ? FeedbackService.getInstance().listAll()
                : FeedbackService.getInstance().listByUser(me.getUserId()));
    }

    private void handleCreateFeedbackTicket(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String content = p.get("content");
        if (content == null || content.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "反馈内容不能为空"));
            return;
        }
        sendJson(ex, 200, FeedbackService.getInstance().createTicket(me.getUserId(), me.getNickname(), p.get("title"), content));
    }

    private void handleUpdateFeedbackStatus(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String ticketId = p.get("ticketId");
        String status = p.get("status");
        if (ticketId == null || ticketId.trim().isEmpty() || status == null || status.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "参数不完整"));
            return;
        }
        try {
            com.chat.model.FeedbackTicket updated = FeedbackService.getInstance().updateStatus(ticketId, status.trim());
            if (updated != null && ("verified".equals(status.trim()) || "已验证".equals(status.trim()))) {
                User submitter = UserService.getInstance().getByUserId(updated.getUserId());
                if (submitter != null) {
                    submitter.addExp(25);
                    UserService.getInstance().save();
                }
            }
            sendJson(ex, 200, updated);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    private void handleCreatePasswordRecoveryRequest(HttpExchange ex, String body) throws IOException {
        Map<String, String> values = parseJson(body);
        String username = values.get("username");
        String reason = values.get("reason");
        if (username == null || username.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请输入账户名称"));
            return;
        }
        if (reason == null || reason.trim().length() < 5 || reason.trim().length() > 500) {
            sendJson(ex, 400, map("error", "丢失理由需为 5~500 个字符"));
            return;
        }
        PasswordRecoveryService.getInstance().create(username, reason);
        sendJson(ex, 200, map("success", "true", "message", "找回申请已发送，请等待管理员处理"));
    }

    private void handleGetAnnouncements(HttpExchange ex) throws IOException {
        sendJson(ex, 200, AnnouncementService.getInstance().listAll());
    }

    private void handleGetLatestAnnouncement(HttpExchange ex) throws IOException {
        Announcement latest = AnnouncementService.getInstance().latest();
        sendJson(ex, 200, latest == null ? new LinkedHashMap<>() : latest);
    }

    private void handleCreateAnnouncement(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !isSuperAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "仅超级管理员可发布公告"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String content = p.get("content");
        if (content == null || content.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "公告内容不能为空"));
            return;
        }
        sendJson(ex, 200, AnnouncementService.getInstance().create(me, p.get("title"), content));
    }

    private void handleGetPublicRoomConfig(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        PublicRoomService.getInstance().ensureInitialized();
        sendJson(ex, 200, PublicRoomService.getInstance().getConfig());
    }

    private void handleSetPublicRoomMuteAll(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        boolean allMuted = Boolean.parseBoolean(parseJson(body).getOrDefault("allMuted", "false"));
        try {
            PublicRoomService.getInstance().setAllMuted(me.getUserId(), allMuted);
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleAddPublicRoomAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String targetUserId = parseJson(body).get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        try {
            PublicRoomService.getInstance().addAdmin(me.getUserId(), targetUserId.trim());
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleRemovePublicRoomAdmin(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String targetUserId = parseJson(body).get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        try {
            PublicRoomService.getInstance().removeAdmin(me.getUserId(), targetUserId.trim());
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleDeleteOldPublicRoomMessages(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !PublicRoomService.getInstance().isAdmin(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权限"));
            return;
        }
        int days = Math.max(1, parseInt(parseJson(body).get("days"), 1));
        long olderThanMs = System.currentTimeMillis() - days * 24L * 3600 * 1000;
        int removed = MessageService.getInstance().deleteMessagesOlderThan("public", olderThanMs);
        sendJson(ex, 200, map("success", "true", "removed", String.valueOf(removed)));
    }

    private void handleMutePublicRoomUser(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String targetUserId = parseJson(body).get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        try {
            PublicRoomService.getInstance().muteUser(me.getUserId(), targetUserId.trim());
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleUnmutePublicRoomUser(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        String targetUserId = parseJson(body).get("targetUserId");
        if (targetUserId == null || targetUserId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少用户ID"));
            return;
        }
        try {
            PublicRoomService.getInstance().unmuteUser(me.getUserId(), targetUserId.trim());
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleSetPublicRoomDescription(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "\u672A\u767B\u5F55"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String description = p.get("description");
        try {
            PublicRoomService.getInstance().setDescription(me.getUserId(), description);
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 403, map("error", e.getMessage()));
        }
    }

    private void handleGetVideos(HttpExchange ex, User me) throws IOException { if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; } sendJson(ex, 200, VideoService.getInstance().listEntries()); }
    private void handleGetVideoCategories(HttpExchange ex, User me) throws IOException { if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; } sendJson(ex, 200, VideoService.getInstance().listCategories()); }

    private void handleGetVideoComments(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, VideoService.getInstance().listComments(parseQuery(query).get("videoId")));
    }

    private void handleGetVideoDanmaku(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        sendJson(ex, 200, VideoService.getInstance().listDanmaku(parseQuery(query).get("videoId")));
    }

    private void handleCreateVideoCategory(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !isSuperAdmin(me.getUserId())) { sendJson(ex, 403, map("error", "无权限")); return; }
        sendJson(ex, 200, VideoService.getInstance().ensureCategory(parseJson(body).get("name")));
    }

    private void handleUploadVideo(HttpExchange ex, String body, User me) throws IOException {
        if (me == null || !isSuperAdmin(me.getUserId())) { sendJson(ex, 403, map("error", "无权限")); return; }
        Map<String, String> p = parseJson(body);
        VideoEntry entry = new VideoEntry();
        entry.setTitle(p.get("title"));
        entry.setDescription(p.get("description"));
        entry.setCategoryId(p.get("categoryId"));
        entry.setFilePath(p.get("filePath"));
        entry.setCoverPath(p.get("coverPath"));
        entry.setUploadedBy(me.getUserId());
        sendJson(ex, 200, VideoService.getInstance().addEntry(entry));
    }

    private void handlePlayVideo(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        VideoService.getInstance().bumpPlayCount(parseJson(body).get("videoId"));
        sendJson(ex, 200, map("success", "true"));
    }

    private void handleCreateVideoComment(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        sendJson(ex, 200, VideoService.getInstance().addComment(p.get("videoId"), me.getUserId(), me.getNickname(), p.get("content")));
    }

    private void handleCreateVideoDanmaku(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        sendJson(ex, 200, VideoService.getInstance().addDanmaku(p.get("videoId"), me.getUserId(), me.getNickname(), p.get("content"), p.get("color"), p.get("position"), parseDouble(p.get("timeSec"), 0)));
    }

    private void handleSearch(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> q = parseQuery(query);
        String keyword = q.getOrDefault("q", "").trim();
        String scope = q.getOrDefault("scope", "all"); // users|groups|messages|sent|all
        String room = q.getOrDefault("room", "");       // 可选：按聊天室筛选
        if (keyword.isEmpty()) { sendJson(ex, 200, map("users", "[]", "groups", "[]", "messages", "[]")); return; }
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if ("all".equals(scope) || "users".equals(scope)) {
            List<Map<String, Object>> users = new ArrayList<>();
            for (User u : UserService.getInstance().getAllUsers()) {
                if ((u.getNickname() != null && u.getNickname().toLowerCase().contains(keyword.toLowerCase()))
                        || (u.getUserId() != null && u.getUserId().toLowerCase().contains(keyword.toLowerCase()))) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("userId", u.getUserId());
                    m.put("nickname", u.getNickname());
                    m.put("avatarPath", u.getAvatarPath());
                    m.put("levelDisplay", u.getLevelDisplay());
                    m.put("isPrimarySuperAdmin", isPrimarySuperAdmin(u.getUserId()));
                    m.put("isSuperAdmin", isSuperAdmin(u.getUserId()));
                    m.put("isDeveloper", isDeveloper(u.getUserId()));
                    users.add(m);
                }
                if (users.size() >= 20) break;
            }
            result.put("users", users);
        }
        if ("all".equals(scope) || "groups".equals(scope)) {
            List<Map<String, Object>> groups = new ArrayList<>();
            for (com.chat.model.Group g : GroupService.getInstance().getAllGroups()) {
                if ((g.getGroupName() != null && g.getGroupName().toLowerCase().contains(keyword.toLowerCase()))
                        || (g.getGroupId() != null && g.getGroupId().toLowerCase().contains(keyword.toLowerCase()))) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("groupId", g.getGroupId());
                    m.put("groupName", g.getGroupName());
                    m.put("memberCount", g.getMembers() != null ? g.getMembers().size() : 0);
                    groups.add(m);
                }
                if (groups.size() >= 20) break;
            }
            result.put("groups", groups);
        }
        if ("all".equals(scope) || "messages".equals(scope) || "sent".equals(scope)) {
            List<Map<String, Object>> msgs = new ArrayList<>();
            for (com.chat.model.Message msg : MessageService.getInstance().searchMessages(keyword, 30)) {
                if (!msg.isRecalled() && msg.getContent() != null) {
                    // "sent" 范围仅搜索自己发送的消息
                    if ("sent".equals(scope) && !msg.getFromUserId().equals(me.getUserId())) continue;
                    // 按聊天室筛选
                    if (!room.isEmpty() && !room.equals(msg.getChatRoomId())) continue;
                    boolean canSee = canUserReadMessage(me, msg);
                    if (canSee) {
                        Map<String, Object> m = new java.util.LinkedHashMap<>();
                        m.put("id", msg.getId());
                        m.put("content", msg.getContent().length() > 100 ? msg.getContent().substring(0, 100) + "..." : msg.getContent());
                        m.put("chatRoomId", msg.getChatRoomId());
                        m.put("fromNickname", msg.getFromNickname());
                        m.put("fromUserId", msg.getFromUserId());
                        m.put("isDeveloper", isDeveloper(msg.getFromUserId()));
                        m.put("timestamp", msg.getTimestamp());
                        m.put("msgType", msg.getMsgType());
                        msgs.add(m);
                    }
                }
                if (msgs.size() >= 20) break;
            }
            result.put("messages", msgs);
        }
        if ("all".equals(scope) || "ai".equals(scope)) {
            List<Map<String, Object>> aiConvs = new ArrayList<>();
            for (com.chat.model.AiConversation c : AiService.getInstance().listConversations(me.getUserId())) {
                if (c.getTitle() != null && c.getTitle().toLowerCase().contains(keyword.toLowerCase())) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("title", c.getTitle());
                    m.put("type", c.getType());
                    m.put("modelId", c.getModelId());
                    m.put("updatedAt", c.getUpdatedAt());
                    aiConvs.add(m);
                }
                if (aiConvs.size() >= 20) break;
            }
            result.put("aiConversations", aiConvs);
        }
        sendJson(ex, 200, result);
    }

    private void handleGetShareData(HttpExchange ex, String query, User me) throws IOException {
        Map<String, String> p = parseQuery(query);
        String type = p.get("type");
        String id = p.get("id");
        if (type == null || id == null) { sendJson(ex, 400, map("error", "参数不完整")); return; }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("type", type);
        switch (type) {
            case "note": {
                Note note = NoteService.getInstance().getByShareId(id);
                if (note == null) { sendJson(ex, 404, map("error", "分享不存在")); return; }
                User owner = UserService.getInstance().getByUserId(note.getOwnerId());
                res.put("ownerNickname", owner != null ? owner.getNickname() : note.getOwnerId());
                res.put("ownerIsDeveloper", isDeveloper(note.getOwnerId()));
                res.put("title", note.getTitle());
                res.put("content", note.getContent());
                res.put("updatedAt", note.getUpdatedAt());
                break;
            }
            case "cloud": {
                CloudShareLink share = CloudService.getInstance().getShare(id);
                if (share == null) { sendJson(ex, 404, map("error", "分享不存在")); return; }
                CloudEntry entry = CloudService.getInstance().getEntry(share.getEntryId());
                if (entry == null || entry.isDeleted()) { sendJson(ex, 404, map("error", "分享文件不存在")); return; }
                User owner = UserService.getInstance().getByUserId(share.getOwnerId());
                res.put("share", CloudEntryMapper.cloudShareToMap(share));
                Map<String, Object> sharedEntry = CloudEntryMapper.cloudEntryToMap(entry);
                if (sharedEntry != null && !entry.isFolder()) {
                    sharedEntry.put("filePath", "/shared-cloud-files/" + share.getId());
                }
                res.put("entry", sharedEntry);
                res.put("ownerNickname", owner != null ? owner.getNickname() : share.getOwnerId());
                res.put("ownerIsDeveloper", isDeveloper(share.getOwnerId()));
                break;
            }
            case "music": {
                MusicTrack track = MusicService.getInstance().getTrack(id);
                if (track == null) { sendJson(ex, 404, map("error", "歌曲不存在")); return; }
                sendJson(ex, 200, track);
                return;
            }
            case "video": {
                VideoEntry video = VideoService.getInstance().getEntry(id);
                if (video == null) { sendJson(ex, 404, map("error", "视频不存在")); return; }
                sendJson(ex, 200, video);
                return;
            }
            case "game": {
                Map<String, Object> game = GameService.getInstance().getGame(id, me, me != null && isSuperAdmin(me.getUserId()));
                if (game == null) { sendJson(ex, 404, map("error", "小程序不存在")); return; }
                game.put("developerIsDeveloper", isDeveloper(String.valueOf(game.getOrDefault("developerId", ""))));
                sendJson(ex, 200, game);
                return;
            }
            default:
                sendJson(ex, 400, map("error", "不支持的分享类型"));
                return;
        }
        sendJson(ex, 200, res);
    }

    private void handleSendShareCard(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String type = p.get("type");
        String id = p.get("id");
        String room = p.get("room");
        if (type == null || id == null || room == null) { sendJson(ex, 400, map("error", "参数不完整")); return; }
        room = normalizeWritableRoom(me, room);
        if (!ensureRoomWriteAllowed(ex, me, room)) return;
        Map<String, Object> payload = buildShareCardPayload(type, id, me);
        Message msg = MessageService.getInstance().sendCardMessage(me.getUserId(), me.getNickname(), room, type, JsonUtil.toJson(payload), String.valueOf(payload.get("title")));
        sendJson(ex, 200, mapMessage(msg));
    }

    
    private void handleCheckIn(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        java.time.LocalDate todayDate = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"));
        String today = todayDate.toString();
        boolean alreadyCheckedIn = today.equals(me.getLastCheckIn());
        int expGained = 0;
        if (!alreadyCheckedIn) {
            int streak = 1;
            try {
                java.time.LocalDate previous = java.time.LocalDate.parse(me.getLastCheckIn());
                if (previous.plusDays(1).equals(todayDate)) streak = Math.max(1, me.getCheckInStreak()) + 1;
            } catch (Exception ignored) { }
            me.setCheckInStreak(streak);
            me.setLastCheckIn(today);
            me.addExp(15);
            expGained = 15;
            UserService.getInstance().save();
        } else if (me.getCheckInStreak() <= 0) {
            me.setCheckInStreak(1);
            UserService.getInstance().save();
        }
        Map<String, Object> res = new java.util.LinkedHashMap<>();
        res.put("success", true);
        res.put("alreadyCheckedIn", alreadyCheckedIn);
        res.put("expGained", expGained);
        res.put("totalExp", me.getExp());
        res.put("level", me.getEffectiveLevel());
        res.put("levelDisplay", me.getLevelDisplay());
        res.put("nextLevelExp", getNextLevelExp(me));
        res.put("lastCheckIn", me.getLastCheckIn());
        res.put("checkInStreak", me.getCheckInStreak());
        sendJson(ex, 200, res);
    }

    private void handleTutorialComplete(HttpExchange ex, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        me.setTutorialCompleted(true);
        UserService.getInstance().save();
        sendJson(ex, 200, map("success", "true"));
    }

    private void handleDecodeQR(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String imageData = p.get("image");
        if (imageData == null || imageData.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请提供图片数据"));
            return;
        }
        try {
            if (imageData.contains(",")) {
                imageData = imageData.substring(imageData.indexOf(",") + 1);
            }
            byte[] imgBytes = Base64.getDecoder().decode(imageData);
            BufferedImage img;
            try (InputStream bais = new ByteArrayInputStream(imgBytes)) {
                img = ImageIO.read(bais);
            }
            if (img == null) {
                sendJson(ex, 400, map("error", "无法解析图片"));
                return;
            }
            LuminanceSource source = new BufferedImageLuminanceSource(img);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap);
            sendJson(ex, 200, map("success", "true", "text", result.getText()));
        } catch (NotFoundException e) {
            sendJson(ex, 200, map("success", "false", "error", "未识别到二维码"));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "解码失败: " + e.getMessage()));
        }
    }

    private void handleEncodeQR(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) {
            sendJson(ex, 401, map("error", "未登录"));
            return;
        }
        Map<String, String> p = parseJson(body);
        String text = p.get("text");
        if (text == null || text.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "请输入要编码的内容"));
            return;
        }
        if (text.length() > 4096) {
            sendJson(ex, 400, map("error", "编码内容不能超过 4096 个字符"));
            return;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name());
            hints.put(EncodeHintType.MARGIN, 2);
            com.google.zxing.common.BitMatrix matrix = new MultiFormatWriter()
                    .encode(text, BarcodeFormat.QR_CODE, 360, 360, hints);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", output);
            String image = "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
            sendJson(ex, 200, map("success", "true", "image", image));
        } catch (WriterException e) {
            sendJson(ex, 400, map("error", "二维码内容过长或格式不受支持"));
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "编码失败: " + e.getMessage()));
        }
    }

    private void handleApiLogout(HttpExchange ex, String sid, User me) throws IOException {
        if (sid != null) {
            SessionManager.getInstance().removeSession(sid);
        }
        clearSessionCookie(ex);
        sendJson(ex, 200, map("success", "true"));
    }

    private void doLogout(HttpExchange ex, String sid, User me) throws IOException {
        if (sid != null)
            SessionManager.getInstance().removeSession(sid);
        clearSessionCookie(ex);
        ex.getResponseHeaders().add("Location", "/login");
        ex.sendResponseHeaders(302, -1);
    }

    private void sendSessionCookie(HttpExchange ex, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            clearSessionCookie(ex);
            return;
        }
        SessionCookieSecurity.setSessionCookies(ex, sessionId, SessionManager.SESSION_MAX_AGE_SECONDS);
    }

    private void clearSessionCookie(HttpExchange ex) {
        SessionCookieSecurity.clearAllCookies(ex, ex.getRequestHeaders().getFirst("Cookie"));
    }

    // ======================== 工具方法 ========================
    @SuppressWarnings("unchecked")
    private Map<String, Object> postJson(String url, String apiKey, Map<String, Object> body) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body), StandardCharsets.UTF_8))
                .build();
        java.net.http.HttpResponse<String> response = SHARED_HTTP_CLIENT
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> data = parseJsonObj(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException(asString(asObjectMap(data.get("error")).get("message")).isEmpty()
                    ? asString(data.get("message"))
                    : asString(asObjectMap(data.get("error")).get("message")));
        }
        return data;
    }

    private Map<String, Object> buildShareCardPayload(String type, String id, User me) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shareType", type);
        switch (type) {
            case "note": {
                Note note = NoteService.getInstance().getNote(id, me.getUserId());
                if (note == null) throw new IllegalArgumentException("笔记不存在");
                String shareId = note.getShareId() == null || note.getShareId().trim().isEmpty()
                        ? ("note_" + UUID.randomUUID().toString().replace("-", ""))
                        : note.getShareId();
                note = NoteService.getInstance().updateShare(me.getUserId(), id, shareId);
                payload.put("title", note.getTitle());
                payload.put("subtitle", "来自 " + me.getNickname());
                payload.put("developerIsDeveloper", isDeveloper(me.getUserId()));
                payload.put("contentPreview", note.getContent());
                payload.put("url", "/share/note/" + shareId);
                break;
            }
            case "cloud": {
                CloudShareLink share = CloudService.getInstance().createShare(me.getUserId(), id, null, "cloud");
                CloudEntry entry = CloudService.getInstance().getEntry(id);
                payload.put("title", entry != null ? entry.getName() : "文件");
                payload.put("subtitle", "云盘分享");
                payload.put("developerIsDeveloper", isDeveloper(me.getUserId()));
                payload.put("coverPath", entry != null && entry.getContentType() != null && entry.getContentType().startsWith("image/") ? "/files/" + entry.getStoredName() : "");
                payload.put("url", "/share/cloud/" + share.getId());
                payload.put("filePath", entry != null ? "/files/" + entry.getStoredName() : "");
                break;
            }
            case "music": {
                MusicTrack track = MusicService.getInstance().getTrack(id);
                if (track == null) throw new IllegalArgumentException("歌曲不存在");
                payload.put("title", track.getTitle());
                payload.put("subtitle", track.getArtist());
                payload.put("coverPath", track.getCover());
                payload.put("filePath", track.getFilePath());
                payload.put("url", "/share/music/" + id);
                break;
            }
            case "video": {
                VideoEntry video = VideoService.getInstance().getEntry(id);
                if (video == null) throw new IllegalArgumentException("视频不存在");
                payload.put("title", video.getTitle());
                payload.put("subtitle", video.getDescription());
                payload.put("coverPath", video.getCoverPath());
                payload.put("filePath", video.getFilePath());
                payload.put("url", "/share/video/" + id);
                break;
            }
            case "game": {
                Map<String, Object> game = GameService.getInstance().getGame(id, me, isSuperAdmin(me.getUserId()));
                if (game == null) throw new IllegalArgumentException("小程序不存在");
                payload.put("title", String.valueOf(game.get("title")));
                payload.put("subtitle", String.valueOf(game.getOrDefault("developerNickname", "")));
                payload.put("developerIsDeveloper", isDeveloper(String.valueOf(game.getOrDefault("developerId", ""))));
                payload.put("coverPath", String.valueOf(game.getOrDefault("coverPath", "")));
                payload.put("contentPreview", String.valueOf(game.getOrDefault("desc", "")));
                payload.put("url", "/share/game/" + id);
                break;
            }
            default:
                throw new IllegalArgumentException("不支持的分享类型");
        }
        return payload;
    }

    // ======================== 分享页 ========================

}
