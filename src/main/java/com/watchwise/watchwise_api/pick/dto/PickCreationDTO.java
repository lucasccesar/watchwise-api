package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pick.entity.PickVisibility;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PickCreationDTO(PickVisibility visibility, @NotEmpty List<@Valid PickSelectionCreationDTO> selections) { }
