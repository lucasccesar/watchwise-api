package com.watchwise.watchwise_api.dailygame.dto;

import java.util.List;

public record DailyGameComparisonCellDTO(
        DailyGameComparisonStatus status,
        DailyGameComparisonDirection direction,
        Object displayValue,
        List<String> matchedValues,
        Integer matchCount) {

    public DailyGameComparisonCellDTO {
        matchedValues = matchedValues == null ? List.of() : List.copyOf(matchedValues);
    }
}
