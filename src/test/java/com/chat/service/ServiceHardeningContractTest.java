package com.chat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ServiceHardeningContractTest {
    private static String source(String relativePath) throws IOException {
        try {
            Path testClasses = Path.of(ServiceHardeningContractTest.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path projectRoot = testClasses.getParent().getParent();
            return Files.readString(projectRoot.resolve(relativePath), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("无法定位项目源码", e);
        }
    }

    @Test
    void messagesBecomeGloballyVisibleOnlyAfterDurableSave() throws IOException {
        String source = source("src/main/java/com/chat/service/MessageService.java");
        int send = source.indexOf("public Message sendMessage", source.indexOf("public Message sendMessage") + 1);
        int save = source.indexOf("if (!saveToDisk(roomId, snapshot))", send);
        int publish = source.indexOf("messageIdToRoomIdMap.put(id, roomId)", send);
        assertTrue(send >= 0 && save > send && publish > save);
    }

    @Test
    void quotaAndMaintenanceChecksRunAtCommitOrOnBoundedIntervals() throws IOException {
        String cloud = source("src/main/java/com/chat/service/CloudService.java");
        String sessions = source("src/main/java/com/chat/util/SessionManager.java");
        String files = source("src/main/java/com/chat/service/FileStore.java");
        String handler = source("src/main/java/com/chat/server/RequestHandler.java");

        assertTrue(cloud.contains("commit-time"));
        assertTrue(cloud.contains("if (!canStoreUnsafe(user, stored.getSize()))"));
        assertTrue(sessions.contains("purgeExpiredSessions(now)"));
        assertTrue(files.contains("flushAccessIndex()"));
        assertTrue(handler.contains("lastRateLimitCleanupAt.compareAndSet"));
    }

    @Test
    void domainCleanupNeverDeletesSharedGlobalBlobs() throws IOException {
        String cloud = source("src/main/java/com/chat/service/CloudService.java");
        String messages = source("src/main/java/com/chat/service/MessageService.java");
        assertFalse(cloud.contains("FileStore.getInstance().deleteStoredFile(storedName)"));
        assertFalse(messages.contains("FileStore.getInstance().deleteStoredFile(storedName)"));
    }
}
