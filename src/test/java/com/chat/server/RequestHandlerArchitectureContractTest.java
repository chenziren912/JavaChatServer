package com.chat.server;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class RequestHandlerArchitectureContractTest {
    private static Path projectFile(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(RequestHandlerArchitectureContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            return testClasses.getParent().getParent().resolve(relativePath);
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(projectFile(relativePath), StandardCharsets.UTF_8);
    }

    @Test
    void requestHandlerDelegatesLargeDomainsAndStaysBelowFourThousandLines() throws IOException {
        Path handlerPath = projectFile("src/main/java/com/chat/server/RequestHandler.java");
        String handler = source("src/main/java/com/chat/server/RequestHandler.java");

        assertTrue(handler.contains("extends RequestHandlerSupport"));

        assertTrue(handler.contains("new AiRequestHandler()"));
        assertTrue(handler.contains("aiHandler.handleGetAiModels("));
        assertTrue(handler.contains("aiHandler.handleSendAiPromptStream("));

        assertTrue(handler.contains("new CloudRequestHandler()"));
        assertTrue(handler.contains("cloudHandler.handleGetCloudList("));
        assertTrue(handler.contains("cloudHandler.handleCompressCloudBatch("));

        assertTrue(handler.contains("new AppPageRenderer()"));
        assertTrue(handler.contains("pageRenderer.buildLoginPage("));
        assertTrue(handler.contains("pageRenderer.buildChatPage("));
        assertTrue(handler.contains("pageRenderer.buildSharePage("));

        assertTrue(handler.contains("new GameRequestHandler()"));
        assertTrue(handler.contains("gameHandler.handleUploadGameBinary("));
        assertTrue(handler.contains("gameHandler.handleApproveGame("));

        assertTrue(handler.contains("new AdminRequestHandler()"));
        assertTrue(handler.contains("adminHandler.handleGetAdminOverview("));
        assertTrue(handler.contains("adminHandler.handleSetAiTokens("));

        assertTrue(handler.contains("new MusicRequestHandler()"));
        assertTrue(handler.contains("musicHandler.handleGetTracks("));
        assertTrue(handler.contains("musicHandler.handleImportZip("));

        long lineCount;
        try (var lines = Files.lines(handlerPath, StandardCharsets.UTF_8)) {
            lineCount = lines.count();
        }
        assertTrue(lineCount < 4_000,
                "RequestHandler should remain an orchestration layer below 4000 lines, but was " + lineCount);
    }

    @Test
    void extractedHandlersReuseSharedSupportMappersAndRoleChecks() throws IOException {
        String support = source("src/main/java/com/chat/server/RequestHandlerSupport.java");
        String ai = source("src/main/java/com/chat/server/AiRequestHandler.java");
        String cloud = source("src/main/java/com/chat/server/CloudRequestHandler.java");
        String game = source("src/main/java/com/chat/server/GameRequestHandler.java");
        String admin = source("src/main/java/com/chat/server/AdminRequestHandler.java");
        String renderer = source("src/main/java/com/chat/server/AppPageRenderer.java");
        String cloudMapper = source("src/main/java/com/chat/server/CloudEntryMapper.java");
        String userRoles = source("src/main/java/com/chat/server/UserRoles.java");

        assertTrue(support.contains("abstract class RequestHandlerSupport"));
        for (String delegate : new String[] {ai, cloud, game, admin, renderer}) {
            assertTrue(delegate.contains("extends RequestHandlerSupport"));
        }

        assertTrue(cloudMapper.contains("final class CloudEntryMapper"));
        assertTrue(cloudMapper.contains("cloudEntryToMap(CloudEntry entry)"));
        assertTrue(cloudMapper.contains("cloudShareToMap(CloudShareLink share)"));
        assertTrue(cloud.contains("CloudEntryMapper::cloudEntryToMap"));
        assertTrue(cloud.contains("CloudEntryMapper.cloudShareToMap("));

        assertTrue(userRoles.contains("final class UserRoles"));
        assertTrue(userRoles.contains("static boolean isSuperAdmin("));
        assertTrue(userRoles.contains("static boolean isPrimarySuperAdmin("));
        assertTrue(userRoles.contains("static boolean isDeveloper("));
        assertTrue(game.contains("UserRoles.isSuperAdmin("));
        assertTrue(game.contains("UserRoles.isDeveloper("));
        assertTrue(admin.contains("UserRoles.isSuperAdmin("));
        assertTrue(admin.contains("UserRoles.isPrimarySuperAdmin("));
    }
}
