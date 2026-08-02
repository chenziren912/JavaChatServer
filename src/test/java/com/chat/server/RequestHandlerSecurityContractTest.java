package com.chat.server;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestHandlerSecurityContractTest {
    @Test
    void generatedCodeAndGameFramesKeepSecurityBoundaries() throws IOException {
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String client = source("src/main/resources/assets/app-extra.js");
        String handler = source("src/main/java/com/chat/server/RequestHandler.java");
        assertFalse(renderer.contains("tokens truncated"));
        assertFalse(renderer.contains("removeAttribute('sandbox')"));
        assertFalse(client.contains("removeAttribute('sandbox')"));
        assertFalse(client.contains("toggleMsgSelected"));
        assertTrue(renderer.contains("sandbox='allow-scripts allow-forms"));
        assertTrue(renderer.contains("renderer.html=function(raw)"));
        assertTrue(renderer.contains("safeRenderedUrl"));
        assertTrue(renderer.contains("if(/^on/i.test(attr.name)"));
        assertTrue(renderer.contains("replace(/&/g,'\\\\\\\\u0026')"));
        assertTrue(renderer.contains("String meJson = inlineJson(meData)"));
        assertTrue(renderer.contains("meData.put(\"language\""));
        assertFalse(renderer.contains("账户已被封禁，无法退出登录"));
        assertTrue(handler.contains("Content-Security-Policy"));
        assertTrue(handler.contains("sandbox allow-scripts allow-forms"));
    }

    @Test
    void requestAndAiBodiesAreBoundedAndExternalCallsHaveTimeouts() throws IOException {
        String support = source("src/main/java/com/chat/server/RequestHandlerSupport.java");
        String ai = source("src/main/java/com/chat/server/AiRequestHandler.java");
        assertTrue(support.contains("MAX_REQUEST_BODY_BYTES"));
        assertTrue(support.contains("readNBytes((int) MAX_REQUEST_BODY_BYTES + 1)"));
        assertTrue(ai.contains("MAX_AI_ATTACHMENT_BYTES"));
        assertTrue(ai.contains("metadataFromPath(path, owner)"));
        assertTrue(ai.contains("connectTimeout("));
        assertTrue(ai.contains(".timeout(java.time.Duration"));
        assertFalse(ai.contains("newSingleThreadExecutor"));
    }

    @Test
    void everyLiteralClientApiHasAServerRoute() throws IOException {
        String client = source("src/main/resources/assets/app-extra.js")
                + source("src/main/java/com/chat/server/AppPageRenderer.java");
        String handler = source("src/main/java/com/chat/server/RequestHandler.java");
        Matcher matcher = Pattern.compile("/api/[A-Za-z0-9_./-]+").matcher(client);
        Set<String> missing = new LinkedHashSet<>();
        while (matcher.find()) {
            String route = matcher.group();
            if (route.endsWith("/")) continue;
            if (!handler.contains("\"" + route + "\"")) missing.add(route);
        }
        assertTrue(missing.isEmpty(), "前端存在未实现接口: " + missing);
    }

    @Test
    void sensitiveAdminNumbersAndFileReferencesAreValidated() throws IOException {
        String admin = source("src/main/java/com/chat/server/AdminRequestHandler.java");
        String support = source("src/main/java/com/chat/server/RequestHandlerSupport.java");
        String fileStore = source("src/main/java/com/chat/service/FileStore.java");
        String fileAccess = source("src/main/java/com/chat/server/StoredFileAccess.java");
        assertTrue(admin.contains("Double.isFinite(tokens)"));
        assertTrue(admin.contains("服主密码不能通过管理接口重置"));
        assertTrue(support.contains("StoredFileAccess.canAccess(storedName, user)"));
        assertTrue(fileStore.contains("metadata.addOwnerUserId(userId)"));
        assertTrue(fileAccess.contains("status != null && !\"approved\".equals(status)"));
    }

    private String source(String relativePath) throws IOException {
        return Files.readString(projectFile(relativePath), StandardCharsets.UTF_8);
    }

    private Path projectFile(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
            return testClasses.getParent().getParent().resolve(relativePath);
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
    }
}
