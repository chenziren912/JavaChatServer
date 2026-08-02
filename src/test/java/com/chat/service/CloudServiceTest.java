package com.chat.service;

import com.chat.model.CloudEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class CloudServiceTest {

    @Test
    public void testCloudEntryCreation() {
        CloudEntry entry = new CloudEntry();
        entry.setId("entry_1");
        entry.setOwnerId("user_1");
        entry.setName("test.txt");
        entry.setType("file");
        entry.setSize(1024L);
        entry.setCreatedAt(System.currentTimeMillis());

        assertEquals("entry_1", entry.getId());
        assertEquals("user_1", entry.getOwnerId());
        assertEquals("test.txt", entry.getName());
        assertFalse(entry.isFolder());
        assertEquals(1024L, entry.getSize());
    }
}
