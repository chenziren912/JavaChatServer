package com.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageRoomSecurityTest {
    @Test
    void privateRoomsRequireExactParticipants() {
        String room = MessageService.normalizePrivateRoomId("12", "312");
        assertEquals("312", MessageService.getPrivateRoomPeer(room, "12"));
        assertEquals("12", MessageService.getPrivateRoomPeer(room, "312"));
        assertTrue(MessageService.isPrivateRoomParticipant(room, "12"));
        assertTrue(MessageService.isPrivateRoomParticipant(room, "312"));
        assertFalse(MessageService.isPrivateRoomParticipant(room, "2"));
        assertNull(MessageService.getPrivateRoomPeer("private_112_312", "12"));
    }
}
