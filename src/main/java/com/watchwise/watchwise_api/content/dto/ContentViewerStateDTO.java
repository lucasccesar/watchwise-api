package com.watchwise.watchwise_api.content.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ContentViewerStateDTO(
        WatchStatus watchStatus,
        Integer myRating,
        String myReview,
        LocalDate lastWatchedDate,
        Integer lastWatchNumber,
        Boolean watchedInTheater,
        Integer playsCount,
        UUID latestDiaryEntryId,
        Boolean inWatchlist,
        UUID watchlistEntryId,
        Boolean dropped,
        UUID droppedEntryId,
        List<ContentListMembershipDTO> lists,
        Integer watchedEpisodeCount,
        Integer releasedEpisodeCount,
        Long currentRatingVersion,
        Integer reviewsCount,
        List<UUID> reviewIds) {

    public ContentViewerStateDTO {
        lists = lists == null ? List.of() : List.copyOf(lists);
        reviewIds = reviewIds == null ? List.of() : List.copyOf(reviewIds);
    }

    public ContentViewerStateDTO(
            WatchStatus watchStatus, Integer myRating, String myReview, LocalDate lastWatchedDate,
            Integer lastWatchNumber, Boolean watchedInTheater, Integer playsCount, UUID latestDiaryEntryId,
            Boolean inWatchlist, UUID watchlistEntryId, Boolean dropped, UUID droppedEntryId,
            List<ContentListMembershipDTO> lists, Integer watchedEpisodeCount, Integer releasedEpisodeCount) {
        this(watchStatus, myRating, myReview, lastWatchedDate, lastWatchNumber, watchedInTheater,
                playsCount, latestDiaryEntryId, inWatchlist, watchlistEntryId, dropped, droppedEntryId,
                lists, watchedEpisodeCount, releasedEpisodeCount, null, 0, List.of());
    }
}
