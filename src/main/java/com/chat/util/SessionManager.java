package com.chat.util;

import com.chat.model.SessionRecord;
import com.chat.model.User;
import com.chat.service.UserService;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class SessionManager {
    public static final long SESSION_MAX_AGE_SECONDS = 30L * 24L * 3600L;
    private static final long EXPIRED_SESSION_CLEANUP_INTERVAL_MS = 5L * 60L * 1000L;
    private static final Path DATA_FILE = Paths.get("chatserver", "sessions.json");
    private static final SessionManager INSTANCE = new SessionManager();

    public static SessionManager getInstance() {
        return INSTANCE;
    }

    private final Map<String, SessionRecord> sessions = new ConcurrentHashMap<>();
    private volatile boolean dirty = false;
    private volatile long lastExpiredSessionCleanupAt = 0L;

    private SessionManager() {
        load();
        
        Thread saveThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                    long now = System.currentTimeMillis();
                    if (now - lastExpiredSessionCleanupAt >= EXPIRED_SESSION_CLEANUP_INTERVAL_MS) {
                        lastExpiredSessionCleanupAt = now;
                        if (purgeExpiredSessions(now) > 0) {
                            save();
                        }
                    }
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

    public synchronized String createSession(User user) {
        String sessionId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        SessionRecord record = new SessionRecord();
        record.setSessionId(sessionId);
        record.setUserId(user.getUserId());
        record.setCreatedAt(now);
        record.setExpiresAt(now + SESSION_MAX_AGE_SECONDS * 1000L);
        sessions.put(sessionId, record);
        save();
        return sessionId;
    }

    public synchronized User getUser(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        SessionRecord record = sessions.get(sessionId);
        if (record == null) {
            return null;
        }
        if (record.getExpiresAt() > 0 && System.currentTimeMillis() > record.getExpiresAt()) {
            sessions.remove(sessionId);
            save();
            return null;
        }
        User user = UserService.getInstance().getByUserId(record.getUserId());
        if (user == null) {
            sessions.remove(sessionId);
            save();
        }
        return user;
    }

    public synchronized void refreshSession(String sessionId) {
        SessionRecord record = sessions.get(sessionId);
        if (record == null) {
            return;
        }
        record.setExpiresAt(System.currentTimeMillis() + SESSION_MAX_AGE_SECONDS * 1000L);
        save();
    }

    public synchronized void removeSession(String sessionId) {
        if (sessions.remove(sessionId) != null) {
            save();
        }
    }

    public synchronized void removeSessionsForUser(String userId) {
        boolean changed = sessions.entrySet().removeIf(entry -> userId.equals(entry.getValue().getUserId()));
        if (changed) {
            save();
        }
    }

    public synchronized int getActiveSessionCount(String userId) {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream()
                .filter(record -> userId.equals(record.getUserId()))
                .filter(record -> record.getExpiresAt() <= 0 || record.getExpiresAt() > now)
                .count();
    }

    public synchronized int getTotalActiveSessions() {
        long now = System.currentTimeMillis();
        return (int) sessions.values().stream()
                .filter(record -> record.getExpiresAt() <= 0 || record.getExpiresAt() > now)
                .count();
    }

    public String getSessionIdFromCookie(String cookieHeader) {
        if (cookieHeader == null) {
            return null;
        }
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("sessionId=")) {
                return trimmed.substring("sessionId=".length());
            }
        }
        return null;
    }

    private synchronized void load() {
        sessions.clear();
        try {
            Path parent = DATA_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (!Files.exists(DATA_FILE)) {
                return;
            }
            String json = Files.readString(DATA_FILE, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            Type type = new TypeToken<List<SessionRecord>>() { }.getType();
            List<SessionRecord> loaded = JsonUtil.fromJson(json, type);
            long now = System.currentTimeMillis();
            if (loaded != null) {
                int accepted = 0;
                for (SessionRecord record : loaded) {
                    if (record == null || record.getSessionId() == null || record.getUserId() == null
                            || isExpired(record, now)) {
                        continue;
                    }
                    sessions.put(record.getSessionId(), record);
                    accepted++;
                }
                // Rewrite the file once after startup when stale or malformed rows were discarded.
                dirty = accepted != loaded.size();
            }
        } catch (Exception e) {
            System.err.println("[SessionManager] 加载失败: " + e.getMessage());
        }
    }

    private void save() {
        dirty = true;
    }

    synchronized int purgeExpiredSessions(long now) {
        int before = sessions.size();
        sessions.entrySet().removeIf(entry -> isExpired(entry.getValue(), now));
        return before - sessions.size();
    }

    static boolean isExpired(SessionRecord record, long now) {
        return record == null || (record.getExpiresAt() > 0 && record.getExpiresAt() <= now);
    }

    private synchronized void saveSync() {
        if (!dirty) {
            return;
        }
        try {
            purgeExpiredSessions(System.currentTimeMillis());
            Path parent = DATA_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<SessionRecord> snapshot = new ArrayList<>(sessions.values().stream()
                    .filter(record -> record.getSessionId() != null && record.getUserId() != null)
                    .collect(Collectors.toList()));
            com.chat.util.JsonUtil.saveJsonAtomic(DATA_FILE, snapshot);
            dirty = false;
        } catch (Exception e) {
            System.err.println("[SessionManager] 保存失败: " + e.getMessage());
        }
    }
}
