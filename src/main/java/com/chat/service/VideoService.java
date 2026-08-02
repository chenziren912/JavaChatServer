package com.chat.service;

import com.chat.model.VideoCategory;
import com.chat.model.VideoComment;
import com.chat.model.VideoDanmaku;
import com.chat.model.VideoEntry;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class VideoService {
    private static final VideoService INSTANCE = new VideoService();
    private static Path dataDir() { return Paths.get("chatserver", "videos"); }
    private static Path entriesFile() { return dataDir().resolve("entries.json"); }
    private static Path categoriesFile() { return dataDir().resolve("categories.json"); }
    private static Path danmakuFile() { return dataDir().resolve("danmaku.json"); }
    private static Path commentsFile() { return dataDir().resolve("comments.json"); }

    private final List<VideoEntry> entries = new ArrayList<>();
    private final List<VideoCategory> categories = new ArrayList<>();
    private final List<VideoDanmaku> danmakus = new ArrayList<>();
    private final List<VideoComment> comments = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static VideoService getInstance() {
        return INSTANCE;
    }

    private VideoService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public List<VideoEntry> listEntries() {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .sorted(Comparator.comparingLong(VideoEntry::getCreatedAt).reversed())
                    .map(this::copyEntry)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public VideoEntry addEntry(VideoEntry entry) {
        lock.writeLock().lock();
        try {
            entry.setId("video_" + UUID.randomUUID().toString().replace("-", ""));
            entry.setCreatedAt(System.currentTimeMillis());
            entries.add(entry);
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VideoEntry getEntry(String videoId) {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .filter(item -> Objects.equals(item.getId(), videoId))
                    .findFirst()
                    .map(this::copyEntry)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<VideoCategory> listCategories() {
        lock.readLock().lock();
        try {
            return categories.stream()
                    .sorted(Comparator.comparing(VideoCategory::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(this::copyCategory)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public VideoCategory ensureCategory(String name) {
        lock.writeLock().lock();
        try {
            String cleaned = name != null ? name.trim() : "";
            VideoCategory existing = categories.stream()
                    .filter(item -> Objects.equals(item.getName(), cleaned))
                    .findFirst().orElse(null);
            if (existing != null) {
                return copyCategory(existing);
            }
            VideoCategory category = new VideoCategory();
            category.setId("vcat_" + UUID.randomUUID().toString().replace("-", ""));
            category.setName(cleaned.isEmpty() ? "默认栏目" : cleaned);
            category.setCreatedAt(System.currentTimeMillis());
            categories.add(category);
            saveUnsafe();
            return copyCategory(category);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VideoComment addComment(String videoId, String userId, String nickname, String content) {
        lock.writeLock().lock();
        try {
            VideoComment comment = new VideoComment();
            comment.setId("vcom_" + UUID.randomUUID().toString().replace("-", ""));
            comment.setVideoId(videoId);
            comment.setUserId(userId);
            comment.setNickname(nickname);
            comment.setContent(content);
            comment.setCreatedAt(System.currentTimeMillis());
            comments.add(comment);
            saveUnsafe();
            return copyComment(comment);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public VideoDanmaku addDanmaku(String videoId, String userId, String nickname, String content,
                                   String color, String position, double timeSec) {
        lock.writeLock().lock();
        try {
            VideoDanmaku danmaku = new VideoDanmaku();
            danmaku.setId("vdm_" + UUID.randomUUID().toString().replace("-", ""));
            danmaku.setVideoId(videoId);
            danmaku.setUserId(userId);
            danmaku.setNickname(nickname);
            danmaku.setContent(content);
            danmaku.setColor(color);
            danmaku.setPosition(position);
            danmaku.setTimeSec(timeSec);
            danmaku.setCreatedAt(System.currentTimeMillis());
            danmakus.add(danmaku);
            saveUnsafe();
            return copyDanmaku(danmaku);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<VideoComment> listComments(String videoId) {
        lock.readLock().lock();
        try {
            return comments.stream()
                    .filter(item -> Objects.equals(item.getVideoId(), videoId))
                    .sorted(Comparator.comparingLong(VideoComment::getCreatedAt))
                    .map(this::copyComment)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<VideoDanmaku> listDanmaku(String videoId) {
        lock.readLock().lock();
        try {
            return danmakus.stream()
                    .filter(item -> Objects.equals(item.getVideoId(), videoId))
                    .sorted(Comparator.comparingDouble(VideoDanmaku::getTimeSec))
                    .map(this::copyDanmaku)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public void bumpPlayCount(String videoId) {
        lock.writeLock().lock();
        try {
            VideoEntry entry = entries.stream().filter(item -> Objects.equals(item.getId(), videoId)).findFirst().orElse(null);
            if (entry != null) {
                entry.setPlayCount(entry.getPlayCount() + 1);
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            categories.clear();
            danmakus.clear();
            comments.clear();
            loadList(entriesFile(), new TypeToken<List<VideoEntry>>() {}.getType(), entries);
            loadList(categoriesFile(), new TypeToken<List<VideoCategory>>() {}.getType(), categories);
            loadList(danmakuFile(), new TypeToken<List<VideoDanmaku>>() {}.getType(), danmakus);
            loadList(commentsFile(), new TypeToken<List<VideoComment>>() {}.getType(), comments);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T> void loadList(Path file, Type type, List<T> target) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            List<T> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                target.addAll(list.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            System.err.println("[VideoService] 加载失败: " + file.getFileName() + " -> " + e.getMessage());
            try {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[VideoService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private final java.util.concurrent.ExecutorService saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-service-saver");
        t.setDaemon(true);
        return t;
    });

    private void saveUnsafe() {
        List<VideoEntry> snapEntries = new ArrayList<>(entries);
        List<VideoCategory> snapCategories = new ArrayList<>(categories);
        List<VideoDanmaku> snapDanmakus = new ArrayList<>(danmakus);
        List<VideoComment> snapComments = new ArrayList<>(comments);
        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(dataDir());
                com.chat.util.JsonUtil.saveJsonAtomic(entriesFile(), snapEntries);
                com.chat.util.JsonUtil.saveJsonAtomic(categoriesFile(), snapCategories);
                com.chat.util.JsonUtil.saveJsonAtomic(danmakuFile(), snapDanmakus);
                com.chat.util.JsonUtil.saveJsonAtomic(commentsFile(), snapComments);
            } catch (Exception e) {
                System.err.println("[VideoService] 异步保存失败: " + e.getMessage());
            }
        });
    }

    private VideoEntry copyEntry(VideoEntry source) {
        VideoEntry copy = new VideoEntry();
        copy.setId(source.getId());
        copy.setTitle(source.getTitle());
        copy.setDescription(source.getDescription());
        copy.setCoverPath(source.getCoverPath());
        copy.setFilePath(source.getFilePath());
        copy.setCategoryId(source.getCategoryId());
        copy.setUploadedBy(source.getUploadedBy());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setPlayCount(source.getPlayCount());
        return copy;
    }

    private VideoCategory copyCategory(VideoCategory source) {
        VideoCategory copy = new VideoCategory();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private VideoDanmaku copyDanmaku(VideoDanmaku source) {
        VideoDanmaku copy = new VideoDanmaku();
        copy.setId(source.getId());
        copy.setVideoId(source.getVideoId());
        copy.setUserId(source.getUserId());
        copy.setNickname(source.getNickname());
        copy.setContent(source.getContent());
        copy.setColor(source.getColor());
        copy.setPosition(source.getPosition());
        copy.setTimeSec(source.getTimeSec());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private VideoComment copyComment(VideoComment source) {
        VideoComment copy = new VideoComment();
        copy.setId(source.getId());
        copy.setVideoId(source.getVideoId());
        copy.setUserId(source.getUserId());
        copy.setNickname(source.getNickname());
        copy.setContent(source.getContent());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }
}
