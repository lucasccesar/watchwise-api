package com.watchwise.watchwise_api.userlist.dto;

import java.util.UUID;

public record UserListOrderItemDTO(
        UUID id,
        Integer position,
        String label,
        String thumbnailUrl,
        boolean movable) {
}
