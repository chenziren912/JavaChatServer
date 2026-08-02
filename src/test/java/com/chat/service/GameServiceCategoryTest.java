package com.chat.service;

import com.chat.model.GameEntry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameServiceCategoryTest {
    @Test
    void keepsSupportedMiniProgramCategories() {
        assertEquals("工具", GameService.normalizeCategory(" 工具 "));
        assertEquals("游戏", GameService.normalizeCategory("游戏"));
        assertEquals("学习", GameService.normalizeCategory(" 学习 "));
        assertEquals("生活", GameService.normalizeCategory("生活"));
        assertEquals("娱乐", GameService.normalizeCategory("娱乐"));
        assertEquals("其他", GameService.normalizeCategory("其他"));

        assertTrue(GameService.isSupportedCategory("工具"));
        assertTrue(GameService.isSupportedCategory("游戏"));
        assertTrue(GameService.isSupportedCategory("学习"));
        assertTrue(GameService.isSupportedCategory("生活"));
        assertTrue(GameService.isSupportedCategory("娱乐"));
        assertTrue(GameService.isSupportedCategory("其他"));
    }

    @Test
    void migratesMissingOrUnknownLegacyCategoriesToGame() {
        assertEquals("游戏", GameService.normalizeCategory(null));
        assertEquals("游戏", GameService.normalizeCategory(""));
        assertEquals("游戏", GameService.normalizeCategory("未知分类"));
        assertFalse(GameService.isSupportedCategory("未知分类"));
    }

    @Test
    void validatesGameEntryConstantsAndDefaults() {
        assertEquals("游戏", GameEntry.CATEGORY_GAME);
        assertEquals("工具", GameEntry.CATEGORY_TOOL);
        assertEquals("学习", GameEntry.CATEGORY_STUDY);
        assertEquals("生活", GameEntry.CATEGORY_LIFE);
        assertEquals("娱乐", GameEntry.CATEGORY_ENTERTAINMENT);
        assertEquals("其他", GameEntry.CATEGORY_OTHER);

        GameEntry entry = new GameEntry();
        assertEquals("游戏", entry.getCategory());
        assertTrue(GameEntry.SUPPORTED_CATEGORIES.contains("工具"));
        assertTrue(GameEntry.SUPPORTED_CATEGORIES.contains("学习"));
        assertTrue(GameEntry.SUPPORTED_CATEGORIES.contains("生活"));
        assertTrue(GameEntry.SUPPORTED_CATEGORIES.contains("娱乐"));
        assertTrue(GameEntry.SUPPORTED_CATEGORIES.contains("其他"));
    }
}
