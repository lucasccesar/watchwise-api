package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickAllowedType;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

public record PickContentTargetDTO(PickAllowedType type, String tmdbId, String seriesTmdbId,
                                   @PositiveOrZero Integer seasonNumber, @Positive Integer episodeNumber) { }
