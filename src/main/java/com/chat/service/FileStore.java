package com.chat.service;

import com.chat.model.StoredFileMetadata;
import com.chat.util.JsonUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FileStore {
    private static final FileStore INSTANCE = new FileStore();
    private static final long ACCESS_TOUCH_GRANULARITY_MS = 60_000L;
    private static final long ACCESS_INDEX_FLUSH_INTERVAL_MS = 30_000L;

    private static Path filesDir() { return Paths.get("chatserver", "files"); }
    private static Path tempDir()  { return filesDir().resolve(".tmp"); }
    private static Path indexFile(){ return filesDir().resolve("index.json"); }

    private final Map<String, StoredFileMetadata> filesByStoredName = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile boolean accessIndexDirty = false;

    public static FileStore getInstance() {
        return INSTANCE;
    }

    private FileStore() {
        try {
            Files.createDirectories(filesDir());
            Files.createDirectories(tempDir());
        } catch (Exception e) {
            System.err.println("[FileStore] 创建目录失败: " + e.getMessage());
        }
        loadIndex();
        Thread accessIndexSaver = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(ACCESS_INDEX_FLUSH_INTERVAL_MS);
                    flushAccessIndex();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "file-store-access-index");
        accessIndexSaver.setDaemon(true);
        accessIndexSaver.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::flushAccessIndex));
    }

    public StoredFileMetadata store(InputStream inputStream, String originalFileName, String contentType, String userId) throws Exception {
        String normalizedName = normalizeUploadedFileName(originalFileName);
        Path tempFile = tempDir().resolve(UUID.randomUUID().toString().replace("-", "") + ".part");
        long size = 0L;
        String storedName;

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            try (java.security.DigestInputStream dis = new java.security.DigestInputStream(inputStream, md);
                 OutputStream out = Files.newOutputStream(tempFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = dis.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    size += read;
                }
            }
            byte[] hashBytes = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            storedName = sb.toString();
        } catch (Exception e) {
            deleteQuietly(tempFile);
            if (e instanceof IllegalArgumentException) {
                throw (IllegalArgumentException) e;
            }
            throw new Exception("文件保存与哈希计算失败", e);
        }

        List<StoredFileMetadata> snapshot = null;
        lock.writeLock().lock();
        try {
            StoredFileMetadata existing = filesByStoredName.get(storedName);
            if (existing != null) {
                deleteQuietly(tempFile);
                if (existing.addOwnerUserId(userId)) {
                    snapshot = new ArrayList<>(filesByStoredName.values());
                }
                return existing;
            }

            StoredFileMetadata metadata = new StoredFileMetadata();
            metadata.setStoredName(storedName);
            metadata.setOriginalFileName(normalizedName);
            metadata.setContentType(normalizeContentType(contentType, normalizedName));
            metadata.setSize(size);
            metadata.setCreatedAt(System.currentTimeMillis());
            metadata.addOwnerUserId(userId);

            Files.move(tempFile, resolveStoredPath(storedName), StandardCopyOption.REPLACE_EXISTING);

            filesByStoredName.put(storedName, metadata);
            snapshot = new ArrayList<>(filesByStoredName.values());
            return metadata;
        } finally {
            lock.writeLock().unlock();
            deleteQuietly(tempFile);
            if (snapshot != null) {
                saveIndex(snapshot);
            }
        }
    }

    public StoredFileMetadata getMetadata(String storedName) {
        lock.writeLock().lock();
        try {
            StoredFileMetadata metadata = filesByStoredName.get(storedName);
            if (metadata != null) {
                touchMetadataUnsafe(metadata, System.currentTimeMillis());
                return metadata;
            }
            Path path = resolveStoredPath(storedName);
            if (!Files.exists(path)) {
                return null;
            }
            StoredFileMetadata legacy = new StoredFileMetadata();
            legacy.setStoredName(storedName);
            legacy.setOriginalFileName(extractDisplayName(storedName));
            legacy.setContentType(guessMime(storedName));
            legacy.setSize(Files.size(path));
            legacy.setCreatedAt(Files.getLastModifiedTime(path).toMillis());
            return legacy;
        } catch (Exception e) {
            return null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean isOwnedBy(String storedName, String userId) {
        if (storedName == null || userId == null) return false;
        lock.readLock().lock();
        try {
            StoredFileMetadata metadata = filesByStoredName.get(storedName);
            return metadata != null && metadata.isOwnedBy(userId);
        } finally {
            lock.readLock().unlock();
        }
    }

    public InputStream openStream(String storedName) throws Exception {
        touchMetadata(storedName);
        Path local = resolveStoredPath(storedName);
        if (Files.exists(local)) {
            return Files.newInputStream(local, StandardOpenOption.READ);
        }
        throw new java.io.FileNotFoundException("文件未找到: " + storedName);
    }

    public int getStoredFileCount() {
        lock.readLock().lock();
        try {
            return filesByStoredName.size();
        } finally {
            lock.readLock().unlock();
        }
    }

    public Path resolveStoredPath(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) {
            throw new IllegalArgumentException("存储文件名不能为空");
        }
        Path baseDir = filesDir().toAbsolutePath().normalize();
        Path resolved = baseDir.resolve(storedName).toAbsolutePath().normalize();
        if (!resolved.startsWith(baseDir)) {
            throw new SecurityException("非法路径穿越请求: " + storedName);
        }
        return resolved;
    }

    public boolean deleteStoredFile(String storedName) {
        List<StoredFileMetadata> snapshot = null;
        boolean result = false;
        lock.writeLock().lock();
        try {
            StoredFileMetadata removed = filesByStoredName.remove(storedName);
            boolean deleted = Files.deleteIfExists(resolveStoredPath(storedName));
            if (removed != null || deleted) {
                snapshot = new ArrayList<>(filesByStoredName.values());
            }
            result = deleted || removed != null;
        } catch (Exception e) {
            result = false;
        } finally {
            lock.writeLock().unlock();
            if (snapshot != null) {
                saveIndex(snapshot);
            }
        }
        return result;
    }

    private void loadIndex() {
        if (!Files.exists(indexFile())) {
            return;
        }
        try {
            String json = Files.readString(indexFile(), StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            List<StoredFileMetadata> files = null;
            if (json.startsWith("{")) {
                JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
                Type listType = new TypeToken<List<StoredFileMetadata>>() {}.getType();
                files = JsonUtil.fromJson(obj.get("files").toString(), listType);
            } else if (json.startsWith("[")) {
                Type listType = new TypeToken<List<StoredFileMetadata>>() {}.getType();
                files = JsonUtil.fromJson(json, listType);
            }
            if (files != null) {
                for (StoredFileMetadata metadata : files) {
                    filesByStoredName.put(metadata.getStoredName(), metadata);
                }
            }
            System.out.println("[FileStore] 已加载 " + filesByStoredName.size() + " 个文件索引");
        } catch (Exception e) {
            System.err.println("[FileStore] 加载索引失败: " + e.getMessage());
        }
    }

    private boolean saveIndex(List<StoredFileMetadata> snapshot) {
        try {
            Map<String, Object> index = new LinkedHashMap<>();
            index.put("version", 2);
            index.put("files", snapshot);
            com.chat.util.JsonUtil.saveJsonAtomic(indexFile(), index);
            return true;
        } catch (Exception e) {
            System.err.println("[FileStore] 保存索引失败: " + e.getMessage());
            return false;
        }
    }

    private void touchMetadata(String storedName) {
        lock.writeLock().lock();
        try {
            StoredFileMetadata metadata = filesByStoredName.get(storedName);
            if (metadata != null) touchMetadataUnsafe(metadata, System.currentTimeMillis());
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void touchMetadataUnsafe(StoredFileMetadata metadata, long now) {
        if (metadata.getLastAccessAt() <= 0 || now - metadata.getLastAccessAt() >= ACCESS_TOUCH_GRANULARITY_MS) {
            metadata.setLastAccessAt(now);
            accessIndexDirty = true;
        }
    }

    void flushAccessIndex() {
        List<StoredFileMetadata> snapshot;
        lock.writeLock().lock();
        try {
            if (!accessIndexDirty) return;
            snapshot = new ArrayList<>(filesByStoredName.values());
            accessIndexDirty = false;
        } finally {
            lock.writeLock().unlock();
        }
        if (!saveIndex(snapshot)) accessIndexDirty = true;
    }

    public static String guessMime(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".txt")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json; charset=utf-8";
        if (lower.endsWith(".md")) return "text/markdown; charset=utf-8";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".opus")) return "audio/ogg";
        if (lower.endsWith(".wma")) return "audio/x-ms-wma";
        if (lower.endsWith(".utf") || lower.endsWith(".lrc")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    public static boolean isImage(String fileName) {
        if (fileName == null) return false;
        String lower = fileName.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg");
    }

    public static String normalizeUploadedFileName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "file";
        }
        String normalized = name.replace("\\", "/");
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        if (normalized.startsWith("\"") && normalized.endsWith("\"") && normalized.length() > 1) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (looksLikeUtf8Mojibake(normalized)) {
            try {
                normalized = new String(normalized.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
        }
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFC);
        return normalized.isEmpty() ? "file" : normalized;
    }

    private static boolean looksLikeUtf8Mojibake(String value) {
        return value.contains("Ã") || value.contains("Â") || value.contains("å")
                || value.contains("ä") || value.contains("æ") || value.contains("ç")
                || value.contains("¤") || value.contains("½");
    }

    private static String extractDisplayName(String storedName) {
        if (storedName == null || storedName.isEmpty()) {
            return "file";
        }
        int separator = storedName.indexOf('_');
        return separator >= 0 && separator + 1 < storedName.length()
                ? storedName.substring(separator + 1) : storedName;
    }

    private static String normalizeContentType(String contentType, String fileName) {
        if (contentType == null || contentType.trim().isEmpty() || "application/octet-stream".equalsIgnoreCase(contentType)) {
            return guessMime(fileName);
        }
        return contentType;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (Exception ignored) {
        }
    }
}
