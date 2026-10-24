package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        Integer releasedEpisodeCount,
        Long currentRatingVersion,
        Integer reviewsCount,
        List<UUID> reviewIds) {

    public ContentCardViewerStateDTO {
        reviewIds = reviewIds == null ? List.of() : List.copyOf(reviewIds);
    }

    public ContentCardViewerStateDTO(WatchStatus watchStatus, Integer myRating,
            LocalDate lastWatchedDate, Integer lastWatchNumber, Boolean watchedInTheater,
            Integer playsCount, Boolean inWatchlist, Boolean dropped,
            Integer watchedEpisodeCount, Integer releasedEpisodeCount) {
        this(watchStatus, myRating, lastWatchedDate, lastWatchNumber, watchedInTheater,
                playsCount, inWatchlist, dropped, watchedEpisodeCount, releasedEpisodeCount,
                null, 0, List.of());
    }
}
