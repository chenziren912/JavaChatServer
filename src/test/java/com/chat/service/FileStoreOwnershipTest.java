package com.chat.service;

import com.chat.model.StoredFileMetadata;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileStoreOwnershipTest {
    @Test
    void deduplicatedFilesRememberEveryUploader() throws Exception {
        byte[] content = ("ownership-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        FileStore store = FileStore.getInstance();
        StoredFileMetadata first = store.store(new ByteArrayInputStream(content), "one.txt", "text/plain", "user-a");
        StoredFileMetadata second = store.store(new ByteArrayInputStream(content), "two.txt", "text/plain", "user-b");
        assertEquals(first.getStoredName(), second.getStoredName());
        assertTrue(store.isOwnedBy(first.getStoredName(), "user-a"));
        assertTrue(store.isOwnedBy(first.getStoredName(), "user-b"));
        store.deleteStoredFile(first.getStoredName());
    }

    @Test
    void preservesUploadLimitExceptionInsteadOfWrappingItAsServerFailure() {
        InputStream limited = new InputStream() {
            @Override public int read() { throw new IllegalArgumentException("too large"); }
        };
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> FileStore.getInstance().store(limited, "large.bin", "application/octet-stream", "user"));
        assertEquals("too large", error.getMessage());
    }
}
