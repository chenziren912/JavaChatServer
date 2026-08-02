package com.chat.server;

import com.chat.model.User;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;

public class TestGen {
    public static void main(String[] args) throws Exception {
        User u = new User();
        u.setUserId("test999");
        u.setUsername("test999");
        u.setNickname("Test 999");
        
        RequestHandler handler = new RequestHandler();
        Method m = RequestHandler.class.getDeclaredMethod("buildChatPage", User.class);
        m.setAccessible(true);
        String html = (String) m.invoke(handler, u);
        
        Files.writeString(Paths.get("test_chat_page.html"), html);
        
        Method m2 = RequestHandler.class.getDeclaredMethod("buildLoginPage");
        m2.setAccessible(true);
        String html2 = (String) m2.invoke(handler);
        Files.writeString(Paths.get("test_login_page.html"), html2);
        
        System.out.println("Generated HTML files");
    }
}
