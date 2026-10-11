package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.TmdbSeriesHierarchyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TmdbSeriesHierarchyServiceImplTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-27T12:00:00Z"), ZoneOffset.UTC);
    @Mock private TmdbClient tmdbClient;

    @Test
    @DisplayName("[listSeasons] Should Normalize Seasons And Include Specials When Requested - When TMDB Returns A Series")
    void listsNormalizedSeasonsAndCanIncludeSpecials() {
        when(tmdbClient.getTvFullDetails("1399", "pt-BR")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbTvFullDetails("1399", "Series", null, null, null, null, null, List.of(), List.of(),
                        List.of(), List.of(), List.of(
                        new TmdbSeasonSummary(0, "Specials", null, null, 1, "/special.jpg"),
                        new TmdbSeasonSummary(1, "Season 1", null, "2020-01-01", 3, null)),
                        null, null, null, null, null, null, null, null, "Ended")));

        List<ContentSeasonSelectionDTO> result = service().listSeasons("1399", true, "pt-BR");

        assertThat(result).containsExactly(
                new ContentSeasonSelectionDTO("1399", 0, "Specials", 1,
                        "https://image.tmdb.org/t/p/w500/special.jpg", null),
                new ContentSeasonSelectionDTO("1399", 1, "Season 1", 3, null,
                        java.time.LocalDate.of(2020, 1, 1)));
        verify(tmdbClient).getTvFullDetails("1399", "pt-BR");
    }

    @Test
    @DisplayName("[listEpisodes] Should Include Only Released Valid Episodes By Default - When Future Episodes Are Not Requested")
    void listsOnlyReleasedValidEpisodesUnlessFutureEpisodesRequested() {
        when(tmdbClient.getSeasonFullDetails("1399", 1, "en-US")).thenReturn(new TmdbLookupResult.Found<>(
                new TmdbSeasonFullDetails(1, "Season 1", null, null, null, 1, List.of(
                        new TmdbEpisodeSummary(1, "Pilot", null, "2020-01-01", 40, "/pilot.jpg", null),
                        new TmdbEpisodeSummary(2, "Future", null, "2026-09-28", 40, null, null)), null, null)));

        List<ContentEpisodeSelectionDTO> released = service().listEpisodes("1399", 1, false, "en-US");
        List<ContentEpisodeSelectionDTO> all = service().listEpisodes("1399", 1, true, "en-US");

        assertThat(released).containsExactly(new ContentEpisodeSelectionDTO("1399", 1, 1, "Pilot",
                "https://image.tmdb.org/t/p/w300/pilot.jpg", java.time.LocalDate.of(2020, 1, 1)));
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("[listSeasons] Should Reject Invalid Coordinates And Map TMDB Errors - When Series Or Season Lookup Fails")
    void rejectsInvalidCoordinatesAndMapsMissingOrUnavailable() {
        assertThatThrownBy(() -> service().listSeasons("0", false, "en-US"))
                .isInstanceOf(BadRequestException.class);
        when(tmdbClient.getTvFullDetails("1", "en-US")).thenReturn(new TmdbLookupResult.NotFound<>());
        assertThatThrownBy(() -> service().listSeasons("1", false, "en-US"))
                .isInstanceOf(NotFoundException.class);
        when(tmdbClient.getSeasonFullDetails("1", 1, "en-US")).thenReturn(new TmdbLookupResult.Unavailable<>());
        assertThatThrownBy(() -> service().listEpisodes("1", 1, false, "en-US"))
                .isInstanceOf(TmdbUnavailableException.class);
    }

    @Test
    @DisplayName("[listEpisodes] Should Accept Season Zero - When Looking Up Specials")
    void allowsSpecialSeasonZero() {
        when(tmdbClient.getSeasonFullDetails("1", 0, "en-US"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbSeasonFullDetails(
                        0, "Specials", null, null, null, 0, List.of(), null, null)));

        assertThat(service().listEpisodes("1", 0, true, "en-US")).isEmpty();
    }

    private TmdbSeriesHierarchyService service() {
        return new TmdbSeriesHierarchyServiceImpl(tmdbClient, CLOCK);
    }
}
