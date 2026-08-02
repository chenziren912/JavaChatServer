package com.chat.util;

import com.sun.net.httpserver.HttpExchange;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SessionCookieSecurity {
    public static final int GUARD_COOKIE_COUNT = 12;
    private static final String GUARD_PREFIX = "chatGuard";
    private static final Path SECRET_FILE = Paths.get("chatserver", "cookie-secret.key");
    private static final byte[] SECRET = loadOrCreateSecret();

    private SessionCookieSecurity() { }

    public static boolean validate(String cookieHeader, String sessionId) {
        Map<String, String> cookies = parseCookies(cookieHeader);
        long guardCount = cookies.keySet().stream().filter(name -> name.startsWith(GUARD_PREFIX)).count();
        if (sessionId == null || sessionId.isBlank()) return guardCount == 0;
        if (guardCount != GUARD_COOKIE_COUNT) return false;
        Map<String, String> expected = guardValues(sessionId);
        for (Map.Entry<String, String> entry : expected.entrySet()) {
            String actual = cookies.get(entry.getKey());
            if (actual == null || !MessageDigest.isEqual(
                    actual.getBytes(StandardCharsets.UTF_8), entry.getValue().getBytes(StandardCharsets.UTF_8))) {
                return false;
            }
        }
        return true;
    }

    public static Map<String, String> guardValues(String sessionId) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 1; index <= GUARD_COOKIE_COUNT; index++) {
            String name = guardName(index);
            values.put(name, sign(sessionId + ":" + index + ":ChatServer"));
        }
        return values;
    }

    public static void setSessionCookies(HttpExchange exchange, String sessionId, long maxAgeSeconds) {
        String suffix = "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAgeSeconds + secureSuffix(exchange);
        exchange.getResponseHeaders().add("Set-Cookie", "sessionId=" + sessionId + suffix);
        for (Map.Entry<String, String> entry : guardValues(sessionId).entrySet()) {
            exchange.getResponseHeaders().add("Set-Cookie", entry.getKey() + "=" + entry.getValue() + suffix);
        }
    }

    public static void clearAllCookies(HttpExchange exchange, String cookieHeader) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("sessionId", "");
        for (int index = 1; index <= GUARD_COOKIE_COUNT; index++) names.put(guardName(index), "");
        for (String name : parseCookies(cookieHeader).keySet()) {
            if (name.matches("[A-Za-z0-9_.-]{1,80}")) names.put(name, "");
        }
        String suffix = "; Path=/; HttpOnly; SameSite=Lax; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT"
                + secureSuffix(exchange);
        for (String name : names.keySet()) {
            exchange.getResponseHeaders().add("Set-Cookie", name + "=" + suffix);
        }
    }

    public static Map<String, String> parseCookies(String cookieHeader) {
        Map<String, String> cookies = new LinkedHashMap<>();
        if (cookieHeader == null || cookieHeader.isBlank()) return cookies;
        for (String part : cookieHeader.split(";")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length == 2 && !pair[0].isBlank()) cookies.put(pair[0].trim(), pair[1].trim());
        }
        return cookies;
    }

    private static String guardName(int index) {
        return GUARD_PREFIX + String.format("%02d", index);
    }

    private static String sign(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cookie 签名失败", e);
        }
    }

    private static byte[] loadOrCreateSecret() {
        try {
            if (Files.exists(SECRET_FILE)) {
                String value = Files.readString(SECRET_FILE, StandardCharsets.UTF_8).trim();
                byte[] decoded = Base64.getDecoder().decode(value);
                if (decoded.length >= 32) return decoded;
            }
            byte[] secret = new byte[48];
            new SecureRandom().nextBytes(secret);
            JsonUtil.writeBytesAtomic(SECRET_FILE,
                    Base64.getEncoder().encodeToString(secret).getBytes(StandardCharsets.UTF_8));
            return secret;
        } catch (Exception e) {
            throw new IllegalStateException("无法初始化 Cookie 安全密钥", e);
        }
    }

    private static String secureSuffix(HttpExchange exchange) {
        String configured = System.getProperty("chat.cookie.secure", System.getenv("CHATSERVER_COOKIE_SECURE"));
        String forwarded = exchange != null ? exchange.getRequestHeaders().getFirst("X-Forwarded-Proto") : null;
        return "true".equalsIgnoreCase(configured) || "https".equalsIgnoreCase(forwarded) ? "; Secure" : "";
    }
}
