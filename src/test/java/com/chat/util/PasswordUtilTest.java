package com.chat.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class PasswordUtilTest {

    @Test
    public void testHashAndPasswordVerification() {
        String rawPassword = "MySecurePassword123!";
        String hash = PasswordUtil.hashPassword(rawPassword);

        assertNotNull(hash);
        assertTrue(PasswordUtil.looksHashed(hash));
        assertTrue(PasswordUtil.verifyPassword(rawPassword, hash));
        assertFalse(PasswordUtil.verifyPassword("WrongPassword", hash));
    }
}
