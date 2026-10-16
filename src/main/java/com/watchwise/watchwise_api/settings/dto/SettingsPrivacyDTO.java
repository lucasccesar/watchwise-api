package com.watchwise.watchwise_api.settings.dto;

import java.util.List;

public record SettingsPrivacyDTO(long followersCount, long followingCount, long pendingFollowRequestsCount,
        List<SettingsUserPreviewDTO> pendingFollowRequests,
        long blockedUsersCount, List<SettingsUserPreviewDTO> blockedUsers) {}
