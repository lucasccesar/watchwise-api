package com.watchwise.watchwise_api.pick.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

public record PickTargetDTO(@Valid PickContentTargetDTO content,
                            @Pattern(regexp = "^[0-9]+$") String personTmdbId,
                            @Valid PickContentTargetDTO contextContent) { }
