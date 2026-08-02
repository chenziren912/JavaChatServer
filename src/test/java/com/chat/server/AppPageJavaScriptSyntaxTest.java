package com.chat.server;

import com.chat.model.User;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppPageJavaScriptSyntaxTest {
    @TempDir
    Path tempDir;

    @Test
    void generatedMainPageAndClientAssetContainValidJavaScript() throws Exception {
        Assumptions.assumeTrue(nodeAvailable(), "Node.js 不可用，跳过 JavaScript 语法检查");
        User user = new User();
        user.setUserId("syntax-user");
        user.setUsername("syntax-user");
        user.setNickname("语法检查");

        String html = new AppPageRenderer().buildChatPage(user);
        int start = html.indexOf("<script>window.ME=");
        int end = html.indexOf("</script>", start);
        assertTrue(start >= 0 && end > start, "主页面必须包含初始化脚本");
        String inlineScript = html.substring(start + "<script>".length(), end);
        assertFalse(inlineScript.contains("tokens truncated"), "生成代码不得包含工具截断标记");
        assertNodeCheck(inlineScript, "generated-main-page.js");

        String clientScript = Files.readString(projectFile("src/main/resources/assets/app-extra.js"),
                StandardCharsets.UTF_8);
        assertNodeCheck(clientScript, "app-extra.js");
    }

    private boolean nodeAvailable() {
        try {
            Process process = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void assertNodeCheck(String script, String fileName) throws Exception {
        Path scriptFile = tempDir.resolve(fileName);
        Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
        Process process = new ProcessBuilder("node", "--check", scriptFile.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
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
