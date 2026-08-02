package com.chat.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class MediaWorkspaceUiContractTest {
    private static String source(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(MediaWorkspaceUiContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path projectRoot = testClasses.getParent().getParent();
            return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
    }

    private static String between(String text, String startNeedle, String endNeedle) {
        int start = text.indexOf(startNeedle);
        int end = text.indexOf(endNeedle, start + startNeedle.length());
        assertTrue(start >= 0, "missing start marker: " + startNeedle);
        assertTrue(end > start, "missing end marker: " + endNeedle);
        return text.substring(start, end);
    }

    @Test
    void fiveWorkspacesPublishSemanticLayoutsAndKeepHandlers() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String all = client + "\n" + renderer;

        for (String className : new String[] {
                "cloud-layout-v2", "cloud-sidebar-v2", "cloud-breadcrumb-bar", "cloud-batch-bar",
                "ai-layout", "ai-side", "ai-main", "ai-input-area", "ai-settings-panel",
                "music-layout", "music-sidebar", "music-content", "music-bar",
                "yt-watch-layout", "yt-card-grid", "yt-video-wrap",
                "games-toolbar", "builtin-miniapp-detail", "builtin-source-badge"}) {
            assertTrue(all.contains(className), "missing semantic UI class: " + className);
        }

        for (String handlerName : new String[] {
                "switchCloudSection", "cloudToggleView", "cloudBatchDownload", "cloudRefresh",
                "aiCreateConversation", "selectAiModel", "sendAiPrompt", "toggleAiSettings",
                "playTrackById", "openMusicComments", "toggleMusicPlay",
                "filterVideosByCategory", "playVideoById", "submitVideoComment",
                "setGameCategoryFilter", "setGamesViewMode", "openBuiltinMiniApp"}) {
            assertTrue(all.contains("window." + handlerName) || all.contains("function " + handlerName),
                    "missing existing handler: " + handlerName);
        }
    }

    @Test
    void staticInlinePresentationIsRemovedFromRefactoredRenderers() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");
        String cloud = between(client, "function renderCloudTaskCards", "window.openFeedback");
        String music = between(client, "function renderMusicPlaylists", "window.openVideos");
        String video = between(client, "window.openVideos", "window.openAi");
        String ai = between(client, "window.openAi", "const oldOpenSelectedGameVersion");

        for (String region : new String[] {cloud, music, video, ai}) {
            assertFalse(region.contains("style='margin:"));
            assertFalse(region.contains("style='padding:"));
            assertFalse(region.contains("style='display:"));
            assertFalse(region.contains("style='color:"));
            assertFalse(region.contains("style='background:#"));
            assertFalse(region.contains("style='background:linear-gradient"));
            assertFalse(region.contains("style='flex:"));
            assertFalse(region.contains("style='opacity:"));
        }

        assertTrue(cloud.contains("style='width:${pct}%"), "task progress remains dynamic");
        assertTrue(music.contains("style='width:0%'"), "player progress remains dynamic");
        assertTrue(video.contains("style='background:${c}'"), "user-selected danmaku color remains dynamic");
    }

    @Test
    void authoritativeThemeCoversDarkAndResponsiveMediaSurfaces() throws IOException {
        String theme = source("src/main/resources/assets/wechat-theme.css");
        assertTrue(theme.contains("/* Media workspaces: cloud, AI, music, video, and mini-program catalog. */"));
        assertTrue(theme.contains(".cloud-layout-v2 {"));
        assertTrue(theme.contains(".ai-layout {"));
        assertTrue(theme.contains(".music-layout {"));
        assertTrue(theme.contains(".yt-thumb {"));
        assertTrue(theme.contains("aspect-ratio: 16 / 9"));
        assertTrue(theme.contains("@media (max-width: 840px)"));
        assertTrue(theme.contains("@media (min-width: 841px) and (max-width: 1100px)"));
        assertTrue(theme.contains("background: var(--panel) !important"));
        assertFalse(theme.contains(".qr-output-image { background: #FFFFFF"));
    }

    @Test
    void builtinMiniAppCardsNeverExposeManagementActions() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");
        String builtin = between(client, "function buildBuiltinGameListItemHtml", "function buildUploadedGameListItemHtml");
        assertTrue(builtin.contains("data-source='builtin'"));
        assertTrue(builtin.contains("系统内置"));
        assertFalse(builtin.contains("openGameMetaEditor"));
        assertFalse(builtin.contains("triggerGameUpdate"));
        assertFalse(builtin.contains("submitGamePublish"));
        assertFalse(builtin.contains("approve"));
    }
}
