package com.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CloudArchiveSecurityTest {
    @Test
    void normalizesPortableZipPathsAndRejectsTraversal() {
        assertEquals("safe/nested/file.txt", CloudService.normalizeZipEntryPath("safe\\nested/file.txt"));
        assertNull(CloudService.normalizeZipEntryPath("../../other-user/file.txt"));
        assertNull(CloudService.normalizeZipEntryPath("safe/../file.txt"));
        assertNull(CloudService.normalizeZipEntryPath("C:/absolute/file.txt"));
        assertNull(CloudService.normalizeZipEntryPath("/absolute/file.txt"));
    }

    @Test
    void extractionLimitsRemainFiniteAndIndependentFromQuota() {
        assertTrue(CloudService.MAX_UNZIP_ENTRIES > 0);
        assertTrue(CloudService.MAX_UNZIP_PATH_DEPTH > 0);
        assertTrue(CloudService.MAX_UNZIP_SINGLE_FILE_BYTES > 0);
        assertTrue(CloudService.MAX_UNZIP_TOTAL_BYTES >= CloudService.MAX_UNZIP_SINGLE_FILE_BYTES);
    }
}
