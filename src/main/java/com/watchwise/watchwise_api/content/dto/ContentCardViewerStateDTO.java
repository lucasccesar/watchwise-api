package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;

public record ContentCardViewerStateDTO(
        WatchStatus watchStatus,
        Integer myRating,
        LocalDate lastWatchedDate,
        Integer lastWatchNumber,
        Boolean watchedInTheater,
        Integer playsCount,
        Boolean inWatchlist,
        Boolean dropped,
        Integer watchedEpisodeCount,
        Integer releasedEpisodeCount) {
}
