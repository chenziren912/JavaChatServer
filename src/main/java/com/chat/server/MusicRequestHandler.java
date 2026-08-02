package com.chat.server;

import com.chat.model.MusicTrack;
import com.chat.model.StoredFileMetadata;
import com.chat.model.User;
import com.chat.service.FileStore;
import com.chat.service.MusicService;
import com.sun.net.httpserver.HttpExchange;
import org.jaudiotagger.audio.AudioFile;
import org.jaudiotagger.audio.AudioFileIO;
import org.jaudiotagger.tag.FieldKey;
import org.jaudiotagger.tag.Tag;
import org.jaudiotagger.tag.images.Artwork;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class MusicRequestHandler extends RequestHandlerSupport {
    private static final int MAX_ZIP_ENTRIES = 500;
    private static final long MAX_ZIP_BYTES = 512L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 200L * 1024 * 1024;
    private static final long MAX_TOTAL_AUDIO_BYTES = 1024L * 1024 * 1024;

    void handleGetTracks(HttpExchange ex, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        sendJson(ex, 200, MusicService.getInstance().listTracks());
    }

    void handleGetPlaylists(HttpExchange ex, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        sendJson(ex, 200, MusicService.getInstance().listPlaylists(me.getUserId()));
    }

    void handleGetRecommend(HttpExchange ex, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        sendJson(ex, 200, MusicService.getInstance().getDailyRecommendations(me.getUserId()));
    }

    void handleGetComments(HttpExchange ex, String query, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        String trackId = parseQuery(query).get("trackId");
        if (trackId == null || trackId.isBlank()) {
            sendJson(ex, 400, map("error", "缺少歌曲ID"));
            return;
        }
        if (MusicService.getInstance().getTrack(trackId) == null) {
            sendJson(ex, 404, map("error", "歌曲不存在"));
            return;
        }
        sendJson(ex, 200, MusicService.getInstance().listComments(trackId));
    }

    void handleExtractMeta(HttpExchange ex, String query, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        String filePath = parseQuery(query).get("filePath");
        StoredFileMetadata source = metadataFromPath(filePath, me);
        if (source == null) {
            sendJson(ex, 404, map("error", "音频文件不存在或无权访问"));
            return;
        }
        try {
            java.nio.file.Path localPath = FileStore.getInstance().resolveStoredPath(source.getStoredName());
            if (!Files.isRegularFile(localPath)) {
                sendJson(ex, 404, map("error", "文件未找到"));
                return;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("title", "");
            meta.put("artist", "");
            meta.put("album", "");
            meta.put("lyrics", "");
            meta.put("cover", "");
            meta.put("hasTitle", false);
            meta.put("hasArtist", false);
            meta.put("hasAlbum", false);
            meta.put("hasCover", false);
            try {
                AudioFile audioFile = AudioFileIO.read(localPath.toFile());
                Tag tag = audioFile.getTag();
                if (tag != null) {
                    putTag(meta, "title", "hasTitle", tag.getFirst(FieldKey.TITLE));
                    putTag(meta, "artist", "hasArtist", tag.getFirst(FieldKey.ARTIST));
                    putTag(meta, "album", "hasAlbum", tag.getFirst(FieldKey.ALBUM));
                    String lyrics = tag.getFirst(FieldKey.LYRICS);
                    if (lyrics != null && !lyrics.isBlank()) meta.put("lyrics", lyrics.trim());
                    Artwork artwork = tag.getFirstArtwork();
                    if (artwork != null && artwork.getBinaryData() != null
                            && artwork.getBinaryData().length <= 10L * 1024 * 1024) {
                        String mime = artwork.getMimeType() != null ? artwork.getMimeType() : "image/jpeg";
                        String ext = mime.contains("png") ? "png" : mime.contains("webp") ? "webp" : "jpg";
                        try (ByteArrayInputStream input = new ByteArrayInputStream(artwork.getBinaryData())) {
                            StoredFileMetadata cover = FileStore.getInstance().store(input,
                                    "cover_" + source.getStoredName() + "." + ext, mime, me.getUserId());
                            meta.put("cover", cover.getAccessPath());
                            meta.put("hasCover", true);
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[MusicMeta] 元数据提取失败: " + e.getMessage());
            }
            sendJson(ex, 200, meta);
        } catch (Exception e) {
            sendJson(ex, 500, map("error", "元数据提取失败: " + e.getMessage()));
        }
    }

    void handleUpload(HttpExchange ex, String body, User me) throws IOException {
        if (!requireSuperAdmin(ex, me)) return;
        Map<String, String> values = parseJson(body);
        StoredFileMetadata audio = metadataFromPath(values.get("filePath"), me);
        if (audio == null) {
            sendJson(ex, 404, map("error", "音频文件不存在或无权访问"));
            return;
        }
        String coverPath = values.get("cover");
        if (coverPath != null && !coverPath.isBlank() && metadataFromPath(coverPath, me) == null) {
            sendJson(ex, 404, map("error", "封面文件不存在或无权访问"));
            return;
        }
        MusicTrack track = new MusicTrack();
        track.setTitle(values.get("title"));
        track.setArtist(values.get("artist"));
        track.setAlbum(values.get("album"));
        track.setLyrics(values.get("lyrics"));
        track.setCover(coverPath);
        track.setFilePath(audio.getAccessPath());
        track.setCloudEntryId(values.get("cloudEntryId"));
        track.setUploadedBy(me.getUserId());
        sendJson(ex, 200, MusicService.getInstance().addTrack(track));
    }

    void handleCreatePlaylist(HttpExchange ex, String body, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        Map<String, String> values = parseJson(body);
        sendJson(ex, 200, MusicService.getInstance().createPlaylist(me.getUserId(), values.get("name"),
                Boolean.parseBoolean(values.getOrDefault("favorite", "false"))));
    }

    void handleTogglePlaylist(HttpExchange ex, String body, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        Map<String, String> values = parseJson(body);
        try {
            sendJson(ex, 200, MusicService.getInstance().toggleTrackInPlaylist(me.getUserId(),
                    values.get("playlistId"), values.get("trackId")));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handlePlay(HttpExchange ex, String body, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        String trackId = parseJson(body).get("trackId");
        if (MusicService.getInstance().getTrack(trackId) == null) {
            sendJson(ex, 404, map("error", "歌曲不存在"));
            return;
        }
        MusicService.getInstance().bumpPlayCount(trackId);
        sendJson(ex, 200, map("success", "true"));
    }

    void handleComment(HttpExchange ex, String body, User me) throws IOException {
        if (!requireUser(ex, me)) return;
        Map<String, String> values = parseJson(body);
        String content = values.get("content");
        if (content == null || content.trim().isEmpty() || content.trim().length() > 1000) {
            sendJson(ex, 400, map("error", "评论内容须为1到1000个字符"));
            return;
        }
        try {
            sendJson(ex, 200, MusicService.getInstance().addComment(values.get("trackId"), me.getUserId(),
                    me.getNickname(), content.trim()));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    void handleUpdate(HttpExchange ex, String body, User me) throws IOException {
        if (!requirePrimaryAdmin(ex, me)) return;
        Map<String, String> values = parseJson(body);
        String coverPath = values.get("cover");
        if (coverPath != null && !coverPath.isBlank() && metadataFromPath(coverPath, me) == null) {
            sendJson(ex, 404, map("error", "封面文件不存在或无权访问"));
            return;
        }
        try {
            sendJson(ex, 200, MusicService.getInstance().updateTrack(values.get("trackId"), values.get("title"),
                    values.get("artist"), values.get("album"), values.get("lyrics"), coverPath));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    void handleDelete(HttpExchange ex, String body, User me) throws IOException {
        if (!requirePrimaryAdmin(ex, me)) return;
        try {
            MusicService.getInstance().deleteTrack(parseJson(body).get("trackId"));
            sendJson(ex, 200, map("success", "true"));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 404, map("error", e.getMessage()));
        }
    }

    void handleImportZip(HttpExchange ex, String body, User me) throws IOException {
        if (!requireSuperAdmin(ex, me)) return;
        StoredFileMetadata archive = metadataFromPath(parseJson(body).get("filePath"), me);
        if (archive == null) {
            sendJson(ex, 404, map("error", "ZIP 文件不存在或无权访问"));
            return;
        }
        if (archive.getSize() > MAX_ZIP_BYTES) {
            sendJson(ex, 413, map("error", "ZIP 文件不能超过512MB"));
            return;
        }
        List<MusicTrack> pending = new ArrayList<>();
        int skipped = 0;
        long totalBytes = 0;
        try (InputStream raw = FileStore.getInstance().openStream(archive.getStoredName());
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            int count = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++count > MAX_ZIP_ENTRIES) throw new IllegalArgumentException("ZIP 条目不能超过500个");
                if (entry.isDirectory()) continue;
                String fileName = zipBaseName(entry.getName());
                if (!isAudioFile(fileName)) {
                    drainLimited(zip, 10L * 1024 * 1024);
                    skipped++;
                    continue;
                }
                long remaining = MAX_TOTAL_AUDIO_BYTES - totalBytes;
                if (remaining <= 0) throw new IllegalArgumentException("解压后的音频总量不能超过1GB");
                long entryLimit = Math.min(MAX_AUDIO_BYTES, remaining);
                InputStream nonClosing = new FilterInputStream(limitInputStream(zip, entryLimit,
                        "单个音频不能超过200MB，且总量不能超过1GB")) {
                    @Override public void close() { }
                };
                StoredFileMetadata stored = FileStore.getInstance().store(nonClosing, fileName,
                        FileStore.guessMime(fileName), me.getUserId());
                totalBytes += stored.getSize();
                MusicTrack track = new MusicTrack();
                track.setTitle(fileName.replaceFirst("\\.[^.]+$", ""));
                track.setArtist("未知歌手");
                track.setFilePath(stored.getAccessPath());
                track.setUploadedBy(me.getUserId());
                pending.add(track);
                zip.closeEntry();
            }
            List<MusicTrack> imported = MusicService.getInstance().addTracks(pending);
            sendJson(ex, 200, obj("importedCount", imported.size(), "skippedCount", skipped));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        } catch (Exception e) {
            sendJson(ex, 400, map("error", "ZIP 导入失败: " + e.getMessage()));
        }
    }

    private void putTag(Map<String, Object> target, String valueKey, String presentKey, String value) {
        if (value != null && !value.isBlank()) {
            target.put(valueKey, value.trim());
            target.put(presentKey, true);
        }
    }

    private String zipBaseName(String name) {
        String normalized = String.valueOf(name).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return FileStore.normalizeUploadedFileName(slash >= 0 ? normalized.substring(slash + 1) : normalized);
    }

    private boolean isAudioFile(String name) {
        String lower = String.valueOf(name).toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".flac") || lower.endsWith(".wav")
                || lower.endsWith(".m4a") || lower.endsWith(".aac") || lower.endsWith(".ogg")
                || lower.endsWith(".opus") || lower.endsWith(".wma");
    }

    private void drainLimited(InputStream input, long limit) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IllegalArgumentException("ZIP 中非音频条目不能超过10MB");
        }
    }

    private boolean requireUser(HttpExchange ex, User user) throws IOException {
        if (user != null) return true;
        sendJson(ex, 401, map("error", "未登录"));
        return false;
    }

    private boolean requireSuperAdmin(HttpExchange ex, User user) throws IOException {
        if (user != null && UserRoles.isSuperAdmin(user.getUserId())) return true;
        sendJson(ex, 403, map("error", "无权限"));
        return false;
    }

    private boolean requirePrimaryAdmin(HttpExchange ex, User user) throws IOException {
        if (user != null && UserRoles.isPrimarySuperAdmin(user.getUserId())) return true;
        sendJson(ex, 403, map("error", "仅服主可执行此操作"));
        return false;
    }
}
