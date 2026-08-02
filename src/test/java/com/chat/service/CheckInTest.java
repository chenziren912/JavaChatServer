package com.chat.service;

import com.chat.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

public class CheckInTest {

    private User testUser;

    @BeforeEach
    public void setUp() {
        testUser = new User();
        testUser.setUserId("test_user_checkin");
        testUser.setUsername("checkin_user");
        testUser.setExp(0);
        testUser.setLevel(1);
    }

    @Test
    public void testFirstCheckInGainsExp() {
        String today = LocalDate.now().toString();
        boolean alreadyCheckedIn = today.equals(testUser.getLastCheckIn());
        assertFalse(alreadyCheckedIn, "New user should not have checked in today");

        // Perform check-in logic
        testUser.setLastCheckIn(today);
        testUser.setCheckInStreak(1);
        testUser.addExp(15);

        assertEquals(today, testUser.getLastCheckIn());
        assertEquals(1, testUser.getCheckInStreak());
        assertEquals(15, testUser.getExp());

        // Second check-in attempt today
        boolean checkedInAgain = today.equals(testUser.getLastCheckIn());
        assertTrue(checkedInAgain, "User should now be marked as checked in for today");
    }

    @Test
    public void testConsecutiveCheckInStreak() {
        LocalDate todayDate = LocalDate.now();
        LocalDate yesterdayDate = todayDate.minusDays(1);

        testUser.setLastCheckIn(yesterdayDate.toString());
        testUser.setCheckInStreak(3);

        // Perform check-in today
        String today = todayDate.toString();
        LocalDate previous = LocalDate.parse(testUser.getLastCheckIn());
        int streak = 1;
        if (previous.plusDays(1).equals(todayDate)) {
            streak = testUser.getCheckInStreak() + 1;
        }
        testUser.setCheckInStreak(streak);
        testUser.setLastCheckIn(today);

        assertEquals(4, testUser.getCheckInStreak());
    }
}
