package com.chat.service;

import com.chat.model.*;
import com.chat.util.JsonUtil;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

public class AiService {
    public static final double DAILY_LIMIT = 500000D;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final AiService INSTANCE = new AiService();

    private static Path dataDir() { return Paths.get("chatserver", "ai"); }
    private static Path conversationsFile() { return dataDir().resolve("conversations.json"); }
    private static Path messagesFile() { return dataDir().resolve("messages.json"); }
    private static Path ledgerFile() { return dataDir().resolve("usage.json"); }
    private static Path tasksFile() { return dataDir().resolve("tasks.json"); }

    private final List<AiConversation> conversations = new ArrayList<>();
    private final List<AiMessage> messages = new ArrayList<>();
    private final List<AiUsageLedger> ledgers = new ArrayList<>();
    private final List<AiMediaTask> tasks = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public static AiService getInstance() {
        return INSTANCE;
    }

    private AiService() {
        try {
            Files.createDirectories(dataDir());
        } catch (Exception ignored) {
        }
        load();
    }

    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> result = new ArrayList<>();
        result.add(model("deepseek-v4-flash", "DeepSeek V4 Flash", "chat", false, false, 1.5, 1.5, "deepseek"));
        result.add(model("deepseek-v4-pro", "DeepSeek V4 Pro", "chat", true, false, 15, 15, "deepseek"));
        result.add(model("LongCat-Flash-Lite", "龙猫 Lite", "chat", false, false, 0.0001, 0.0005, "longcat"));
        result.add(model("LongCat-Flash-Omni-2603", "龙猫 Pro", "chat", true, true, 0.1, 0.5, "longcat"));
        result.add(model("doubao-seed-2-0-mini-260215", "豆包 2.0 Mini", "chat", true, true, 0.6, 3, "volc"));
        result.add(model("doubao-seed-2-0-lite-260215", "豆包 2.0 Lite", "chat", true, true, 1, 6, "volc"));
        result.add(model("doubao-seed-2-0-pro-260215", "豆包 2.0 Pro", "chat", true, true, 4.8, 24, "volc"));
        result.add(model("doubao-seed-2-0-code-preview-260215", "豆包 2.0 Code", "chat", true, true, 6, 36, "volc"));
        result.add(model("doubao-seedream-5-0-260128", "图片生成权益", "image", false, false, 0, 74000, "volc", 3));
        result.add(model("doubao-seedance-1-0-pro-fast-251015", "视频生成权益", "video", false, false, 0, 4.2, "volc", 5));
        return result;
    }

    public AiConversation createConversation(String userId, String type, String modelId) {
        lock.writeLock().lock();
        try {
            long now = System.currentTimeMillis();
            AiConversation conversation = new AiConversation();
            conversation.setId("ai_" + UUID.randomUUID().toString().replace("-", ""));
            conversation.setUserId(userId);
            conversation.setTitle("新标签");
            conversation.setType(type != null ? type : "chat");
            conversation.setModelId(modelId != null && !modelId.trim().isEmpty() ? modelId : defaultModelForType(conversation.getType()));
            conversation.setReasoningDepth("default");
            conversation.setSystemPrompt("");
            conversation.setTemperature(1);
            conversation.setTopP(1);
            conversation.setContextCount(10);
            conversation.setMaxTokens(0);
            conversation.setStreamOutput(true);
            conversation.setImageSize("1024x1024");
            conversation.setImageStyle("通用");
            conversation.setImageCount(1);
            conversation.setVideoDuration(5);
            conversation.setVideoSize("720p");
            conversation.setVideoSeed(0);
            conversation.setVideoFirstFramePath("");
            conversation.setVideoLastFramePath("");
            conversation.setCreatedAt(now);
            conversation.setUpdatedAt(now);
            conversations.add(conversation);
            saveUnsafe();
            return copyConversation(conversation);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AiConversation> listConversations(String userId) {
        lock.readLock().lock();
        try {
            return conversations.stream()
                    .filter(item -> Objects.equals(item.getUserId(), userId))
                    .sorted(Comparator.comparingLong(AiConversation::getUpdatedAt).reversed())
                    .map(this::copyConversation)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public AiConversation getConversation(String userId, String conversationId) {
        lock.readLock().lock();
        try {
            AiConversation conversation = conversations.stream()
                    .filter(item -> Objects.equals(item.getId(), conversationId) && Objects.equals(item.getUserId(), userId))
                    .findFirst().orElse(null);
            return conversation == null ? null : copyConversation(conversation);
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean deleteConversation(String userId, String conversationId) {
        lock.writeLock().lock();
        try {
            boolean exists = conversations.stream()
                    .anyMatch(item -> Objects.equals(item.getId(), conversationId) && Objects.equals(item.getUserId(), userId));
            if (!exists) return false;
            conversations.removeIf(item -> Objects.equals(item.getId(), conversationId));
            messages.removeIf(item -> Objects.equals(item.getConversationId(), conversationId));
            tasks.removeIf(item -> Objects.equals(item.getConversationId(), conversationId));
            saveUnsafe();
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AiConversation updateConversation(AiConversation conversation) {
        if (!Double.isFinite(conversation.getTemperature()) || conversation.getTemperature() < 0
                || conversation.getTemperature() > 2) {
            throw new IllegalArgumentException("temperature 必须在0到2之间");
        }
        if (!Double.isFinite(conversation.getTopP()) || conversation.getTopP() < 0
                || conversation.getTopP() > 1) {
            throw new IllegalArgumentException("topP 必须在0到1之间");
        }
        if (!Double.isFinite(conversation.getVideoDuration()) || conversation.getVideoDuration() < 0
                || conversation.getVideoDuration() > 60) {
            throw new IllegalArgumentException("视频时长必须在0到60秒之间");
        }
        if (conversation.getContextCount() < 0 || conversation.getContextCount() > 100
                || conversation.getMaxTokens() < 0 || conversation.getMaxTokens() > 200000
                || conversation.getImageCount() < 0 || conversation.getImageCount() > 8) {
            throw new IllegalArgumentException("AI 会话参数超出允许范围");
        }
        lock.writeLock().lock();
        try {
            AiConversation target = conversations.stream()
                    .filter(item -> Objects.equals(item.getId(), conversation.getId()))
                    .findFirst().orElse(null);
            if (target == null) {
                throw new IllegalArgumentException("会话不存在");
            }
            if (target.getUserId() != null && conversation.getUserId() != null && !target.getUserId().equals(conversation.getUserId())) {
                throw new SecurityException("无权修改其他用户的会话");
            }
            target.setTitle(conversation.getTitle());
            target.setModelId(conversation.getModelId());
            target.setReasoningDepth(conversation.getReasoningDepth());
            target.setSystemPrompt(conversation.getSystemPrompt());
            target.setTemperature(conversation.getTemperature());
            target.setTopP(conversation.getTopP());
            target.setContextCount(conversation.getContextCount());
            target.setMaxTokens(conversation.getMaxTokens());
            target.setStreamOutput(conversation.isStreamOutput());
            target.setImageSize(conversation.getImageSize());
            target.setImageStyle(conversation.getImageStyle());
            target.setImageCount(conversation.getImageCount());
            target.setVideoDuration(conversation.getVideoDuration());
            target.setVideoSize(conversation.getVideoSize());
            target.setVideoSeed(conversation.getVideoSeed());
            target.setVideoFirstFramePath(conversation.getVideoFirstFramePath());
            target.setVideoLastFramePath(conversation.getVideoLastFramePath());
            target.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyConversation(target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AiMessage addMessage(String conversationId, String role, String type, String content, String filePath,
                                String modelId, int promptTokens, int completionTokens, double weightedTokens) {
        return addMessage(conversationId, role, type, content, null, filePath, modelId, promptTokens, completionTokens, weightedTokens);
    }

    public AiMessage addMessage(String conversationId, String role, String type, String content, String reasoningContent,
                                String filePath, String modelId, int promptTokens, int completionTokens, double weightedTokens) {
        lock.writeLock().lock();
        try {
            AiConversation conversation = conversations.stream()
                    .filter(item -> Objects.equals(item.getId(), conversationId))
                    .findFirst().orElse(null);
            if (conversation == null) {
                throw new IllegalArgumentException("会话不存在");
            }
            AiMessage message = new AiMessage();
            message.setId("aim_" + UUID.randomUUID().toString().replace("-", ""));
            message.setConversationId(conversationId);
            message.setRole(role);
            message.setType(type);
            message.setContent(content);
            message.setReasoningContent(reasoningContent);
            message.setFilePath(filePath);
            message.setModelId(modelId);
            message.setPromptTokens(promptTokens);
            message.setCompletionTokens(completionTokens);
            message.setWeightedTokens(weightedTokens);
            message.setCreatedAt(System.currentTimeMillis());
            messages.add(message);
            conversation.setUpdatedAt(message.getCreatedAt());
            saveUnsafe();
            return copyMessage(message);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AiMessage> listMessages(String conversationId) {
        lock.readLock().lock();
        try {
            return messages.stream()
                    .filter(item -> Objects.equals(item.getConversationId(), conversationId))
                    .sorted(Comparator.comparingLong(AiMessage::getCreatedAt))
                    .map(this::copyMessage)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public AiMediaTask createTask(String userId, String conversationId, String taskType, String modelId,
                                  String prompt, double estimatedTokens) {
        lock.writeLock().lock();
        try {
            AiMediaTask task = new AiMediaTask();
            long now = System.currentTimeMillis();
            task.setId("ait_" + UUID.randomUUID().toString().replace("-", ""));
            task.setUserId(userId);
            task.setConversationId(conversationId);
            task.setTaskType(taskType);
            task.setModelId(modelId);
            task.setPrompt(prompt);
            task.setStatus("queued");
            task.setEstimatedTokens(estimatedTokens);
            task.setCreatedAt(now);
            task.setUpdatedAt(now);
            tasks.add(task);
            saveUnsafe();
            return copyTask(task);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public AiMediaTask updateTask(AiMediaTask task) {
        lock.writeLock().lock();
        try {
            AiMediaTask target = tasks.stream().filter(item -> Objects.equals(item.getId(), task.getId())).findFirst().orElse(null);
            if (target == null) {
                throw new IllegalArgumentException("任务不存在");
            }
            target.setProviderTaskId(task.getProviderTaskId());
            target.setStatus(task.getStatus());
            target.setEstimatedTokens(task.getEstimatedTokens());
            target.setFinalTokens(task.getFinalTokens());
            target.setOutputPath(task.getOutputPath());
            target.setExtraPath(task.getExtraPath());
            target.setUpdatedAt(System.currentTimeMillis());
            saveUnsafe();
            return copyTask(target);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<AiMediaTask> listTasks(String userId) {
        lock.readLock().lock();
        try {
            return tasks.stream()
                    .filter(item -> Objects.equals(item.getUserId(), userId))
                    .sorted(Comparator.comparingLong(AiMediaTask::getUpdatedAt).reversed())
                    .map(this::copyTask)
                    .collect(Collectors.toList());
        } finally {
            lock.readLock().unlock();
        }
    }

    public double getRemainingTokens(User user) {
        if (user == null) return 0D;
        if (SuperAdminService.getInstance().getPrimarySuperAdminId() != null
                && SuperAdminService.getInstance().getPrimarySuperAdminId().equals(user.getUserId())) {
            return Double.MAX_VALUE;
        }
        String day = LocalDate.now(ZONE).toString();
        refreshUserUsageDay(user, day);
        double limit = user.getAiDailyLimitByLevel();
        if (limit == Double.MAX_VALUE) return Double.MAX_VALUE;
        return Math.max(0, limit - user.getAiUsedTokensToday());
    }

    public AiUsageLedger addUsage(User user, String modelId, String usageType, int inputTokens, int outputTokens, double weightedTokens) {
        if (!Double.isFinite(weightedTokens)) {
            throw new IllegalArgumentException("AI 点数必须是有限数字");
        }
        lock.writeLock().lock();
        try {
            String day = LocalDate.now(ZONE).toString();
            refreshUserUsageDay(user, day);
            user.setAiUsedTokensToday(user.getAiUsedTokensToday() + weightedTokens);
            user.setAiUsageDay(day);
            UserService.getInstance().save();

            AiUsageLedger ledger = new AiUsageLedger();
            ledger.setId("aul_" + UUID.randomUUID().toString().replace("-", ""));
            ledger.setUserId(user.getUserId());
            ledger.setDay(day);
            ledger.setModelId(modelId);
            ledger.setUsageType(usageType);
            ledger.setInputTokens(inputTokens);
            ledger.setOutputTokens(outputTokens);
            ledger.setWeightedTokens(weightedTokens);
            ledger.setCreatedAt(System.currentTimeMillis());
            ledgers.add(ledger);
            saveUnsafe();
            return copyLedger(ledger);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void refreshUserUsageDay(User user, String day) {
        if (user == null) {
            return;
        }
        if (!Objects.equals(user.getAiUsageDay(), day)) {
            user.setAiUsageDay(day);
            user.setAiUsedTokensToday(0);
            UserService.getInstance().save();
        }
    }

    private Map<String, Object> model(String id, String label, String type, boolean supportsThinking,
                                      boolean supportsImage, double inputRatio, double outputRatio, String provider) {
        return model(id, label, type, supportsThinking, supportsImage, inputRatio, outputRatio, provider, 1);
    }

    private Map<String, Object> model(String id, String label, String type, boolean supportsThinking,
                                      boolean supportsImage, double inputRatio, double outputRatio, String provider,
                                      int minLevel) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("label", label);
        map.put("type", type);
        map.put("supportsThinking", supportsThinking);
        map.put("supportsImage", supportsImage);
        map.put("inputRatio", inputRatio);
        map.put("outputRatio", outputRatio);
        map.put("provider", provider);
        map.put("minLevel", minLevel);
        return map;
    }

    private String defaultModelForType(String type) {
        if ("image".equals(type)) return "doubao-seedream-5-0-260128";
        if ("video".equals(type)) return "doubao-seedance-1-0-pro-fast-251015";
        return "deepseek-v4-flash";
    }

    private void load() {
        lock.writeLock().lock();
        try {
            conversations.clear();
            messages.clear();
            ledgers.clear();
            tasks.clear();
            loadList(conversationsFile(), new TypeToken<List<AiConversation>>() {}.getType(), conversations);
            loadList(messagesFile(), new TypeToken<List<AiMessage>>() {}.getType(), messages);
            loadList(ledgerFile(), new TypeToken<List<AiUsageLedger>>() {}.getType(), ledgers);
            loadList(tasksFile(), new TypeToken<List<AiMediaTask>>() {}.getType(), tasks);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private <T> void loadList(Path file, Type type, List<T> target) {
        try {
            if (!Files.exists(file)) {
                return;
            }
            String json = Files.readString(file, StandardCharsets.UTF_8).trim();
            if (json.isEmpty()) {
                return;
            }
            List<T> list = JsonUtil.fromJson(json, type);
            if (list != null) {
                target.addAll(list.stream().filter(Objects::nonNull).collect(Collectors.toList()));
            }
        } catch (Exception e) {
            System.err.println("[AiService] 加载失败: " + file.getFileName() + " -> " + e.getMessage());
            // 旧版存档兼容：备份损坏文件
            try {
                Path backup = file.resolveSibling(file.getFileName() + ".bak");
                Files.copy(file, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.err.println("[AiService] 已备份损坏文件到: " + backup);
            } catch (Exception ignored) {}
        }
    }

    private void saveUnsafe() {
        try {
            Files.createDirectories(dataDir());
            com.chat.util.JsonUtil.saveJsonAtomic(conversationsFile(), conversations);
            com.chat.util.JsonUtil.saveJsonAtomic(messagesFile(), messages);
            com.chat.util.JsonUtil.saveJsonAtomic(ledgerFile(), ledgers);
            com.chat.util.JsonUtil.saveJsonAtomic(tasksFile(), tasks);
        } catch (Exception e) {
            System.err.println("[AiService] 保存失败: " + e.getMessage());
        }
    }

    private AiConversation copyConversation(AiConversation source) {
        AiConversation copy = new AiConversation();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setTitle(source.getTitle());
        copy.setType(source.getType());
        copy.setModelId(source.getModelId());
        copy.setReasoningDepth(source.getReasoningDepth());
        copy.setSystemPrompt(source.getSystemPrompt());
        copy.setTemperature(source.getTemperature());
        copy.setTopP(source.getTopP());
        copy.setContextCount(source.getContextCount());
        copy.setMaxTokens(source.getMaxTokens());
        copy.setStreamOutput(source.isStreamOutput());
        copy.setImageSize(source.getImageSize());
        copy.setImageStyle(source.getImageStyle());
        copy.setImageCount(source.getImageCount());
        copy.setVideoDuration(source.getVideoDuration());
        copy.setVideoSize(source.getVideoSize());
        copy.setVideoSeed(source.getVideoSeed());
        copy.setVideoFirstFramePath(source.getVideoFirstFramePath());
        copy.setVideoLastFramePath(source.getVideoLastFramePath());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private AiMessage copyMessage(AiMessage source) {
        AiMessage copy = new AiMessage();
        copy.setId(source.getId());
        copy.setConversationId(source.getConversationId());
        copy.setRole(source.getRole());
        copy.setType(source.getType());
        copy.setContent(source.getContent());
        copy.setReasoningContent(source.getReasoningContent());
        copy.setFilePath(source.getFilePath());
        copy.setModelId(source.getModelId());
        copy.setPromptTokens(source.getPromptTokens());
        copy.setCompletionTokens(source.getCompletionTokens());
        copy.setWeightedTokens(source.getWeightedTokens());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private AiUsageLedger copyLedger(AiUsageLedger source) {
        AiUsageLedger copy = new AiUsageLedger();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setDay(source.getDay());
        copy.setModelId(source.getModelId());
        copy.setUsageType(source.getUsageType());
        copy.setInputTokens(source.getInputTokens());
        copy.setOutputTokens(source.getOutputTokens());
        copy.setWeightedTokens(source.getWeightedTokens());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private AiMediaTask copyTask(AiMediaTask source) {
        AiMediaTask copy = new AiMediaTask();
        copy.setId(source.getId());
        copy.setUserId(source.getUserId());
        copy.setConversationId(source.getConversationId());
        copy.setTaskType(source.getTaskType());
        copy.setProviderTaskId(source.getProviderTaskId());
        copy.setModelId(source.getModelId());
        copy.setPrompt(source.getPrompt());
        copy.setStatus(source.getStatus());
        copy.setEstimatedTokens(source.getEstimatedTokens());
        copy.setFinalTokens(source.getFinalTokens());
        copy.setOutputPath(source.getOutputPath());
        copy.setExtraPath(source.getExtraPath());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
