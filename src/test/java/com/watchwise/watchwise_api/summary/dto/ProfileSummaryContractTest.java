package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileSummaryContractTest {

    @Test
    void profileDiaryPreviewDoesNotExposeReviewTextOrSocialCounters() {
        ContentRefDTO content = content(ContentType.MOVIE);

        ProfileDiaryPreviewDTO preview = new ProfileDiaryPreviewDTO(
                UUID.randomUUID(), content, 9, LocalDate.of(2026, 10, 5), 2, null, List.of());

        assertThat(List.of(ProfileDiaryPreviewDTO.class.getRecordComponents()))
                .extracting(component -> component.getName())
                .doesNotContain("comment", "likesCount", "commentsCount");
    }

    @Test
    void activityPreviewExposesWatchCountAndNullableScore() {
        ContentRefDTO content = content(ContentType.MOVIE);

        RecentActivityItemDTO activity = new RecentActivityItemDTO(
                content, RecentActivityStatus.DROPPED, null, 0L, null, LocalDateTime.of(2026, 10, 5, 10, 0));

        assertThat(activity.timesWatched()).isZero();
        assertThat(activity.score()).isNull();
    }

    @Test
    void watchTimeExposesTotalAndRecentWatchCounts() {
        WatchTimeDTO watchTime = new WatchTimeDTO(22800L, 2538L, 421L, 18L);

        assertThat(watchTime.totalWatchedCount()).isEqualTo(421L);
        assertThat(watchTime.watchedCountLast30Days()).isEqualTo(18L);
    }

    private ContentRefDTO content(ContentType type) {
        return new ContentRefDTO(
                UUID.randomUUID(), "550", type, null, null, null, false, false,
                LocalDateTime.now(), LocalDateTime.now());
    }
}
