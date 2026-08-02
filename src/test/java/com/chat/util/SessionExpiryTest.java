package com.chat.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.chat.model.SessionRecord;
import org.junit.jupiter.api.Test;

class SessionExpiryTest {
    @Test
    void detectsExpiredAndNonExpiringSessions() {
        long now = System.currentTimeMillis();
        SessionRecord expired = new SessionRecord();
        expired.setExpiresAt(now - 1);
        SessionRecord active = new SessionRecord();
        active.setExpiresAt(now + 1_000);
        SessionRecord nonExpiring = new SessionRecord();
        nonExpiring.setExpiresAt(0);

        assertTrue(SessionManager.isExpired(expired, now));
        assertFalse(SessionManager.isExpired(active, now));
        assertFalse(SessionManager.isExpired(nonExpiring, now));
    }
}
