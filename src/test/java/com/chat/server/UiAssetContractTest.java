package com.chat.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

class UiAssetContractTest {
    private static final String ICON_ROOT = "/assets/icons/generated/";

    private static final String[] REQUIRED_ICONS = {
            "brand-chat.png", "contacts.png", "moments.png", "miniapps.png",
            "cloud.png", "ai.png", "music.png", "video.png", "notes.png",
            "profile.png", "admin.png", "feedback.png", "qr.png",
            "category-tools.png", "category-games.png", "category-study.png",
            "category-life.png", "category-entertainment.png", "category-other.png",
            "empty-messages.png", "empty-files.png", "empty-videos.png", "empty-notes.png"
    };

    @Test
    void generatedIconsAreReadableSquarePngAssets() throws IOException {
        for (String fileName : REQUIRED_ICONS) {
            try (InputStream input = getClass().getResourceAsStream(ICON_ROOT + fileName)) {
                assertNotNull(input, "missing generated icon: " + fileName);
                BufferedImage image = ImageIO.read(input);
                assertNotNull(image, "unreadable PNG icon: " + fileName);
                assertEquals(256, image.getWidth(), "wrong icon width: " + fileName);
                assertEquals(256, image.getHeight(), "wrong icon height: " + fileName);
            }
        }
    }

    @Test
    void iconScriptPublishesStablePublicHelpers() throws IOException {
        String script;
        try (InputStream input = getClass().getResourceAsStream("/assets/app-icons.js")) {
            assertNotNull(input, "app-icons.js must be packaged");
            script = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(script.contains("window.AppIcons"));
        assertTrue(script.contains("window.featureIcon"));
        assertTrue(script.contains("window.normalizeTheme"));
        assertTrue(script.contains("dark: 'ink'"));
        assertTrue(script.contains("tea: 'pine'"));
        assertTrue(script.contains("rgb: 'clay'"));
    }
}
