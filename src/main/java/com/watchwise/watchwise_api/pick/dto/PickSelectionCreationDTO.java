package com.watchwise.watchwise_api.pick.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record PickSelectionCreationDTO(@NotNull UUID categoryId, @NotNull @Valid PickTargetDTO target) { }
