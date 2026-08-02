package com.chat.service;

import com.chat.model.Group;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class GroupService {
    private static final GroupService INSTANCE = new GroupService();

    private static final String DATA_DIR = "chatserver/groups";
    private static final String DATA_FILE = "chatserver/groups/groups.json";

    private final Map<String, Group> groups = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static GroupService getInstance() {
        return INSTANCE;
    }

    private GroupService() {
        try {
            Files.createDirectories(Paths.get(DATA_DIR));
        } catch (Exception ignored) {
        }
        load();
    }

    private void load() {
        File file = new File(DATA_FILE);
        if (!file.exists()) {
            return;
        }
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<Group>>() { }.getType();
            List<Group> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                for (Group group : list) {
                    // 旧版存档兼容迁移
                    if (group.getMutedUntil() == null) {
                        group.setMutedUntil(new ConcurrentHashMap<>());
                    } else {
                        group.setMutedUntil(new ConcurrentHashMap<>(group.getMutedUntil()));
                    }
                    if (group.getMembers() == null) {
                        group.setMembers(new ArrayList<>());
                    } else {
                        group.setMembers(new ArrayList<>(group.getMembers()));
                    }
                    if (group.getAdmins() == null) {
                        group.setAdmins(new ArrayList<>());
                    } else {
                        group.setAdmins(new ArrayList<>(group.getAdmins()));
                    }
                    groups.put(group.getGroupId(), group);
                    try {
                        long num = Long.parseLong(group.getGroupId().replace("g", ""));
                        if (num >= idCounter.get()) {
                            idCounter.set(num + 1);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            System.out.println("[GroupService] 已加载 " + groups.size() + " 个群组");
        } catch (Exception e) {
            System.err.println("[GroupService] 加载失败: " + e.getMessage());
            // 旧版存档兼容：备份损坏文件
            try {
                Path backup = Paths.get(DATA_FILE + ".bak");
                Files.copy(Paths.get(DATA_FILE), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[GroupService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        lock.writeLock().lock();
        try {
            List<Group> snapshots = new ArrayList<>();
            for (Group g : groups.values()) {
                Group copy = new Group();
                copy.setGroupId(g.getGroupId());
                copy.setGroupName(g.getGroupName());
                copy.setOwnerId(g.getOwnerId());
                copy.setCreatedAt(g.getCreatedAt());
                copy.setIconPath(g.getIconPath());
                copy.setDescription(g.getDescription());
                copy.setAllMuted(g.isAllMuted());
                copy.setAllMutedAt(g.getAllMutedAt());
                synchronized (g.getMembers()) {
                    copy.setMembers(new ArrayList<>(g.getMembers()));
                }
                synchronized (g.getAdmins()) {
                    copy.setAdmins(new ArrayList<>(g.getAdmins()));
                }
                copy.setMutedUntil(new ConcurrentHashMap<>(g.getMutedUntil()));
                snapshots.add(copy);
            }
            String json = JsonUtil.toJson(snapshots);
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(DATA_FILE), json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[GroupService] 保存失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Group createGroup(String groupName, String ownerId) {
        String groupId = "g" + idCounter.getAndIncrement();
        Group group = new Group(groupId, groupName, ownerId);
        groups.put(groupId, group);
        save();
        return group;
    }

    public Group getGroup(String groupId) {
        return groups.get(groupId);
    }

    public List<Group> getGroupsOfUser(String userId) {
        return groups.values().stream()
                .filter(group -> group.isMember(userId))
                .collect(Collectors.toList());
    }

    public List<Group> getAllGroups() {
        return groups.values().stream()
                .sorted(Comparator.comparingLong(Group::getCreatedAt).reversed())
                .collect(Collectors.toList());
    }

    private boolean isPrimarySuperAdmin(String userId) {
        return userId != null && userId.equals(SuperAdminService.getInstance().getPrimarySuperAdminId());
    }

    private boolean isOwnerEquivalent(Group group, String userId) {
        return group != null && (group.isOwner(userId) || (isPrimarySuperAdmin(userId) && group.isMember(userId)));
    }

    private boolean canManage(Group group, String userId) {
        return group != null && (group.canManage(userId) || isOwnerEquivalent(group, userId));
    }

    private boolean grantOwnerEquivalentStateIfNeeded(Group group, String userId) {
        if (group == null || !isPrimarySuperAdmin(userId) || group.isOwner(userId) || group.isAdmin(userId)) {
            return false;
        }
        synchronized (group.getAdmins()) {
            if (!group.isAdmin(userId)) {
                group.getAdmins().add(userId);
                return true;
            }
        }
        return false;
    }

    public String inviteMember(String groupId, String operatorId, String targetUserId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        if (UserService.getInstance().getByUserId(targetUserId) == null) {
            return "用户不存在";
        }
        com.chat.model.User owner = UserService.getInstance().getByUserId(group.getOwnerId());
        if (!isOwnerEquivalent(group, operatorId)) {
            if (owner == null || !owner.getFriends().contains(targetUserId)) {
                return "只能邀请群主的好友";
            }
        }
        synchronized (group.getMembers()) {
            if (group.isMember(targetUserId)) {
                return "已经是成员";
            }
            group.getMembers().add(targetUserId);
        }
        grantOwnerEquivalentStateIfNeeded(group, targetUserId);
        save();
        return "ok";
    }

    public String forceAddMember(String groupId, String operatorId, String targetUserId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (UserService.getInstance().getByUserId(targetUserId) == null) {
            return "用户不存在";
        }
        synchronized (group.getMembers()) {
            if (group.isMember(targetUserId)) {
                return "已经是成员";
            }
            group.getMembers().add(targetUserId);
        }
        grantOwnerEquivalentStateIfNeeded(group, targetUserId);
        save();
        return "ok";
    }

    public String joinAsAdmin(String groupId, String userId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (UserService.getInstance().getByUserId(userId) == null) {
            return "用户不存在";
        }
        boolean changed = false;
        synchronized (group.getMembers()) {
            if (!group.isMember(userId)) {
                group.getMembers().add(userId);
                changed = true;
            }
        }
        if (grantOwnerEquivalentStateIfNeeded(group, userId)) {
            changed = true;
        }
        synchronized (group.getAdmins()) {
            if (!group.isOwner(userId) && !group.isAdmin(userId)) {
                group.getAdmins().add(userId);
                changed = true;
            }
        }
        if (changed) {
            save();
        }
        return "ok";
    }

    public String kickMember(String groupId, String operatorId, String targetUserId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        if (SuperAdminService.getInstance().isSuperAdmin(targetUserId)) {
            return "不能移除超级管理员";
        }
        if (group.isOwner(targetUserId)) {
            return "不能踢出群主";
        }
        if (!isOwnerEquivalent(group, operatorId) && group.isAdmin(targetUserId)) {
            return "管理员不能踢管理员";
        }
        synchronized (group.getMembers()) {
            group.getMembers().remove(targetUserId);
        }
        synchronized (group.getAdmins()) {
            group.getAdmins().remove(targetUserId);
        }
        save();
        return "ok";
    }

    public String setAdmin(String groupId, String ownerId, String targetUserId, boolean isAdmin) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!isOwnerEquivalent(group, ownerId)) {
            return "只有群主可以设置管理员";
        }
        if (!group.isMember(targetUserId)) {
            return "对方不是群成员";
        }
        synchronized (group.getAdmins()) {
            if (isAdmin) {
                if (!group.getAdmins().contains(targetUserId)) {
                    group.getAdmins().add(targetUserId);
                }
            } else {
                group.getAdmins().remove(targetUserId);
            }
        }
        save();
        return "ok";
    }

    public String transferOwner(String groupId, String ownerId, String newOwnerId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!isOwnerEquivalent(group, ownerId)) {
            return "你不是群主";
        }
        if (!group.isMember(newOwnerId)) {
            return "对方不是群成员";
        }
        group.setOwnerId(newOwnerId);
        synchronized (group.getAdmins()) {
            group.getAdmins().remove(ownerId);
        }
        save();
        return "ok";
    }

    public String leaveGroup(String groupId, String userId) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!group.isMember(userId)) {
            return "你不在该群";
        }
        if (group.isOwner(userId)) {
            return "群主请先转让群主再退群";
        }
        synchronized (group.getMembers()) {
            group.getMembers().remove(userId);
        }
        synchronized (group.getAdmins()) {
            group.getAdmins().remove(userId);
        }
        save();
        return "ok";
    }

    public String renameGroup(String groupId, String operatorId, String newName) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        if (newName == null || newName.trim().isEmpty()) {
            return "群名不能为空";
        }
        group.setGroupName(newName.trim());
        save();
        return "ok";
    }

    public String setGroupIcon(String groupId, String operatorId, String iconPath) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!isOwnerEquivalent(group, operatorId)) {
            return "只有群主可以修改群图标";
        }
        group.setIconPath(iconPath);
        save();
        return "ok";
    }

    public String setDescription(String groupId, String operatorId, String description) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        group.setDescription(description != null ? description.trim() : null);
        save();
        return "ok";
    }

    public String muteMember(String groupId, String operatorId, String targetUserId, long durationSeconds) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        if (SuperAdminService.getInstance().isSuperAdmin(targetUserId)) {
            return "不能禁言超级管理员";
        }
        if (group.isOwner(targetUserId)) {
            return "不能禁言群主";
        }
        if (!isOwnerEquivalent(group, operatorId) && group.isAdmin(targetUserId)) {
            return "管理员不能禁言其他管理员";
        }
        if (durationSeconds <= 0) {
            group.getMutedUntil().remove(targetUserId);
            save();
            return "ok";
        }
        long maxSec = isOwnerEquivalent(group, operatorId) ? 60L * 24 * 3600 : 5L * 24 * 3600;
        if (durationSeconds > maxSec) {
            durationSeconds = maxSec;
        }
        group.getMutedUntil().put(targetUserId, System.currentTimeMillis() + durationSeconds * 1000L);
        save();
        return "ok";
    }

    public String setAllMuted(String groupId, String operatorId, boolean allMuted) {
        Group group = groups.get(groupId);
        if (group == null) {
            return "群组不存在";
        }
        if (!canManage(group, operatorId)) {
            return "无权限";
        }
        group.setAllMuted(allMuted);
        group.setAllMutedAt(System.currentTimeMillis());
        save();
        return "ok";
    }

    public boolean deleteGroup(String groupId) {
        Group removed = groups.remove(groupId);
        if (removed == null) {
            return false;
        }
        try {
            Path groupDir = Paths.get(DATA_DIR, groupId);
            if (Files.exists(groupDir)) {
                Files.walk(groupDir)
                        .sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (Exception ignored) {
                            }
                        });
            }
        } catch (Exception ignored) {
        }
        save();
        return true;
    }
}
