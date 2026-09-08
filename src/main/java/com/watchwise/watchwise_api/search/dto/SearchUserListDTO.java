package com.watchwise.watchwise_api.search.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;

import java.util.List;
import java.util.UUID;

public record SearchUserListDTO(
        UUID id,
        UserPreviewDTO user,
        String name,
        List<ContentRefDTO> previewItems,
        long nestedListsCount
) {
}
