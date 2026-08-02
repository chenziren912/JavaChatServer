package com.chat.util;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionCookieSecurityTest {
    @Test
    void requiresAllTwelveSignedGuardCookies() {
        String sessionId = "session-for-test";
        Map<String, String> guards = new LinkedHashMap<>(SessionCookieSecurity.guardValues(sessionId));
        assertEquals(12, guards.size());
        assertTrue(SessionCookieSecurity.validate(header(sessionId, guards), sessionId));

        guards.remove("chatGuard12");
        assertFalse(SessionCookieSecurity.validate(header(sessionId, guards), sessionId));
    }

    @Test
    void rejectsTamperedAndUnexpectedGuardCookies() {
        String sessionId = "session-for-test";
        Map<String, String> guards = new LinkedHashMap<>(SessionCookieSecurity.guardValues(sessionId));
        guards.put("chatGuard01", "tampered");
        assertFalse(SessionCookieSecurity.validate(header(sessionId, guards), sessionId));

        guards = new LinkedHashMap<>(SessionCookieSecurity.guardValues(sessionId));
        guards.put("chatGuard99", "unexpected");
        assertFalse(SessionCookieSecurity.validate(header(sessionId, guards), sessionId));
    }

    @Test
    void allowsAnonymousRequestsOnlyWithoutGuardCookies() {
        assertTrue(SessionCookieSecurity.validate("theme=dark", null));
        assertFalse(SessionCookieSecurity.validate("chatGuard01=orphaned", null));
    }

    private String header(String sessionId, Map<String, String> guards) {
        StringBuilder value = new StringBuilder("sessionId=").append(sessionId);
        guards.forEach((name, guard) -> value.append("; ").append(name).append('=').append(guard));
        return value.toString();
    }
}
