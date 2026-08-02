package com.chat.service;

import com.chat.model.Moment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MomentServiceDeletionTest {
    @Test
    void ownerHasFiveMinutesWhileSuperAdminCanDeleteAnyTime() {
        MomentService service = MomentService.getInstance();
        Moment moment = service.post("owner", "发布者", "内容", null, List.of());

        assertEquals("forbidden", service.deleteMoment(moment.getId(), "other", false));
        moment.setTimestamp(System.currentTimeMillis() - 5 * 60 * 1000L - 1);
        assertEquals("timeout", service.deleteMoment(moment.getId(), "owner", false));
        assertEquals("ok", service.deleteMoment(moment.getId(), "admin", true));
    }
}
