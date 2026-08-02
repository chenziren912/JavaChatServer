package com.chat.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.lang.reflect.Type;

public class JsonUtil {
    private static final Gson GSON = new GsonBuilder().create();

    public static String toJson(Object obj) {
        return GSON.toJson(obj);
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        return GSON.fromJson(json, clazz);
    }

    public static <T> T fromJson(String json, Type type) {
        return GSON.fromJson(json, type);
    }

    public static void writeStringAtomic(java.nio.file.Path targetFile, String content) throws java.io.IOException {
        java.nio.file.Path parent = targetFile.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp." + java.util.UUID.randomUUID().toString());
        try {
            java.nio.file.Files.writeString(tempFile, content, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            java.nio.file.Files.move(tempFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
            throw e;
        }
    }

    public static void saveJsonAtomic(java.nio.file.Path targetFile, Object obj) throws java.io.IOException {
        writeStringAtomic(targetFile, toJson(obj));
    }

    public static void writeBytesAtomic(java.nio.file.Path targetFile, byte[] content) throws java.io.IOException {
        java.nio.file.Path parent = targetFile.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp." + java.util.UUID.randomUUID().toString());
        try {
            java.nio.file.Files.write(tempFile, content,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            java.nio.file.Files.move(tempFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
            throw e;
        }
    }

    public static void writeLinesAtomic(java.nio.file.Path targetFile, Iterable<? extends CharSequence> lines) throws java.io.IOException {
        java.nio.file.Path parent = targetFile.getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        java.nio.file.Path tempFile = targetFile.resolveSibling(targetFile.getFileName() + ".tmp." + java.util.UUID.randomUUID().toString());
        try {
            java.nio.file.Files.write(tempFile, lines, java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            java.nio.file.Files.move(tempFile, targetFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {}
            throw e;
        }
    }
}
