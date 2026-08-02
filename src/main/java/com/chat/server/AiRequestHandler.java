package com.chat.server;

import com.chat.model.*;
import com.chat.service.*;
import com.chat.util.JsonUtil;
import com.sun.net.httpserver.HttpExchange;

import java.io.*;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

final class AiRequestHandler extends RequestHandlerSupport {
    private static final String LONGCAT_API_URL = "https://api.longcat.chat/openai/v1/chat/completions";
    private static final String LONGCAT_API_KEY = System.getenv("LONGCAT_API_KEY") != null
            ? System.getenv("LONGCAT_API_KEY") : System.getProperty("longcat.api.key", "");
    private static final String VOLC_CHAT_API_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
    private static final String VOLC_IMAGE_API_URL = "https://ark.cn-beijing.volces.com/api/v3/images/generations";
    private static final String VOLC_VIDEO_TASK_API_URL = "https://ark.cn-beijing.volces.com/api/v3/contents/generations/tasks";
    private static final String VOLC_API_KEY = System.getenv("VOLC_API_KEY") != null
            ? System.getenv("VOLC_API_KEY") : System.getProperty("volc.api.key", "");
    private static final long MAX_AI_ATTACHMENT_BYTES = 10L * 1024 * 1024;
    private static final java.net.http.HttpClient SHARED_HTTP_CLIENT = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10))
            .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
            .build();
    private static final java.util.concurrent.ExecutorService TITLE_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "ai-title-generator");
                thread.setDaemon(true);
                return thread;
            });

    void handleGetAiModels(HttpExchange ex, User me) throws IOException { if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; } sendJson(ex, 200, AiService.getInstance().listModels()); }

    void handleGetAiConversations(HttpExchange ex, User me) throws IOException { if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; } sendJson(ex, 200, AiService.getInstance().listConversations(me.getUserId())); }

    void handleGetAiMessages(HttpExchange ex, String query, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        String conversationId = parseQuery(query).get("conversationId");
        if (conversationId == null || conversationId.trim().isEmpty()) {
            sendJson(ex, 400, map("error", "缺少会话ID"));
            return;
        }
        AiConversation conversation = AiService.getInstance()
                .getConversation(me.getUserId(), conversationId.trim());
        if (conversation == null) {
            sendJson(ex, 404, map("error", "会话不存在"));
            return;
        }
        sendJson(ex, 200, AiService.getInstance().listMessages(conversation.getId()));
    }

    void handleGetAiTasks(HttpExchange ex, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        refreshAiProviderTasks(me.getUserId());
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("tasks", AiService.getInstance().listTasks(me.getUserId()));
        res.put("remainingTokens", AiService.getInstance().getRemainingTokens(me) == Double.MAX_VALUE ? -1 : AiService.getInstance().getRemainingTokens(me));
        sendJson(ex, 200, res);
    }

    void handleCreateAiConversation(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        sendJson(ex, 200, AiService.getInstance().createConversation(me.getUserId(), p.get("type"), p.get("modelId")));
    }

    void handleUpdateAiConversation(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        AiConversation conversation = JsonUtil.fromJson(body, AiConversation.class);
        if (conversation == null || conversation.getId() == null) { sendJson(ex, 400, map("error", "参数不完整")); return; }
        AiConversation existing = AiService.getInstance().getConversation(me.getUserId(), conversation.getId());
        if (existing == null) { sendJson(ex, 404, map("error", "会话不存在")); return; }
        if (existing.getUserId() != null && !existing.getUserId().equals(me.getUserId())) {
            sendJson(ex, 403, map("error", "无权修改该会话"));
            return;
        }
        conversation.setUserId(me.getUserId());
        conversation.setTitle(existing.getTitle());
        conversation.setType(existing.getType());
        conversation.setCreatedAt(existing.getCreatedAt());
        try {
            sendJson(ex, 200, AiService.getInstance().updateConversation(conversation));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleDeleteAiConversation(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        Map<String, String> p = parseJson(body);
        String conversationId = p.get("conversationId");
        if (conversationId == null || conversationId.trim().isEmpty()) { sendJson(ex, 400, map("error", "缺少会话ID")); return; }
        boolean deleted = AiService.getInstance().deleteConversation(me.getUserId(), conversationId);
        if (!deleted) { sendJson(ex, 404, map("error", "会话不存在")); return; }
        sendJson(ex, 200, map("success", "true"));
    }

    void handleSendAiPrompt(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        if (me.isFeatureBanned("ai")) { sendJson(ex, 403, map("error", "AI功能已被封禁")); return; }
        try {
            sendJson(ex, 200, processAiPrompt(parseJsonObj(body), me));
        } catch (IllegalArgumentException e) {
            sendJson(ex, 400, map("error", e.getMessage()));
        }
    }

    void handleSendAiPromptStream(HttpExchange ex, String body, User me) throws IOException {
        if (me == null) { sendJson(ex, 401, map("error", "未登录")); return; }
        if (me.isFeatureBanned("ai")) { sendJson(ex, 403, map("error", "AI功能已被封禁")); return; }
        ex.getResponseHeaders().set("Content-Type", "application/x-ndjson; charset=utf-8");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream os = ex.getResponseBody()) {
            try {
            Map<String, Object> payload = parseJsonObj(body);
            String conversationId = asString(payload.get("conversationId"));
            String prompt = asString(payload.get("prompt"));
            if (conversationId.isEmpty() || prompt.isEmpty()) {
                throw new IllegalArgumentException("参数不完整");
            }
            AiConversation conversation = AiService.getInstance().getConversation(me.getUserId(), conversationId);
            if (conversation == null) {
                throw new IllegalArgumentException("会话不存在");
            }
            if (!"chat".equals(conversation.getType())) {
                Map<String, Object> result = processAiPrompt(payload, me);
                writeJsonLine(os, obj(
                        "type", "done",
                        "message", result.get("message"),
                        "task", result.get("task"),
                        "remainingTokens", result.get("remainingTokens")
                ));
                return;
            }
            String modelId = asString(payload.get("modelId"));
            if (modelId.isEmpty()) modelId = conversation.getModelId();
            final String resolvedModelId = modelId;
            List<Map<String, Object>> attachments = asObjectList(payload.get("attachments"));
            validateAiAttachments(attachments, me);
            AiService.getInstance().addMessage(conversationId, "user", "text", prompt, null, resolvedModelId, 0, 0, 0);
            for (Map<String, Object> attachment : attachments) {
                String filePath = asString(attachment.get("filePath"));
                if (filePath.isEmpty()) continue;
                String type = asString(attachment.get("type"));
                String content = asString(attachment.get("name"));
                AiService.getInstance().addMessage(conversationId, "user",
                        type.startsWith("image") ? "image" : "file",
                        content.isEmpty() ? "附件" : content, filePath, resolvedModelId, 0, 0, 0);
            }
            List<AiMessage> history = AiService.getInstance().listMessages(conversationId);
            history = stripCurrentAiPrompt(history, attachments.size());
            List<AiMessage> contextMessages = pickAiContextMessages(history, conversation);
            Map<String, Object> model = AiService.getInstance().listModels().stream()
                    .filter(item -> Objects.equals(String.valueOf(item.get("id")), resolvedModelId))
                    .findFirst().orElse(null);
            int promptTokens = estimateConversationTokens(conversation, contextMessages)
                    + attachments.stream().mapToInt(item -> 256).sum();
            int completionTokens = Math.max(8, Math.min(512, promptTokens / 2 + 24));
            double weightedTokens = buildWeightedTokens(model, promptTokens, completionTokens);
            double ar = AiService.getInstance().getRemainingTokens(me);
            if (ar < weightedTokens) {
                throw new IllegalArgumentException("今日 AI 额度已用尽");
            }
            if (weightedTokens > ar * 0.6) {
                throw new IllegalArgumentException("预估 token 消耗超过剩余额度 60%，已取消生成");
            }
            Map<String, Object> streamed = streamChatCompletion(os, resolvedModelId, conversation, prompt, attachments, contextMessages);
            String content = asString(streamed.get("content"));
            String reasoningContent = asString(streamed.get("reasoningContent"));
            Map<String, Object> usage = asObjectMap(streamed.get("usage"));
            promptTokens = parseInt(asString(usage.get("prompt_tokens")), promptTokens);
            completionTokens = parseInt(asString(usage.get("completion_tokens")), Math.max(16, estimateTokens(content)));
            weightedTokens = buildWeightedTokens(model, promptTokens, completionTokens);
            AiService.getInstance().addUsage(me, resolvedModelId, "chat", promptTokens, completionTokens, weightedTokens);
            AiMessage message = AiService.getInstance().addMessage(conversationId, "assistant", "text",
                    content, reasoningContent.isEmpty() ? null : reasoningContent, null, resolvedModelId, promptTokens, completionTokens, weightedTokens);
            if ("新标签".equals(conversation.getTitle())) {
                conversation.setTitle(generateAiConversationTitle(prompt, content));
            }
            conversation.setModelId(resolvedModelId);
            AiService.getInstance().updateConversation(conversation);
            Map<String, Object> doneMsg = new LinkedHashMap<>();
            doneMsg.put("id", message.getId());
            doneMsg.put("content", message.getContent());
            doneMsg.put("reasoningContent", message.getReasoningContent());
            doneMsg.put("createdAt", message.getCreatedAt());
            doneMsg.put("weightedTokens", message.getWeightedTokens());
            writeJsonLine(os, obj(
                    "type", "done",
                    "message", doneMsg,
                    "remainingTokens", AiService.getInstance().getRemainingTokens(me) == Double.MAX_VALUE
                            ? -1
                            : AiService.getInstance().getRemainingTokens(me)
            ));
            } catch (IllegalArgumentException e) {
                writeJsonLine(os, obj("type", "error", "error", e.getMessage()));
            }
        }
    }

    private int estimateTokens(String text) {
        return Math.max(1, (String.valueOf(text).length() + 3) / 4);
    }

    private void validateAiAttachments(List<Map<String, Object>> attachments, User user) {
        if (attachments == null) return;
        for (Map<String, Object> attachment : attachments) {
            validateAiLocalPath(asString(attachment.get("filePath")), user);
        }
    }

    private void validateAiLocalPath(String filePath, User user) {
        String path = asString(filePath);
        if (path.isEmpty() || path.startsWith("http://") || path.startsWith("https://") || path.startsWith("data:")) {
            return;
        }
        StoredFileMetadata metadata = metadataFromPath(path, user);
        if (metadata == null) throw new IllegalArgumentException("AI 附件不存在或无权访问");
        if (metadata.getSize() > MAX_AI_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("AI 附件不能超过10MB");
        }
    }

    private double buildWeightedTokens(Map<String, Object> model, int promptTokens, int completionTokens) {
        double in = parseDouble(String.valueOf(model != null ? model.get("inputRatio") : 1), 1);
        double out = parseDouble(String.valueOf(model != null ? model.get("outputRatio") : 1), 1);
        return promptTokens * in + completionTokens * out;
    }

    private double estimateVideoWeightedTokens(Map<String, Object> model, double durationSec) {
        double out = parseDouble(String.valueOf(model != null ? model.get("outputRatio") : 8), 8);
        return Math.max(2, durationSec) * out * 1000D;
    }

    private Map<String, Object> processAiPrompt(Map<String, Object> payload, User me) throws IOException {
        String conversationId = asString(payload.get("conversationId"));
        String prompt = asString(payload.get("prompt"));
        if (conversationId.isEmpty() || prompt.isEmpty()) {
            throw new IllegalArgumentException("参数不完整");
        }
        AiConversation conversation = AiService.getInstance().getConversation(me.getUserId(), conversationId);
        if (conversation == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        String modelId = asString(payload.get("modelId"));
        if (modelId.isEmpty()) {
            modelId = conversation.getModelId();
        }
        List<Map<String, Object>> attachments = asObjectList(payload.get("attachments"));
        validateAiAttachments(attachments, me);
        AiService.getInstance().addMessage(conversationId, "user", "text", prompt, null, modelId, 0, 0, 0);
        for (Map<String, Object> attachment : attachments) {
            String filePath = asString(attachment.get("filePath"));
            if (filePath.isEmpty()) continue;
            String type = asString(attachment.get("type"));
            String content = asString(attachment.get("name"));
            AiService.getInstance().addMessage(conversationId, "user",
                    type.startsWith("image") ? "image" : "file",
                    content.isEmpty() ? "附件" : content, filePath, modelId, 0, 0, 0);
        }
        final String resolvedModelId = modelId;
        Map<String, Object> model = AiService.getInstance().listModels().stream()
                .filter(item -> Objects.equals(String.valueOf(item.get("id")), resolvedModelId))
                .findFirst().orElse(null);
        String type = conversation.getType() != null ? conversation.getType() : "chat";
        Map<String, Object> res = new LinkedHashMap<>();
        if ("image".equals(type)) {
            Map<String, Object> imageOptions = asObjectMap(payload.get("imageOptions"));
            int imageCount = Math.max(1, parseInt(asString(imageOptions.getOrDefault("count", conversation.getImageCount())), 1));
            String imageSize = asString(imageOptions.getOrDefault("size", conversation.getImageSize()));
            if (imageSize == null || imageSize.isEmpty()) imageSize = "1920x1920";
            String[] parts = imageSize.split("x");
            if (parts.length == 2) {
                try {
                    int w = Integer.parseInt(parts[0].trim()), h = Integer.parseInt(parts[1].trim());
                    if (w * h < 3686400) imageSize = "1920x1920";
                } catch (NumberFormatException ignored) { imageSize = "1920x1920"; }
            } else {
                imageSize = "1920x1920";
            }
            String imageStyle = asString(imageOptions.getOrDefault("style", conversation.getImageStyle()));
            double weightedTokens = estimateImageWeightedTokens(modelId, imageSize, imageCount);
            double ar = AiService.getInstance().getRemainingTokens(me);
            if (ar < weightedTokens) {
                throw new IllegalArgumentException("\u9884\u4F30\u70B9\u6570\u4E0D\u8DB3\uFF0C\u65E0\u6CD5\u751F\u6210\u3002\u9700\u8981 " + Math.round(weightedTokens) + " \u70B9\uFF0C\u5269\u4F59 " + (ar <= 0 ? 0 : Math.round(ar)) + " \u70B9\u3002");
            }
            if (weightedTokens > ar * 0.6) {
                throw new IllegalArgumentException("\u9884\u4F30\u70B9\u6570\u4E0D\u8DB3\uFF0C\u65E0\u6CD5\u751F\u6210\u3002\u9700\u8981 " + Math.round(weightedTokens) + " \u70B9\uFF0C\u5269\u4F59 " + Math.round(ar) + " \u70B9\u3002");
            }
            AiMediaTask task = AiService.getInstance().createTask(me.getUserId(), conversationId, "image", modelId, prompt, weightedTokens);
            task.setStatus("running");
            AiService.getInstance().updateTask(task);
            Map<String, Object> imageResult = generateImageWithProvider(modelId, prompt, imageSize, imageStyle, imageCount, attachments, me.getUserId());
            String outputPath = asString(imageResult.get("url"));
            Map<String, Object> usage = asObjectMap(imageResult.get("usage"));
            task.setStatus("done");
            task.setOutputPath(outputPath);
            task.setFinalTokens(parseDouble(asString(usage.get("total_tokens")), weightedTokens));
            AiService.getInstance().updateTask(task);
            AiService.getInstance().addUsage(me, modelId, "image", 0, imageCount, task.getFinalTokens());
            AiMessage assistant = AiService.getInstance().addMessage(conversationId, "assistant", "image",
                    "图片生成完成\n尺寸：" + (imageSize.isEmpty() ? "默认" : imageSize) + "\n风格：" + (imageStyle.isEmpty() ? "通用" : imageStyle),
                    outputPath, modelId, 0, imageCount, task.getFinalTokens());
            res.put("message", assistant);
            res.put("task", task);
        } else if ("video".equals(type)) {
            Map<String, Object> videoOptions = asObjectMap(payload.get("videoOptions"));
            double duration = Math.max(1, parseDouble(asString(videoOptions.getOrDefault("duration", conversation.getVideoDuration())), 5));
            String size = asString(videoOptions.getOrDefault("size", conversation.getVideoSize()));
            long seed = parseLong(asString(videoOptions.getOrDefault("seed", conversation.getVideoSeed())), 0);
            String firstFramePath = asString(videoOptions.getOrDefault("firstFramePath", conversation.getVideoFirstFramePath()));
            String lastFramePath = asString(videoOptions.getOrDefault("lastFramePath", conversation.getVideoLastFramePath()));
            validateAiLocalPath(firstFramePath, me);
            validateAiLocalPath(lastFramePath, me);
            double weightedTokens = estimateVideoWeightedTokens(model, duration);
            double ar = AiService.getInstance().getRemainingTokens(me);
            if (ar < weightedTokens) {
                throw new IllegalArgumentException("今日 AI 额度已用尽");
            }
            if (weightedTokens > ar * 0.6) {
                throw new IllegalArgumentException("预估 token 消耗超过剩余额度 60%，已取消生成");
            }
            AiMediaTask task = AiService.getInstance().createTask(me.getUserId(), conversationId, "video", modelId, prompt, weightedTokens);
            Map<String, Object> remoteTask = createVideoTaskWithProvider(modelId, prompt, duration, size, seed,
                    firstFramePath, lastFramePath, me.getUserId());
            task.setProviderTaskId(asString(remoteTask.get("id")));
            task.setStatus(asString(remoteTask.get("status")).isEmpty() ? "queued" : asString(remoteTask.get("status")));
            task.setExtraPath(lastFramePath.isEmpty() ? firstFramePath : lastFramePath);
            task.setFinalTokens(parseDouble(asString(asObjectMap(remoteTask.get("usage")).get("total_tokens")), weightedTokens));
            AiService.getInstance().updateTask(task);
            AiService.getInstance().addUsage(me, modelId, "video", 0, 1, task.getFinalTokens());
            String summary = "视频任务已创建\n时长：" + duration + " 秒\n尺寸：" + (size.isEmpty() ? "默认" : size)
                    + "\n随机种子：" + seed
                    + (firstFramePath.isEmpty() ? "" : "\n首帧：已上传")
                    + (lastFramePath.isEmpty() ? "" : "\n尾帧：已上传");
            AiMessage assistant = AiService.getInstance().addMessage(conversationId, "assistant", "text",
                    summary, null, modelId, 0, 1, task.getFinalTokens());
            res.put("message", assistant);
            res.put("task", task);
        } else {
            List<AiMessage> history = AiService.getInstance().listMessages(conversationId);
            history = stripCurrentAiPrompt(history, attachments.size());
            List<AiMessage> contextMessages = pickAiContextMessages(history, conversation);
            int promptTokens = estimateConversationTokens(conversation, contextMessages)
                    + attachments.stream().mapToInt(item -> 256).sum();
            int completionTokens = Math.max(8, Math.min(512, promptTokens / 2 + 24));
            double weightedTokens = buildWeightedTokens(model, promptTokens, completionTokens);
            double ar = AiService.getInstance().getRemainingTokens(me);
            if (ar < weightedTokens) {
                throw new IllegalArgumentException("今日 AI 额度已用尽");
            }
            if (weightedTokens > ar * 0.6) {
                throw new IllegalArgumentException("预估 token 消耗超过剩余额度 60%，已取消生成");
            }
            Map<String, Object> completion = generateChatCompletion(modelId, conversation, prompt, attachments, contextMessages);
            String content = asString(completion.get("content"));
            Map<String, Object> usage = asObjectMap(completion.get("usage"));
            promptTokens = parseInt(asString(usage.get("prompt_tokens")), promptTokens);
            completionTokens = parseInt(asString(usage.get("completion_tokens")), completionTokens);
            weightedTokens = buildWeightedTokens(model, promptTokens, completionTokens);
            AiService.getInstance().addUsage(me, modelId, "chat", promptTokens, completionTokens, weightedTokens);
            AiMessage assistant = AiService.getInstance().addMessage(conversationId, "assistant", "text",
                    content, null, modelId, promptTokens, completionTokens, weightedTokens);
            if ("新标签".equals(conversation.getTitle())) {
                conversation.setTitle(generateAiConversationTitle(prompt, content));
            }
            conversation.setModelId(modelId);
            AiService.getInstance().updateConversation(conversation);
            res.put("message", assistant);
        }
        res.put("remainingTokens", AiService.getInstance().getRemainingTokens(me) == Double.MAX_VALUE ? -1 : AiService.getInstance().getRemainingTokens(me));
        return res;
    }

    private List<AiMessage> stripCurrentAiPrompt(List<AiMessage> history, int attachmentCount) {
        if (history == null || history.isEmpty()) return Collections.emptyList();
        int trim = Math.max(1, 1 + Math.max(0, attachmentCount));
        if (history.size() <= trim) return Collections.emptyList();
        return new ArrayList<>(history.subList(0, history.size() - trim));
    }

    private List<AiMessage> pickAiContextMessages(List<AiMessage> history, AiConversation conversation) {
        int maxItems = Math.max(1, conversation != null ? conversation.getContextCount() : 10);
        int tokenBudget = 4000;
        List<AiMessage> reversed = new ArrayList<>(history == null ? Collections.emptyList() : history);
        Collections.reverse(reversed);
        List<AiMessage> selected = new ArrayList<>();
        int used = estimateTokens(conversation != null ? conversation.getSystemPrompt() : "");
        for (AiMessage message : reversed) {
            int cost = estimateTokens(message.getContent()) + ("image".equals(message.getType()) ? 256 : 0);
            if (!selected.isEmpty() && (selected.size() >= maxItems || used + cost > tokenBudget)) {
                continue;
            }
            selected.add(message);
            used += cost;
        }
        Collections.reverse(selected);
        return selected;
    }

    private int estimateConversationTokens(AiConversation conversation, List<AiMessage> messages) {
        int tokens = estimateTokens(conversation != null ? conversation.getSystemPrompt() : "");
        for (AiMessage message : messages) {
            tokens += estimateTokens(message.getContent());
            if ("image".equals(message.getType())) tokens += 256;
        }
        return Math.max(tokens, 1);
    }

    private List<String> splitAiContent(String text, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        String value = String.valueOf(text == null ? "" : text);
        if (value.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        for (int i = 0; i < value.length(); i += Math.max(1, chunkSize)) {
            chunks.add(value.substring(i, Math.min(value.length(), i + Math.max(1, chunkSize))));
        }
        return chunks;
    }

    private double estimateImageWeightedTokens(String modelId, String size, int imageCount) {
        if (modelId.contains("3-0")) return 80000D * imageCount;
        if (modelId.contains("4-0")) return 60000D * imageCount;
        if (modelId.contains("4-5")) return 80000D * imageCount;
        if (modelId.contains("5-0")) return 74000D * imageCount;
        return 80000D * imageCount;
    }

    private Map<String, Object> generateChatCompletion(String modelId, AiConversation conversation, String prompt,
                                                       List<Map<String, Object>> attachments, List<AiMessage> contextMessages) {
        try {
            if (modelId.startsWith("LongCat")) {
                return requestLongcatChat(modelId, conversation, prompt, contextMessages);
            }
            return requestVolcChat(modelId, conversation, prompt, attachments, contextMessages);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("content", buildFallbackAiReply(prompt, conversation, attachments, contextMessages));
            fallback.put("usage", obj(
                    "prompt_tokens", estimateConversationTokens(conversation, contextMessages),
                    "completion_tokens", Math.max(16, estimateTokens(prompt) / 2)
            ));
            return fallback;
        }
    }

    private Map<String, Object> streamChatCompletion(OutputStream os, String modelId, AiConversation conversation,
                                                     String prompt, List<Map<String, Object>> attachments,
                                                     List<AiMessage> contextMessages) {
        try {
            String apiUrl = modelId.startsWith("LongCat") ? LONGCAT_API_URL : VOLC_CHAT_API_URL;
            String apiKey = modelId.startsWith("LongCat") ? LONGCAT_API_KEY : VOLC_API_KEY;
            Map<String, Object> body = modelId.startsWith("LongCat")
                    ? buildLongcatChatBody(modelId, conversation, prompt, contextMessages, true)
                    : buildVolcChatBody(modelId, conversation, prompt, attachments, contextMessages, true);
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(apiUrl))
                    .timeout(java.time.Duration.ofMinutes(2))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body), StandardCharsets.UTF_8))
                    .build();
            java.net.http.HttpResponse<InputStream> response = SHARED_HTTP_CLIENT
                    .send(request, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readNBytes(1024 * 1024), StandardCharsets.UTF_8);
                Map<String, Object> errorData = parseJsonObj(errorBody);
                throw new IllegalArgumentException(asString(asObjectMap(errorData.get("error")).get("message")).isEmpty()
                        ? asString(errorData.get("message"))
                        : asString(asObjectMap(errorData.get("error")).get("message")));
            }
            StringBuilder content = new StringBuilder();
            StringBuilder reasoningContent = new StringBuilder();
            Map<String, Object> usage = new LinkedHashMap<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("data:")) continue;
                    String data = trimmed.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;
                    Map<String, Object> event = parseJsonObj(data);
                    if (!asObjectMap(event.get("usage")).isEmpty()) {
                        usage = asObjectMap(event.get("usage"));
                    }
                    List<Map<String, Object>> choices = asObjectList(event.get("choices"));
                    if (choices.isEmpty()) continue;
                    Map<String, Object> delta = asObjectMap(choices.get(0).get("delta"));
                    String reasoningChunk = asString(delta.get("reasoning_content"));
                    if (!reasoningChunk.isEmpty()) {
                        reasoningContent.append(reasoningChunk);
                        writeJsonLine(os, obj("type", "delta", "reasoning_content", reasoningChunk));
                    }
                    String chunk = asString(delta.get("content"));
                    if (!chunk.isEmpty()) {
                        for (String piece : splitAiContent(chunk, chunk.length() > 24 ? 2 : 1)) {
                            content.append(piece);
                            writeJsonLine(os, obj("type", "delta", "content", piece));
                        }
                    }
                }
            }
            if (usage.isEmpty()) {
                usage.put("prompt_tokens", estimateConversationTokens(conversation, contextMessages)
                        + attachments.stream().mapToInt(item -> 256).sum());
                usage.put("completion_tokens", Math.max(16, estimateTokens(content.toString())));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content.toString());
            result.put("reasoningContent", reasoningContent.toString());
            result.put("usage", usage);
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Map<String, Object> fallback = generateChatCompletion(modelId, conversation, prompt, attachments, contextMessages);
            for (String chunk : splitAiContent(asString(fallback.get("content")), 48)) {
                try {
                    writeJsonLine(os, obj("type", "delta", "content", chunk));
                } catch (IOException ignored) {
                }
            }
            return fallback;
        }
    }

    private Map<String, Object> requestLongcatChat(String modelId, AiConversation conversation, String prompt,
                                                   List<AiMessage> contextMessages) throws Exception {
        Map<String, Object> body = buildLongcatChatBody(modelId, conversation, prompt, contextMessages, false);
        Map<String, Object> resp = postJson(LONGCAT_API_URL, LONGCAT_API_KEY, body);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> choices = asObjectList(resp.get("choices"));
        String content = "";
        if (!choices.isEmpty()) {
            content = asString(asObjectMap(choices.get(0).get("message")).get("content"));
        }
        result.put("content", content);
        result.put("usage", asObjectMap(resp.get("usage")));
        return result;
    }

    private Map<String, Object> requestVolcChat(String modelId, AiConversation conversation, String prompt,
                                                List<Map<String, Object>> attachments, List<AiMessage> contextMessages) throws Exception {
        Map<String, Object> body = buildVolcChatBody(modelId, conversation, prompt, attachments, contextMessages, false);
        Map<String, Object> resp = postJson(VOLC_CHAT_API_URL, VOLC_API_KEY, body);
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> choices = asObjectList(resp.get("choices"));
        String content = "";
        if (!choices.isEmpty()) {
            content = asString(asObjectMap(choices.get(0).get("message")).get("content"));
        }
        result.put("content", content);
        result.put("usage", asObjectMap(resp.get("usage")));
        return result;
    }

    private Map<String, Object> buildLongcatChatBody(String modelId, AiConversation conversation, String prompt,
                                                     List<AiMessage> contextMessages, boolean stream) {
        List<Map<String, Object>> messages = new ArrayList<>();
        if (conversation != null && conversation.getSystemPrompt() != null && !conversation.getSystemPrompt().trim().isEmpty()) {
            messages.add(obj("role", "system", "content", conversation.getSystemPrompt().trim()));
        }
        for (AiMessage item : contextMessages) {
            if (!"text".equals(item.getType())) continue;
            String msgContent = item.getContent();
            if (msgContent == null || msgContent.trim().isEmpty()) continue;
            messages.add(obj("role", item.getRole(), "content", msgContent));
        }
        messages.add(obj("role", "user", "content", prompt));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("messages", messages);
        body.put("stream", stream);
        if (conversation != null && conversation.getMaxTokens() > 0) body.put("max_tokens", conversation.getMaxTokens());
        if (conversation != null) {
            body.put("temperature", conversation.getTemperature());
            body.put("top_p", conversation.getTopP());
        }
        return body;
    }

    private Map<String, Object> buildVolcChatBody(String modelId, AiConversation conversation, String prompt,
                                                  List<Map<String, Object>> attachments, List<AiMessage> contextMessages,
                                                  boolean stream) {
        // 检查模型是否支持图片（纯文本模型如DeepSeek/GLM需要content为string格式）
        Map<String, Object> modelInfo = AiService.getInstance().listModels().stream()
                .filter(m -> Objects.equals(String.valueOf(m.get("id")), modelId))
                .findFirst().orElse(null);
        boolean supportsImage = modelInfo != null && Boolean.TRUE.equals(modelInfo.get("supportsImage"));

        List<Map<String, Object>> messages = new ArrayList<>();
        if (conversation != null && conversation.getSystemPrompt() != null && !conversation.getSystemPrompt().trim().isEmpty()) {
            messages.add(obj("role", "system", "content", conversation.getSystemPrompt().trim()));
        }
        for (AiMessage item : contextMessages) {
            if ("assistant".equals(item.getRole()) && "text".equals(item.getType())) {
                String msgContent = item.getContent();
                if (msgContent == null || msgContent.trim().isEmpty()) continue;
                messages.add(obj("role", "assistant", "content", msgContent));
            } else if ("user".equals(item.getRole())) {
                if (!supportsImage) {
                    // 纯文本模型：content 使用字符串格式
                    String text = "";
                    if (item.getContent() != null && !item.getContent().trim().isEmpty()) {
                        text = item.getContent();
                    }
                    if (!text.isEmpty()) {
                        messages.add(obj("role", "user", "content", text));
                    }
                } else {
                    // 多模态模型：content 使用数组格式
                    List<Map<String, Object>> content = new ArrayList<>();
                    if ("image".equals(item.getType()) && item.getFilePath() != null) {
                        content.add(obj("type", "image_url",
                                "image_url", obj("url", absoluteUrl(item.getFilePath(), conversation.getUserId()), "detail", "high")));
                        if (item.getContent() != null && !item.getContent().trim().isEmpty()) {
                            content.add(obj("type", "text", "text", item.getContent()));
                        }
                    } else if (item.getContent() != null && !item.getContent().trim().isEmpty()) {
                        content.add(obj("type", "text", "text", item.getContent()));
                    }
                    if (!content.isEmpty()) {
                        messages.add(obj("role", "user", "content", content));
                    }
                }
            }
        }

        if (!supportsImage && (attachments == null || attachments.isEmpty())) {
            // 纯文本模型：当前 prompt 直接使用字符串
            if (prompt != null && !prompt.trim().isEmpty()) {
                messages.add(obj("role", "user", "content", prompt));
            }
        } else {
            // 多模态模型或有附件：使用数组格式
            List<Map<String, Object>> userContent = new ArrayList<>();
            if (attachments != null) {
                for (Map<String, Object> attachment : attachments) {
                    String filePath = asString(attachment.get("filePath"));
                    if (filePath.isEmpty()) continue;
                    if (supportsImage) {
                        userContent.add(obj("type", "image_url",
                                "image_url", obj("url", absoluteUrl(filePath, conversation.getUserId()), "detail", "high")));
                    }
                }
            }
            if (prompt != null && !prompt.trim().isEmpty()) {
                userContent.add(obj("type", "text", "text", prompt));
            }
            if (!userContent.isEmpty()) {
                messages.add(obj("role", "user", "content", userContent));
            }
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("messages", messages);
        body.put("stream", stream);
        Map<String, Object> streamOpts = new LinkedHashMap<>();
        streamOpts.put("include_usage", true);
        body.put("stream_options", streamOpts);

        // 纯文本模型(DeepSeek)使用 max_tokens，多模态模型使用 max_completion_tokens
        if (conversation != null && conversation.getMaxTokens() > 0) {
            if (!supportsImage && (modelId.startsWith("deepseek") || modelId.startsWith("glm"))) {
                body.put("max_tokens", conversation.getMaxTokens());
            } else {
                body.put("max_completion_tokens", conversation.getMaxTokens());
            }
        }

        // 推理深度
        boolean supportsThinking = modelInfo != null && Boolean.TRUE.equals(modelInfo.get("supportsThinking"));
        if (supportsThinking && conversation != null && conversation.getReasoningDepth() != null && !"default".equals(conversation.getReasoningDepth())) {
            if (modelId.startsWith("glm-")) {
                Map<String, Object> thinking = new LinkedHashMap<>();
                if ("minimal".equals(conversation.getReasoningDepth())) {
                    thinking.put("type", "disabled");
                } else {
                    thinking.put("type", "enabled");
                }
                body.put("thinking", thinking);
            } else if (!modelId.startsWith("deepseek-r1")) {
                // DeepSeek-R1 使用自有机制，不发送 reasoning_effort
                body.put("reasoning_effort", conversation.getReasoningDepth());
            }
        }
        return body;
    }

    private Map<String, Object> generateImageWithProvider(String modelId, String prompt, String size, String style,
                                                          int imageCount, List<Map<String, Object>> attachments, String userId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("prompt", prompt);
        if (!size.isEmpty()) body.put("size", size);
        if (!style.isEmpty()) body.put("watermark", false);
        if (imageCount > 1) body.put("sequential_image_generation", "auto");
        List<String> images = attachments.stream().map(item -> absoluteUrl(asString(item.get("filePath")), userId))
                .filter(item -> item != null && !item.isEmpty()).collect(Collectors.toList());
        if (!images.isEmpty()) body.put("image", images.size() == 1 ? images.get(0) : images);
        try {
            Map<String, Object> resp = postJson(VOLC_IMAGE_API_URL, VOLC_API_KEY, body);
            List<Map<String, Object>> data = asObjectList(resp.get("data"));
            String url;
            if (data.isEmpty()) {
                url = createAiPlaceholderAsset("image", prompt, "AI 图片占位图", userId);
            } else {
                String cdnUrl = asString(data.get(0).get("url"));
                url = cdnUrl; // fallback
                try {
                    java.net.http.HttpRequest dlReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(cdnUrl))
                            .timeout(java.time.Duration.ofMinutes(2)).GET().build();
                    java.net.http.HttpResponse<java.io.InputStream> dlResp = SHARED_HTTP_CLIENT
                            .send(dlReq, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                    if (dlResp.statusCode() >= 200 && dlResp.statusCode() < 300) {
                        String ext = cdnUrl.contains(".png") ? ".png" : ".jpg";
                        StoredFileMetadata meta = FileStore.getInstance().store(
                                dlResp.body(), "ai_image_" + System.currentTimeMillis() + ext,
                                "image/" + (ext.equals(".png") ? "png" : "jpeg"), userId);
                        url = "/files/" + meta.getStoredName();
                    }
                } catch (Exception dlErr) {
                    System.err.println("[AI] 图片下载到本地失败: " + dlErr.getMessage());
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("url", url);
            result.put("usage", asObjectMap(resp.get("usage")));
            return result;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private Map<String, Object> createVideoTaskWithProvider(String modelId, String prompt, double duration, String size,
                                                            long seed, String firstFramePath, String lastFramePath,
                                                            String userId) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", modelId);
        body.put("content", buildVideoContents(prompt, firstFramePath, lastFramePath, userId));
        body.put("duration", (int) Math.round(duration));
        body.put("seed", seed <= 0 ? -1 : seed);
        if (!size.isEmpty()) body.put("resolution", size.toLowerCase(Locale.ROOT));
        body.put("watermark", false);
        try {
            return postJson(VOLC_VIDEO_TASK_API_URL, VOLC_API_KEY, body);
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    private List<Map<String, Object>> buildVideoContents(String prompt, String firstFramePath, String lastFramePath,
                                                         String userId) {
        List<Map<String, Object>> content = new ArrayList<>();
        if (prompt != null && !prompt.trim().isEmpty()) {
            content.add(obj("type", "text", "text", prompt.trim()));
        }
        if (firstFramePath != null && !firstFramePath.trim().isEmpty()) {
            content.add(obj(
                    "type", "image_url",
                    "role", "first_frame",
                    "image_url", obj("url", absoluteUrl(firstFramePath, userId))
            ));
        }
        if (lastFramePath != null && !lastFramePath.trim().isEmpty()) {
            content.add(obj(
                    "type", "image_url",
                    "role", "last_frame",
                    "image_url", obj("url", absoluteUrl(lastFramePath, userId))
            ));
        }
        return content;
    }

    private Map<String, Object> getJson(String url, String apiKey) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        java.net.http.HttpResponse<String> response = SHARED_HTTP_CLIENT
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> data = parseJsonObj(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException(asString(asObjectMap(data.get("error")).get("message")));
        }
        return data;
    }

    private void refreshAiProviderTasks(String userId) {
        for (AiMediaTask task : AiService.getInstance().listTasks(userId)) {
            if (!"video".equals(task.getTaskType()) || task.getProviderTaskId() == null || task.getProviderTaskId().trim().isEmpty()) continue;
            if ("done".equals(task.getStatus()) || "succeeded".equals(task.getStatus())
                    || "failed".equals(task.getStatus()) || "cancelled".equals(task.getStatus())
                    || "expired".equals(task.getStatus())) continue;
            try {
                Map<String, Object> remote = getJson(VOLC_VIDEO_TASK_API_URL + "/" + URLEncoder.encode(task.getProviderTaskId(), StandardCharsets.UTF_8), VOLC_API_KEY);
                String status = asString(remote.get("status"));
                if (!status.isEmpty()) task.setStatus(status);
                Map<String, Object> usage = asObjectMap(remote.get("usage"));
                double oldFinalTokens = task.getFinalTokens();
                double newFinalTokens = !usage.isEmpty()
                        ? parseDouble(asString(usage.get("total_tokens")), oldFinalTokens)
                        : oldFinalTokens;
                task.setFinalTokens(newFinalTokens);
                Map<String, Object> output = asObjectMap(remote.get("content"));
                if (output.isEmpty()) output = asObjectMap(remote.get("output"));
                String videoUrl = asString(output.get("video_url"));
                if (videoUrl.isEmpty()) videoUrl = asString(output.get("url"));
                if (!videoUrl.isEmpty()) task.setOutputPath(videoUrl);
                if (Math.abs(newFinalTokens - oldFinalTokens) > 0.0001D) {
                    User taskOwner = UserService.getInstance().getByUserId(userId);
                    if (taskOwner != null) {
                        AiService.getInstance().addUsage(taskOwner, task.getModelId(), "video-finalize", 0, 0, newFinalTokens - oldFinalTokens);
                    }
                }
                if (("succeeded".equals(task.getStatus()) || "done".equals(task.getStatus()))
                        && task.getOutputPath() != null && !task.getOutputPath().trim().isEmpty()) {
                    // Auto-download generated video to local storage (7-day expiry workaround)
                    String op = task.getOutputPath();
                    if (op.startsWith("http") && !op.contains("/files/")) {
                        try {
                            java.net.http.HttpRequest dlReq = java.net.http.HttpRequest.newBuilder()
                                    .uri(java.net.URI.create(op))
                                    .timeout(java.time.Duration.ofMinutes(5)).GET().build();
                            java.net.http.HttpResponse<java.io.InputStream> dlResp = SHARED_HTTP_CLIENT
                                    .send(dlReq, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                            if (dlResp.statusCode() >= 200 && dlResp.statusCode() < 300) {
                                StoredFileMetadata meta = FileStore.getInstance().store(
                                        dlResp.body(), "video_" + task.getId() + ".mp4", "video/mp4", userId);
                                task.setOutputPath("/files/" + meta.getStoredName());
                            }
                        } catch (Exception ignored) {}
                    }
                    List<AiMessage> existing = AiService.getInstance().listMessages(task.getConversationId());
                    boolean hasVideoMessage = existing.stream().anyMatch(item ->
                            "assistant".equals(item.getRole())
                                    && "video".equals(item.getType())
                                    && Objects.equals(item.getFilePath(), task.getOutputPath()));
                    if (!hasVideoMessage) {
                        AiService.getInstance().addMessage(task.getConversationId(), "assistant", "video",
                                "视频生成完成", task.getOutputPath(), task.getModelId(), 0, 1, task.getFinalTokens());
                    }
                }
                AiService.getInstance().updateTask(task);
            } catch (Exception ignored) {
            }
        }
    }

    private String absoluteUrl(String filePath, String userId) {
        String path = asString(filePath);
        if (path.startsWith("http://") || path.startsWith("https://") || path.startsWith("data:")) return path;
        User owner = UserService.getInstance().getByUserId(userId);
        StoredFileMetadata metadata = metadataFromPath(path, owner);
        if (metadata == null) throw new IllegalArgumentException("AI 附件不存在或无权访问");
        if (metadata.getSize() > MAX_AI_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException("AI 附件不能超过10MB");
        }
        try (InputStream in = FileStore.getInstance().openStream(metadata.getStoredName())) {
            byte[] bytes = in.readNBytes((int) MAX_AI_ATTACHMENT_BYTES + 1);
            if (bytes.length > MAX_AI_ATTACHMENT_BYTES) {
                throw new IllegalArgumentException("AI 附件不能超过10MB");
            }
            String base64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + (metadata.getContentType() != null ? metadata.getContentType() : "application/octet-stream")
                    + ";base64," + base64;
        } catch (Exception e) {
            return path;
        }
    }

    private String buildFallbackAiReply(String prompt, AiConversation conversation, List<Map<String, Object>> attachments,
                                        List<AiMessage> contextMessages) {
        String system = conversation != null && conversation.getSystemPrompt() != null && !conversation.getSystemPrompt().trim().isEmpty()
                ? "系统提示词已生效。\n"
                : "";
        String attachmentSummary = attachments != null && !attachments.isEmpty()
                ? "\n已附带图片/文件 " + attachments.size() + " 个。"
                : "";
        String contextSummary = contextMessages != null && !contextMessages.isEmpty()
                ? "\n本次已按近似 4K tokens 预算携带 " + contextMessages.size() + " 条上下文。"
                : "";
        return system + "已收到你的请求：\n\n" + prompt.trim() + attachmentSummary + contextSummary
                + "\n\n当前服务器未配置可用外部模型时，会先使用本地兜底回复。你仍然可以继续提问，或切换图片/视频生成标签继续创建任务。";
    }

    private String generateAiConversationTitle(String prompt, String response) {
        String text = String.valueOf(prompt == null ? "" : prompt).trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) return "新标签";
        String respText = String.valueOf(response == null ? "" : response).trim().replaceAll("\\s+", " ");
        String combined = respText.isEmpty() ? text : text + "\n" + respText;
        java.util.concurrent.Future<String> future = null;
        try {
            future = TITLE_EXECUTOR.submit(() -> {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("model", "LongCat-Flash-Lite");
                List<Map<String, Object>> msgs = new ArrayList<>();
                msgs.add(obj("role", "system", "content", "你是一个标题生成助手。根据对话内容（用户问题和AI回答），生成一个简短的中文会话标题（不超过8个字）。只返回标题本身，不要解释，不要标点。"));
                msgs.add(obj("role", "user", "content", "用户：" + text));
                if (!respText.isEmpty()) {
                    msgs.add(obj("role", "assistant", "content", respText));
                }
                body.put("messages", msgs);
                body.put("stream", false);
                body.put("max_tokens", 32);
                Map<String, Object> resp = postJson(LONGCAT_API_URL, LONGCAT_API_KEY, body);
                List<Map<String, Object>> choices = asObjectList(resp.get("choices"));
                if (!choices.isEmpty()) {
                    String title = asString(asObjectMap(choices.get(0).get("message")).get("content")).trim();
                    if (!title.isEmpty()) return title;
                }
                return null;
            });
            String title = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
            if (title != null && !title.isEmpty() && !"null".equals(title)) {
                return title.length() > 18 ? title.substring(0, 18) + "..." : title;
            }
        } catch (Exception e) {
            // Fall through to fallback
        } finally {
            if (future != null) future.cancel(true);
        }
        return text.length() > 18 ? text.substring(0, 18) + "..." : text;
    }

    private String createAiPlaceholderAsset(String kind, String prompt, String title, String userId) throws IOException {
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='800'>"
                + "<defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'><stop stop-color='#0f172a'/><stop offset='1' stop-color='#2563eb'/></linearGradient></defs>"
                + "<rect width='100%' height='100%' fill='url(#g)'/>"
                + "<text x='64' y='120' font-size='42' fill='white' font-family='Microsoft YaHei, sans-serif'>" + escapeXml(title) + "</text>"
                + "<text x='64' y='190' font-size='26' fill='#cbd5e1' font-family='Microsoft YaHei, sans-serif'>"
                + escapeXml(prompt.length() > 80 ? prompt.substring(0, 80) + "..." : prompt) + "</text>"
                + "</svg>";
        try {
            StoredFileMetadata stored = FileStore.getInstance().store(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)),
                    "ai-" + kind + "-" + System.currentTimeMillis() + ".svg", "image/svg+xml", userId);
            return stored.getAccessPath();
        } catch (Exception e) {
            throw new IOException("生成占位图失败: " + e.getMessage(), e);
        }
    }

    private String escapeXml(String text) {
        return String.valueOf(text == null ? "" : text)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private Map<String, Object> postJson(String url, String apiKey, Map<String, Object> body) throws Exception {
        java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(JsonUtil.toJson(body), StandardCharsets.UTF_8))
                .build();
        java.net.http.HttpResponse<String> response = SHARED_HTTP_CLIENT
                .send(request, java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Map<String, Object> data = parseJsonObj(response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalArgumentException(asString(asObjectMap(data.get("error")).get("message")).isEmpty()
                    ? asString(data.get("message"))
                    : asString(asObjectMap(data.get("error")).get("message")));
        }
        return data;
    }

}
