package com.chat.service;

import com.chat.model.PasswordRecoveryRequest;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class PasswordRecoveryService {
    private static final PasswordRecoveryService INSTANCE = new PasswordRecoveryService();
    private static final Path DATA_FILE = Paths.get("chatserver", "password-recovery", "requests.json");

    public static PasswordRecoveryService getInstance() { return INSTANCE; }

    private final List<PasswordRecoveryRequest> requests = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private PasswordRecoveryService() { load(); }

    public PasswordRecoveryRequest create(String username, String reason) {
        PasswordRecoveryRequest request = new PasswordRecoveryRequest();
        request.setId("recovery_" + UUID.randomUUID().toString().replace("-", ""));
        request.setUsername(username.trim());
        request.setReason(reason.trim());
        request.setAccountExists(UserService.getInstance().existsByUsername(username.trim()));
        request.setStatus("open");
        request.setCreatedAt(System.currentTimeMillis());
        request.setUpdatedAt(request.getCreatedAt());
        lock.writeLock().lock();
        try {
            requests.add(request);
            saveUnsafe();
            return request;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<PasswordRecoveryRequest> list() {
        lock.readLock().lock();
        try {
            List<PasswordRecoveryRequest> copy = new ArrayList<>(requests);
            copy.sort(Comparator.comparingLong(PasswordRecoveryRequest::getCreatedAt).reversed());
            return copy;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean updateStatus(String id, String status) {
        if (!"open".equals(status) && !"processed".equals(status) && !"rejected".equals(status)) return false;
        lock.writeLock().lock();
        try {
            for (PasswordRecoveryRequest request : requests) {
                if (!request.getId().equals(id)) continue;
                request.setStatus(status);
                request.setUpdatedAt(System.currentTimeMillis());
                saveUnsafe();
                return true;
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            if (!Files.exists(DATA_FILE)) return;
            Type type = new TypeToken<List<PasswordRecoveryRequest>>() { }.getType();
            List<PasswordRecoveryRequest> loaded = JsonUtil.fromJson(Files.readString(DATA_FILE, StandardCharsets.UTF_8), type);
            if (loaded != null) requests.addAll(loaded);
        } catch (Exception e) {
            System.err.println("[PasswordRecoveryService] 加载失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveUnsafe() {
        try {
            JsonUtil.saveJsonAtomic(DATA_FILE, requests);
        } catch (Exception e) {
            System.err.println("[PasswordRecoveryService] 保存失败: " + e.getMessage());
        }
    }
}
