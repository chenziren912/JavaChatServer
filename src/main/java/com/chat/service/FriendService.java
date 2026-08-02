package com.chat.service;

import com.chat.model.FriendRequest;
import com.chat.model.User;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class FriendService {
    private static final FriendService INSTANCE = new FriendService();
    public static FriendService getInstance() { return INSTANCE; }

    private static final String DATA_FILE = "chatserver/users/friends.json";

    private final List<FriendRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicLong idCounter          = new AtomicLong(1);
    private final ReentrantReadWriteLock lock   = new ReentrantReadWriteLock();

    private FriendService() {
        load();
    }

    private void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<FriendRequest>>(){}.getType();
            List<FriendRequest> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                requests.addAll(list);
                list.stream()
                    .mapToLong(r -> { try { return Long.parseLong(r.getId()); } catch (Exception e) { return 0L; } })
                    .max().ifPresent(max -> idCounter.set(max + 1));
            }
            System.out.println("[FriendService] 已加载 " + requests.size() + " 条好友记录");
        } catch (Exception e) {
            System.err.println("[FriendService] 加载失败: " + e.getMessage());
            // 旧版存档兼容：备份损坏文件
            try {
                Path backup = Paths.get(DATA_FILE + ".bak");
                Files.copy(Paths.get(DATA_FILE), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[FriendService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private void save() {
        lock.writeLock().lock();
        try {
            Path filePath = Paths.get(DATA_FILE);
            if (filePath.getParent() != null) {
                Files.createDirectories(filePath.getParent());
            }
            List<FriendRequest> copy;
            synchronized (requests) { copy = new ArrayList<>(requests); }
            String json = JsonUtil.toJson(copy);
            com.chat.util.JsonUtil.writeBytesAtomic(filePath, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            System.err.println("[FriendService] 保存失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean sendRequest(String fromUserId, String fromNickname,
                               String toUserId, String message) {
        UserService us = UserService.getInstance();
        User fromUser = us.getByUserId(fromUserId);
        User toUser   = us.getByUserId(toUserId);
        if (fromUser == null || toUser == null) return false;
        if (fromUser.getFriends().contains(toUserId)) return false;

        // 对方已向我发过申请，自动互加
        Optional<FriendRequest> reverse;
        synchronized (requests) {
            reverse = requests.stream()
                    .filter(r -> r.getFromUserId().equals(toUserId)
                              && r.getToUserId().equals(fromUserId)
                              && "pending".equals(r.getStatus()))
                    .findFirst();
        }
        if (reverse.isPresent()) {
            reverse.get().setStatus("accepted");
            fromUser.addFriend(toUserId);
            toUser.addFriend(fromUserId);
            save();
            us.save();
            return true;
        }

        boolean exists;
        synchronized (requests) {
            exists = requests.stream().anyMatch(r ->
                    r.getFromUserId().equals(fromUserId) &&
                    r.getToUserId().equals(toUserId) &&
                    "pending".equals(r.getStatus()));
        }
        if (exists) return false;

        String id = String.valueOf(idCounter.getAndIncrement());
        requests.add(new FriendRequest(id, fromUserId, fromNickname, toUserId, message));
        save();
        return true;
    }

    public List<FriendRequest> getSentRequests(String fromUserId) {
        synchronized (requests) {
            return requests.stream()
                    .filter(r -> r.getFromUserId().equals(fromUserId))
                    .collect(Collectors.toList());
        }
    }

    public List<FriendRequest> getReceivedRequests(String toUserId) {
        synchronized (requests) {
            return requests.stream()
                    .filter(r -> r.getToUserId().equals(toUserId) && "pending".equals(r.getStatus()))
                    .collect(Collectors.toList());
        }
    }

    public Map<String, Integer> addAllUsersAsFriends(String ownerUserId) {
        Map<String, Integer> result = new LinkedHashMap<>();
        int total = 0;
        int added = 0;
        int repaired = 0;
        int already = 0;
        int skipped = 0;
        int acceptedRequests = 0;

        UserService us = UserService.getInstance();
        User owner = us.getByUserId(ownerUserId);
        if (owner == null) {
            result.put("total", total);
            result.put("added", added);
            result.put("repaired", repaired);
            result.put("already", already);
            result.put("skipped", skipped);
            result.put("acceptedRequests", acceptedRequests);
            return result;
        }

        lock.writeLock().lock();
        try {
            if (owner.getFriends() == null) owner.setFriends(new ArrayList<>());
            Set<String> targetIds = new HashSet<>();
            for (User target : us.getAllUsers()) {
                String targetId = target != null ? target.getUserId() : null;
                if (targetId == null || targetId.trim().isEmpty() || targetId.equals(ownerUserId)) {
                    skipped++;
                    continue;
                }
                total++;
                targetIds.add(targetId);
                if (target.getFriends() == null) target.setFriends(new ArrayList<>());
                boolean ownerHad = owner.getFriends().contains(targetId);
                boolean targetHad = target.getFriends().contains(ownerUserId);
                if (!ownerHad) owner.addFriend(targetId);
                if (!targetHad) target.addFriend(ownerUserId);
                if (!ownerHad) {
                    added++;
                } else if (!targetHad) {
                    repaired++;
                } else {
                    already++;
                }
            }

            synchronized (requests) {
                for (FriendRequest req : requests) {
                    if (!"pending".equals(req.getStatus())) continue;
                    boolean ownerSent = ownerUserId.equals(req.getFromUserId()) && targetIds.contains(req.getToUserId());
                    boolean ownerReceived = ownerUserId.equals(req.getToUserId()) && targetIds.contains(req.getFromUserId());
                    if (ownerSent || ownerReceived) {
                        req.setStatus("accepted");
                        acceptedRequests++;
                    }
                }
            }

            if (added > 0 || repaired > 0) us.save();
            if (acceptedRequests > 0) save();
        } finally {
            lock.writeLock().unlock();
        }

        result.put("total", total);
        result.put("added", added);
        result.put("repaired", repaired);
        result.put("already", already);
        result.put("skipped", skipped);
        result.put("acceptedRequests", acceptedRequests);
        return result;
    }

    public boolean handleRequest(String requestId, String action, String currentUserId) {
        Optional<FriendRequest> opt;
        synchronized (requests) {
            opt = requests.stream()
                    .filter(r -> r.getId().equals(requestId) && r.getToUserId().equals(currentUserId))
                    .findFirst();
        }
        if (opt.isEmpty()) return false;
        FriendRequest req = opt.get();
        if ("accept".equals(action)) {
            req.setStatus("accepted");
            UserService us = UserService.getInstance();
            User from = us.getByUserId(req.getFromUserId());
            User to   = us.getByUserId(req.getToUserId());
            if (from != null && to != null) {
                from.addFriend(to.getUserId());
                to.addFriend(from.getUserId());
                us.save();
            }
        } else {
            req.setStatus("rejected");
        }
        save();
        return true;
    }

    public void cleanupUserRequests(String userId) {
        if (userId == null) return;
        lock.writeLock().lock();
        try {
            boolean removed = false;
            synchronized (requests) {
                removed = requests.removeIf(r -> userId.equals(r.getFromUserId()) || userId.equals(r.getToUserId()));
            }
            if (removed) {
                save();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
