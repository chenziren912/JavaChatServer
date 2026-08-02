package com.chat.model;

import java.util.ArrayList;
import java.util.List;

public class Moment {
    private String id;
    private String fromUserId;
    private String fromNickname;
    private String content;
    private long timestamp;
    private List<String> likes;
    private List<Comment> comments;
    // 可见性: "public"=所有人, "friends"=仅好友, "private"=仅指定好友
    private String visibility;
    // visibility="private"时，允许查看的好友userId列表
    private List<String> allowedViewers;
    private List<Attachment> attachments;

    public static class Attachment {
        private String cloudEntryId;
        private String fileName;
        private String filePath;
        private String type;

        public String getCloudEntryId() { return cloudEntryId; }
        public void setCloudEntryId(String v) { this.cloudEntryId = v; }
        public String getFileName() { return fileName; }
        public void setFileName(String v) { this.fileName = v; }
        public String getFilePath() { return filePath; }
        public void setFilePath(String v) { this.filePath = v; }
        public String getType() { return type; }
        public void setType(String v) { this.type = v; }
    }

    public static class Comment {
        private String fromUserId;
        private String fromNickname;
        private String content;
        private long timestamp;

        public Comment() {}
        public Comment(String fromUserId, String fromNickname, String content) {
            this.fromUserId = fromUserId;
            this.fromNickname = fromNickname;
            this.content = content;
            this.timestamp = System.currentTimeMillis();
        }
        public String getFromUserId()   { return fromUserId; }
        public void setFromUserId(String v) { this.fromUserId = v; }
        public String getFromNickname() { return fromNickname; }
        public void setFromNickname(String v) { this.fromNickname = v; }
        public String getContent()      { return content; }
        public void setContent(String v) { this.content = v; }
        public long getTimestamp()      { return timestamp; }
        public void setTimestamp(long v) { this.timestamp = v; }
    }

    public Moment() {
        this.likes = new ArrayList<>();
        this.comments = new ArrayList<>();
        this.visibility = "friends";
        this.allowedViewers = new ArrayList<>();
        this.attachments = new ArrayList<>();
    }

    public Moment(String id, String fromUserId, String fromNickname, String content) {
        this.id = id;
        this.fromUserId = fromUserId;
        this.fromNickname = fromNickname;
        this.content = content;
        this.timestamp = System.currentTimeMillis();
        this.likes = new ArrayList<>();
        this.comments = new ArrayList<>();
        this.visibility = "friends";
        this.allowedViewers = new ArrayList<>();
        this.attachments = new ArrayList<>();
    }

    /** 判断某用户是否有权查看此动态 */
    public boolean canView(String viewerUserId) {
        if (viewerUserId == null) return false;
        if (viewerUserId.equals(fromUserId)) return true; // 自己永远可见
        if ("public".equals(visibility) || visibility == null) return true;
        if ("friends".equals(visibility)) return true; // 由Service层再判断是否好友
        if ("private".equals(visibility)) {
            return allowedViewers != null && allowedViewers.contains(viewerUserId);
        }
        return false;
    }

    public String getId()           { return id; }
    public void setId(String v)     { this.id = v; }
    public String getFromUserId()   { return fromUserId; }
    public void setFromUserId(String v) { this.fromUserId = v; }
    public String getFromNickname() { return fromNickname; }
    public void setFromNickname(String v) { this.fromNickname = v; }
    public String getContent()      { return content; }
    public void setContent(String v) { this.content = v; }
    public long getTimestamp()      { return timestamp; }
    public void setTimestamp(long v) { this.timestamp = v; }
    public List<String> getLikes()  {
        if (likes == null) likes = new ArrayList<>();
        return likes;
    }
    public void setLikes(List<String> v) { this.likes = v; }
    public List<Comment> getComments() {
        if (comments == null) comments = new ArrayList<>();
        return comments;
    }
    public void setComments(List<Comment> v) { this.comments = v; }
    public String getVisibility()   { return visibility; }
    public void setVisibility(String v) { this.visibility = v; }
    public List<String> getAllowedViewers() {
        if (allowedViewers == null) allowedViewers = new ArrayList<>();
        return allowedViewers;
    }
    public void setAllowedViewers(List<String> v) { this.allowedViewers = v; }
    public List<Attachment> getAttachments() {
        if (attachments == null) attachments = new ArrayList<>();
        return attachments;
    }
    public void setAttachments(List<Attachment> v) { this.attachments = v; }
}
