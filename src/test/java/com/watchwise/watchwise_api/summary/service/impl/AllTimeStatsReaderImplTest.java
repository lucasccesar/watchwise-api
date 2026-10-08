package com.watchwise.watchwise_api.summary.service.impl;

import tools.jackson.databind.ObjectMapper;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.mapper.ContentMapper;
import com.watchwise.watchwise_api.content.repository.ContentRepository;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.entity.DiaryEntry;
import com.watchwise.watchwise_api.diaryentry.mapper.DiaryEntryMapper;
import com.watchwise.watchwise_api.summary.dto.AllTimeEditionStatsDTO;
import com.watchwise.watchwise_api.summary.repository.AllTimeStatsQueryRepository;
import com.watchwise.watchwise_api.summary.repository.ProfileSummaryQueryRepository;
import com.watchwise.watchwise_api.top5entry.repository.Top5EntryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllTimeStatsReaderImplTest {

    @Mock
    private AllTimeStatsQueryRepository queryRepository;

    @Mock
    private ProfileSummaryQueryRepository profileSummaryQueryRepository;

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private ContentMapper contentMapper;

    @Mock
    private DiaryEntryMapper diaryEntryMapper;

    @Mock
    private Top5EntryRepository top5EntryRepository;

    @Mock
    private UserContentPosterService userContentPosterService;

    @Mock
    private ContentCardAssembler contentCardAssembler;

    @InjectMocks
    private AllTimeStatsReaderImpl reader;

    @Test
    @DisplayName("[read] Should Return Empty Movie Edition - When User Has No Movie Entries")
    void shouldReturnEmptyMovieEditionWhenUserHasNoMovieEntries() {
        UUID userId = UUID.randomUUID();
        when(queryRepository.findMinWatchedDateByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(null);
        when(profileSummaryQueryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(0L);
        when(profileSummaryQueryRepository.sumRuntimeMinutesByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(0L);
        when(profileSummaryQueryRepository.countDistinctMoviesByGenre(userId)).thenReturn(List.of());
        when(profileSummaryQueryRepository.countLatestScoresByUserIdAndContentType(userId, ContentType.MOVIE.name()))
                .thenReturn(List.of());
        when(queryRepository.countByUserIdAndContentTypeGroupByYear(userId, ContentType.MOVIE.name()))
                .thenReturn(List.of());
        when(queryRepository.countDistinctMoviesByDecade(userId)).thenReturn(List.of());
        when(queryRepository.countDistinctMoviesByCountry(userId)).thenReturn(List.of());
        when(queryRepository.countMostLoggedMovies(org.mockito.ArgumentMatchers.eq(userId), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of());
        when(queryRepository.findTopRatedByUserIdAndContentType(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(queryRepository.findBottomRatedByUserIdAndContentType(org.mockito.ArgumentMatchers.eq(userId),
                org.mockito.ArgumentMatchers.eq(ContentType.MOVIE), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(queryRepository.countTheaterVisitsByUserId(userId)).thenReturn(0L);

        AllTimeEditionStatsDTO result = reader.read(userId, ContentType.MOVIE);

        assertThat(result.type()).isEqualTo(ContentType.MOVIE);
        assertThat(result.watchedCount()).isZero();
        assertThat(result.minutesWatched()).isZero();
        assertThat(result.totalTheaterVisits()).isZero();
        assertThat(result.watchCountByYear()).isEmpty();
        assertThat(result.ratingsDistribution()).isEmpty();
    }

    @Test
    @DisplayName("[read] Should Resolve Cards For Most Logged And Rated Items Once")
    void shouldResolveCardsForMostLoggedAndRatedItemsOnce() throws Exception {
        UUID userId = UUID.randomUUID();
        Content movie = Content.builder()
                .id(UUID.randomUUID())
                .tmdbId("550")
                .type(ContentType.MOVIE)
                .runtimeMinutes(139)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        DiaryEntry entry = DiaryEntry.builder()
                .id(UUID.randomUUID())
                .content(movie)
                .score(9)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        ContentRefDTO contentRef = new ContentRefDTO(movie.getId(), "550", ContentType.MOVIE,
                null, null, null, null, null, movie.getCreatedAt(), movie.getUpdatedAt());
        DiaryEntryResponseDTO diaryResponse = new DiaryEntryResponseDTO(
                entry.getId(), userId, contentRef, null, 9, null, 1, false, null,
                false, false, entry.getCreatedAt(), entry.getUpdatedAt(), 0, false);
        ContentCardDTO card = new ContentCardDTO(movie.getId(), ContentType.MOVIE, "550", null,
                null, null, "Fight Club", "/fight-club.jpg", null, null, null, 139,
                null, null, null, null, null, null, ContentPreviewStatus.AVAILABLE);

        when(profileSummaryQueryRepository.countByUserIdAndContentType(userId, ContentType.MOVIE)).thenReturn(1L);
        when(profileSummaryQueryRepository.sumRuntimeMinutesByUserIdAndContentType(userId, ContentType.MOVIE))
                .thenReturn(139L);
        when(queryRepository.findMinWatchedDateByUserIdAndContentType(userId, ContentType.MOVIE))
                .thenReturn(null);
        when(queryRepository.countMostLoggedMovies(eq(userId), any()))
                .thenReturn(List.of(contentWatchCount(movie.getId(), 3L)));
        when(queryRepository.findTopRatedByUserIdAndContentType(eq(userId), eq(ContentType.MOVIE), any()))
                .thenReturn(List.of(entry));
        when(queryRepository.findBottomRatedByUserIdAndContentType(eq(userId), eq(ContentType.MOVIE), any()))
                .thenReturn(List.of(entry));
        when(queryRepository.countTheaterVisitsByUserId(userId)).thenReturn(0L);
        when(contentRepository.findAllById(List.of(movie.getId()))).thenReturn(List.of(movie));
        when(contentMapper.contentToContentRefDto(movie)).thenReturn(contentRef);
        when(diaryEntryMapper.diaryEntryToResponseDto(entry, false)).thenReturn(diaryResponse);
        when(contentCardAssembler.assemble(anyCollection(), any(ContentCardContext.class), anySet()))
                .thenReturn(Map.of(ContentCoordinate.from(movie), card));

        AllTimeEditionStatsDTO result = reader.read(userId, ContentType.MOVIE);

        String json = new ObjectMapper().writeValueAsString(result);
        assertThat(json).contains("\"card\"");
        assertThat(result.topRated().getFirst().card()).isEqualTo(card);
        assertThat(result.bottomRated().getFirst().card()).isEqualTo(card);
        assertThat(result.watchedCount()).isEqualTo(1L);
        assertThat(result.minutesWatched()).isEqualTo(139L);

        org.mockito.ArgumentCaptor<Collection<ContentCardSpec>> specs = org.mockito.ArgumentCaptor.forClass(Collection.class);
        org.mockito.ArgumentCaptor<java.util.Set<ContentCardFieldSet>> fields = org.mockito.ArgumentCaptor.forClass(java.util.Set.class);
        verify(contentCardAssembler).assemble(specs.capture(), any(ContentCardContext.class), fields.capture());
        assertThat(specs.getValue()).hasSize(1);
        assertThat(fields.getValue()).containsExactlyInAnyOrder(
                ContentCardFieldSet.BASIC_METADATA, ContentCardFieldSet.STATS);
        assertThat(fields.getValue()).doesNotContain(ContentCardFieldSet.VIEWER_STATE);
    }

    private AllTimeStatsQueryRepository.ContentWatchCount contentWatchCount(UUID contentId, long count) {
        return new AllTimeStatsQueryRepository.ContentWatchCount() {
            @Override
            public UUID getContentId() {
                return contentId;
            }

            @Override
            public Long getCount() {
                return count;
            }
        };
    }
}
