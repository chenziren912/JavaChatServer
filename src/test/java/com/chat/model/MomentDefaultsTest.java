package com.chat.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MomentDefaultsTest {
    @Test
    void newMomentsDefaultToFriendsOnly() {
        assertEquals("friends", new Moment().getVisibility());
        assertEquals("friends", new Moment("id", "user", "昵称", "内容").getVisibility());
    }
}
