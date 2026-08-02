package com.chat.service;

import com.chat.model.PublicRoomConfig;
import com.chat.util.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PublicRoomService {
    private static final PublicRoomService INSTANCE = new PublicRoomService();
    private static Path dataFile() { return Paths.get("chatserver", "public-room", "config.json"); }

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private PublicRoomConfig config = new PublicRoomConfig();

    public static PublicRoomService getInstance() {
        return INSTANCE;
    }

    private PublicRoomService() {
        try {
            Files.createDirectories(dataFile().getParent());
        } catch (Exception ignored) {
        }
        load();
    }

    public PublicRoomConfig getConfig() {
        lock.readLock().lock();
        try {
            return JsonUtil.fromJson(JsonUtil.toJson(config), PublicRoomConfig.class);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void ensureInitialized() {
        lock.writeLock().lock();
        try {
            if (config.getOwnerId() == null || config.getOwnerId().trim().isEmpty()) {
                config.setOwnerId(SuperAdminService.getInstance().getPrimarySuperAdminId());
                List<String> admins = new ArrayList<>(SuperAdminService.getInstance().listSuperAdminIds());
                admins.removeIf(id -> Objects.equals(id, config.getOwnerId()));
                config.setAdminIds(admins);
                config.setUpdatedAt(System.currentTimeMillis());
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isAdmin(String userId) {
        lock.readLock().lock();
        try {
            return userId != null && (Objects.equals(userId, config.getOwnerId()) || config.getAdminIds().contains(userId));
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isOwner(String userId) {
        lock.readLock().lock();
        try {
            return Objects.equals(userId, config.getOwnerId());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void setAllMuted(String operatorId, boolean allMuted) {
        lock.writeLock().lock();
        try {
            if (!isAdmin(operatorId)) {
                throw new IllegalArgumentException("无权限");
            }
            config.setAllMuted(allMuted);
            config.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void addAdmin(String operatorId, String targetUserId) {
        lock.writeLock().lock();
        try {
            if (!isOwner(operatorId)) {
                throw new IllegalArgumentException("只有服主可设置公共聊天室管理员");
            }
            if (!config.getAdminIds().contains(targetUserId)) {
                config.getAdminIds().add(targetUserId);
                config.setUpdatedAt(System.currentTimeMillis());
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeAdmin(String operatorId, String targetUserId) {
        lock.writeLock().lock();
        try {
            if (!isOwner(operatorId)) {
                throw new IllegalArgumentException("只有服主可移除公共聊天室管理员");
            }
            config.getAdminIds().remove(targetUserId);
            config.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isMuted(String userId) {
        lock.readLock().lock();
        try {
            return userId != null && config.getMutedUserIds().contains(userId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public void muteUser(String operatorId, String targetUserId) {
        lock.writeLock().lock();
        try {
            if (!isAdmin(operatorId)) {
                throw new IllegalArgumentException("无权限");
            }
            if (!config.getMutedUserIds().contains(targetUserId)) {
                config.getMutedUserIds().add(targetUserId);
                config.setUpdatedAt(System.currentTimeMillis());
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void unmuteUser(String operatorId, String targetUserId) {
        lock.writeLock().lock();
        try {
            if (!isAdmin(operatorId)) {
                throw new IllegalArgumentException("无权限");
            }
            config.getMutedUserIds().remove(targetUserId);
            config.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setDescription(String operatorId, String description) {
        lock.writeLock().lock();
        try {
            if (!isAdmin(operatorId)) {
                throw new IllegalArgumentException("无权限");
            }
            config.setDescription(description != null ? description.trim() : null);
            config.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(dataFile())) {
                ensureInitialized();
                return;
            }
            String json = Files.readString(dataFile(), StandardCharsets.UTF_8).trim();
            if (!json.isEmpty()) {
                PublicRoomConfig loaded = JsonUtil.fromJson(json, PublicRoomConfig.class);
                if (loaded != null) {
                    config = loaded;
                }
            }
            ensureInitialized();
        } catch (Exception e) {
            System.err.println("[PublicRoomService] 加载失败: " + e.getMessage());
            try {
                Path backup = dataFile().resolveSibling(dataFile().getFileName() + ".bak");
                Files.copy(dataFile(), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[PublicRoomService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(dataFile().getParent());
            com.chat.util.JsonUtil.saveJsonAtomic(dataFile(), config);
        } catch (Exception e) {
            System.err.println("[PublicRoomService] 保存失败: " + e.getMessage());
        }
    }
}
