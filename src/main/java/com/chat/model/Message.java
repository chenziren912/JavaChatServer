package com.chat.model;

import java.util.List;

public class Message {
    private String id;
    private String fromUserId;
    private String fromNickname;
    private String toUserId;
    private String content;
    private long   timestamp;
    private String chatRoomId;
    private boolean recalled;
    private String msgType;                // "text"|"image"|"file"|"sticker"
    private String fileName;
    private String filePath;
    private String forwardedFromNickname;
    private String forwardedFromUserId;
    private List<String> mentions;
    private String bubbleSkin;             // 发送者气泡皮肤快照（对方看到的是这个）
    private String messageFont;            // 发送者字体快照（接收方同步显示）
    private String cloudEntryId;
    private String cardType;
    private String cardPayload;
    private boolean adminDeleted;
    private String deletedByUserId;
    private long deletedAt;
    private String clientMsgId;   // 客户端临时ID，用于去重显示
    private boolean aiProxy;       // AI 代理消息

    public Message() {}

    public Message(String id, String fromUserId, String fromNickname,
                   String toUserId, String content, String chatRoomId) {
        this.id = id; this.fromUserId = fromUserId; this.fromNickname = fromNickname;
        this.toUserId = toUserId; this.content = content; this.chatRoomId = chatRoomId;
        this.timestamp = System.currentTimeMillis(); this.recalled = false; this.msgType = "text";
    }

    public String getId() { return id; }
    public void setId(String v) { this.id = v; }
    public String getFromUserId() { return fromUserId; }
    public void setFromUserId(String v) { this.fromUserId = v; }
    public String getFromNickname() { return fromNickname; }
    public void setFromNickname(String v) { this.fromNickname = v; }
    public String getToUserId() { return toUserId; }
    public void setToUserId(String v) { this.toUserId = v; }
    public String getContent() { return content; }
    public void setContent(String v) { this.content = v; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
    public String getChatRoomId() { return chatRoomId; }
    public void setChatRoomId(String v) { this.chatRoomId = v; }
    public boolean isRecalled() { return recalled; }
    public void setRecalled(boolean v) { this.recalled = v; }
    public String getMsgType() { return msgType; }
    public void setMsgType(String v) { this.msgType = v; }
    public String getFileName() { return fileName; }
    public void setFileName(String v) { this.fileName = v; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String v) { this.filePath = v; }
    public String getForwardedFromNickname() { return forwardedFromNickname; }
    public void setForwardedFromNickname(String v) { this.forwardedFromNickname = v; }
    public String getForwardedFromUserId() { return forwardedFromUserId; }
    public void setForwardedFromUserId(String v) { this.forwardedFromUserId = v; }
    public List<String> getMentions() { return mentions; }
    public void setMentions(List<String> v) { this.mentions = v; }
    public String getBubbleSkin() { return bubbleSkin; }
    public void setBubbleSkin(String v) { this.bubbleSkin = v; }
    public String getMessageFont() { return messageFont == null || messageFont.isBlank() ? "default" : messageFont; }
    public void setMessageFont(String v) { this.messageFont = v; }
    public String getCloudEntryId() { return cloudEntryId; }
    public void setCloudEntryId(String v) { this.cloudEntryId = v; }
    public String getCardType() { return cardType; }
    public void setCardType(String v) { this.cardType = v; }
    public String getCardPayload() { return cardPayload; }
    public void setCardPayload(String v) { this.cardPayload = v; }
    public boolean isAdminDeleted() { return adminDeleted; }
    public void setAdminDeleted(boolean v) { this.adminDeleted = v; }
    public String getDeletedByUserId() { return deletedByUserId; }
    public void setDeletedByUserId(String v) { this.deletedByUserId = v; }
    public long getDeletedAt() { return deletedAt; }
    public void setDeletedAt(long v) { this.deletedAt = v; }
    public String getClientMsgId() { return clientMsgId; }
    public void setClientMsgId(String v) { this.clientMsgId = v; }
    public boolean isAiProxy() { return aiProxy; }
    public void setAiProxy(boolean v) { this.aiProxy = v; }
}
