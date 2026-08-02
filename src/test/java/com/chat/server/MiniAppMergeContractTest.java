package com.chat.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniAppMergeContractTest {
    private static String source(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(MiniAppMergeContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path projectRoot = testClasses.getParent().getParent();
            return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
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
    void standaloneMiniToolsViewAndNavigationAreRemoved() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String client = source("src/main/resources/assets/app-extra.js");
        String businessCss = source("src/main/resources/assets/app-extra.css");

        for (String removed : new String[]{"miniToolsView", "miniToolsRoot", "c-miniTools", "viewMiniTools", "renderMiniTools"}) {
            assertFalse(renderer.contains(removed), "server shell still contains " + removed);
            assertFalse(client.contains(removed), "client still contains " + removed);
        }
        assertFalse(renderer.contains("小功能"));
        assertFalse(businessCss.contains("mini-tools-grid"));
        assertFalse(businessCss.contains("mini-tool-card"));
    }

    @Test
    void publishesExactlyOneStableBuiltinQrMiniApp() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");
        assertTrue(client.contains("window.BuiltinMiniApps = Object.freeze(["));
        assertEquals(1, occurrences(client, "id: 'builtin-qr'"));
        assertTrue(client.contains("title: '二维码工具'"));
        assertTrue(client.contains("category: '工具'"));
        assertTrue(client.contains("iconKey: 'qr'"));
        assertTrue(client.contains("source: 'builtin'"));
        assertTrue(client.contains("launch: 'qr'"));
        assertTrue(client.contains("window.openBuiltinMiniApp"));
        assertTrue(client.contains("系统内置"));
    }

    @Test
    void builtinCatalogEntryCannotUseUploadedGameManagementFlows() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js");
        int builtinCardsStart = client.indexOf("function buildBuiltinGameListItemHtml");
        int uploadedCardsStart = client.indexOf("function buildUploadedGameListItemHtml");
        assertTrue(builtinCardsStart >= 0 && uploadedCardsStart > builtinCardsStart);
        String builtinCards = client.substring(builtinCardsStart, uploadedCardsStart);
        assertFalse(builtinCards.contains("openGameMetaEditor"));
        assertFalse(builtinCards.contains("triggerGameUpdate"));
        assertFalse(builtinCards.contains("approve"));

        assertTrue(client.contains("game.id !== BUILTIN_QR_ID && game.source !== 'builtin'"));
        assertTrue(client.contains("serverApps.forEach(game => { window.gameMap[game.id] = game; });"));
        assertFalse(client.contains("window.BuiltinMiniApps.forEach(game => { window.gameMap"));
        assertTrue(client.contains("gameId === BUILTIN_QR_ID"));
        assertTrue(client.contains("gameUploadContext.gameId === BUILTIN_QR_ID"));
    }

    @Test
    void toolsRouteRedirectsAndQrApisRemainAvailable() throws IOException {
        String handler = source("src/main/java/com/chat/server/RequestHandler.java");
        String client = source("src/main/resources/assets/app-extra.js");

        assertTrue(handler.contains("redirect(ex, \"/games?app=builtin-qr\")"));
        assertTrue(client.contains("const BUILTIN_QR_ROUTE = '/games?app=builtin-qr'"));
        assertTrue(client.contains("window.openMiniTools"));
        assertTrue(client.contains("history.replaceState"));
        assertTrue(handler.contains("case \"/api/tools/decode-qr\""));
        assertTrue(handler.contains("case \"/api/tools/encode-qr\""));
        assertTrue(client.contains("apiPost('/api/tools/decode-qr'"));
        assertTrue(client.contains("apiPost('/api/tools/encode-qr'"));
    }
}
