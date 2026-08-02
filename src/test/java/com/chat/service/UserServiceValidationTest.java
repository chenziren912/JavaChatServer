package com.chat.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UserServiceValidationTest {
    @Test
    void acceptsSupportedUsernameCharactersAndBoundaries() {
        assertNull(UserService.validateUsername("测试_user1"));
        assertNull(UserService.validateUsername("abc"));
        assertNull(UserService.validateUsername("a1234567890123456789"));
    }

    @Test
    void rejectsUnsafeOrMalformedUsernames() {
        assertNotNull(UserService.validateUsername("ab"));
        assertNotNull(UserService.validateUsername("_abc"));
        assertNotNull(UserService.validateUsername("abc_"));
        assertNotNull(UserService.validateUsername("bad<name"));
        assertNotNull(UserService.validateUsername("a12345678901234567890"));
    }

    @Test
    void acceptsFriendlyNicknamesAndRejectsDangerousCharacters() {
        assertNull(UserService.validateNickname("昵称 🙂"));
        assertNull(UserService.validateNickname("A B"));
        assertNotNull(UserService.validateNickname("bad<name"));
        assertNotNull(UserService.validateNickname("bad\nname"));
        assertNotNull(UserService.validateNickname("一二三四五六七八九十一二三四五六七八九十甲"));
    }
}
