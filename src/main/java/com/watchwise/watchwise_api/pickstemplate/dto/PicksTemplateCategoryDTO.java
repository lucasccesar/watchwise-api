package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PicksTemplateCategoryDTO(UUID id, String name, String description, PickCategoryGroup group,
                                        Integer displayOrder, PickAllowedType allowedType,
                                        PickCategoryOptionMode optionMode, LocalDateTime createdAt,
                                        LocalDateTime updatedAt, List<PicksTemplateOptionDTO> options) { }
