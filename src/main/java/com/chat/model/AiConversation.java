package com.chat.model;

public class AiConversation {
    private String id;
    private String userId;
    private String title;
    private String type;
    private String modelId;
    private String reasoningDepth;
    private String systemPrompt;
    private double temperature;
    private double topP;
    private int contextCount;
    private int maxTokens;
    private boolean streamOutput;
    private String imageSize;
    private String imageStyle;
    private int imageCount;
    private double videoDuration;
    private String videoSize;
    private long videoSeed;
    private String videoFirstFramePath;
    private String videoLastFramePath;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getReasoningDepth() { return reasoningDepth; }
    public void setReasoningDepth(String reasoningDepth) { this.reasoningDepth = reasoningDepth; }
    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public double getTopP() { return topP; }
    public void setTopP(double topP) { this.topP = topP; }
    public int getContextCount() { return contextCount; }
    public void setContextCount(int contextCount) { this.contextCount = contextCount; }
    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    public boolean isStreamOutput() { return streamOutput; }
    public void setStreamOutput(boolean streamOutput) { this.streamOutput = streamOutput; }
    public String getImageSize() { return imageSize; }
    public void setImageSize(String imageSize) { this.imageSize = imageSize; }
    public String getImageStyle() { return imageStyle; }
    public void setImageStyle(String imageStyle) { this.imageStyle = imageStyle; }
    public int getImageCount() { return imageCount; }
    public void setImageCount(int imageCount) { this.imageCount = imageCount; }
    public double getVideoDuration() { return videoDuration; }
    public void setVideoDuration(double videoDuration) { this.videoDuration = videoDuration; }
    public String getVideoSize() { return videoSize; }
    public void setVideoSize(String videoSize) { this.videoSize = videoSize; }
    public long getVideoSeed() { return videoSeed; }
    public void setVideoSeed(long videoSeed) { this.videoSeed = videoSeed; }
    public String getVideoFirstFramePath() { return videoFirstFramePath; }
    public void setVideoFirstFramePath(String videoFirstFramePath) { this.videoFirstFramePath = videoFirstFramePath; }
    public String getVideoLastFramePath() { return videoLastFramePath; }
    public void setVideoLastFramePath(String videoLastFramePath) { this.videoLastFramePath = videoLastFramePath; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
