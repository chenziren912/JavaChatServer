package com.chat.service;

import com.chat.model.Announcement;
import com.chat.model.User;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class AnnouncementService {
    private static final AnnouncementService INSTANCE = new AnnouncementService();

    private static Path dataDir() { return Paths.get("chatserver", "announcements"); }
    private static Path dataFile() { return dataDir().resolve("announcements.json"); }

    private final List<Announcement> announcements = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static AnnouncementService getInstance() {
        return INSTANCE;
    }

    private AnnouncementService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public Announcement create(User actor, String title, String content) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            Announcement item = new Announcement();
            item.setId("ann_" + UUID.randomUUID().toString().replace("-", ""));
            item.setTitle(title == null || title.trim().isEmpty() ? "系统公告" : title.trim());
            item.setContent(content == null ? "" : content.trim());
            item.setAuthorUserId(actor != null ? actor.getUserId() : "");
            item.setAuthorNickname(actor != null ? actor.getNickname() : "");
            item.setCreatedAt(now);
            item.setUpdatedAt(now);
            announcements.add(item);
            saveUnsafe();
            return copy(item);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Announcement> listAll() {
        lock.readLock().lock();
        try {
            return announcements.stream()
                    .sorted(Comparator.comparingLong(Announcement::getUpdatedAt).reversed())
                    .map(this::copy)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Announcement latest() {
        lock.readLock().lock();
        try {
            return announcements.stream()
                    .max(Comparator.comparingLong(Announcement::getUpdatedAt))
                    .map(this::copy)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            announcements.clear();
            if (!Files.exists(dataFile())) {
                return;
            }
            String json = Files.readString(dataFile(), StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            Type type = new TypeToken<List<Announcement>>() {}.getType();
            List<Announcement> items = JsonUtil.fromJson(json, type);
            if (items != null) {
                announcements.addAll(items.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            System.err.println("[AnnouncementService] 加载失败: " + e.getMessage());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(dataDir());
            com.chat.util.JsonUtil.saveJsonAtomic(dataFile(), announcements);
        } catch (Exception e) {
            System.err.println("[AnnouncementService] 保存失败: " + e.getMessage());
        }
    }

    private Announcement copy(Announcement source) {
        return JsonUtil.fromJson(JsonUtil.toJson(source), Announcement.class);
    }
}
