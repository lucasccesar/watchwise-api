package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;

import java.util.UUID;

public record PickAnswerViewDTO(
        UUID categoryId,
        String name,
        PickCategoryGroup group,
        Integer displayOrder,
        PickAllowedType allowedType,
        PickCategoryOptionMode optionMode,
        PickOptionSearchDTO target,
        PickTargetPreviewDTO preview,
        Boolean isAnswered,
        Boolean isValid) { }
