package com.chat.service;

import com.chat.model.Note;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class NoteService {
    private static final Path NOTES_DIR = Paths.get("chatserver", "notes");
    private static final Path NOTES_FILE = NOTES_DIR.resolve("notes.json");
    private static final NoteService INSTANCE = new NoteService();

    private final List<Note> notes = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static NoteService getInstance() {
        return INSTANCE;
    }

    private NoteService() {
        try {
            Files.createDirectories(NOTES_DIR);
        } catch (Exception ignored) {
        }
        load();
    }

    public List<Note> listNotes(String ownerId) {
        lock.readLock().lock();
        try {
            return notes.stream()
                    .filter(note -> Objects.equals(note.getOwnerId(), ownerId))
                    .sorted(Comparator.comparingLong(Note::getUpdatedAt).reversed())
                    .map(this::copyOf)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Map<String, Object>> listNoteSummaries(String ownerId, int offset, int limit) {
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, limit);
        lock.readLock().lock();
        try {
            return notes.stream()
                    .filter(note -> Objects.equals(note.getOwnerId(), ownerId))
                    .sorted(Comparator.comparingLong(Note::getUpdatedAt).reversed())
                    .skip(safeOffset)
                    .limit(safeLimit)
                    .map(this::summaryOf)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public Note getNote(String noteId, String ownerId) {
        lock.readLock().lock();
        try {
            return notes.stream()
                    .filter(note -> Objects.equals(note.getId(), noteId) && Objects.equals(note.getOwnerId(), ownerId))
                    .findFirst()
                    .map(this::copyOf)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public Note createNote(String ownerId, String title) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            Note note = new Note();
            note.setId("note_" + UUID.randomUUID().toString().replace("-", ""));
            note.setOwnerId(ownerId);
            note.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : "未命名笔记");
            note.setContent("");
            note.setCreatedAt(now);
            note.setUpdatedAt(now);
            notes.add(note);
            saveUnsafe();
            return copyOf(note);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Note updateNote(String ownerId, String noteId, String title, String content) {
        lock.writeLock().lock();
        try {
            Note note = notes.stream()
                    .filter(item -> Objects.equals(item.getId(), noteId) && Objects.equals(item.getOwnerId(), ownerId))
                    .findFirst()
                    .orElse(null);
            if (note == null) {
                return null;
            }
            note.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : "未命名笔记");
            note.setContent(content != null ? content : "");
            note.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyOf(note);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean deleteNote(String ownerId, String noteId) {
        lock.writeLock().lock();
        try {
            boolean removed = notes.removeIf(note ->
                    Objects.equals(note.getId(), noteId) && Objects.equals(note.getOwnerId(), ownerId));
            if (removed) {
                saveUnsafe();
            }
            return removed;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Note updateShare(String ownerId, String noteId, String shareId) {
        lock.writeLock().lock();
        try {
            Note note = notes.stream()
                    .filter(item -> Objects.equals(item.getId(), noteId) && Objects.equals(item.getOwnerId(), ownerId))
                    .findFirst()
                    .orElse(null);
            if (note == null) {
                return null;
            }
            note.setShareId(shareId);
            note.setShareUpdatedAt(System.currentTimeMillis());
            note.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyOf(note);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Note getByShareId(String shareId) {
        lock.readLock().lock();
        try {
            return notes.stream()
                    .filter(note -> Objects.equals(note.getShareId(), shareId))
                    .findFirst()
                    .map(this::copyOf)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            notes.clear();
            if (!Files.exists(NOTES_FILE)) {
                return;
            }
            String json = Files.readString(NOTES_FILE, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            Type type = new TypeToken<List<Note>>() { }.getType();
            List<Note> loaded = JsonUtil.fromJson(json, type);
            if (loaded != null) {
                loaded.stream().filter(Objects::nonNull).forEach(note -> {
                    if (note.getTitle() == null || note.getTitle().trim().isEmpty()) {
                        note.setTitle("未命名笔记");
                    }
                    if (note.getContent() == null) {
                        note.setContent("");
                    }
                    notes.add(note);
                });
            }
        } catch (Exception e) {
            System.err.println("[NoteService] 加载失败: " + e.getMessage());
            try {
                Path backup = NOTES_FILE.resolveSibling(NOTES_FILE.getFileName() + ".bak");
                Files.copy(NOTES_FILE, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[NoteService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(NOTES_DIR);
            com.chat.util.JsonUtil.saveJsonAtomic(NOTES_FILE, notes);
        } catch (Exception e) {
            System.err.println("[NoteService] 保存失败: " + e.getMessage());
        }
    }

    private Note copyOf(Note note) {
        Note copy = new Note();
        copy.setId(note.getId());
        copy.setOwnerId(note.getOwnerId());
        copy.setTitle(note.getTitle());
        copy.setContent(note.getContent());
        copy.setCreatedAt(note.getCreatedAt());
        copy.setUpdatedAt(note.getUpdatedAt());
        copy.setShareId(note.getShareId());
        copy.setShareUpdatedAt(note.getShareUpdatedAt());
        return copy;
    }

    private Map<String, Object> summaryOf(Note note) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", note.getId());
        summary.put("title", note.getTitle());
        summary.put("preview", previewOf(note.getContent(), 20));
        summary.put("createdAt", note.getCreatedAt());
        summary.put("updatedAt", note.getUpdatedAt());
        if (note.getShareId() != null && !note.getShareId().trim().isEmpty()) {
            summary.put("shareId", note.getShareId());
        }
        return summary;
    }

    private String previewOf(String content, int maxChars) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (text.isEmpty() || text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, maxChars));
    }

    private String extractFileName(String path) {
        if (path == null) return null;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private boolean contentContainsFile(String content, String storedName) {
        if (content == null || storedName == null || content.isEmpty()) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?:/files/|files/)([^\\s\"'>)]+)").matcher(content);
        while (m.find()) {
            String extracted = extractFileName(m.group(1));
            if (Objects.equals(extracted, storedName)) {
                return true;
            }
        }
        return false;
    }

    public boolean hasAccessToFile(String userId, String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return false;
        lock.readLock().lock();
        try {
            // 1. 笔记所有者可访问自己笔记中的附件
            if (userId != null && notes.stream()
                    .filter(n -> Objects.equals(n.getOwnerId(), userId))
                    .anyMatch(n -> contentContainsFile(n.getContent(), storedName))) {
                return true;
            }
            // 2. 处于公开分享状态的笔记，任何人均可访问其嵌入附件（支持分享链接预览）
            return notes.stream()
                    .filter(n -> n.getShareId() != null && !n.getShareId().isEmpty())
                    .anyMatch(n -> contentContainsFile(n.getContent(), storedName));
        } finally {
            lock.readLock().unlock();
        }
    }
}
