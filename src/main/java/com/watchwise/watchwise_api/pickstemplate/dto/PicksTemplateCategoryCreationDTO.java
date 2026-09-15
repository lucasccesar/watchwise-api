package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryGroup;
import com.watchwise.watchwise_api.pickstemplate.entity.PickCategoryOptionMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

public record PicksTemplateCategoryCreationDTO(@NotBlank @Size(max = 120) String name,
                                                String description, @NotNull PickCategoryGroup group,
                                                @NotNull @Positive Integer displayOrder,
                                                @NotNull PickAllowedType allowedType,
                                                @NotNull PickCategoryOptionMode optionMode,
                                                List<@Valid PicksTemplateOptionCreationDTO> options) { }
