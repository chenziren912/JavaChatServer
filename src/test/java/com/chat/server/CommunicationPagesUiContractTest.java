package com.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class CommunicationPagesUiContractTest {
    private static String source(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(CommunicationPagesUiContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path projectRoot = testClasses.getParent().getParent();
            return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
    }

    private static String method(String source, String name, String nextName) {
        int start = source.indexOf("private String " + name + "()");
        int end = source.indexOf("private String " + nextName + "()", start + 1);
        assertTrue(start >= 0, "missing method " + name);
        assertTrue(end > start, "missing method boundary " + nextName);
        return source.substring(start, end);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = text.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    @Test
    void fiveViewsUseSemanticThemeAwareStructure() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String chat = method(renderer, "viewChat", "viewDiscover");
        String discover = method(renderer, "viewDiscover", "viewMoments");
        String moments = method(renderer, "viewMoments", "viewProfile");
        String profile = method(renderer, "viewProfile", "viewGames");
        String notes = method(renderer, "viewNotes", "viewServerAdmin");

        assertTrue(chat.contains("chat-attachment-grid"));
        assertTrue(chat.contains("camera-panel-header"));
        assertFalse(chat.contains("camera-glass-panel"));
        assertFalse(chat.contains("backdrop-filter"));
        assertFalse(chat.contains("linear-gradient"));

        assertTrue(discover.contains("discover-layout"));
        assertTrue(discover.contains("discover-tabs"));
        assertTrue(moments.contains("moment-composer"));
        assertTrue(moments.contains("moment-visibility-row"));
        assertTrue(profile.contains("profile-summary-card"));
        assertTrue(notes.contains("notes-workspace"));
        assertTrue(notes.contains("notes-split"));

        for (String view : new String[] {discover, moments, profile, notes}) {
            assertFalse(view.contains("style='"), "static inline styles remain in a refactored view");
            assertFalse(view.contains("#fff"), "forced white leaks into a refactored view");
            assertFalse(view.contains("linear-gradient"), "decorative gradient remains in a refactored view");
        }
        assertEquals(1, occurrences(chat, "style='"), "only upload progress width may stay inline");
        assertTrue(chat.contains("style='width:0%'"));
    }

    @Test
    void discoverProfileAndNoteControlsHaveHandlers() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String client = source("src/main/resources/assets/app-extra.js");
        String profile = method(renderer, "viewProfile", "viewGames");
        String allScripts = renderer + "\n" + client;

        for (String handlerName : new String[] {
                "swDisc", "handleReq", "openFriendModal", "saveProfile", "openPasswordChange",
                "confirmDeleteAccount", "setTheme", "setSkin", "setMessageFont", "createNote",
                "backToNoteList", "enterNoteEditMode", "enterNotePreviewMode", "saveCurrentNote",
                "shareCurrentNote", "deleteCurrentNote", "closeCurrentNote"}) {
            assertTrue(allScripts.contains("function " + handlerName)
                            || allScripts.contains("window." + handlerName + " =")
                            || allScripts.contains("window." + handlerName + "="),
                    "missing onclick handler " + handlerName);
        }

        assertEquals(4, occurrences(client, "class='profile-settings-tab"));
        for (String section : new String[] {"basic", "appearance", "account", "resources"}) {
            assertTrue(client.contains("switchProfileSection('" + section + "')"));
        }
        assertFalse(client.contains("profileSectionInfo"));
        assertFalse(client.contains("data-profile-section='info'"));

        for (String theme : new String[] {"sand", "ink", "pine", "clay"}) {
            assertTrue(profile.contains("setTheme('" + theme + "')"));
        }
        assertEquals(4, occurrences(profile, "data-theme-option="));
    }

    @Test
    void notesAndDarkThemeNeverRequireWhiteSurfaces() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String theme = source("src/main/resources/assets/wechat-theme.css");
        String scripts = renderer.substring(renderer.indexOf("// ===== NOTES"),
                renderer.indexOf("function openServerAdmin", renderer.indexOf("// ===== NOTES")));

        assertTrue(scripts.contains("function setNoteStage"));
        assertTrue(scripts.contains("function backToNoteList"));
        assertTrue(scripts.contains("refreshCurrentNotePreview"));
        assertTrue(scripts.contains("emptyState('emptyNotes'"));
        assertFalse(scripts.contains("background:#fff"));
        assertFalse(scripts.contains("background:white"));

        assertTrue(theme.contains(".notes-workspace {"));
        assertTrue(theme.contains(".notes-split {"));
        assertTrue(theme.contains(".notes-stage-edit .notes-preview-pane"));
        assertTrue(theme.contains(".notes-stage-preview .notes-editor-pane"));
        assertTrue(theme.contains("body.t-ink #notesView"));
        assertTrue(theme.contains("background: var(--panel) !important"));
        assertTrue(theme.contains("#notesView #noteMdEditor textarea"));
    }

    @Test
    void primaryControlsDoNotUseEmojiAsIcons() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String pageViews = method(renderer, "viewChat", "viewGames")
                + method(renderer, "viewNotes", "viewServerAdmin");
        Pattern emoji = Pattern.compile("[\\x{1F300}-\\x{1FAFF}]");
        Matcher matcher = emoji.matcher(pageViews);
        boolean found = matcher.find();
        String foundEmoji = found ? matcher.group() : "";
        assertFalse(found, "primary page controls still contain emoji: " + foundEmoji);
    }

    @Test
    void narrowViewportsEnableTheMobileShellWithoutDependingOnUserAgent() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");

        assertTrue(client.contains("if (width > 0 && width <= 860) return true;"));
        assertTrue(client.contains("window.matchMedia('(pointer: coarse)').matches"));
        assertTrue(client.contains("document.body.classList.toggle('mobile-shell', X.mobile.enabled)"));
        assertFalse(client.contains("if (!isMobileUA) return false;"));
    }

    @Test
    void sidebarDoesNotCloseTheMainShellBeforeTheWorkspace() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String sidebar = method(renderer, "sidebar", "viewChat");

        assertTrue(sidebar.contains("+ \"</div></div>\\n\";"));
        assertFalse(sidebar.contains("+ \"</div></div></div>\\n\";"));
    }
}
