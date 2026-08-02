package com.chat.service;

import com.chat.model.StoredFileMetadata;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GradientPaint;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.UUID;

final class GameCoverRenderer {
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    private GameCoverRenderer() { }

    static String renderAndStore(String title, String ownerId) {
        Path work = Paths.get("chatserver", "game-cover-temp", UUID.randomUUID().toString());
        try {
            Files.createDirectories(work);
            Path screenshot = work.resolve("cover.png");
            // 不执行用户上传的 HTML；封面由可信绘图代码生成，避免服务端浏览器 SSRF/脚本执行。
            renderFallback(title, screenshot);
            try (InputStream input = Files.newInputStream(screenshot)) {
                StoredFileMetadata stored = FileStore.getInstance().store(input,
                        safeTitle(title) + "-cover.png", "image/png", ownerId);
                return stored.getAccessPath();
            }
        } catch (Exception e) {
            System.err.println("[GameCoverRenderer] 自动封面失败: " + e.getMessage());
            return "";
        } finally {
            deleteTree(work);
        }
    }

    private static void renderFallback(String title, Path output) throws Exception {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setPaint(new GradientPaint(0, 0, new Color(42, 67, 175), WIDTH, HEIGHT, new Color(119, 47, 168)));
            graphics.fillRect(0, 0, WIDTH, HEIGHT);
            graphics.setColor(new Color(255, 255, 255, 45));
            graphics.fillOval(-120, -160, 580, 580);
            graphics.fillOval(880, 360, 540, 540);
            graphics.setColor(Color.WHITE);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 72));
            String value = title == null || title.isBlank() ? "小游戏" : title.trim();
            if (value.codePointCount(0, value.length()) > 18) {
                value = value.substring(0, value.offsetByCodePoints(0, 18)) + "…";
            }
            int textWidth = graphics.getFontMetrics().stringWidth(value);
            graphics.drawString(value, Math.max(60, (WIDTH - textWidth) / 2), 365);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 30));
            String subtitle = "ChatServer 小游戏";
            int subtitleWidth = graphics.getFontMetrics().stringWidth(subtitle);
            graphics.drawString(subtitle, (WIDTH - subtitleWidth) / 2, 425);
        } finally {
            graphics.dispose();
        }
        ImageIO.write(image, "png", output.toFile());
    }

    private static String safeTitle(String title) {
        String value = title == null ? "game" : title.trim().toLowerCase(Locale.ROOT);
        value = value.replaceAll("[^\\p{L}\\p{N}_-]+", "-").replaceAll("^-+|-+$", "");
        return value.isBlank() ? "game" : value;
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
    }
}
