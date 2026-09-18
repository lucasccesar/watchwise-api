package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import java.util.UUID;

public record PickAnsweredCategoryPreviewDTO(
        UUID categoryId,
        String name,
        PickCategoryGroup group,
        Integer displayOrder,
        PickOptionSearchDTO target,
        Boolean isValid
) { }
