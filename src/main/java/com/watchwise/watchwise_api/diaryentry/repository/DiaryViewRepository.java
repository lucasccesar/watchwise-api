package com.watchwise.watchwise_api.diaryentry.repository;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryViewFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface DiaryViewRepository {

    Page<DiaryViewKey> findPage(
            UUID ownerId,
            String region,
            String language,
            Instant now,
            DiaryViewFilter filter,
            Pageable pageable);

    Page<GroupedDiaryViewKey> findGroupedPage(UUID ownerId, String region, String language,
            Instant now, DiaryViewFilter filter, Pageable pageable);

    record GroupedDiaryViewKey(DiaryViewKey item, long groupSize, ContentType groupType,
            LocalDate groupDate, UUID seriesContentId, String seriesTmdbId,
            int seasonCount, List<Integer> seasonNumbers, int episodeCount, Long totalRuntimeMinutes) { }

    record DiaryViewKey(
            String kind,
            UUID targetId,
            UUID contentId,
            ContentType contentType,
            String tmdbId,
            String seriesTmdbId,
            Integer seasonNumber,
            Integer episodeNumber,
            Integer watchNumber,
            UUID anchorEntryId,
            LocalDate watchedDate,
            Instant orderedAt,
            Instant groupSortAt,
            UUID groupSortId,
            Integer score) {
    }
}
