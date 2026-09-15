package com.watchwise.watchwise_api.pick.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PickSelectionDTO(UUID id, UUID categoryId, PickOptionSearchDTO target, LocalDateTime createdAt,
                               LocalDateTime updatedAt, Boolean isValid) { }
