package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pick.dto.PickTargetDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record PicksTemplateOptionCreationDTO(@NotNull @Valid PickTargetDTO target) { }
