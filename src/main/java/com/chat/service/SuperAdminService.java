package com.chat.service;

import com.chat.model.User;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SuperAdminService {
    private static final Path DATA_FILE = Paths.get("chatserver", "superadmins.txt");
    private static final Path COOWNERS_FILE = Paths.get("chatserver", "coowners.txt");
    private static final String PRIMARY_SUPER_ADMIN_USERNAME = "陈梓仁";
    private static final String LEGACY_PRIMARY_SUPER_ADMIN_USERNAME = "chenziren";
    private static final String DEFAULT_SUPER_ADMIN = PRIMARY_SUPER_ADMIN_USERNAME;
    private static final SuperAdminService INSTANCE = new SuperAdminService();

    private final Set<String> superAdmins = new LinkedHashSet<>();
    private final Set<String> coOwners = new LinkedHashSet<>();

    public static SuperAdminService getInstance() {
        return INSTANCE;
    }

    private SuperAdminService() {
        load();
    }

    public synchronized boolean isSuperAdmin(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return superAdmins.contains(userId) || isCoOwner(userId) || userId.equals(resolvePrimarySuperAdminId());
    }
    
    public synchronized boolean isCoOwner(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        return coOwners.contains(userId);
    }

    public synchronized List<String> listSuperAdminIds() {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        String primaryId = resolvePrimarySuperAdminId();
        if (primaryId != null && !primaryId.trim().isEmpty()) {
            merged.add(primaryId);
        }
        merged.addAll(coOwners);
        merged.addAll(superAdmins);
        return new ArrayList<>(merged);
    }

    public synchronized String getPrimarySuperAdminId() {
        return resolvePrimarySuperAdminId();
    }

    public synchronized boolean addSuperAdmin(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        boolean changed = superAdmins.add(userId.trim());
        if (changed) {
            saveUnsafe();
        }
        return changed;
    }
    
    public synchronized boolean addCoOwner(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return false;
        }
        boolean changed = coOwners.add(userId.trim());
        if (changed) {
            superAdmins.remove(userId.trim()); // Remove from normal admins if present
            saveUnsafe();
        }
        return changed;
    }

    public synchronized boolean removeSuperAdmin(String userId) {
        if (userId == null || !superAdmins.contains(userId)) {
            return false;
        }
        String primaryId = resolvePrimarySuperAdminId();
        if (userId.equals(primaryId)) {
            return false;
        }
        boolean changed = superAdmins.remove(userId);
        if (changed) {
            saveUnsafe();
        }
        return changed;
    }
    
    public synchronized boolean removeCoOwner(String userId) {
        if (userId == null || !coOwners.contains(userId)) {
            return false;
        }
        String primaryId = resolvePrimarySuperAdminId();
        if (userId.equals(primaryId)) {
            return false;
        }
        boolean changed = coOwners.remove(userId);
        if (changed) {
            saveUnsafe();
        }
        return changed;
    }

    private synchronized void load() {
        superAdmins.clear();
        coOwners.clear();
        try {
            if (Files.exists(DATA_FILE)) {
                Files.readAllLines(DATA_FILE, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(superAdmins::add);
            }
            if (Files.exists(COOWNERS_FILE)) {
                Files.readAllLines(COOWNERS_FILE, StandardCharsets.UTF_8).stream()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(coOwners::add);
            }
        } catch (Exception e) {
            System.err.println("[SuperAdminService] 加载失败: " + e.getMessage());
        }
    }

    private String resolvePrimarySuperAdminId() {
        try {
            UserService userService = UserService.getInstance();
            if (userService != null) {
                User byPrimaryUsername = userService.getByUsername(PRIMARY_SUPER_ADMIN_USERNAME);
                if (byPrimaryUsername != null) {
                    return byPrimaryUsername.getUserId();
                }
                User byLegacyUsername = userService.getByUsername(LEGACY_PRIMARY_SUPER_ADMIN_USERNAME);
                if (byLegacyUsername != null) {
                    return byLegacyUsername.getUserId();
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_SUPER_ADMIN;
    }

    private void saveUnsafe() {
        try {
            Path parent = DATA_FILE.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            com.chat.util.JsonUtil.writeLinesAtomic(DATA_FILE, superAdmins);
            com.chat.util.JsonUtil.writeLinesAtomic(COOWNERS_FILE, coOwners);
        } catch (Exception e) {
            System.err.println("[SuperAdminService] 保存失败: " + e.getMessage());
        }
    }
}

