package com.chat.service;

import com.chat.model.MusicComment;
import com.chat.model.MusicPlaylist;
import com.chat.model.MusicTrack;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.time.LocalDate;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class MusicService {
    private static final MusicService INSTANCE = new MusicService();
    private static Path dataDir() { return Paths.get("chatserver", "music"); }
    private static Path tracksFile() { return dataDir().resolve("tracks.json"); }
    private static Path playlistsFile() { return dataDir().resolve("playlists.json"); }
    private static Path commentsFile() { return dataDir().resolve("comments.json"); }

    private final List<MusicTrack> tracks = new ArrayList<>();
    private final List<MusicPlaylist> playlists = new ArrayList<>();
    private final List<MusicComment> comments = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static MusicService getInstance() {
        return INSTANCE;
    }

    private MusicService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public List<MusicTrack> listTracks() {
        lock.readLock().lock();
        try {
            return tracks.stream()
                    .sorted(Comparator.comparingLong(MusicTrack::getCreatedAt).reversed())
                    .map(this::copyTrack)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public MusicTrack addTrack(MusicTrack track) {
        lock.writeLock().lock();
        try {
            track.setId("music_" + UUID.randomUUID().toString().replace("-", ""));
            track.setCreatedAt(System.currentTimeMillis());
            tracks.add(track);
            saveUnsafe();
            return copyTrack(track);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<MusicTrack> addTracks(List<MusicTrack> items) {
        lock.writeLock().lock();
        try {
            List<MusicTrack> added = new ArrayList<>();
            if (items != null) {
                for (MusicTrack track : items) {
                    if (track == null) continue;
                    track.setId("music_" + UUID.randomUUID().toString().replace("-", ""));
                    track.setCreatedAt(System.currentTimeMillis());
                    tracks.add(track);
                    added.add(copyTrack(track));
                }
            }
            if (!added.isEmpty()) saveUnsafe();
            return added;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public MusicTrack getTrack(String trackId) {
        lock.readLock().lock();
        try {
            return tracks.stream()
                    .filter(item -> Objects.equals(item.getId(), trackId))
                    .findFirst()
                    .map(this::copyTrack)
                    .orElse(null);
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<MusicPlaylist> listPlaylists(String userId) {
        lock.readLock().lock();
        try {
            return playlists.stream()
                    .filter(item -> Objects.equals(item.getUserId(), userId))
                    .sorted(Comparator.comparingLong(MusicPlaylist::getUpdatedAt).reversed())
                    .map(this::copyPlaylist)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public MusicPlaylist createPlaylist(String userId, String name, boolean favorite) {
        lock.writeLock().lock();
        try {
            MusicPlaylist playlist = new MusicPlaylist();
            long now = System.currentTimeMillis();
            playlist.setId("plist_" + UUID.randomUUID().toString().replace("-", ""));
            playlist.setUserId(userId);
            playlist.setName(name != null && !name.trim().isEmpty() ? name.trim() : (favorite ? "我的喜爱" : "未命名歌单"));
            playlist.setFavorite(favorite);
            playlist.setCreatedAt(now);
            playlist.setUpdatedAt(now);
            playlists.add(playlist);
            saveUnsafe();
            return copyPlaylist(playlist);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public MusicPlaylist toggleTrackInPlaylist(String userId, String playlistId, String trackId) {
        lock.writeLock().lock();
        try {
            MusicPlaylist playlist = playlists.stream()
                    .filter(item -> Objects.equals(item.getId(), playlistId) && Objects.equals(item.getUserId(), userId))
                    .findFirst().orElse(null);
            if (playlist == null) {
                throw new IllegalArgumentException("歌单不存在");
            }
            if (playlist.getTrackIds().contains(trackId)) {
                playlist.getTrackIds().remove(trackId);
            } else {
                playlist.getTrackIds().add(trackId);
            }
            playlist.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyPlaylist(playlist);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<MusicTrack> getDailyRecommendations(String userId) {
        lock.readLock().lock();
        try {
            List<MusicTrack> allTracks = tracks.stream()
                    .sorted(Comparator.comparing(MusicTrack::getId, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
                    .collect(Collectors.toList());
            String day = LocalDate.now(ZoneId.systemDefault()).toString();
            Collections.shuffle(allTracks, new Random(Objects.hash(userId, day, allTracks.size())));
            return allTracks.stream().limit(12).map(this::copyTrack).collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<MusicComment> listComments(String trackId) {
        lock.readLock().lock();
        try {
            return comments.stream()
                    .filter(item -> Objects.equals(item.getTrackId(), trackId))
                    .sorted(Comparator.comparingLong(MusicComment::getCreatedAt))
                    .map(this::copyComment)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public MusicComment addComment(String trackId, String userId, String nickname, String content) {
        lock.writeLock().lock();
        try {
            MusicTrack track = tracks.stream().filter(item -> Objects.equals(item.getId(), trackId)).findFirst().orElse(null);
            if (track == null) {
                throw new IllegalArgumentException("歌曲不存在");
            }
            MusicComment comment = new MusicComment();
            comment.setId("mcom_" + UUID.randomUUID().toString().replace("-", ""));
            comment.setTrackId(trackId);
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

    public MusicTrack updateTrack(String trackId, String title, String artist, String album, String lyrics, String cover) {
        lock.writeLock().lock();
        try {
            MusicTrack track = tracks.stream().filter(item -> Objects.equals(item.getId(), trackId)).findFirst().orElse(null);
            if (track == null) {
                throw new IllegalArgumentException("歌曲不存在");
            }
            if (title != null) track.setTitle(title);
            if (artist != null) track.setArtist(artist);
            if (album != null) track.setAlbum(album);
            if (lyrics != null) track.setLyrics(lyrics);
            if (cover != null) track.setCover(cover);
            saveUnsafe();
            return copyTrack(track);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void deleteTrack(String trackId) {
        lock.writeLock().lock();
        try {
            boolean removed = tracks.removeIf(item -> Objects.equals(item.getId(), trackId));
            if (!removed) {
                throw new IllegalArgumentException("歌曲不存在");
            }
            for (MusicPlaylist playlist : playlists) {
                playlist.getTrackIds().removeIf(id -> Objects.equals(id, trackId));
                playlist.setUpdatedAt(System.currentTimeMillis());
            }
            comments.removeIf(item -> Objects.equals(item.getTrackId(), trackId));
            saveUnsafe();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void bumpPlayCount(String trackId) {
        lock.writeLock().lock();
        try {
            MusicTrack track = tracks.stream().filter(item -> Objects.equals(item.getId(), trackId)).findFirst().orElse(null);
            if (track != null) {
                track.setPlayCount(track.getPlayCount() + 1);
                saveUnsafe();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void load() {
        lock.writeLock().lock();
        try {
            tracks.clear();
            playlists.clear();
            comments.clear();
            loadList(tracksFile(), new TypeToken<List<MusicTrack>>() {}.getType(), tracks);
            loadList(playlistsFile(), new TypeToken<List<MusicPlaylist>>() {}.getType(), playlists);
            loadList(commentsFile(), new TypeToken<List<MusicComment>>() {}.getType(), comments);
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
            System.err.println("[MusicService] 加载失败: " + file.getFileName() + " -> " + e.getMessage());
            try {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[MusicService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private final java.util.concurrent.ExecutorService saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "music-service-saver");
        t.setDaemon(true);
        return t;
    });

    private void saveUnsafe() {
        List<MusicTrack> snapTracks = tracks.stream().map(this::copyTrack).collect(Collectors.toList());
        List<MusicPlaylist> snapPlaylists = playlists.stream().map(this::copyPlaylist).collect(Collectors.toList());
        List<MusicComment> snapComments = comments.stream().map(this::copyComment).collect(Collectors.toList());
        saveExecutor.submit(() -> {
            try {
                Files.createDirectories(dataDir());
                com.chat.util.JsonUtil.saveJsonAtomic(tracksFile(), snapTracks);
                com.chat.util.JsonUtil.saveJsonAtomic(playlistsFile(), snapPlaylists);
                com.chat.util.JsonUtil.saveJsonAtomic(commentsFile(), snapComments);
            } catch (Exception e) {
                System.err.println("[MusicService] 异步保存失败: " + e.getMessage());
            }
        });
    }

    private MusicTrack copyTrack(MusicTrack source) {
        if (source == null) return null;
        MusicTrack target = new MusicTrack();
        target.setId(source.getId());
        target.setTitle(source.getTitle());
        target.setArtist(source.getArtist());
        target.setAlbum(source.getAlbum());
        target.setCover(source.getCover());
        target.setLyrics(source.getLyrics());
        target.setFilePath(source.getFilePath());
        target.setCloudEntryId(source.getCloudEntryId());
        target.setUploadedBy(source.getUploadedBy());
        target.setCreatedAt(source.getCreatedAt());
        target.setPlayCount(source.getPlayCount());
        return target;
    }

    private MusicPlaylist copyPlaylist(MusicPlaylist source) {
        if (source == null) return null;
        MusicPlaylist target = new MusicPlaylist();
        target.setId(source.getId());
        target.setUserId(source.getUserId());
        target.setName(source.getName());
        target.setFavorite(source.isFavorite());
        target.setCreatedAt(source.getCreatedAt());
        target.setUpdatedAt(source.getUpdatedAt());
        if (source.getTrackIds() != null) {
            target.setTrackIds(new ArrayList<>(source.getTrackIds()));
        }
        return target;
    }

    private MusicComment copyComment(MusicComment source) {
        if (source == null) return null;
        MusicComment target = new MusicComment();
        target.setId(source.getId());
        target.setTrackId(source.getTrackId());
        target.setUserId(source.getUserId());
        target.setNickname(source.getNickname());
        target.setContent(source.getContent());
        target.setCreatedAt(source.getCreatedAt());
        return target;
    }
}
