package com.chat.server;

import com.chat.model.Announcement;
import com.chat.model.GameEntry;
import com.chat.model.MusicTrack;
import com.chat.model.User;
import com.chat.model.VideoEntry;
import com.chat.service.AnnouncementService;
import com.chat.service.CloudService;
import com.chat.service.FileStore;
import com.chat.service.GameService;
import com.chat.service.MessageService;
import com.chat.service.MomentService;
import com.chat.service.MusicService;
import com.chat.service.NoteService;
import com.chat.service.UserService;
import com.chat.service.VideoService;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class StoredFileAccess {
    private static final Pattern ANNOUNCEMENT_FILE = Pattern.compile("(?:/files/|files/)([^\\s\"'>)]+)");

    private StoredFileAccess() {
    }

    static String extractStoredName(String path) {
        if (path == null) return null;
        int lastSlash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    static boolean canAccess(String storedName, User user) {
        if (storedName == null || storedName.isBlank()) return false;
        String userId = user != null ? user.getUserId() : null;
        if (userId != null && (UserRoles.isSuperAdmin(userId)
                || FileStore.getInstance().isOwnedBy(storedName, userId))) {
            return true;
        }
        for (User item : UserService.getInstance().getAllUsers()) {
            if (Objects.equals(extractStoredName(item.getAvatarPath()), storedName)) return true;
        }
        for (MusicTrack track : MusicService.getInstance().listTracks()) {
            if (Objects.equals(extractStoredName(track.getFilePath()), storedName)
                    || Objects.equals(extractStoredName(track.getCover()), storedName)) return true;
        }
        for (VideoEntry video : VideoService.getInstance().listEntries()) {
            if (Objects.equals(extractStoredName(video.getFilePath()), storedName)
                    || Objects.equals(extractStoredName(video.getCoverPath()), storedName)) return true;
        }
        for (GameEntry game : GameService.getInstance().listGames()) {
            // 未审核或已拒绝的小程序只能由上传者/超管通过上面的所有权规则读取。
            // 不能因为它已经写入游戏索引，就把其 HTML 二进制暴露为公共文件。
            String status = game.getStatus();
            if (status != null && !"approved".equals(status)) continue;
            if (Objects.equals(extractStoredName(game.getCoverPath()), storedName)
                    || Objects.equals(extractStoredName(game.getPreviewVideoPath()), storedName)) return true;
            if (game.getVersions() != null && game.getVersions().stream()
                    .filter(Objects::nonNull)
                    .anyMatch(version -> Objects.equals(extractStoredName(version.getFilePath()), storedName))) {
                return true;
            }
        }
        for (Announcement announcement : AnnouncementService.getInstance().listAll()) {
            Matcher matcher = ANNOUNCEMENT_FILE.matcher(String.valueOf(announcement.getContent()));
            while (matcher.find()) {
                if (Objects.equals(extractStoredName(matcher.group(1)), storedName)) return true;
            }
        }
        if (MomentService.getInstance().hasAccessToAttachment(storedName, userId)
                || CloudService.getInstance().isPublicFileShare(storedName)) {
            return true;
        }
        if (userId == null) return false;
        return MessageService.getInstance().hasAccessToFile(userId, storedName)
                || CloudService.getInstance().hasAccessToFile(userId, storedName)
                || NoteService.getInstance().hasAccessToFile(userId, storedName);
    }
}
