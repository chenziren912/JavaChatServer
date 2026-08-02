package com.chat.service;

import com.chat.model.CloudEntry;
import com.chat.model.User;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CloudServiceFolderOperationsTest {
    @Test
    void renameMoveAndCopyKeepFolderDescendantsReachable() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User user = new User();
        user.setUserId("cloud-user-" + suffix);
        user.setLevel(6);

        CloudService service = CloudService.getInstance();
        service.ensureUserCloud(user);
        CloudEntry root = service.createFolder(user.getUserId(), "/", "root-" + suffix);
        service.createFolder(user.getUserId(), "/" + root.getName(), "nested");
        byte[] content = "folder-copy-check".getBytes(StandardCharsets.UTF_8);
        service.createTextFile(user, "/" + root.getName() + "/nested", "proof.txt",
                new String(content, StandardCharsets.UTF_8), "test");

        String renamedName = "renamed-" + suffix;
        service.renameEntry(user.getUserId(), root.getId(), renamedName);
        assertEquals("proof.txt", only(service.listEntries(user.getUserId(), "/" + renamedName + "/nested")).getName());

        CloudEntry target = service.createFolder(user.getUserId(), "/", "target-" + suffix);
        service.moveEntry(user.getUserId(), root.getId(), "/" + target.getName());
        String movedRoot = "/" + target.getName() + "/" + renamedName;
        assertEquals("proof.txt", only(service.listEntries(user.getUserId(), movedRoot + "/nested")).getName());
        assertThrows(IllegalArgumentException.class,
                () -> service.moveEntry(user.getUserId(), root.getId(), movedRoot + "/nested"));

        long usedBeforeCopy = service.getUsedBytes(user.getUserId());
        CloudEntry copied = service.copyEntry(user, root.getId(), "/");
        assertEquals(renamedName, copied.getName());
        assertEquals("proof.txt", only(service.listEntries(user.getUserId(), "/" + copied.getName() + "/nested")).getName());
        assertEquals(usedBeforeCopy + content.length, service.getUsedBytes(user.getUserId()));
        assertThrows(IllegalArgumentException.class,
                () -> service.copyEntry(user, root.getId(), movedRoot + "/nested"));
    }

    private CloudEntry only(List<CloudEntry> entries) {
        assertEquals(1, entries.size());
        assertTrue(!entries.get(0).isFolder());
        return entries.get(0);
    }
}
