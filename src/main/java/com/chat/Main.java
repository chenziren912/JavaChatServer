package com.chat;

import com.chat.server.ChatHttpServer;

public class Main {
    public static void main(String[] args) throws Exception {
        int port = resolvePort(args);
        System.out.println("=== Java Chat Server ===");
        System.out.println("Starting on http://0.0.0.0:" + port + " ...");
        ChatHttpServer server = new ChatHttpServer(port);
        server.start();
        System.out.println("Server started! Open http://0.0.0.0:" + port + " in your browser.");
    }

    private static int resolvePort(String[] args) {
        String raw = null;
        if (args != null && args.length > 0 && args[0] != null && !args[0].isBlank()) {
            raw = args[0];
        }
        if ((raw == null || raw.isBlank())) {
            raw = System.getenv("CHAT_SERVER_PORT");
        }
        if (raw == null || raw.isBlank()) {
            return 80;
        }
        try {
            int port = Integer.parseInt(raw.trim());
            return port > 0 && port <= 65535 ? port : 80;
        } catch (NumberFormatException ignore) {
            return 80;
        }
    }
}
