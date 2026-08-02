package com.chat.server;

import com.chat.service.GameService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class ChatHttpServer {
    private final int port;

    public ChatHttpServer(int port) {
        this.port = port;
    }

    public void start() throws Exception {
        GameService.warmUp();
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        RequestHandler handler = new RequestHandler();

        server.createContext("/", handler);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                16,
                64,
                60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1000),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
        server.setExecutor(executor);
        server.start();
    }
}
