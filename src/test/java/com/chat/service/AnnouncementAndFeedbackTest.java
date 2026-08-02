package com.chat.service;

import com.chat.model.Announcement;
import com.chat.model.FeedbackTicket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class AnnouncementAndFeedbackTest {

    @Test
    public void testAnnouncementModel() {
        Announcement ann = new Announcement();
        ann.setId("ann_1");
        ann.setTitle("系统升级通知");
        ann.setContent("服务器将于今晚升级");
        ann.setCreatedAt(System.currentTimeMillis());

        assertEquals("ann_1", ann.getId());
        assertEquals("系统升级通知", ann.getTitle());
        assertEquals("服务器将于今晚升级", ann.getContent());
    }

    @Test
    public void testFeedbackTicketModel() {
        FeedbackTicket ticket = new FeedbackTicket();
        ticket.setId("fb_1");
        ticket.setUserId("user_1");
        ticket.setContent("建议增加夜间模式");
        ticket.setStatus("pending");
        ticket.setCreatedAt(System.currentTimeMillis());

        assertEquals("fb_1", ticket.getId());
        assertEquals("user_1", ticket.getUserId());
        assertEquals("pending", ticket.getStatus());
    }
}
