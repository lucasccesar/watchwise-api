package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pick.dto.PickOptionSearchDTO;
import java.time.LocalDateTime;
import java.util.UUID;

public record PicksTemplateOptionDTO(UUID id, PickOptionSearchDTO target, LocalDateTime createdAt) { }
