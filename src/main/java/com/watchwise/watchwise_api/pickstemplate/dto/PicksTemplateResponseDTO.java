package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PicksTemplateResponseDTO(UUID id, UserPreviewDTO creator, PickOrigin origin, String name,
                                        String description, String coverImage, String instructions,
                                        LocalDate eligibilityStartDate, LocalDate eligibilityEndDate,
                                        LocalDateTime createdAt, LocalDateTime updatedAt,
                                        List<PicksTemplateCategoryDTO> categories) { }
