package com.chat.service;

import com.chat.model.Moment;
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

public class MomentService {
    private static final MomentService INSTANCE = new MomentService();
    public static MomentService getInstance() { return INSTANCE; }

    public static final int PAGE_SIZE = 10;
    private static final String DATA_DIR  = "chatserver/moments";
    private static final String DATA_FILE = "chatserver/moments/moments.json";

    private final List<Moment> moments    = new ArrayList<>();
    private final AtomicLong idCounter    = new AtomicLong(1);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private MomentService() {
        try { Files.createDirectories(Paths.get(DATA_DIR)); } catch (Exception ignored) {}
        load();
    }

    private void load() {
        File f = new File(DATA_FILE);
        if (!f.exists()) return;
        lock.writeLock().lock();
        try {
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            Type type = new TypeToken<List<Moment>>(){}.getType();
            List<Moment> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                for (Moment m : list) {
                    if (m.getVisibility() == null) m.setVisibility("public");
                    if (m.getAllowedViewers() == null) m.setAllowedViewers(new ArrayList<>());
                    if (m.getAttachments() == null) m.setAttachments(new ArrayList<>());
                    if (m.getLikes() == null) m.setLikes(new ArrayList<>());
                    if (m.getComments() == null) m.setComments(new ArrayList<>());
                }
                moments.clear();
                moments.addAll(list);
                list.stream()
                    .mapToLong(m -> { try { return Long.parseLong(m.getId().replace("m","")); } catch(Exception e){ return 0L; } })
                    .max().ifPresent(max -> idCounter.set(max + 1));
            }
            System.out.println("[MomentService] 已加载 " + moments.size() + " 条动态");
        } catch (Exception e) {
            System.err.println("[MomentService] 加载失败: " + e.getMessage());
            try {
                Path backup = Paths.get(DATA_FILE + ".bak");
                Files.copy(Paths.get(DATA_FILE), backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[MomentService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void save() {
        List<Moment> copy = new ArrayList<>(moments);
        try {
            com.chat.util.JsonUtil.writeBytesAtomic(Paths.get(DATA_FILE), JsonUtil.toJson(copy).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { System.err.println("[MomentService] 保存失败: " + e.getMessage()); }
    }

    public Moment post(String fromUserId, String fromNickname, String content,
                       String visibility, List<String> allowedViewers) {
        return post(fromUserId, fromNickname, content, visibility, allowedViewers, null);
    }

    public Moment post(String fromUserId, String fromNickname, String content,
                       String visibility, List<String> allowedViewers, List<Moment.Attachment> attachments) {
        String id = "m" + idCounter.getAndIncrement();
        Moment m = new Moment(id, fromUserId, fromNickname, content);
        m.setVisibility(visibility != null ? visibility : "friends");
        m.setAllowedViewers(allowedViewers != null ? allowedViewers : new ArrayList<>());
        m.setAttachments(attachments != null ? copyAttachments(attachments) : new ArrayList<>());
        lock.writeLock().lock();
        try {
            moments.add(0, m);
            save();
        } finally {
            lock.writeLock().unlock();
        }
        return m;
    }

    private List<Moment.Attachment> copyAttachments(List<Moment.Attachment> attachments) {
        List<Moment.Attachment> copied = new ArrayList<>();
        for (Moment.Attachment attachment : attachments) {
            if (attachment == null) continue;
            Moment.Attachment row = new Moment.Attachment();
            row.setCloudEntryId(attachment.getCloudEntryId());
            row.setFileName(attachment.getFileName());
            row.setFilePath(attachment.getFilePath());
            row.setType(attachment.getType());
            copied.add(row);
        }
        return copied;
    }

    /**
     * 五种可见性：
     * public      - 所有人可见
     * friends     - 仅好友可见
     * specific    - 仅 allowedViewers 中的好友可见
     * no-specific - 除 allowedViewers 中的人外，好友都可见
     * private     - 完全私密，仅自己可见
     */
    public List<Moment> getPaged(int offset, String viewerUserId) {
        User viewer = UserService.getInstance().getByUserId(viewerUserId);
        Set<String> myFriends = viewer != null ? new HashSet<>(viewer.snapshotFriends()) : Collections.emptySet();

        List<Moment> visible;
        lock.readLock().lock();
        try {
            visible = moments.stream()
                .filter(m -> canView(m, viewerUserId, myFriends))
                .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
        if (offset >= visible.size()) return Collections.emptyList();
        return new ArrayList<>(visible.subList(offset, Math.min(offset + PAGE_SIZE, visible.size())));
    }

    public List<Moment> getPagedByUser(String authorUserId, int offset, String viewerUserId) {
        if (authorUserId == null || authorUserId.trim().isEmpty()) {
            return Collections.emptyList();
        }
        User viewer = UserService.getInstance().getByUserId(viewerUserId);
        Set<String> myFriends = viewer != null ? new HashSet<>(viewer.snapshotFriends()) : Collections.emptySet();

        List<Moment> visible;
        lock.readLock().lock();
        try {
            visible = moments.stream()
                    .filter(m -> authorUserId.equals(m.getFromUserId()))
                    .filter(m -> canView(m, viewerUserId, myFriends))
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
        if (offset >= visible.size()) return Collections.emptyList();
        return new ArrayList<>(visible.subList(offset, Math.min(offset + PAGE_SIZE, visible.size())));
    }

    public Moment getVisibleById(String momentId, String viewerUserId) {
        if (momentId == null || momentId.trim().isEmpty() || viewerUserId == null) return null;
        User viewer = UserService.getInstance().getByUserId(viewerUserId);
        Set<String> myFriends = viewer != null ? new HashSet<>(viewer.snapshotFriends()) : Collections.emptySet();
        lock.readLock().lock();
        try {
            for (Moment m : moments) {
                if (momentId.equals(m.getId()) && canView(m, viewerUserId, myFriends)) {
                    return m;
                }
            }
        } finally {
            lock.readLock().unlock();
        }
        return null;
    }

    private boolean canView(Moment m, String viewerUserId, Set<String> viewerFriends) {
        if (m == null) return false;
        if (m.getFromUserId() != null && m.getFromUserId().equals(viewerUserId)) return true; // 自己总能看到
        String vis = m.getVisibility();
        if (vis == null || "public".equals(vis)) return true;
        if ("private".equals(vis)) return false; // 完全私密
        boolean isFriend = viewerFriends != null && m.getFromUserId() != null && viewerFriends.contains(m.getFromUserId());
        if ("friends".equals(vis)) return isFriend;
        List<String> list = m.getAllowedViewers() != null ? m.getAllowedViewers() : Collections.emptyList();
        if ("specific".equals(vis)) return list.contains(viewerUserId);
        if ("no-specific".equals(vis)) return isFriend && !list.contains(viewerUserId);
        return false;
    }

    public boolean toggleLike(String momentId, String userId) {
        lock.writeLock().lock();
        try {
            for (Moment m : moments) {
                if (m.getId().equals(momentId)) {
                    if (m.getLikes().contains(userId)) { m.getLikes().remove(userId); save(); return false; }
                    else { m.getLikes().add(userId); save(); return true; }
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean addComment(String momentId, String fromUserId, String fromNickname, String content) {
        lock.writeLock().lock();
        try {
            for (Moment m : moments) {
                if (m.getId().equals(momentId)) {
                    m.getComments().add(new Moment.Comment(fromUserId, fromNickname, content));
                    save(); return true;
                }
            }
            return false;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public String deleteMoment(String momentId, String actorUserId, boolean isSuperAdmin) {
        if (momentId == null || actorUserId == null) return "not_found";
        lock.writeLock().lock();
        try {
            Iterator<Moment> iterator = moments.iterator();
            while (iterator.hasNext()) {
                Moment moment = iterator.next();
                if (!momentId.equals(moment.getId())) continue;
                boolean owner = actorUserId.equals(moment.getFromUserId());
                if (!owner && !isSuperAdmin) return "forbidden";
                if (!isSuperAdmin && System.currentTimeMillis() - moment.getTimestamp() > 5 * 60 * 1000L) {
                    return "timeout";
                }
                iterator.remove();
                save();
                return "ok";
            }
            return "not_found";
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean hasAccessToAttachment(String storedName, String userId) {
        if (storedName == null || storedName.isEmpty()) return false;
        User viewer = userId != null ? UserService.getInstance().getByUserId(userId) : null;
        Set<String> myFriends = viewer != null ? new HashSet<>(viewer.snapshotFriends()) : Collections.emptySet();
        lock.readLock().lock();
        try {
            for (Moment m : moments) {
                if (m.getAttachments() != null) {
                    for (Moment.Attachment att : m.getAttachments()) {
                        if (att != null) {
                            if (attachmentReferencesStoredName(att, storedName)) {
                                if (canView(m, userId, myFriends)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean hasAccessToFile(String viewerUserId, String storedName) {
        return hasAccessToAttachment(storedName, viewerUserId);
    }

    static boolean attachmentReferencesStoredName(Moment.Attachment attachment, String storedName) {
        if (attachment == null || storedName == null || storedName.isEmpty()) return false;
        String path = attachment.getFilePath();
        if (path != null && !path.isEmpty()) {
            int query = path.indexOf('?');
            int fragment = path.indexOf('#');
            int cut = query < 0 ? fragment : (fragment < 0 ? query : Math.min(query, fragment));
            String clean = (cut >= 0 ? path.substring(0, cut) : path).replace('\\', '/');
            int slash = clean.lastIndexOf('/');
            String fileName = slash >= 0 ? clean.substring(slash + 1) : clean;
            if (storedName.equals(fileName)) return true;
        }
        return storedName.equals(attachment.getFileName());
    }
}
