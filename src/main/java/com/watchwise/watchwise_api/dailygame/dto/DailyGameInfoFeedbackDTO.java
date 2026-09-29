package com.watchwise.watchwise_api.dailygame.dto;

public record DailyGameInfoFeedbackDTO(
        DailyGameComparisonCellDTO platforms,
        DailyGameComparisonCellDTO genres,
        DailyGameComparisonCellDTO year,
        DailyGameComparisonCellDTO certification,
        DailyGameComparisonCellDTO directorOrCreators,
        DailyGameComparisonCellDTO cast,
        DailyGameComparisonCellDTO productionCompanies,
        DailyGameComparisonCellDTO revenueOrSeasons) {

    public DailyGameComparisonCellDTO director() {
        return directorOrCreators;
    }

    public DailyGameComparisonCellDTO creators() {
        return directorOrCreators;
    }

    public DailyGameComparisonCellDTO revenue() {
        return revenueOrSeasons;
    }

    public DailyGameComparisonCellDTO seasons() {
        return revenueOrSeasons;
    }
}
