package com.chat.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class FinalPagesUiContractTest {
    private static String source(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(FinalPagesUiContractTest.class.getProtectionDomain()
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
    void noPageLoadsLegacyGlassStylesheet() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        assertFalse(renderer.contains("/assets/glass-ui.css"));
        assertFalse(renderer.contains("class='box glass-panel'"));
        assertFalse(renderer.contains("class='forgot-card glass-panel'"));
    }

    @Test
    void loginAndRecoveryPublishResponsiveSemanticShells() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String theme = source("src/main/resources/assets/wechat-theme.css");
        String login = between(renderer, "private String renderLoginPage()", "private String renderForgotPasswordPage()");
        String recovery = between(renderer, "private String renderForgotPasswordPage()", "private String loginAnnouncementCss()");

        assertTrue(login.contains("auth-shell auth-login-shell"));
        assertTrue(login.contains("auth-brand-panel"));
        assertTrue(login.contains("auth-form-panel"));
        assertTrue(login.contains("/assets/icons/generated/brand-chat.png"));
        assertTrue(login.contains("/assets/app-icons.js"));
        assertTrue(login.contains("id='s2'"));
        assertTrue(login.contains("function togglePassword"));
        assertTrue(login.contains("function checkUN"));
        assertTrue(login.contains("async function doReg"));
        assertTrue(login.contains("async function doLogin"));
        assertTrue(recovery.contains("auth-forgot-shell"));
        assertTrue(recovery.contains("forgot-status"));
        assertTrue(recovery.contains("submitRecovery()"));
        assertTrue(theme.contains("grid-template-columns: minmax(0, 1.05fr) minmax(380px, .95fr)"));
        assertTrue(theme.contains("@media (max-width: 700px)"));
        assertTrue(theme.contains("min-height: 100dvh"));
    }

    @Test
    void hiddenLoginErrorsAndAutomaticCheckInNeverBlockTheShell() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String theme = source("src/main/resources/assets/wechat-theme.css");
        String login = between(renderer, "private String renderLoginPage()", "private String renderForgotPasswordPage()");
        String checkIn = between(renderer, "let autoCheckInStarted=false", "function showLevelUpAnim");

        assertTrue(login.contains("class='err hidden' id='e1'"));
        assertTrue(theme.contains(".hidden { display: none !important; }"));
        assertTrue(renderer.contains("wechat-theme.css?v=20260728-apple6"));
        assertFalse(renderer.contains("wechat-theme.css?v=20260728-ui4"));
        assertTrue(checkIn.contains("toast('已签到，连续签到 '"));
        assertFalse(checkIn.contains("showAlert('已签到"));
    }

    @Test
    void administrationFeedbackAndAnnouncementsKeepContracts() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String client = source("src/main/resources/assets/app-extra.js");
        String theme = source("src/main/resources/assets/wechat-theme.css");
        String all = renderer + "\n" + client;

        for (String className : new String[] {
                "server-admin-workspace", "server-admin-tabs", "admin-table-scroll",
                "admin-stat-grid", "admin-performance-grid", "feedback-shell",
                "feedback-ticket", "feedback-status-legend", "x-ann-shell", "x-ann-compose-actions"}) {
            assertTrue(all.contains(className) || theme.contains(className), "missing semantic class: " + className);
        }
        for (String handlerName : new String[] {
                "switchAdminTab", "loadAdminOverview", "loadAdminUsers", "loadAdminGroups",
                "loadAdminFeedback", "loadAdminRecovery", "loadAdminSuperAdmins",
                "submitFeedbackTicket", "updateFeedbackStatus", "openAnnouncements",
                "closeAnnouncements", "submitAnnouncement"}) {
            assertTrue(all.contains("function " + handlerName)
                            || all.contains("window." + handlerName + " =")
                            || all.contains("window." + handlerName + "="),
                    "missing handler: " + handlerName);
        }
        assertTrue(theme.contains(".admin-table-scroll table { min-width: 760px"));
        assertTrue(theme.contains(".feedback-ticket-actions"));
        assertTrue(theme.contains(".x-ann-main-body"));
    }

    @Test
    void appleDesignSystemOwnsTheApplicationChrome() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String client = source("src/main/resources/assets/app-extra.js");
        String theme = source("src/main/resources/assets/wechat-theme.css");

        assertTrue(theme.contains("* Apple UI layer"));
        assertTrue(theme.contains("--accent: #007AFF"));
        assertTrue(theme.contains("--canvas: #F2F2F7"));
        assertTrue(theme.contains("backdrop-filter: saturate(190%) blur(28px)"));
        assertTrue(theme.contains("grid-template-columns: repeat(4, minmax(0, 1fr))"));
        assertTrue(theme.contains(".auth-form-panel .err:empty { display: none !important; }"));
        assertTrue(client.contains("const IOS_SYMBOL_PATHS"));
        assertTrue(client.contains("function mobileTabIcon"));
        assertTrue(client.contains("class='ios-tab-symbol'"));
        assertFalse(client.contains("class='x-mobile-native-icon' aria-hidden='true'>＋"));
        assertTrue(renderer.contains(">系统蓝</button>"));
        assertTrue(renderer.contains(">深空黑</button>"));
    }

    @Test
    void sharingAndStatusPagesUseUnifiedThemeAndHandlers() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String share = between(renderer, "private String renderSharePage", "private String simpleErrorPage");
        String status = between(renderer, "private String renderStatusPage", "private String renderLoginPage");

        for (String className : new String[] {
                "share-shell", "share-page-header", "share-card", "share-login-bar",
                "standalone-dialog-layer"}) {
            assertTrue(share.contains(className), "missing share class: " + className);
        }
        assertTrue(share.contains("function downloadCurrentShare"));
        assertTrue(share.contains("function saveCurrentShare"));
        assertTrue(share.contains("function closeShareDialog"));
        assertTrue(status.contains("status-shell"));
        assertTrue(status.contains("status-card"));
        assertTrue(status.contains("status-actions"));
        assertTrue(status.contains("/assets/icons/generated/brand-chat.png"));
    }

    @Test
    void refactoredPageShellsDoNotEmbedHardWhiteOrDecorativeGradients() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String region = between(renderer, "private String renderSharePage", "private String loginAnnouncementScript");
        String adminView = between(renderer, "private String viewServerAdmin()", "private String viewUserProfile()");
        String feedbackView = between(renderer, "private String viewFeedback()", "private String modals()");

        for (String page : new String[] {region, adminView, feedbackView}) {
            assertFalse(page.contains("background:#fff"));
            assertFalse(page.contains("background:white"));
            assertFalse(page.contains("backdrop-filter"));
            assertFalse(page.contains("linear-gradient"));
        }
        assertFalse(adminView.contains("style='"));
        assertFalse(feedbackView.contains("style='"));
    }
}
