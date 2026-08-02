package com.chat.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class UserFriendsSnapshotTest {
    @Test
    void snapshotIsStableAfterTheLiveListChanges() {
        User user = new User();
        user.setFriends(List.of("u1", "u2"));

        List<String> snapshot = user.snapshotFriends();
        user.addFriend("u3");

        assertEquals(List.of("u1", "u2"), snapshot);
        assertEquals(List.of("u1", "u2", "u3"), user.snapshotFriends());
    }
}
