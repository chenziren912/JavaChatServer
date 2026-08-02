package com.chat.service;

import com.chat.model.Message;
import com.chat.model.User;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class MessageService {
    private static final MessageService INSTANCE = new MessageService();
    public static MessageService getInstance() { return INSTANCE; }

    public static final int PAGE_SIZE = 20;

    private static final String CHATS_DIR = "chatserver/chats";

    // old storage paths — for one-time migration
    private static final String OLD_PUBLIC  = "chatserver/public";
    private static final String OLD_PRIVATE = "chatserver/private";
    private static final String OLD_GROUPS  = "chatserver/groups";

    // rooms untouched for this long get evicted from memory
    private static final long CACHE_TTL_MS = 30 * 60 * 1000L;
    // how often to check for stale rooms
    private static final long CLEANUP_INTERVAL_MS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, RoomData> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> messageIdToRoomIdMap = new ConcurrentHashMap<>();
    private final AtomicLong idCounter = new AtomicLong(1);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile long lastCleanupMs = System.currentTimeMillis();

    /** Lightweight handle for a single room's messages. */
    private static final class RoomData {
        final List<Message> messages = new ArrayList<>();
        volatile long lastAccessMs = System.currentTimeMillis();
    }

    // ---------------------------------------------------------------

    private MessageService() {
        try { Files.createDirectories(Paths.get(CHATS_DIR)); } catch (Exception ignored) {}
        migrateOldData();
        initIdCounter();
        System.out.println("[MessageService] 已初始化 缓存模式 TTL=" + (CACHE_TTL_MS / 60000) + "min");
    }

    // ========================  disk I/O  ==========================

    private Path messageFile(String roomId) {
        return Paths.get(CHATS_DIR, roomId, "message");
    }

    private List<Message> loadFromDisk(String roomId) {
        Path f = messageFile(roomId);
        if (!Files.exists(f)) return new ArrayList<>();
        try {
            String json = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
            Type t = new TypeToken<List<Message>>(){}.getType();
            List<Message> list = JsonUtil.fromJson(json, t);
            if (list == null) return new ArrayList<>();
            for (Message m : list) {
                if (m.getMsgType() == null) m.setMsgType("text");
                if (m.getContent() == null) m.setContent("");
            }
            return list;
        } catch (Exception e) {
            System.err.println("[MessageService] load " + roomId + " err: " + e.getMessage());
            try {
                Path bak = f.resolveSibling(f.getFileName() + ".bak");
                Files.copy(f, bak, StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {}
            return new ArrayList<>();
        }
    }

    public void saveRoom(String roomId) {
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            saveToDisk(roomId, new ArrayList<>(rd.messages));
        }
    }

    private boolean saveToDisk(String roomId, Collection<Message> messages) {
        try {
            String json = JsonUtil.toJson(messages);
            Path f = messageFile(roomId);
            Files.createDirectories(f.getParent());
            com.chat.util.JsonUtil.writeBytesAtomic(f, json.getBytes(StandardCharsets.UTF_8));
            return true;
        } catch (Exception e) {
            System.err.println("[MessageService] save " + roomId + " err: " + e.getMessage());
            return false;
        }
    }

    // ========================  cache layer  =======================

    /** Return cached room, or load from disk and cache it. */
    private RoomData loadRoom(String roomId) {
        RoomData rd = cache.get(roomId);
        if (rd != null) {
            rd.lastAccessMs = System.currentTimeMillis();
            return rd;
        }
        lock.writeLock().lock();
        try {
            rd = cache.get(roomId);
            if (rd != null) {
                rd.lastAccessMs = System.currentTimeMillis();
                return rd;
            }
            rd = new RoomData();
            rd.messages.addAll(loadFromDisk(roomId));
            cache.put(roomId, rd);
            return rd;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Evict rooms not accessed within TTL. */
    private void evictStale() {
        long deadline = System.currentTimeMillis() - CACHE_TTL_MS;
        lock.writeLock().lock();
        try {
            Iterator<Map.Entry<String, RoomData>> it = cache.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, RoomData> e = it.next();
                if (e.getValue().lastAccessMs < deadline) {
                    synchronized (e.getValue().messages) {
                        if (saveToDisk(e.getKey(), new ArrayList<>(e.getValue().messages))) {
                            it.remove();
                        }
                    }
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Rate-limited cleanup trigger. */
    private void triggerCleanup() {
        long now = System.currentTimeMillis();
        if (now - lastCleanupMs > CLEANUP_INTERVAL_MS) {
            lastCleanupMs = now;
            evictStale();
        }
    }



    // ========================  migration  =========================

    private void migrateOldData() {
        boolean migrated = false;

        // public
        File pub = new File(OLD_PUBLIC, "messages.json");
        if (pub.exists()) {
            List<Message> msgs = readOldFile(pub);
            if (!msgs.isEmpty()) { saveToDisk("public", msgs); System.out.println("[MessageService] migrate public " + msgs.size() + " msgs"); }
            pub.delete(); migrated = true;
        }

        // private
        File privDir = new File(OLD_PRIVATE);
        if (privDir.exists()) {
            File[] files = privDir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) for (File f : files) {
                String rid = f.getName().replace(".json", "");
                if (!new File(CHATS_DIR, rid).isDirectory()) {
                    List<Message> msgs = readOldFile(f);
                    if (!msgs.isEmpty()) saveToDisk(rid, msgs);
                }
                f.delete();
            }
            migrated = true;
        }

        // groups
        File grpDir = new File(OLD_GROUPS);
        if (grpDir.exists()) {
            File[] dirs = grpDir.listFiles(File::isDirectory);
            if (dirs != null) for (File gd : dirs) {
                File gf = new File(gd, "messages.json");
                if (gf.exists()) {
                    String rid = "group_" + gd.getName();
                    if (!new File(CHATS_DIR, rid).isDirectory()) {
                        List<Message> msgs = readOldFile(gf);
                        if (!msgs.isEmpty()) saveToDisk(rid, msgs);
                    }
                    gf.delete();
                }
            }
            migrated = true;
        }

        if (migrated) {
            // sweep empty old dirs
            deleteIfEmpty(OLD_PUBLIC); deleteIfEmpty(OLD_PRIVATE);
            if (grpDir.exists()) {
                File[] subs = grpDir.listFiles(File::isDirectory);
                if (subs != null) for (File sd : subs) sd.delete();
                grpDir.delete();
            }
            System.out.println("[MessageService] 旧数据迁移完成");
        }
    }

    private static List<Message> readOldFile(File f) {
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Type t = new TypeToken<List<Message>>(){}.getType();
            List<Message> list = JsonUtil.fromJson(json, t);
            return list != null ? list : new ArrayList<>();
        } catch (Exception e) { return new ArrayList<>(); }
    }

    private static void deleteIfEmpty(String path) {
        try { File d = new File(path); if (d.exists() && d.isDirectory() && d.list().length == 0) d.delete(); } catch (Exception ignored) {}
    }

    // ========================  id counter  ========================

    private void initIdCounter() {
        File dir = new File(CHATS_DIR);
        if (!dir.exists()) return;
        long maxId = 0;
        File[] rooms = dir.listFiles(File::isDirectory);
        if (rooms != null) for (File r : rooms) {
            File mf = new File(r, "message");
            if (!mf.exists()) continue;
            try {
                for (Message msg : readOldFile(mf)) {
                    if (msg.getId() != null) {
                        messageIdToRoomIdMap.put(msg.getId(), r.getName());
                    }
                    try { long n = Long.parseLong(msg.getId()); if (n > maxId) maxId = n; } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        if (maxId > 0) idCounter.set(maxId + 1);
        System.out.println("[MessageService] ID counter start: " + idCounter.get());
    }

    // ========================  room id helpers  ===================

    public static String normalizePrivateRoomId(String a, String b) {
        return a.compareTo(b) <= 0 ? "private_" + a + "_" + b : "private_" + b + "_" + a;
    }

    public static String getPrivateRoomPeer(String roomId, String userId) {
        if (roomId == null || userId == null || !roomId.startsWith("private_")) return null;
        String participants = roomId.substring("private_".length());
        String peer;
        if (participants.startsWith(userId + "_")) {
            peer = participants.substring(userId.length() + 1);
        } else if (participants.endsWith("_" + userId)) {
            peer = participants.substring(0, participants.length() - userId.length() - 1);
        } else {
            return null;
        }
        if (peer.isBlank() || !normalizePrivateRoomId(userId, peer).equals(roomId)) return null;
        return peer;
    }

    public static boolean isPrivateRoomParticipant(String roomId, String userId) {
        return getPrivateRoomPeer(roomId, userId) != null;
    }

    private static String normalizeRoomId(String fromUserId, String chatRoomId) {
        if ("public".equals(chatRoomId) || chatRoomId.startsWith("group_") || chatRoomId.startsWith("private_"))
            return chatRoomId;
        return normalizePrivateRoomId(fromUserId, chatRoomId);
    }

    // ========================  public API  ========================

    // ----- read -----

    public Message getLastMessage(String chatRoomId, String currentUserId) {
        String roomId = normalizeRoomId(currentUserId, chatRoomId);
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            return rd.messages.isEmpty() ? null : rd.messages.get(rd.messages.size() - 1);
        }
    }

    public List<Message> getMessagesPaged(String chatRoomId, long beforeId, String currentUserId) {
        String roomId = normalizeRoomId(currentUserId, chatRoomId);
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            long refTimestamp = -1;
            if (beforeId > 0) {
                for (Message m : rd.messages) {
                    if (String.valueOf(beforeId).equals(m.getId())) {
                        refTimestamp = m.getTimestamp();
                        break;
                    }
                }
            }
            final long targetTime = refTimestamp;
            List<Message> filtered = rd.messages.stream()
                .filter(m -> {
                    if (beforeId == 0) return true;
                    try {
                        return Long.parseLong(m.getId()) < beforeId;
                    } catch (Exception e) {
                        if (targetTime > 0) {
                            return m.getTimestamp() < targetTime;
                        } else {
                            return m.getTimestamp() < beforeId;
                        }
                    }
                })
                .collect(Collectors.toList());
            int size = filtered.size();
            if (size == 0) return Collections.emptyList();
            return new ArrayList<>(filtered.subList(Math.max(0, size - PAGE_SIZE), size));
        }
    }

    public List<Message> getMessagesSince(String chatRoomId, long sinceId, String currentUserId) {
        String roomId = normalizeRoomId(currentUserId, chatRoomId);
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            long refTimestamp = -1;
            if (sinceId > 0) {
                for (Message m : rd.messages) {
                    if (String.valueOf(sinceId).equals(m.getId())) {
                        refTimestamp = m.getTimestamp();
                        break;
                    }
                }
            }
            final long targetTime = refTimestamp;
            return rd.messages.stream()
                .filter(m -> {
                    try {
                        return Long.parseLong(m.getId()) > sinceId;
                    } catch (Exception e) {
                        if (targetTime > 0) {
                            return m.getTimestamp() > targetTime;
                        } else {
                            return m.getTimestamp() > sinceId;
                        }
                    }
                })
                .collect(Collectors.toList());
        }
    }

    /**
     * Find message by ID using the pre-populated messageIdToRoomIdMap.
     */
    public Message getById(String messageId) {
        if (messageId == null) return null;
        String roomId = messageIdToRoomIdMap.get(messageId);
        if (roomId == null) return null;
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            for (Message cached : rd.messages) {
                if (cached.getId().equals(messageId)) return cached;
            }
        }
        return null;
    }

    public Message findMessageByClientMsgId(String fromUserId, String chatRoomId, String clientMsgId) {
        if (clientMsgId == null || clientMsgId.trim().isEmpty()) return null;
        String roomId = normalizeRoomId(fromUserId, chatRoomId);
        RoomData rd = loadRoom(roomId);
        synchronized (rd.messages) {
            return rd.messages.stream()
                    .filter(m -> Objects.equals(m.getFromUserId(), fromUserId)
                            && Objects.equals(m.getClientMsgId(), clientMsgId))
                    .findFirst()
                    .orElse(null);
        }
    }

    // ----- write -----

    public Message sendMessage(String fromUserId, String fromNickname,
                               String toUserId, String content, String chatRoomId,
                               String msgType, String fileName, String filePath,
                               String fwdNickname, String fwdUserId,
                               List<String> mentions, String bubbleSkin) {
        return sendMessage(fromUserId, fromNickname, toUserId, content, chatRoomId,
                msgType, fileName, filePath, fwdNickname, fwdUserId, mentions, bubbleSkin, null);
    }

    public Message sendMessage(String fromUserId, String fromNickname,
                               String toUserId, String content, String chatRoomId,
                               String msgType, String fileName, String filePath,
                               String fwdNickname, String fwdUserId,
                               List<String> mentions, String bubbleSkin,
                               String clientMsgId) {
        String roomId = normalizeRoomId(fromUserId, chatRoomId);
        RoomData rd = loadRoom(roomId);
        String id = String.valueOf(idCounter.getAndIncrement());
        Message msg = new Message(id, fromUserId, fromNickname, toUserId, content, roomId);
        msg.setMsgType(msgType != null ? msgType : "text");
        msg.setFileName(fileName);
        msg.setFilePath(filePath);
        msg.setForwardedFromNickname(fwdNickname);
        msg.setForwardedFromUserId(fwdUserId);
        msg.setMentions(mentions);
        msg.setBubbleSkin(bubbleSkin);
        User sender = UserService.getInstance().getByUserId(fromUserId);
        msg.setMessageFont(sender != null ? sender.getMessageFont() : "default");
        msg.setClientMsgId(clientMsgId);
        synchronized (rd.messages) {
            rd.messages.add(msg);
            List<Message> snapshot = new ArrayList<>(rd.messages);
            if (!saveToDisk(roomId, snapshot)) {
                rd.messages.remove(msg);
                throw new IllegalStateException("消息保存失败，请稍后重试");
            }
        }
        // Publish the global lookup only after the room snapshot is durable.
        messageIdToRoomIdMap.put(id, roomId);
        triggerCleanup();
        return msg;
    }

    public Message sendCardMessage(String fromUserId, String fromNickname, String chatRoomId,
                                   String cardType, String cardPayload, String title) {
        Message msg = sendMessage(fromUserId, fromNickname, null, title, chatRoomId,
                "card", null, null, null, null, null, null);
        RoomData rd = loadRoom(msg.getChatRoomId());
        synchronized (rd.messages) {
            msg.setCardType(cardType);
            msg.setCardPayload(cardPayload);
            if (!saveToDisk(msg.getChatRoomId(), new ArrayList<>(rd.messages))) {
                msg.setCardType(null);
                msg.setCardPayload(null);
                throw new IllegalStateException("卡片消息保存失败，请稍后重试");
            }
        }
        return msg;
    }

    public String recallMessage(String messageId, String requestUserId) {
        return recallMessage(messageId, requestUserId, false);
    }

    public String recallMessage(String messageId, String requestUserId, boolean ignoreTimeout) {
        // ensure the owning room is in cache
        Message found = getById(messageId);
        if (found == null) return "not_found";
        String roomId = found.getChatRoomId();
        RoomData rd = loadRoom(roomId);
        if (rd == null) return "not_found";
        String fpToClean = null;
        boolean foundTarget = false;
        synchronized (rd.messages) {
            for (Message m : rd.messages) {
                if (m.getId().equals(messageId)) {
                    if (!m.getFromUserId().equals(requestUserId)) return "not_owner";
                    if (!ignoreTimeout && System.currentTimeMillis() - m.getTimestamp() > 10 * 60 * 1000L) return "timeout";
                    foundTarget = true;
                    fpToClean = m.getFilePath();
                    boolean oldRecalled = m.isRecalled();
                    String oldContent = m.getContent();
                    String oldFilePath = m.getFilePath();
                    m.setRecalled(true);
                    m.setContent("【该消息已撤回】");
                    m.setFilePath(null);
                    if (!saveToDisk(roomId, new ArrayList<>(rd.messages))) {
                        m.setRecalled(oldRecalled);
                        m.setContent(oldContent);
                        m.setFilePath(oldFilePath);
                        return "save_failed";
                    }
                    break;
                }
            }
        }
        if (!foundTarget) return "not_found";
        cleanupOrphanedFile(fpToClean);
        return "ok";
    }

    public Message adminDeleteMessage(String messageId, String operatorId) {
        Message found = getById(messageId);
        if (found == null) return null;
        String roomId = found.getChatRoomId();
        RoomData rd = loadRoom(roomId);
        if (rd == null) return null;
        String fpToClean = null;
        Message resultMsg = null;
        synchronized (rd.messages) {
            for (Message m : rd.messages) {
                if (m.getId().equals(messageId)) {
                    fpToClean = m.getFilePath();
                    boolean oldAdminDeleted = m.isAdminDeleted();
                    String oldDeletedBy = m.getDeletedByUserId();
                    long oldDeletedAt = m.getDeletedAt();
                    boolean oldRecalled = m.isRecalled();
                    String oldContent = m.getContent();
                    String oldFilePath = m.getFilePath();
                    m.setAdminDeleted(true);
                    m.setDeletedByUserId(operatorId);
                    m.setDeletedAt(System.currentTimeMillis());
                    m.setRecalled(true);
                    m.setContent("【该消息已被管理员删除】");
                    m.setFilePath(null);
                    if (!saveToDisk(roomId, new ArrayList<>(rd.messages))) {
                        m.setAdminDeleted(oldAdminDeleted);
                        m.setDeletedByUserId(oldDeletedBy);
                        m.setDeletedAt(oldDeletedAt);
                        m.setRecalled(oldRecalled);
                        m.setContent(oldContent);
                        m.setFilePath(oldFilePath);
                        return null;
                    }
                    resultMsg = m;
                    break;
                }
            }
        }
        if (resultMsg == null) return null;
        cleanupOrphanedFile(fpToClean);
        return resultMsg;
    }

    // ----- batch ops -----

    public int deleteMessagesOlderThan(String roomId, long olderThanMs) {
        return deleteMessagesOlderThanDetailed(roomId, olderThanMs).size();
    }

    public List<Message> deleteMessagesOlderThanDetailed(String roomId, long olderThanMs) {
        RoomData rd = loadRoom(roomId);
        List<Message> removed = new ArrayList<>();
        synchronized (rd.messages) {
            List<Message> before = new ArrayList<>(rd.messages);
            Iterator<Message> it = rd.messages.iterator();
            while (it.hasNext()) {
                Message m = it.next();
                if (m.getTimestamp() < olderThanMs) {
                    removed.add(m);
                    it.remove();
                }
            }
            if (!removed.isEmpty() && !saveToDisk(roomId, new ArrayList<>(rd.messages))) {
                rd.messages.clear();
                rd.messages.addAll(before);
                removed.clear();
            }
        }
        for (Message removedMessage : removed) {
            if (removedMessage.getId() != null) messageIdToRoomIdMap.remove(removedMessage.getId());
        }
        return removed;
    }

    // ----- search & stats -----

    public List<Message> searchMessages(String keyword, int limit) {
        if (keyword == null || keyword.trim().isEmpty()) return Collections.emptyList();
        String lower = keyword.toLowerCase();
        List<Message> results = new ArrayList<>();
        Set<String> searchedRoomIds = new HashSet<>();

        for (Map.Entry<String, RoomData> entry : cache.entrySet()) {
            String roomId = entry.getKey();
            searchedRoomIds.add(roomId);
            RoomData rd = entry.getValue();
            List<Message> snapshot;
            synchronized (rd.messages) {
                if (rd.messages.isEmpty()) continue;
                snapshot = new ArrayList<>(rd.messages);
            }
            for (Message m : snapshot) {
                if (m.getContent() != null && m.getContent().toLowerCase().contains(lower)) {
                    results.add(m);
                    if (results.size() >= limit) return results;
                }
            }
        }

        try {
            Path chatsPath = Paths.get(CHATS_DIR);
            if (Files.exists(chatsPath) && Files.isDirectory(chatsPath)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(chatsPath)) {
                    for (Path roomDir : stream) {
                        if (Files.isDirectory(roomDir)) {
                            String roomId = roomDir.getFileName().toString();
                            if (searchedRoomIds.contains(roomId)) continue;

                            List<Message> messages = loadFromDisk(roomId);
                            for (Message m : messages) {
                                if (m.getContent() != null && m.getContent().toLowerCase().contains(lower)) {
                                    results.add(m);
                                    if (results.size() >= limit) return results;
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MessageService] searchMessages disk search err: " + e.getMessage());
        }

        return results;
    }

    public int getTotalMessageCount() {
        int total = 0;
        Set<String> cachedRoomIds = new HashSet<>(cache.keySet());
        for (String roomId : cachedRoomIds) {
            RoomData rd = cache.get(roomId);
            if (rd != null) {
                synchronized (rd.messages) {
                    total += rd.messages.size();
                }
            }
        }
        try {
            Path chatsPath = Paths.get(CHATS_DIR);
            if (Files.exists(chatsPath) && Files.isDirectory(chatsPath)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(chatsPath)) {
                    for (Path roomDir : stream) {
                        if (Files.isDirectory(roomDir)) {
                            String roomId = roomDir.getFileName().toString();
                            if (!cachedRoomIds.contains(roomId)) {
                                List<Message> diskMsgs = loadFromDisk(roomId);
                                total += diskMsgs.size();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MessageService] getTotalMessageCount err: " + e.getMessage());
        }
        return total;
    }

    public int getRoomCount() {
        Set<String> roomIds = new HashSet<>(cache.keySet());
        try {
            Path chatsPath = Paths.get(CHATS_DIR);
            if (Files.exists(chatsPath) && Files.isDirectory(chatsPath)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(chatsPath)) {
                    for (Path roomDir : stream) {
                        if (Files.isDirectory(roomDir)) {
                            roomIds.add(roomDir.getFileName().toString());
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[MessageService] getRoomCount err: " + e.getMessage());
        }
        return roomIds.size();
    }

    // ----- file cleanup -----

    private void cleanupOrphanedFile(String filePath) {
        // FileStore 按内容去重，同一个 blob 可能同时属于多位上传者并被动态、笔记、
        // 音乐、视频、小程序或 AI 会话引用。仅凭“消息中已无引用”无法证明它是孤儿；
        // 为避免跨模块数据丢失，这里只解除消息引用，不直接删除全局 blob。
        // 后续如需回收空间，应由能扫描全部领域引用与 ownerUserIds 的专用 GC 完成。
    }

    public boolean hasAccessToFile(String userId, String storedName) {
        if (userId == null || storedName == null || storedName.isEmpty()) return false;
        // Phase 1: scan in-memory cache
        for (RoomData rd : cache.values()) {
            synchronized (rd.messages) {
                for (Message m : rd.messages) {
                    if (referencesStoredFile(m, storedName)) {
                        String roomId = m.getChatRoomId();
                        if (userId.equals(m.getFromUserId()) || userId.equals(m.getToUserId())) {
                            return true;
                        }
                        if ("public".equals(roomId)) {
                            return true;
                        }
                        if (isPrivateRoomParticipant(roomId, userId)) {
                            return true;
                        }
                        if (roomId != null && roomId.startsWith("group_")) {
                            // 修复 IDOR：校验请求者必须是该群成员
                            String groupId = roomId.substring("group_".length());
                            com.chat.model.Group g = GroupService.getInstance().getGroup(groupId);
                            if (g != null && g.isMember(userId)) return true;
                        }
                    }
                }
            }
        }
        // Phase 2: 针对已从内存缓存淘汰的历史房间，从磁盘试探加载
        // 扫描 chatserver/chats/ 下所有子目录
        try {
            java.io.File chatsDir = new java.io.File(CHATS_DIR);
            java.io.File[] roomDirs = chatsDir.listFiles(java.io.File::isDirectory);
            if (roomDirs != null) {
                for (java.io.File roomDir : roomDirs) {
                    String roomId = roomDir.getName();
                    // 已在缓存中的房间已在 Phase 1 检查过
                    if (cache.containsKey(roomId)) continue;
                    java.io.File msgFile = new java.io.File(roomDir, "message");
                    if (!msgFile.exists()) continue;
                    List<Message> diskMsgs = loadFromDisk(roomId);
                    for (Message m : diskMsgs) {
                        if (referencesStoredFile(m, storedName)) {
                            String rId = m.getChatRoomId();
                            if (userId.equals(m.getFromUserId()) || userId.equals(m.getToUserId())) {
                                return true;
                            }
                            if ("public".equals(rId)) {
                                return true;
                            }
                            if (isPrivateRoomParticipant(rId, userId)) {
                                return true;
                            }
                            if (rId != null && rId.startsWith("group_")) {
                                String groupId = rId.substring("group_".length());
                                com.chat.model.Group g = GroupService.getInstance().getGroup(groupId);
                                if (g != null && g.isMember(userId)) return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean referencesStoredFile(Message message, String storedName) {
        if (message == null || message.getFilePath() == null) return false;
        String path = message.getFilePath();
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String referenced = slash >= 0 ? path.substring(slash + 1) : path;
        int query = referenced.indexOf('?');
        if (query >= 0) referenced = referenced.substring(0, query);
        return storedName.equals(referenced);
    }
}
