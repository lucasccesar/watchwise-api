package com.watchwise.watchwise_api.pick.repository;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;

import java.time.LocalDateTime;
import java.util.UUID;

public record PickListRow(
        UUID id, UUID templateId, String templateName, String coverImage,
        String creatorUsername, String creatorName, String creatorPicture,
        PickVisibility visibility, LocalDateTime createdAt, Integer likesCount) {
}
