package com.watchwise.watchwise_api.pick.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record PicksTotalsDTO(Long picksCount, Long templatesCount) {
}
