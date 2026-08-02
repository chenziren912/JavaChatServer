package com.chat.service;

import com.chat.model.*;
import com.chat.util.JsonUtil;
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
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class CloudService {
    private static final CloudService INSTANCE = new CloudService();
    private static final long RECYCLE_EXPIRE_MS = 15L * 24 * 3600 * 1000;
    static final int MAX_UNZIP_ENTRIES = 5000;
    static final int MAX_UNZIP_PATH_DEPTH = 64;
    static final long MAX_UNZIP_SINGLE_FILE_BYTES = 500L * 1024L * 1024L;
    static final long MAX_UNZIP_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L;

    private static Path dataDir() { return Paths.get("chatserver", "cloud"); }
    private static Path entriesFile() { return dataDir().resolve("entries.json"); }
    private static Path sharesFile() { return dataDir().resolve("shares.json"); }
    private static Path tasksFile() { return dataDir().resolve("tasks.json"); }
    private static Path downloadsFile() { return dataDir().resolve("downloads.json"); }

    static Path userFilesDir(String userId) { return Paths.get("chatserver", "cloud-files", userId); }

    private final List<CloudEntry> entries = new ArrayList<>();
    private final List<CloudShareLink> shares = new ArrayList<>();
    private final List<CloudTask> tasks = new ArrayList<>();
    private final List<CloudDownloadRecord> downloads = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static CloudService getInstance() {
        return INSTANCE;
    }

    private CloudService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public void ensureUserCloud(User user) {
        if (user == null) {
            return;
        }
        lock.writeLock().lock();
        try {
            if (!hasFolderUnsafe(user.getUserId(), "/share")) {
                long now = System.currentTimeMillis();
                CloudEntry share = new CloudEntry();
                share.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                share.setOwnerId(user.getUserId());
                share.setParentPath("/");
                share.setName("share");
                share.setType("folder");
                share.setCreatedAt(now);
                share.setUpdatedAt(now);
                share.setSourceModule("system");
                entries.add(share);
            }
            if (!hasFolderUnsafe(user.getUserId(), "/uploads")) {
                long now = System.currentTimeMillis();
                CloudEntry uploads = new CloudEntry();
                uploads.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                uploads.setOwnerId(user.getUserId());
                uploads.setParentPath("/");
                uploads.setName("uploads");
                uploads.setType("folder");
                uploads.setCreatedAt(now);
                uploads.setUpdatedAt(now);
                uploads.setSourceModule("system");
                entries.add(uploads);
            }
            if (!user.isCloudInitialized()) {
                user.setCloudInitialized(true);
                UserService.getInstance().save();
                try {
                    createTextFile(user, "/", "欢迎使用云盘.md",
                            "# 欢迎使用云盘\n\n- 上传文件会保存在你的私有云盘。\n- 聊天与朋友圈发送文件会自动进入 `/share`。\n- 删除的文件会先进入回收站 15 天。\n- 支持分享链接、预览、下载与恢复。\n",
                            "system");
                } catch (Exception ignored) {
                }
            }
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean canStore(User user, long incomingBytes) {
        lock.readLock().lock();
        try {
            return canStoreUnsafe(user, incomingBytes);
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getUsedBytes(String userId) {
        lock.readLock().lock();
        try {
            return getUsedBytesUnsafe(userId);
        } finally {
            lock.readLock().unlock();
        }
    }

    private boolean canStoreUnsafe(User user, long incomingBytes) {
        if (user == null) return false;
        if (SuperAdminService.getInstance().isSuperAdmin(user.getUserId())) return true;
        long quota = user.getCloudQuotaByLevel();
        if (quota == Long.MAX_VALUE) return true;
        long incoming = Math.max(incomingBytes, 0L);
        long used = getUsedBytesUnsafe(user.getUserId());
        return incoming <= quota && used <= quota - incoming;
    }

    private long getUsedBytesUnsafe(String userId) {
        return entries.stream()
                .filter(entry -> Objects.equals(entry.getOwnerId(), userId))
                .filter(entry -> !entry.isDeleted() && !entry.isFolder())
                .mapToLong(CloudEntry::getSize)
                .sum();
    }

    public List<CloudEntry> listEntries(String ownerId, String parentPath) {
        lock.writeLock().lock();
        try {
            String normalized = normalizePath(parentPath);
            purgeRecycleUnsafe(false);
            return entries.stream()
                    .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                    .filter(entry -> !entry.isDeleted())
                    .filter(entry -> Objects.equals(normalizePath(entry.getParentPath()), normalized))
                    .sorted(Comparator.comparing(CloudEntry::isFolder).reversed()
                            .thenComparing(CloudEntry::getName, String.CASE_INSENSITIVE_ORDER))
                    .map(this::copyEntry)
                    .collect(Collectors.toList());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<CloudEntry> listRecycleBin(String ownerId) {
        lock.writeLock().lock();
        try {
            purgeRecycleUnsafe(true);
            return entries.stream()
                    .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                    .filter(CloudEntry::isDeleted)
                    .sorted(Comparator.comparingLong(CloudEntry::getDeletedAt).reversed())
                    .map(this::copyEntry)
                    .collect(Collectors.toList());
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<CloudShareLink> listShares(String ownerId) {
        lock.readLock().lock();
        try {
            return shares.stream()
                    .filter(share -> Objects.equals(share.getOwnerId(), ownerId))
                    .sorted(Comparator.comparingLong(CloudShareLink::getUpdatedAt).reversed())
                    .map(this::copyShare)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CloudTask> listTasks(String ownerId) {
        lock.readLock().lock();
        try {
            return tasks.stream()
                    .filter(task -> Objects.equals(task.getOwnerId(), ownerId))
                    .sorted(Comparator.comparingLong(CloudTask::getUpdatedAt).reversed())
                    .limit(30)
                    .map(this::copyTask)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CloudDownloadRecord> listDownloads(String ownerId) {
        lock.readLock().lock();
        try {
            return downloads.stream()
                    .filter(record -> Objects.equals(record.getOwnerId(), ownerId))
                    .sorted(Comparator.comparingLong(CloudDownloadRecord::getDownloadedAt).reversed())
                    .limit(100)
                    .map(this::copyDownload)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public CloudEntry createFolder(String ownerId, String parentPath, String name) {
        lock.writeLock().lock();
        try {
            String normalizedParent = normalizePath(parentPath);
            requireFolderPathUnsafe(ownerId, normalizedParent);
            String cleanedName = sanitizeName(name);
            if (hasNameConflictUnsafe(ownerId, normalizedParent, cleanedName, null)) {
                throw new IllegalArgumentException("同名文件或文件夹已存在");
            }
            long now = System.currentTimeMillis();
            CloudEntry entry = new CloudEntry();
            entry.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
            entry.setOwnerId(ownerId);
            entry.setParentPath(normalizedParent);
            entry.setName(cleanedName);
            entry.setType("folder");
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            entry.setSourceModule("cloud");
            entries.add(entry);
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry createTextFile(User user, String parentPath, String name, String content, String sourceModule) throws Exception {
        byte[] data = (content != null ? content : "").getBytes(StandardCharsets.UTF_8);
        if (!canStore(user, data.length)) {
            throw new IllegalArgumentException("空间已满，无法存储");
        }
        String storedFileName = storeUserFileBytes(user.getUserId(), data, name);
        long now = System.currentTimeMillis();
        ensureUserCloud(user);
        boolean committed = false;
        lock.writeLock().lock();
        try {
            // The earlier check is only an inexpensive preflight. This commit-time
            // check is serialized with every entry mutation and closes the upload TOCTOU window.
            if (!canStoreUnsafe(user, data.length)) {
                throw new IllegalArgumentException("空间已满，无法存储");
            }
            String normalizedParent = normalizePath(parentPath);
            requireFolderPathUnsafe(user.getUserId(), normalizedParent);
            String cleanedName = sanitizeName(name);
            if (hasNameConflictUnsafe(user.getUserId(), normalizedParent, cleanedName, null)) {
                cleanedName = resolveDuplicateNameUnsafe(user.getUserId(), normalizedParent, cleanedName);
            }
            CloudEntry entry = new CloudEntry();
            entry.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
            entry.setOwnerId(user.getUserId());
            entry.setParentPath(normalizedParent);
            entry.setName(cleanedName);
            entry.setType("file");
            entry.setStoredName(storedFileName);
            entry.setContentType("text/markdown; charset=utf-8");
            entry.setSize(data.length);
            entry.setCreatedAt(now);
            entry.setUpdatedAt(now);
            entry.setSourceModule(sourceModule != null ? sourceModule : "cloud");
            entries.add(entry);
            saveUnsafe();
            committed = true;
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
            if (!committed) {
                deleteUserStoredFileQuietly(user.getUserId(), storedFileName);
            }
        }
    }

    public CloudEntry storeUserFile(User user, StoredFileMetadata stored, String parentPath, String fileName, String sourceModule) {
        if (user == null || stored == null) {
            throw new IllegalArgumentException("参数不完整");
        }
        Path targetPath = null;
        boolean committed = false;
        try {
            Path userDir = userFilesDir(user.getUserId());
            Files.createDirectories(userDir);
            String ext = extractExtension(fileName);
            String newStoredName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
            targetPath = userDir.resolve(newStoredName);
            try (InputStream in = openStreamCompat(user.getUserId(), stored.getStoredName())) {
                Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            ensureUserCloud(user);
            lock.writeLock().lock();
            try {
                if (!canStoreUnsafe(user, stored.getSize())) {
                    throw new IllegalArgumentException("空间已满，无法存储");
                }
                String normalizedParent = normalizePath(parentPath);
                requireFolderPathUnsafe(user.getUserId(), normalizedParent);
                String cleanedName = sanitizeName(fileName);
                if (hasNameConflictUnsafe(user.getUserId(), normalizedParent, cleanedName, null)) {
                    cleanedName = resolveDuplicateNameUnsafe(user.getUserId(), normalizedParent, cleanedName);
                }
                long now = System.currentTimeMillis();
                CloudEntry entry = new CloudEntry();
                entry.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                entry.setOwnerId(user.getUserId());
                entry.setParentPath(normalizedParent);
                entry.setName(cleanedName);
                entry.setType("file");
                entry.setStoredName(newStoredName);
                entry.setContentType(stored.getContentType());
                entry.setSize(stored.getSize());
                entry.setCreatedAt(now);
                entry.setUpdatedAt(now);
                entry.setSourceModule(sourceModule != null ? sourceModule : "cloud");
                entries.add(entry);
                saveUnsafe();
                committed = true;
                return copyEntry(entry);
            } finally {
                lock.writeLock().unlock();
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("存储文件失败", e);
        } finally {
            if (!committed && targetPath != null) {
                try { Files.deleteIfExists(targetPath); } catch (Exception ignored) {}
            }
        }
    }

    public String storeUserFileStream(String userId, InputStream inputStream, String originalFileName) throws Exception {
        Path userDir = userFilesDir(userId);
        Files.createDirectories(userDir);
        String ext = extractExtension(originalFileName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        Path targetPath = userDir.resolve(storedName);
        Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
        return storedName;
    }

    public CloudEntry storeAutoShareFile(User user, StoredFileMetadata stored, String originalName, String sourceModule) {
        String cleaned = FileStore.normalizeUploadedFileName(originalName);
        String uniqueName = appendTimestampBeforeExtension(cleaned, System.currentTimeMillis() / 1000L);
        return storeUserFile(user, stored, "/share", uniqueName, sourceModule);
    }

    public CloudEntry getEntry(String entryId) {
        lock.readLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            return entry == null ? null : copyEntry(entry);
        } finally {
            lock.readLock().unlock();
        }
    }

    public CloudEntry requireOwnerEntry(String userId, String entryId) {
        lock.readLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), userId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            return copyEntry(entry);
        } finally {
            lock.readLock().unlock();
        }
    }

    public CloudEntry renameEntry(String ownerId, String entryId, String newName) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            if (entry.isDeleted()) throw new IllegalArgumentException("文件已在回收站");
            String cleaned = sanitizeName(newName);
            if (hasNameConflictUnsafe(ownerId, entry.getParentPath(), cleaned, entry.getId())) {
                throw new IllegalArgumentException("同名文件或文件夹已存在");
            }
            String oldPath = entry.isFolder() ? buildEntryPath(entry) : null;
            entry.setName(cleaned);
            long now = System.currentTimeMillis();
            entry.setUpdatedAt(now);
            if (entry.isFolder()) {
                String newPath = buildEntryPath(entry);
                rewriteDescendantParentPathsUnsafe(ownerId, oldPath, newPath, now);
            }
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry moveEntry(String ownerId, String entryId, String targetParentPath) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            if (entry.isDeleted()) throw new IllegalArgumentException("文件已在回收站");
            String normalized = normalizePath(targetParentPath);
            requireFolderPathUnsafe(ownerId, normalized);
            String oldPath = entry.isFolder() ? buildEntryPath(entry) : null;
            if (entry.isFolder() && (normalized.equals(oldPath) || normalized.startsWith(oldPath + "/"))) {
                throw new IllegalArgumentException("不能把文件夹移动到自身或其子目录");
            }
            if (hasNameConflictUnsafe(ownerId, normalized, entry.getName(), entry.getId())) {
                throw new IllegalArgumentException("目标位置已有同名文件");
            }
            entry.setParentPath(normalized);
            long now = System.currentTimeMillis();
            entry.setUpdatedAt(now);
            if (entry.isFolder()) {
                rewriteDescendantParentPathsUnsafe(ownerId, oldPath, buildEntryPath(entry), now);
            }
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry copyEntry(User user, String entryId, String targetParentPath) {
        if (user == null) throw new IllegalArgumentException("未登录");
        lock.writeLock().lock();
        try {
            String ownerId = user.getUserId();
            CloudEntry source = findEntryUnsafe(entryId);
            if (source == null || !Objects.equals(source.getOwnerId(), ownerId) || source.isDeleted()) {
                throw new IllegalArgumentException("文件不存在");
            }
            String target = normalizePath(targetParentPath);
            requireFolderPathUnsafe(ownerId, target);
            String sourcePath = source.isFolder() ? buildEntryPath(source) : null;
            if (source.isFolder() && (target.equals(sourcePath) || target.startsWith(sourcePath + "/"))) {
                throw new IllegalArgumentException("不能把文件夹复制到自身或其子目录");
            }

            List<CloudEntry> originals = new ArrayList<>();
            originals.add(source);
            if (source.isFolder()) originals.addAll(listDescendantsUnsafe(ownerId, sourcePath));
            long additionalBytes = 0L;
            for (CloudEntry original : originals) {
                if (original.isFolder()) continue;
                if (Long.MAX_VALUE - additionalBytes < Math.max(0L, original.getSize())) {
                    additionalBytes = Long.MAX_VALUE;
                    break;
                }
                additionalBytes += Math.max(0L, original.getSize());
            }
            if (!canStoreUnsafe(user, additionalBytes)) {
                throw new IllegalArgumentException("空间已满，无法复制");
            }

            long now = System.currentTimeMillis();
            String rootName = resolveDuplicateNameUnsafe(ownerId, target, source.getName());
            CloudEntry rootCopy = cloneCloudEntryUnsafe(source, ownerId, target, rootName, now);
            entries.add(rootCopy);
            if (source.isFolder()) {
                String copiedRootPath = buildEntryPath(rootCopy);
                for (int i = 1; i < originals.size(); i++) {
                    CloudEntry original = originals.get(i);
                    String originalParent = normalizePath(original.getParentPath());
                    String relativeParent = originalParent.equals(sourcePath)
                            ? "" : originalParent.substring(sourcePath.length());
                    String copiedParent = normalizePath(copiedRootPath + relativeParent);
                    entries.add(cloneCloudEntryUnsafe(original, ownerId, copiedParent, original.getName(), now));
                }
            }
            saveUnsafe();
            return copyEntry(rootCopy);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteEntry(String ownerId, String entryId) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            markDeletedUnsafe(entry);
            if (entry.isFolder()) {
                String entryPath = buildEntryPath(entry);
                for (CloudEntry child : entries) {
                    if (Objects.equals(child.getOwnerId(), ownerId) && !child.isDeleted()) {
                        String childPath = buildEntryPath(child);
                        if (childPath.startsWith(entryPath + "/")) {
                            markDeletedUnsafe(child);
                        }
                    }
                }
            }
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void restoreEntry(String ownerId, String entryId) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            entry.setDeleted(false);
            entry.setDeletedAt(0);
            entry.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void permanentlyDelete(String ownerId, String entryId) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            String storedName = entry.getStoredName();
            entries.removeIf(item -> Objects.equals(item.getId(), entryId));
            saveUnsafe();
            cleanupStoredFile(ownerId, storedName);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudShareLink createShare(String ownerId, String entryId, String title, String shareType) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            CloudShareLink existing = shares.stream()
                    .filter(share -> Objects.equals(share.getOwnerId(), ownerId) && Objects.equals(share.getEntryId(), entryId))
                    .findFirst().orElse(null);
            long now = System.currentTimeMillis();
            if (existing != null) {
                existing.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : entry.getName());
                existing.setShareType(shareType != null ? shareType : "cloud");
                existing.setUpdatedAt(now);
                saveUnsafe();
                return copyShare(existing);
            }
            CloudShareLink share = new CloudShareLink();
            share.setId("share_" + UUID.randomUUID().toString().replace("-", ""));
            share.setOwnerId(ownerId);
            share.setEntryId(entryId);
            share.setTitle(title != null && !title.trim().isEmpty() ? title.trim() : entry.getName());
            share.setShareType(shareType != null ? shareType : "cloud");
            share.setCreatedAt(now);
            share.setUpdatedAt(now);
            shares.add(share);
            saveUnsafe();
            return copyShare(share);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudShareLink getShare(String shareId) {
        lock.readLock().lock();
        try {
            CloudShareLink share = shares.stream().filter(item -> Objects.equals(item.getId(), shareId)).findFirst().orElse(null);
            return share == null ? null : copyShare(share);
        } finally {
            lock.readLock().unlock();
        }
    }

    public CloudEntry copySharedEntryToUser(String shareId, User user, String parentPath) {
        lock.writeLock().lock();
        try {
            CloudShareLink share = shares.stream().filter(item -> Objects.equals(item.getId(), shareId)).findFirst().orElse(null);
            if (share == null) {
                throw new IllegalArgumentException("分享不存在");
            }
            CloudEntry source = findEntryUnsafe(share.getEntryId());
            if (source == null || source.isDeleted()) {
                throw new IllegalArgumentException("分享文件不存在");
            }
            if (source.isFolder()) {
                throw new IllegalArgumentException("暂不支持保存整个文件夹");
            }
            if (!canStore(user, source.getSize())) {
                throw new IllegalArgumentException("空间已满，无法存储");
            }
            try {
                Path userDir = userFilesDir(user.getUserId());
                Files.createDirectories(userDir);
                String ext = extractExtension(source.getName());
                String newStoredName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
                Path targetPath = userDir.resolve(newStoredName);
                try (InputStream in = openStreamCompat(source.getOwnerId(), source.getStoredName())) {
                    Files.copy(in, targetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                String normalizedParent = normalizePath(parentPath);
                requireFolderPathUnsafe(user.getUserId(), normalizedParent);
                String cleanedName = sanitizeName(source.getName());
                if (hasNameConflictUnsafe(user.getUserId(), normalizedParent, cleanedName, null)) {
                    cleanedName = resolveDuplicateNameUnsafe(user.getUserId(), normalizedParent, cleanedName);
                }
                long now = System.currentTimeMillis();
                CloudEntry entry = new CloudEntry();
                entry.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                entry.setOwnerId(user.getUserId());
                entry.setParentPath(normalizedParent);
                entry.setName(cleanedName);
                entry.setType("file");
                entry.setStoredName(newStoredName);
                entry.setContentType(source.getContentType());
                entry.setSize(source.getSize());
                entry.setCreatedAt(now);
                entry.setUpdatedAt(now);
                entry.setSourceModule("share");
                entries.add(entry);
                saveUnsafe();
                return copyEntry(entry);
            } catch (Exception e) {
                throw new RuntimeException("保存分享文件失败", e);
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void incrementMessageRef(String entryId) {
        if (entryId == null || entryId.trim().isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry != null) {
                entry.setMessageRefCount(Math.max(0, entry.getMessageRefCount()) + 1);
                entry.setUpdatedAt(System.currentTimeMillis());
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void releaseMessageRef(String entryId, String deletePolicy) {
        if (entryId == null || entryId.trim().isEmpty()) {
            return;
        }
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null) {
                return;
            }
            entry.setMessageRefCount(Math.max(0, entry.getMessageRefCount() - 1));
            entry.setUpdatedAt(System.currentTimeMillis());
            if (entry.getMessageRefCount() <= 0) {
                String policy = deletePolicy == null || deletePolicy.trim().isEmpty() ? "recycle" : deletePolicy;
                if ("delete".equals(policy)) {
                    String storedName = entry.getStoredName();
                    entries.removeIf(item -> Objects.equals(item.getId(), entry.getId()));
                    cleanupStoredFile(entry.getOwnerId(), storedName);
                } else if ("keep".equals(policy)) {
                } else {
                    markDeletedUnsafe(entry);
                }
            }
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void recordDownload(String ownerId, String entryId, String fileName) {
        lock.writeLock().lock();
        try {
            CloudDownloadRecord record = new CloudDownloadRecord();
            record.setId("dl_" + UUID.randomUUID().toString().replace("-", ""));
            record.setOwnerId(ownerId);
            record.setEntryId(entryId);
            record.setFileName(fileName);
            record.setDownloadedAt(System.currentTimeMillis());
            downloads.add(record);
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clearDownloads(String ownerId) {
        lock.writeLock().lock();
        try {
            downloads.removeIf(record -> Objects.equals(record.getOwnerId(), ownerId));
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> previewZipTree(String ownerId, String entryId) {
        lock.readLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId) || entry.isFolder()) {
                throw new IllegalArgumentException("压缩文件不存在");
            }
            if (entry.getStoredName() == null || !String.valueOf(entry.getName()).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new IllegalArgumentException("仅支持预览 ZIP 文件");
            }
            List<Map<String, Object>> items = new ArrayList<>();
            try (InputStream raw = openStreamCompat(ownerId, entry.getStoredName());
                 ZipInputStream zis = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
                ZipEntry zipEntry;
                int count = 0;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (count++ >= 5000) {
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("path", zipEntry.getName());
                    row.put("directory", zipEntry.isDirectory());
                    row.put("size", Math.max(0L, zipEntry.getSize()));
                    items.add(row);
                    zis.closeEntry();
                }
            } catch (Exception e) {
                throw new IllegalArgumentException("读取 ZIP 失败");
            }
            return items;
        } finally {
            lock.readLock().unlock();
        }
    }

    public CloudEntry unzipEntry(User user, String entryId) {
        if (user == null) {
            throw new IllegalArgumentException("未登录");
        }
        String taskId = "cloud_unzip_" + UUID.randomUUID().toString().replace("-", "");
        upsertTask(user.getUserId(), taskId, "unzip", "解压压缩包", "running", 0, 0, 0, "正在读取压缩包");
        lock.writeLock().lock();
        try {
            CloudEntry source = findEntryUnsafe(entryId);
            if (source == null || !Objects.equals(source.getOwnerId(), user.getUserId()) || source.isFolder()) {
                throw new IllegalArgumentException("压缩文件不存在");
            }
            if (source.getStoredName() == null || !String.valueOf(source.getName()).toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new IllegalArgumentException("仅支持解压 ZIP 文件");
            }
            String baseName = source.getName().replaceFirst("(?i)\\.zip$", "");
            String rootName = resolveDuplicateNameUnsafe(user.getUserId(), source.getParentPath(), sanitizeName(baseName.isEmpty() ? "解压文件" : baseName));
            CloudEntry rootFolder = new CloudEntry();
            long now = System.currentTimeMillis();
            rootFolder.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
            rootFolder.setOwnerId(user.getUserId());
            rootFolder.setParentPath(normalizePath(source.getParentPath()));
            rootFolder.setName(rootName);
            rootFolder.setType("folder");
            rootFolder.setCreatedAt(now);
            rootFolder.setUpdatedAt(now);
            rootFolder.setSourceModule("cloud-unzip");
            entries.add(rootFolder);

            long totalBytes = 0;
            long doneBytes = 0;
            Set<String> createdFolders = new HashSet<>();
            createdFolders.add(buildEntryPath(rootFolder));
            try (InputStream raw = openStreamCompat(user.getUserId(), source.getStoredName());
                 ZipInputStream zis = new ZipInputStream(raw, StandardCharsets.UTF_8)) {
                ZipEntry zipEntry;
                int entryCount = 0;
                while ((zipEntry = zis.getNextEntry()) != null) {
                    if (++entryCount > MAX_UNZIP_ENTRIES) {
                        throw new IllegalArgumentException("压缩包文件数量超过限制(" + MAX_UNZIP_ENTRIES + ")");
                    }
                    String normalizedPath = normalizeZipEntryPath(zipEntry.getName());
                    if (normalizedPath == null) {
                        zis.closeEntry();
                        continue;
                    }
                    if (zipEntry.isDirectory()) {
                        ensureFolderPathUnsafe(user.getUserId(), buildEntryPath(rootFolder), normalizedPath, createdFolders);
                        zis.closeEntry();
                        continue;
                    }
                    int slash = normalizedPath.lastIndexOf('/');
                    String relativeParent = slash >= 0 ? normalizedPath.substring(0, slash) : "";
                    String fileName = slash >= 0 ? normalizedPath.substring(slash + 1) : normalizedPath;
                    String targetParent = ensureFolderPathUnsafe(user.getUserId(), buildEntryPath(rootFolder), relativeParent, createdFolders);
                    long[] entrySizeBytes = new long[1];
                    String storedFileName = storeUserFileStream(user.getUserId(), zis, fileName, entrySizeBytes, user, totalBytes);
                    long bytesLen = entrySizeBytes[0];
                    totalBytes += bytesLen;
                    CloudEntry fileEntry = new CloudEntry();
                    fileEntry.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                    fileEntry.setOwnerId(user.getUserId());
                    fileEntry.setParentPath(targetParent);
                    fileEntry.setName(resolveDuplicateNameUnsafe(user.getUserId(), targetParent, sanitizeName(fileName)));
                    fileEntry.setType("file");
                    fileEntry.setStoredName(storedFileName);
                    fileEntry.setContentType(guessContentType(fileName));
                    fileEntry.setSize(bytesLen);
                    fileEntry.setCreatedAt(System.currentTimeMillis());
                    fileEntry.setUpdatedAt(System.currentTimeMillis());
                    fileEntry.setSourceModule("cloud-unzip");
                    entries.add(fileEntry);
                    doneBytes += bytesLen;
                    zis.closeEntry();
                }
            } catch (Exception e) {
                rollbackCreatedEntriesUnsafe(user.getUserId(), buildEntryPath(rootFolder));
                entries.removeIf(item -> Objects.equals(item.getId(), rootFolder.getId()));
                removeTask(user.getUserId(), taskId);
                throw e instanceof IllegalArgumentException ? (IllegalArgumentException) e : new IllegalArgumentException("解压失败");
            }
            saveUnsafe();
            upsertTask(user.getUserId(), taskId, "unzip", "解压压缩包", "done", totalBytes, doneBytes, 0, "解压完成");
            return copyEntry(rootFolder);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry compressEntry(User user, String entryId) {
        if (user == null) {
            throw new IllegalArgumentException("未登录");
        }
        String taskId = "cloud_zip_" + UUID.randomUUID().toString().replace("-", "");
        upsertTask(user.getUserId(), taskId, "compress", "压缩文件夹", "running", 0, 0, 0, "正在整理文件");
        lock.writeLock().lock();
        try {
            CloudEntry source = findEntryUnsafe(entryId);
            if (source == null || !Objects.equals(source.getOwnerId(), user.getUserId())) {
                throw new IllegalArgumentException("文件或文件夹不存在");
            }
            List<CloudEntry> descendants;
            if (source.isFolder()) {
                descendants = listDescendantsUnsafe(user.getUserId(), buildEntryPath(source));
            } else {
                descendants = Collections.emptyList();
            }
            long totalBytes = descendants.stream().filter(item -> !item.isFolder()).mapToLong(CloudEntry::getSize).sum();
            if (!source.isFolder()) {
                totalBytes = source.getSize();
            }
            if (!canStore(user, totalBytes)) {
                throw new IllegalArgumentException("云盘空间不足，无法压缩");
            }
            Path tempZip = null;
            String storedFileName;
            long zipSize = 0;
            try {
                tempZip = Files.createTempFile("cloud_zip_", ".tmp");
                try (OutputStream fout = Files.newOutputStream(tempZip);
                     ZipOutputStream zos = new ZipOutputStream(fout, StandardCharsets.UTF_8)) {
                    if (source.isFolder()) {
                        if (descendants.isEmpty()) {
                            zos.putNextEntry(new ZipEntry(source.getName() + "/"));
                            zos.closeEntry();
                        }
                        for (CloudEntry item : descendants) {
                            String fullPath = buildEntryPath(item);
                            String relative = fullPath.substring(buildEntryPath(source).length() + 1).replace("\\", "/");
                            if (item.isFolder()) {
                                if (!relative.isEmpty()) {
                                    zos.putNextEntry(new ZipEntry(relative + "/"));
                                    zos.closeEntry();
                                }
                                continue;
                            }
                            zos.putNextEntry(new ZipEntry(relative));
                            try (InputStream in = openStreamCompat(user.getUserId(), item.getStoredName())) {
                                in.transferTo(zos);
                            }
                            zos.closeEntry();
                        }
                    } else {
                        zos.putNextEntry(new ZipEntry(source.getName()));
                        try (InputStream in = openStreamCompat(user.getUserId(), source.getStoredName())) {
                            in.transferTo(zos);
                        }
                        zos.closeEntry();
                    }
                    zos.finish();
                }
                zipSize = Files.size(tempZip);
                storedFileName = storeUserFileFromPath(user.getUserId(), tempZip, source.getName() + ".zip");
            } catch (Exception e) {
                removeTask(user.getUserId(), taskId);
                throw new IllegalArgumentException("压缩失败");
            } finally {
                if (tempZip != null) {
                    try { Files.deleteIfExists(tempZip); } catch (Exception ignored) {}
                }
            }
            String zipDisplayName = source.getName() + ".zip";
            String normalizedParent = normalizePath(source.getParentPath());
            String cleanedName = sanitizeName(zipDisplayName);
            if (hasNameConflictUnsafe(user.getUserId(), normalizedParent, cleanedName, null)) {
                cleanedName = resolveDuplicateNameUnsafe(user.getUserId(), normalizedParent, cleanedName);
            }
            long now = System.currentTimeMillis();
            CloudEntry zipped = new CloudEntry();
            zipped.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
            zipped.setOwnerId(user.getUserId());
            zipped.setParentPath(normalizedParent);
            zipped.setName(cleanedName);
            zipped.setType("file");
            zipped.setStoredName(storedFileName);
            zipped.setContentType("application/zip");
            zipped.setSize(zipSize);
            zipped.setCreatedAt(now);
            zipped.setUpdatedAt(now);
            zipped.setSourceModule("cloud-zip");
            entries.add(zipped);
            saveUnsafe();
            upsertTask(user.getUserId(), taskId, "compress", "压缩文件夹", "done", totalBytes, totalBytes, 0, "压缩完成");
            return copyEntry(zipped);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry compressEntries(User user, List<String> entryIds, String zipName) {
        if (user == null || entryIds == null || entryIds.isEmpty()) {
            throw new IllegalArgumentException("参数不完整");
        }
        String taskId = "cloud_zip_" + UUID.randomUUID().toString().replace("-", "");
        upsertTask(user.getUserId(), taskId, "compress", "批量压缩", "running", 0, 0, 0, "正在整理文件");
        lock.writeLock().lock();
        try {
            List<CloudEntry> sources = new ArrayList<>();
            String commonParent = null;
            long totalBytes = 0;
            for (String eid : entryIds) {
                CloudEntry e = findEntryUnsafe(eid);
                if (e == null || !Objects.equals(e.getOwnerId(), user.getUserId()) || e.isDeleted()) {
                    throw new IllegalArgumentException("文件不存在: " + eid);
                }
                sources.add(e);
                if (commonParent == null) {
                    commonParent = e.getParentPath();
                }
                if (e.isFolder()) {
                    List<CloudEntry> desc = listDescendantsUnsafe(user.getUserId(), buildEntryPath(e));
                    totalBytes += desc.stream().filter(d -> !d.isFolder()).mapToLong(CloudEntry::getSize).sum();
                } else {
                    totalBytes += e.getSize();
                }
            }
            if (!canStore(user, totalBytes)) {
                throw new IllegalArgumentException("云盘空间不足，无法压缩");
            }
            Path tempZip = null;
            String storedFileName;
            long zipSize = 0;
            String finalZipName = (zipName != null && !zipName.trim().isEmpty()) ? zipName : "压缩包.zip";
            try {
                tempZip = Files.createTempFile("cloud_zip_", ".tmp");
                try (OutputStream fout = Files.newOutputStream(tempZip);
                     ZipOutputStream zos = new ZipOutputStream(fout, StandardCharsets.UTF_8)) {
                    for (CloudEntry src : sources) {
                        if (src.isFolder()) {
                            List<CloudEntry> desc = listDescendantsUnsafe(user.getUserId(), buildEntryPath(src));
                            if (desc.isEmpty()) {
                                zos.putNextEntry(new ZipEntry(src.getName() + "/"));
                                zos.closeEntry();
                            }
                            for (CloudEntry item : desc) {
                                String fullPath = buildEntryPath(item);
                                String relative = fullPath.substring(buildEntryPath(src).length() + 1).replace("\\", "/");
                                if (item.isFolder()) {
                                    if (!relative.isEmpty()) {
                                        zos.putNextEntry(new ZipEntry(src.getName() + "/" + relative + "/"));
                                        zos.closeEntry();
                                    }
                                    continue;
                                }
                                zos.putNextEntry(new ZipEntry(src.getName() + "/" + relative));
                                try (InputStream in = openStreamCompat(user.getUserId(), item.getStoredName())) {
                                    in.transferTo(zos);
                                }
                                zos.closeEntry();
                            }
                        } else {
                            zos.putNextEntry(new ZipEntry(src.getName()));
                            try (InputStream in = openStreamCompat(user.getUserId(), src.getStoredName())) {
                                in.transferTo(zos);
                            }
                            zos.closeEntry();
                        }
                    }
                    zos.finish();
                }
                zipSize = Files.size(tempZip);
                storedFileName = storeUserFileFromPath(user.getUserId(), tempZip, finalZipName);
            } catch (Exception e) {
                removeTask(user.getUserId(), taskId);
                throw new IllegalArgumentException("压缩失败");
            } finally {
                if (tempZip != null) {
                    try { Files.deleteIfExists(tempZip); } catch (Exception ignored) {}
                }
            }
            String parentPath = commonParent != null ? commonParent : "/";
            String finalName = (zipName != null && !zipName.trim().isEmpty()) ? zipName : "压缩包.zip";
            String normalizedParent = normalizePath(parentPath);
            String cleanedName = sanitizeName(finalName);
            if (hasNameConflictUnsafe(user.getUserId(), normalizedParent, cleanedName, null)) {
                cleanedName = resolveDuplicateNameUnsafe(user.getUserId(), normalizedParent, cleanedName);
            }
            long now = System.currentTimeMillis();
            CloudEntry zipped = new CloudEntry();
            zipped.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
            zipped.setOwnerId(user.getUserId());
            zipped.setParentPath(normalizedParent);
            zipped.setName(cleanedName);
            zipped.setType("file");
            zipped.setStoredName(storedFileName);
            zipped.setContentType("application/zip");
            zipped.setSize(zipSize);
            zipped.setCreatedAt(now);
            zipped.setUpdatedAt(now);
            zipped.setSourceModule("cloud-zip");
            entries.add(zipped);
            saveUnsafe();
            upsertTask(user.getUserId(), taskId, "compress", "批量压缩", "done", totalBytes, totalBytes, 0, "压缩完成");
            return copyEntry(zipped);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudTask upsertTask(String ownerId, String taskId, String type, String title, String status,
                                long totalBytes, long processedBytes, double speedBytes, String detail) {
        lock.writeLock().lock();
        try {
            CloudTask task = tasks.stream().filter(item -> Objects.equals(item.getId(), taskId)).findFirst().orElse(null);
            long now = System.currentTimeMillis();
            if (task == null) {
                task = new CloudTask();
                task.setId(taskId);
                task.setOwnerId(ownerId);
                task.setType(type);
                task.setTitle(title);
                task.setCreatedAt(now);
                tasks.add(task);
            }
            task.setStatus(status);
            task.setTitle(title);
            task.setTotalBytes(totalBytes);
            task.setProcessedBytes(processedBytes);
            task.setSpeedBytesPerSec(speedBytes);
            task.setDetail(detail);
            task.setUpdatedAt(now);
            saveUnsafe();
            return copyTask(task);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void removeTask(String ownerId, String taskId) {
        lock.writeLock().lock();
        try {
            tasks.removeIf(task -> Objects.equals(task.getOwnerId(), ownerId) && Objects.equals(task.getId(), taskId));
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry toggleFavorite(String ownerId, String entryId) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            entry.setFavorite(!entry.isFavorite());
            entry.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public CloudEntry toggleSafebox(String ownerId, String entryId) {
        lock.writeLock().lock();
        try {
            CloudEntry entry = findEntryUnsafe(entryId);
            if (entry == null || !Objects.equals(entry.getOwnerId(), ownerId)) {
                throw new IllegalArgumentException("文件不存在");
            }
            entry.setSafebox(!entry.isSafebox());
            entry.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyEntry(entry);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<CloudEntry> listFavorites(String ownerId) {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                    .filter(entry -> !entry.isDeleted())
                    .filter(CloudEntry::isFavorite)
                    .sorted(Comparator.comparingLong(CloudEntry::getUpdatedAt).reversed())
                    .map(this::copyEntry)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<CloudEntry> listSafebox(String ownerId) {
        lock.readLock().lock();
        try {
            return entries.stream()
                    .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                    .filter(entry -> !entry.isDeleted())
                    .filter(CloudEntry::isSafebox)
                    .sorted(Comparator.comparingLong(CloudEntry::getUpdatedAt).reversed())
                    .map(this::copyEntry)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public InputStream openCloudFileStream(String userId, String storedName) throws Exception {
        if (userId == null || storedName == null || storedName.trim().isEmpty()) {
            throw new IllegalArgumentException("参数不能为空");
        }
        Path userDir = userFilesDir(userId).toAbsolutePath().normalize();
        Path userFile = userDir.resolve(storedName).toAbsolutePath().normalize();
        if (!userFile.startsWith(userDir)) {
            throw new SecurityException("非法路径穿越请求");
        }
        if (Files.exists(userFile)) {
            return Files.newInputStream(userFile, StandardOpenOption.READ);
        }
        return FileStore.getInstance().openStream(storedName);
    }

    private InputStream openStreamCompat(String userId, String storedName) throws Exception {
        return openCloudFileStream(userId, storedName);
    }

    private void load() {
        lock.writeLock().lock();
        try {
            entries.clear();
            shares.clear();
            tasks.clear();
            downloads.clear();
            loadList(entriesFile(), new TypeToken<List<CloudEntry>>() {}.getType(), entries);
            loadList(sharesFile(), new TypeToken<List<CloudShareLink>>() {}.getType(), shares);
            loadList(tasksFile(), new TypeToken<List<CloudTask>>() {}.getType(), tasks);
            loadList(downloadsFile(), new TypeToken<List<CloudDownloadRecord>>() {}.getType(), downloads);
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
            System.err.println("[CloudService] 加载失败: " + file.getFileName() + " -> " + e.getMessage());
            try {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[CloudService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(dataDir());
            com.chat.util.JsonUtil.saveJsonAtomic(entriesFile(), entries);
            com.chat.util.JsonUtil.saveJsonAtomic(sharesFile(), shares);
            com.chat.util.JsonUtil.saveJsonAtomic(tasksFile(), tasks);
            com.chat.util.JsonUtil.saveJsonAtomic(downloadsFile(), downloads);
        } catch (Exception e) {
            System.err.println("[CloudService] 保存失败: " + e.getMessage());
        }
    }

    private boolean hasFolderUnsafe(String ownerId, String fullPath) {
        String normalized = normalizePath(fullPath);
        return entries.stream().anyMatch(entry ->
                Objects.equals(entry.getOwnerId(), ownerId)
                        && !entry.isDeleted()
                        && entry.isFolder()
                        && Objects.equals(buildEntryPath(entry), normalized));
    }

    private void requireFolderPathUnsafe(String ownerId, String fullPath) {
        String normalized = normalizePath(fullPath);
        if (!"/".equals(normalized) && !hasFolderUnsafe(ownerId, normalized)) {
            throw new IllegalArgumentException("目标文件夹不存在");
        }
    }

    private void rewriteDescendantParentPathsUnsafe(String ownerId, String oldRootPath,
                                                     String newRootPath, long updatedAt) {
        if (oldRootPath == null || Objects.equals(oldRootPath, newRootPath)) return;
        for (CloudEntry child : entries) {
            if (!Objects.equals(child.getOwnerId(), ownerId) || child.isDeleted()) continue;
            String parent = normalizePath(child.getParentPath());
            if (parent.equals(oldRootPath) || parent.startsWith(oldRootPath + "/")) {
                child.setParentPath(normalizePath(newRootPath + parent.substring(oldRootPath.length())));
                child.setUpdatedAt(updatedAt);
            }
        }
    }

    private CloudEntry cloneCloudEntryUnsafe(CloudEntry source, String ownerId, String parentPath,
                                              String name, long now) {
        CloudEntry copy = new CloudEntry();
        copy.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
        copy.setOwnerId(ownerId);
        copy.setParentPath(normalizePath(parentPath));
        copy.setName(name);
        copy.setType(source.getType());
        copy.setStoredName(source.getStoredName());
        copy.setContentType(source.getContentType());
        copy.setSize(source.getSize());
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy.setSourceModule("cloud-copy");
        copy.setFavorite(source.isFavorite());
        copy.setSafebox(source.isSafebox());
        return copy;
    }

    private CloudEntry findEntryUnsafe(String entryId) {
        return entries.stream().filter(entry -> Objects.equals(entry.getId(), entryId)).findFirst().orElse(null);
    }

    private boolean hasNameConflictUnsafe(String ownerId, String parentPath, String name, String excludeId) {
        return entries.stream().anyMatch(entry ->
                Objects.equals(entry.getOwnerId(), ownerId)
                        && !entry.isDeleted()
                        && Objects.equals(normalizePath(entry.getParentPath()), normalizePath(parentPath))
                        && Objects.equals(entry.getName(), name)
                        && !Objects.equals(entry.getId(), excludeId));
    }

    private String resolveDuplicateNameUnsafe(String ownerId, String parentPath, String name) {
        String base = name;
        String ext = "";
        int idx = name.lastIndexOf('.');
        if (idx > 0) {
            base = name.substring(0, idx);
            ext = name.substring(idx);
        }
        int suffix = 1;
        String candidate = name;
        while (hasNameConflictUnsafe(ownerId, parentPath, candidate, null)) {
            candidate = base + "_" + suffix + ext;
            suffix++;
        }
        return candidate;
    }

    private void markDeletedUnsafe(CloudEntry entry) {
        entry.setDeleted(true);
        entry.setDeletedAt(System.currentTimeMillis());
        entry.setUpdatedAt(System.currentTimeMillis());
    }

    private void purgeRecycleUnsafe(boolean saveAfter) {
        long now = System.currentTimeMillis();
        List<CloudEntry> expired = entries.stream()
                .filter(CloudEntry::isDeleted)
                .filter(entry -> entry.getDeletedAt() > 0 && now - entry.getDeletedAt() >= RECYCLE_EXPIRE_MS)
                .collect(Collectors.toList());
        if (expired.isEmpty()) {
            return;
        }
        Set<String> expiredIds = expired.stream().map(CloudEntry::getId).collect(Collectors.toSet());
        entries.removeIf(e -> expiredIds.contains(e.getId()));
        for (CloudEntry entry : expired) {
            cleanupStoredFile(entry.getOwnerId(), entry.getStoredName());
        }
        if (saveAfter) {
            saveUnsafe();
        }
    }

    private void cleanupStoredFile(String ownerId, String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) {
            return;
        }
        boolean stillUsed = entries.stream()
                .anyMatch(entry -> !entry.isFolder() && Objects.equals(entry.getStoredName(), storedName));
        if (!stillUsed) {
            Path userFile = userFilesDir(ownerId).resolve(storedName);
            try {
                Files.deleteIfExists(userFile);
            } catch (Exception ignored) {}
            // cloud-files 是每用户副本；同名的全局 FileStore blob 可能仍被聊天、
            // 动态、笔记、小程序或另一位去重上传者引用。这里绝不能级联删除全局 blob，
            // 全局存储清理由拥有完整引用视图的专用 GC 负责。
        }
    }

    private void deleteUserStoredFileQuietly(String ownerId, String storedName) {
        if (ownerId == null || storedName == null || storedName.isBlank()) return;
        try {
            Path base = userFilesDir(ownerId).toAbsolutePath().normalize();
            Path target = base.resolve(storedName).toAbsolutePath().normalize();
            if (target.startsWith(base)) Files.deleteIfExists(target);
        } catch (Exception ignored) {}
    }

    private String normalizePath(String path) {
        if (path == null || path.trim().isEmpty()) {
            return "/";
        }
        String normalized = path.replace("\\", "/").trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.contains("//")) {
            normalized = normalized.replace("//", "/");
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized.isEmpty() ? "/" : normalized;
    }

    private String sanitizeName(String name) {
        String cleaned = FileStore.normalizeUploadedFileName(name);
        if (cleaned.contains("/")) {
            cleaned = cleaned.substring(cleaned.lastIndexOf('/') + 1);
        }
        cleaned = cleaned.trim();
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)
                || cleaned.chars().anyMatch(ch -> ch < 32)) {
            throw new IllegalArgumentException("名称不能为空");
        }
        return cleaned;
    }

    static String normalizeZipEntryPath(String rawPath) {
        if (rawPath == null) return null;
        String value = rawPath.replace('\\', '/').trim();
        if (value.isEmpty() || value.startsWith("/") || value.indexOf('\0') >= 0
                || value.matches("^[A-Za-z]:.*")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String rawPart : value.split("/")) {
            if (rawPart.isEmpty()) continue;
            if (".".equals(rawPart) || "..".equals(rawPart) || rawPart.indexOf(':') >= 0) {
                return null;
            }
            parts.add(rawPart);
            if (parts.size() > MAX_UNZIP_PATH_DEPTH) return null;
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    private String buildEntryPath(CloudEntry entry) {
        String parent = normalizePath(entry.getParentPath());
        return "/".equals(parent) ? "/" + entry.getName() : parent + "/" + entry.getName();
    }

    private List<CloudEntry> listDescendantsUnsafe(String ownerId, String rootPath) {
        return entries.stream()
                .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                .filter(entry -> !entry.isDeleted())
                .filter(entry -> buildEntryPath(entry).startsWith(rootPath + "/"))
                .sorted(Comparator.comparing(CloudEntry::isFolder).reversed().thenComparing(this::buildEntryPath))
                .collect(Collectors.toList());
    }

    private String ensureFolderPathUnsafe(String ownerId, String rootPath, String relativePath, Set<String> createdFolders) {
        String currentParent = rootPath;
        if (relativePath == null || relativePath.trim().isEmpty()) {
            return rootPath;
        }
        for (String rawPart : relativePath.split("/")) {
            String part = sanitizeName(rawPart);
            String nextPath = currentParent + "/" + part;
            if (!createdFolders.contains(nextPath)) {
                String parentPath = normalizePath(currentParent);
                CloudEntry folder = new CloudEntry();
                folder.setId("cloud_" + UUID.randomUUID().toString().replace("-", ""));
                folder.setOwnerId(ownerId);
                folder.setParentPath(parentPath);
                folder.setName(resolveDuplicateNameUnsafe(ownerId, parentPath, part));
                folder.setType("folder");
                folder.setCreatedAt(System.currentTimeMillis());
                folder.setUpdatedAt(System.currentTimeMillis());
                folder.setSourceModule("cloud-unzip");
                entries.add(folder);
                nextPath = buildEntryPath(folder);
                createdFolders.add(nextPath);
            }
            currentParent = nextPath;
        }
        return currentParent;
    }

    private void rollbackCreatedEntriesUnsafe(String ownerId, String rootPath) {
        List<CloudEntry> created = entries.stream()
                .filter(entry -> Objects.equals(entry.getOwnerId(), ownerId))
                .filter(entry -> buildEntryPath(entry).startsWith(rootPath + "/"))
                .collect(Collectors.toList());
        for (CloudEntry entry : created) {
            entries.removeIf(item -> Objects.equals(item.getId(), entry.getId()));
            cleanupStoredFile(ownerId, entry.getStoredName());
        }
    }

    private String guessContentType(String fileName) {
        String lower = String.valueOf(fileName).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain; charset=utf-8";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".html")) return "text/html; charset=utf-8";
        if (lower.endsWith(".css")) return "text/css; charset=utf-8";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }

    private String appendTimestampBeforeExtension(String fileName, long timestampSeconds) {
        int idx = fileName.lastIndexOf('.');
        if (idx > 0) {
            return fileName.substring(0, idx) + "_" + timestampSeconds + fileName.substring(idx);
        }
        return fileName + "_" + timestampSeconds;
    }

    private String extractExtension(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx > 0 && idx < fileName.length() - 1) {
            return fileName.substring(idx + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }

    private String storeUserFileBytes(String userId, byte[] data, String originalFileName) throws Exception {
        Path userDir = userFilesDir(userId);
        Files.createDirectories(userDir);
        String ext = extractExtension(originalFileName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        Path targetPath = userDir.resolve(storedName);
        com.chat.util.JsonUtil.writeBytesAtomic(targetPath, data);
        return storedName;
    }

    private String storeUserFileFromPath(String userId, Path sourceFile, String originalFileName) throws Exception {
        Path userDir = userFilesDir(userId);
        Files.createDirectories(userDir);
        String ext = extractExtension(originalFileName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        Path targetPath = userDir.resolve(storedName);
        Files.move(sourceFile, targetPath, StandardCopyOption.REPLACE_EXISTING);
        return storedName;
    }

    private String storeUserFileStream(String userId, InputStream inputStream, String originalFileName, long[] outWrittenSize, User user, long currentTotalUnzippedBytes) throws Exception {
        Path userDir = userFilesDir(userId);
        Files.createDirectories(userDir);
        String ext = extractExtension(originalFileName);
        String storedName = UUID.randomUUID().toString().replace("-", "") + (ext.isEmpty() ? "" : "." + ext);
        Path targetPath = userDir.resolve(storedName);
        Path tempPath = userDir.resolve(storedName + ".tmp");
        long size = 0;
        try {
            try (OutputStream out = Files.newOutputStream(tempPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = inputStream.read(buffer)) != -1) {
                    size += len;
                    if (size > MAX_UNZIP_SINGLE_FILE_BYTES) {
                        throw new IllegalArgumentException("解压单个文件超过限制(500MB)");
                    }
                    if (currentTotalUnzippedBytes > MAX_UNZIP_TOTAL_BYTES - size) {
                        throw new IllegalArgumentException("压缩包解压总大小超过限制(2GB)");
                    }
                    if (!canStoreUnsafe(user, size)) {
                        throw new IllegalArgumentException("云盘空间不足，无法完成解压");
                    }
                    out.write(buffer, 0, len);
                }
            }
            Files.move(tempPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
            if (outWrittenSize != null && outWrittenSize.length > 0) {
                outWrittenSize[0] = size;
            }
            return storedName;
        } finally {
            try {
                Files.deleteIfExists(tempPath);
            } catch (Exception ignored) {}
        }
    }

    public boolean hasAccessToFile(String userId, String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return false;
        lock.readLock().lock();
        try {
            return entries.stream()
                    .filter(e -> !e.isFolder() && Objects.equals(e.getStoredName(), storedName))
                    .anyMatch(e -> Objects.equals(e.getOwnerId(), userId) || isPublicFileShare(storedName));
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean isPublicFileShare(String storedName) {
        if (storedName == null || storedName.trim().isEmpty()) return false;
        lock.readLock().lock();
        try {
            Set<String> sharedEntryIds = shares.stream().map(CloudShareLink::getEntryId).collect(Collectors.toSet());
            return entries.stream()
                    .filter(e -> !e.isFolder() && Objects.equals(e.getStoredName(), storedName))
                    .anyMatch(e -> sharedEntryIds.contains(e.getId()));
        } finally {
            lock.readLock().unlock();
        }
    }

    private CloudEntry copyEntry(CloudEntry source) {
        if (source == null) return null;
        CloudEntry copy = new CloudEntry();
        copy.setId(source.getId());
        copy.setOwnerId(source.getOwnerId());
        copy.setParentPath(source.getParentPath());
        copy.setName(source.getName());
        copy.setType(source.getType());
        copy.setStoredName(source.getStoredName());
        copy.setContentType(source.getContentType());
        copy.setSize(source.getSize());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setDeleted(source.isDeleted());
        copy.setDeletedAt(source.getDeletedAt());
        copy.setSourceModule(source.getSourceModule());
        copy.setMessageRefCount(source.getMessageRefCount());
        copy.setFavorite(source.isFavorite());
        copy.setSafebox(source.isSafebox());
        return copy;
    }

    private CloudShareLink copyShare(CloudShareLink source) {
        if (source == null) return null;
        CloudShareLink copy = new CloudShareLink();
        copy.setId(source.getId());
        copy.setOwnerId(source.getOwnerId());
        copy.setEntryId(source.getEntryId());
        copy.setTitle(source.getTitle());
        copy.setShareType(source.getShareType());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        copy.setVisitCount(source.getVisitCount());
        return copy;
    }

    private CloudTask copyTask(CloudTask source) {
        if (source == null) return null;
        CloudTask copy = new CloudTask();
        copy.setId(source.getId());
        copy.setOwnerId(source.getOwnerId());
        copy.setType(source.getType());
        copy.setTitle(source.getTitle());
        copy.setStatus(source.getStatus());
        copy.setTotalBytes(source.getTotalBytes());
        copy.setProcessedBytes(source.getProcessedBytes());
        copy.setSpeedBytesPerSec(source.getSpeedBytesPerSec());
        copy.setDetail(source.getDetail());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private CloudDownloadRecord copyDownload(CloudDownloadRecord source) {
        if (source == null) return null;
        CloudDownloadRecord copy = new CloudDownloadRecord();
        copy.setId(source.getId());
        copy.setOwnerId(source.getOwnerId());
        copy.setEntryId(source.getEntryId());
        copy.setFileName(source.getFileName());
        copy.setDownloadedAt(source.getDownloadedAt());
        return copy;
    }

    public void deleteUserCloud(String userId) {
        if (userId == null) return;
        lock.writeLock().lock();
        try {
            List<CloudEntry> toDelete = entries.stream()
                .filter(e -> Objects.equals(e.getOwnerId(), userId))
                .collect(Collectors.toList());
            for (CloudEntry entry : toDelete) {
                if (!entry.isFolder() && entry.getStoredName() != null) {
                    cleanupStoredFile(userId, entry.getStoredName());
                }
            }
            entries.removeIf(e -> Objects.equals(e.getOwnerId(), userId));
            shares.removeIf(s -> Objects.equals(s.getOwnerId(), userId));
            tasks.removeIf(t -> Objects.equals(t.getOwnerId(), userId));
            downloads.removeIf(d -> Objects.equals(d.getOwnerId(), userId));
            saveUnsafe();
            
            Path userDir = userFilesDir(userId);
            try {
                if (Files.exists(userDir)) {
                    Files.walk(userDir)
                         .sorted(Comparator.reverseOrder())
                         .map(Path::toFile)
                         .forEach(java.io.File::delete);
                }
            } catch (Exception ignored) {}
        } finally {
            lock.writeLock().unlock();
        }
    }
}
