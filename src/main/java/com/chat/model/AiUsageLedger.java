package com.chat.model;

public class AiUsageLedger {
    private String id;
    private String userId;
    private String day;
    private String modelId;
    private String usageType;
    private int inputTokens;
    private int outputTokens;
    private double weightedTokens;
    private long createdAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getDay() { return day; }
    public void setDay(String day) { this.day = day; }
    public String getModelId() { return modelId; }
    public void setModelId(String modelId) { this.modelId = modelId; }
    public String getUsageType() { return usageType; }
    public void setUsageType(String usageType) { this.usageType = usageType; }
    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }
    public double getWeightedTokens() { return weightedTokens; }
    public void setWeightedTokens(double weightedTokens) { this.weightedTokens = weightedTokens; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
}
