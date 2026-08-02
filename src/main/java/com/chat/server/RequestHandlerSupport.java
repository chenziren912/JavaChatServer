package com.chat.server;

import com.chat.model.StoredFileMetadata;
import com.chat.service.FileStore;
import com.chat.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

abstract class RequestHandlerSupport {
    protected static final long MAX_REQUEST_BODY_BYTES = 16L * 1024 * 1024;

    protected static final class RequestBodyTooLargeException extends IOException {
        RequestBodyTooLargeException(long maxBytes) {
            super("请求体不能超过" + (maxBytes / 1024 / 1024) + "MB");
        }
    }

    protected String readBody(HttpExchange ex) throws IOException {
        long contentLength = parseLong(ex.getRequestHeaders().getFirst("Content-Length"), -1);
        if (contentLength > MAX_REQUEST_BODY_BYTES) {
            throw new RequestBodyTooLargeException(MAX_REQUEST_BODY_BYTES);
        }
        try (InputStream is = ex.getRequestBody()) {
            byte[] bytes = is.readNBytes((int) MAX_REQUEST_BODY_BYTES + 1);
            if (bytes.length > MAX_REQUEST_BODY_BYTES) {
                throw new RequestBodyTooLargeException(MAX_REQUEST_BODY_BYTES);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    protected StoredFileMetadata storeStreamFile(HttpExchange ex, InputStream bodyStream, String fileName,
                                                  long maxBytes, String tooLargeMessage, String userId) throws Exception {
        String normalizedFileName = FileStore.normalizeUploadedFileName(fileName);
        long contentLength = parseLong(ex.getRequestHeaders().getFirst("Content-Length"), -1);
        if (contentLength > maxBytes) {
            throw new IllegalArgumentException(tooLargeMessage);
        }
        InputStream limitedStream = maxBytes > 0
                ? limitInputStream(bodyStream, maxBytes, tooLargeMessage)
                : bodyStream;
        return FileStore.getInstance().store(limitedStream, normalizedFileName,
                ex.getRequestHeaders().getFirst("Content-Type"), userId);
    }

    protected InputStream limitInputStream(InputStream inputStream, long maxBytes, String tooLargeMessage) {
        return new FilterInputStream(inputStream) {
            private long count;

            private void ensureWithinLimit(long delta) {
                if (delta <= 0) return;
                count += delta;
                if (count > maxBytes) throw new IllegalArgumentException(tooLargeMessage);
            }

            @Override
            public int read() throws IOException {
                int value = super.read();
                if (value != -1) ensureWithinLimit(1);
                return value;
            }

            @Override
            public int read(byte[] bytes, int offset, int length) throws IOException {
                int read = super.read(bytes, offset, length);
                if (read > 0) ensureWithinLimit(read);
                return read;
            }
        };
    }

    protected long getNextLevelExp(com.chat.model.User user) {
        int level = user.getEffectiveLevel();
        if (level >= 6 || level >= com.chat.model.User.LEVEL_CONFIG.length) return -1;
        return com.chat.model.User.LEVEL_CONFIG[level][0];
    }

    protected Map<String, String> parseJson(String b) {
        try {
            Map<String, Object> raw = JsonUtil.fromJson(b, Map.class);
            Map<String, String> converted = new LinkedHashMap<>();
            if (raw != null) {
                raw.forEach((key, value) -> converted.put(key, value == null ? null : String.valueOf(value)));
            }
            return converted;
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    protected Map<String, Object> parseJsonObj(String b) {
        try {
            return JsonUtil.fromJson(b, Map.class);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    protected Map<String, Object> asObjectMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    protected List<Map<String, Object>> asObjectList(Object value) {
        if (!(value instanceof List)) return new ArrayList<>();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Object item : (List<Object>) value) {
            if (item instanceof Map) list.add((Map<String, Object>) item);
        }
        return list;
    }

    protected String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    protected Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<>();
        if (q == null || q.isEmpty())
            return m;
        for (String pair : q.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2)
                try {
                    m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                            URLDecoder.decode(kv[1], StandardCharsets.UTF_8));
                } catch (Exception ignored) {
                }
        }
        return m;
    }

    protected StoredFileMetadata metadataFromPath(String filePath, com.chat.model.User user) {
        if (filePath == null || !filePath.startsWith("/files/")) {
            return null;
        }
        String storedName = filePath.substring("/files/".length());
        if (!StoredFileAccess.canAccess(storedName, user)) return null;
        return FileStore.getInstance().getMetadata(storedName);
    }

    protected String asciiFileName(String displayName) {
        String safe = displayName == null ? "download" : displayName.replaceAll("[^\\x20-\\x7E]", "_");
        safe = safe.replace("\"", "_");
        return safe.isEmpty() ? "download" : safe;
    }

    protected void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().set("Location", location);
        ex.sendResponseHeaders(302, -1);
    }

    protected void send(HttpExchange ex, int code, String ct, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    protected void sendText(HttpExchange ex, int c, String t) throws IOException {
        send(ex, c, "text/plain;charset=utf-8", t);
    }

    protected void sendJson(HttpExchange ex, int code, Object value) throws IOException {
        send(ex, code, "application/json;charset=utf-8", JsonUtil.toJson(value));
    }

    protected Map<String, String> map(String... keyValues) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            result.put(keyValues[i], keyValues[i + 1]);
        }
        return result;
    }

    protected Map<String, Object> obj(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (kv == null) return m;
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    protected void writeJsonLine(OutputStream os, Map<String, Object> data) throws IOException {
        os.write((JsonUtil.toJson(data) + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    protected long parseLong(String s, long d) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return d;
        }
    }

    protected int parseInt(String s, int d) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return d;
        }
    }

    protected double parseDouble(String s, double d) {
        try {
            double value = Double.parseDouble(s);
            return Double.isFinite(value) ? value : d;
        } catch (Exception e) {
            return d;
        }
    }

    protected String eH(String s) {
        if (s == null)
            return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'",
                "&#39;");
    }

    protected String eJ(String s) {
        if (s == null)
            return "";
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"").replace("\n", "\\n").replace("\r",
                "\\r");
    }

    protected List<String> parseMentions(String c) {
        List<String> l = new ArrayList<>();
        if (c == null)
            return l;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("@([\\w\\u4e00-\\u9fa5]+)").matcher(c);
        while (m.find())
            l.add(m.group(1));
        return l;
    }

    protected String fmtDur(long s) {
        if (s >= 86400)
            return (s / 86400) + "天";
        if (s >= 3600)
            return (s / 3600) + "小时";
        if (s >= 60)
            return (s / 60) + "分钟";
        return s + "秒";
    }
}
