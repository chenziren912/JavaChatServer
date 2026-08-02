package com.chat.server;

import com.chat.model.CloudEntry;
import com.chat.model.CloudShareLink;

import java.util.LinkedHashMap;
import java.util.Map;

final class CloudEntryMapper {
    private CloudEntryMapper() {
    }

    static Map<String, Object> cloudEntryToMap(CloudEntry entry) {
        if (entry == null) return null;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entry.getId());
        map.put("ownerId", entry.getOwnerId());
        map.put("parentPath", entry.getParentPath());
        map.put("name", entry.getName());
        map.put("type", entry.getType());
        map.put("contentType", entry.getContentType());
        map.put("size", entry.getSize());
        map.put("createdAt", entry.getCreatedAt());
        map.put("updatedAt", entry.getUpdatedAt());
        map.put("deleted", entry.isDeleted());
        map.put("deletedAt", entry.getDeletedAt());
        map.put("sourceModule", entry.getSourceModule());
        map.put("messageRefCount", entry.getMessageRefCount());
        map.put("filePath", entry.getStoredName() != null ? "/cloud-files/" + entry.getOwnerId() + "/" + entry.getStoredName() : "");
        map.put("favorite", entry.isFavorite());
        map.put("safebox", entry.isSafebox());
        return map;
    }

    static Map<String, Object> cloudRecycleEntryToMap(CloudEntry entry) {
        Map<String, Object> map = CloudEntryMapper.cloudEntryToMap(entry);
        if (map != null) {
            long left = Math.max(0, 15 - ((System.currentTimeMillis() - entry.getDeletedAt()) / (24L * 3600 * 1000)));
            map.put("daysLeft", left);
        }
        return map;
    }

    static Map<String, Object> cloudShareToMap(CloudShareLink share) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", share.getId());
        map.put("entryId", share.getEntryId());
        map.put("title", share.getTitle());
        map.put("shareType", share.getShareType());
        map.put("visitCount", share.getVisitCount());
        map.put("createdAt", share.getCreatedAt());
        map.put("updatedAt", share.getUpdatedAt());
        map.put("url", "/share/cloud/" + share.getId());
        return map;
    }
}
