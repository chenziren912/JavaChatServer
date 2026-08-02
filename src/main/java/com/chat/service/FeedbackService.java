package com.chat.service;

import com.chat.model.FeedbackTicket;
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

public class FeedbackService {
    private static final FeedbackService INSTANCE = new FeedbackService();
    private static Path dataDir() { return Paths.get("chatserver", "feedback"); }
    private static Path dataFile() { return dataDir().resolve("tickets.json"); }

    private final List<FeedbackTicket> tickets = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static FeedbackService getInstance() {
        return INSTANCE;
    }

    private FeedbackService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public FeedbackTicket createTicket(String userId, String nickname, String title, String content) {
        lock.writeLock().lock();
        try {
            FeedbackTicket ticket = new FeedbackTicket();
            long now = System.currentTimeMillis();
            ticket.setId("fb_" + UUID.randomUUID().toString().replace("-", ""));
            ticket.setUserId(userId);
            ticket.setUserNickname(nickname);
            ticket.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : "未命名反馈");
            ticket.setContent(content != null ? content.trim() : "");
            ticket.setStatus("等待审核");
            ticket.setCreatedAt(now);
            ticket.setUpdatedAt(now);
            tickets.add(ticket);
            saveUnsafe();
            return copy(ticket);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<FeedbackTicket> listByUser(String userId) {
        lock.readLock().lock();
        try {
            return tickets.stream()
                    .filter(ticket -> Objects.equals(ticket.getUserId(), userId))
                    .sorted(Comparator.comparingLong(FeedbackTicket::getUpdatedAt).reversed())
                    .map(this::copy)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<FeedbackTicket> listAll() {
        lock.readLock().lock();
        try {
            return tickets.stream()
                    .sorted(Comparator.comparingLong(FeedbackTicket::getUpdatedAt).reversed())
                    .map(this::copy)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public FeedbackTicket updateStatus(String ticketId, String status) {
        lock.writeLock().lock();
        try {
            FeedbackTicket ticket = tickets.stream()
                    .filter(item -> Objects.equals(item.getId(), ticketId))
                    .findFirst().orElse(null);
            if (ticket == null) {
                throw new IllegalArgumentException("反馈不存在");
            }
            ticket.setStatus(status);
            ticket.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copy(ticket);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            tickets.clear();
            if (!Files.exists(dataFile())) {
                return;
            }
            String json = Files.readString(dataFile(), StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            Type type = new TypeToken<List<FeedbackTicket>>() {}.getType();
            List<FeedbackTicket> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                tickets.addAll(list.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            System.err.println("[FeedbackService] 加载失败: " + e.getMessage());
            try {
                Path backup = dataFile().resolveSibling(dataFile().getFileName() + ".bak");
                Files.copy(dataFile(), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[FeedbackService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(dataDir());
            com.chat.util.JsonUtil.saveJsonAtomic(dataFile(), tickets);
        } catch (Exception e) {
            System.err.println("[FeedbackService] 保存失败: " + e.getMessage());
        }
    }

    private FeedbackTicket copy(FeedbackTicket source) {
        return JsonUtil.fromJson(JsonUtil.toJson(source), FeedbackTicket.class);
    }
}
