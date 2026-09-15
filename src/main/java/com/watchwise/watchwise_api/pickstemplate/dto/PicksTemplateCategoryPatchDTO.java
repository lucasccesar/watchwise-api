package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PicksTemplateCategoryPatchDTO(@Size(max = 120) String name, String description,
                                             PickCategoryGroup group, @Positive Integer displayOrder,
                                             PickAllowedType allowedType, PickCategoryOptionMode optionMode) { }
