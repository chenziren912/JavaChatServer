package com.chat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chat.model.Moment;
import org.junit.jupiter.api.Test;

class MomentAttachmentAccessTest {
    @Test
    void storedNamesMustMatchTheExactPathSegment() {
        Moment.Attachment attachment = new Moment.Attachment();
        attachment.setFilePath("/files/abc123?download=1");

        assertTrue(MomentService.attachmentReferencesStoredName(attachment, "abc123"));
        assertFalse(MomentService.attachmentReferencesStoredName(attachment, "abc"));
        assertFalse(MomentService.attachmentReferencesStoredName(attachment, "123"));
    }

    @Test
    void legacyFileNameFallbackIsAlsoExact() {
        Moment.Attachment attachment = new Moment.Attachment();
        attachment.setFileName("report.pdf");

        assertTrue(MomentService.attachmentReferencesStoredName(attachment, "report.pdf"));
        assertFalse(MomentService.attachmentReferencesStoredName(attachment, "port.pdf"));
    }
}
