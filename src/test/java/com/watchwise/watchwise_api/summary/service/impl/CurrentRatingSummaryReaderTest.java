package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.contentrating.entity.ContentRating;
import com.watchwise.watchwise_api.contentrating.repository.ContentRatingRepository;
import com.watchwise.watchwise_api.summary.dto.ContentRatingSummaryDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentRatingSummaryReaderTest {

    @Mock
    private ContentRatingRepository ratingRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentMapper contentMapper;

    @InjectMocks
    private CurrentRatingSummaryReader reader;

    @Test
    void shouldReturnTypedTopAndBottomSeriesAndSeasonRatings() {
        UUID ownerId = UUID.randomUUID();
        Content series = content(ContentType.SERIES, "33001", null, null);
        Content season = content(ContentType.SEASON, null, "33001", 1);
        ContentRating seriesRating = rating(ownerId, series, 9, 4L);
        ContentRating seasonRating = rating(ownerId, season, 6, 2L);
        ContentRefDTO seriesReference = reference(series);
        ContentRefDTO seasonReference = reference(season);

        when(ratingRepository.findByUserIdAndContentTypeInOrderByScoreDescContentIdAsc(
                ownerId, List.of(ContentType.SERIES, ContentType.SEASON), PageRequest.of(0, 5)))
                .thenReturn(List.of(seriesRating, seasonRating));
        when(ratingRepository.findByUserIdAndContentTypeInOrderByScoreAscContentIdAsc(
                ownerId, List.of(ContentType.SERIES, ContentType.SEASON), PageRequest.of(0, 5)))
                .thenReturn(List.of(seasonRating, seriesRating));
        when(contentRepository.findAllById(anyCollection())).thenReturn(List.of(series, season));
        when(contentMapper.contentToContentRefDto(series)).thenReturn(seriesReference);
        when(contentMapper.contentToContentRefDto(season)).thenReturn(seasonReference);

        CurrentRatingSummaryReader.Ratings result = reader.readSeriesRatings(ownerId, 5);

        assertThat(result.top()).containsExactly(
                new ContentRatingSummaryDTO(seriesReference, 9, 4L),
                new ContentRatingSummaryDTO(seasonReference, 6, 2L));
        assertThat(result.bottom()).containsExactly(
                new ContentRatingSummaryDTO(seasonReference, 6, 2L),
                new ContentRatingSummaryDTO(seriesReference, 9, 4L));
        verify(ratingRepository).findByUserIdAndContentTypeInOrderByScoreDescContentIdAsc(
                ownerId, List.of(ContentType.SERIES, ContentType.SEASON), PageRequest.of(0, 5));
        verify(ratingRepository).findByUserIdAndContentTypeInOrderByScoreAscContentIdAsc(
                ownerId, List.of(ContentType.SERIES, ContentType.SEASON), PageRequest.of(0, 5));
    }

    @Test
    void shouldSkipQueriesWhenLimitIsNotPositive() {
        CurrentRatingSummaryReader.Ratings result = reader.readSeriesRatings(UUID.randomUUID(), 0);

        assertThat(result.top()).isEmpty();
        assertThat(result.bottom()).isEmpty();
        verifyNoInteractions(ratingRepository, contentRepository, contentMapper);
    }

    private Content content(ContentType type, String tmdbId, String seriesTmdbId, Integer seasonNumber) {
        return Content.builder()
                .id(UUID.randomUUID())
                .type(type)
                .tmdbId(tmdbId)
                .seriesTmdbId(seriesTmdbId)
                .seasonNumber(seasonNumber)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ContentRating rating(UUID ownerId, Content content, int score, long version) {
        return ContentRating.builder()
                .id(UUID.randomUUID())
                .userId(ownerId)
                .contentId(content.getId())
                .contentType(content.getType())
                .score(score)
                .version(version)
                .createdAt(java.time.Instant.now())
                .updatedAt(java.time.Instant.now())
                .build();
    }

    private ContentRefDTO reference(Content content) {
        return new ContentRefDTO(content.getId(), content.getTmdbId(), content.getType(),
                content.getSeriesTmdbId(), content.getSeasonNumber(), null, null, null, null, null);
    }
}
