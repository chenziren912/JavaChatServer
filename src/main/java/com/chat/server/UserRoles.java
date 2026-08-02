package com.chat.server;

import com.chat.model.User;
import com.chat.service.SuperAdminService;
import com.chat.service.UserService;

final class UserRoles {
    private static final SuperAdminService SUPER_ADMINS = SuperAdminService.getInstance();

    private UserRoles() {
    }

    static boolean isSuperAdmin(String userId) {
        return SUPER_ADMINS.isSuperAdmin(userId);
    }

    static boolean isPrimarySuperAdmin(String userId) {
        return userId != null && userId.equals(SUPER_ADMINS.getPrimarySuperAdminId());
    }

    static boolean isDeveloper(String userId) {
        if (userId == null) return false;
        User user = UserService.getInstance().getByUserId(userId);
        return user != null && ("陈梓仁".equalsIgnoreCase(user.getUsername())
                || "chenziren".equalsIgnoreCase(user.getUsername()));
    }
}
