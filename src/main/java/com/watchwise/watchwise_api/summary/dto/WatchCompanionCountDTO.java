package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

public record WatchCompanionCountDTO(UserPreviewDTO companion, long watchCount) {
}
