package com.chat.model;

public class AiMediaTask {
    private String id;
    private String userId;
    private String conversationId;
    private String taskType;
    private String providerTaskId;
    private String modelId;
    private String prompt;
    private String status;
    private double estimatedTokens;
    private double finalTokens;
    private String outputPath;
    private String extraPath;
    private long createdAt;
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }
    public String getTaskType() { return taskType; }
    public void setTaskType(String taskType) { this.taskType = taskType; }
    public String getProviderTaskId() { return providerTaskId; }
    public void setProviderTaskId(String providerTaskId) { this.providerTaskId = providerTaskId; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public double getEstimatedTokens() { return estimatedTokens; }
    public void setEstimatedTokens(double estimatedTokens) { this.estimatedTokens = estimatedTokens; }
    public double getFinalTokens() { return finalTokens; }
    public void setFinalTokens(double finalTokens) { this.finalTokens = finalTokens; }
    public String getOutputPath() { return outputPath; }
    public void setOutputPath(String outputPath) { this.outputPath = outputPath; }
    public String getExtraPath() { return extraPath; }
    public void setExtraPath(String extraPath) { this.extraPath = extraPath; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
