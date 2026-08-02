package com.chat.service;

import com.chat.model.GameVersion;
import com.chat.model.StoredFileMetadata;
import com.chat.model.User;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServiceWorkflowTest {
    @Test
    void binaryCreationPreservesMetadataAndRequiresReview() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User developer = new User();
        developer.setUserId("developer-" + suffix);
        developer.setNickname("开发者");
        StoredFileMetadata binary = new StoredFileMetadata();
        binary.setStoredName("stored-" + suffix);
        binary.setOriginalFileName("demo.html");
        binary.setContentType("text/html");
        binary.setSize(1234);

        GameService service = GameService.getInstance();
        Map<String, Object> created = service.createGameFromBinary(developer, "演示程序", "工具", "说明文字",
                "1.2.3", "首个版本", binary);

        assertEquals("工具", created.get("category"));
        assertEquals("说明文字", created.get("desc"));
        assertEquals("pending", created.get("status"));
        List<?> versions = (List<?>) created.get("versions");
        assertEquals(1, versions.size());
        GameVersion version = (GameVersion) versions.get(0);
        assertEquals("1.2.3", version.getVersion());
        assertEquals("首个版本", version.getAnnouncement());
        assertEquals(binary.getAccessPath(), version.getFilePath());
        assertEquals("demo.html", version.getFileName());
        assertEquals(1234, version.getFileSize());

        String gameId = String.valueOf(created.get("id"));
        assertThrows(IllegalArgumentException.class, () -> service.recordVisit(gameId, developer.getUserId()));
        Map<String, Object> reviewed = service.reviewGame(gameId, developer, true);
        assertEquals("approved", reviewed.get("status"));
        assertTrue(service.recordVisit(gameId, developer.getUserId()) > 0);
        assertThrows(IllegalArgumentException.class,
                () -> service.recordVisit("missing-" + suffix, developer.getUserId()));
    }
}
