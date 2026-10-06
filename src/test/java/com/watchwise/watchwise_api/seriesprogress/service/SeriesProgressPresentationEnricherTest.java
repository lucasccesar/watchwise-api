package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.user.entity.User;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SeriesProgressPresentationEnricherTest {

    private final TmdbClient tmdbClient = mock(TmdbClient.class);
    private final SeriesProgressNextEpisodeResolver nextEpisodeResolver = mock(SeriesProgressNextEpisodeResolver.class);
    private final SeriesProgressPresentationEnricher enricher =
            new SeriesProgressPresentationEnricher(tmdbClient, nextEpisodeResolver);

    @Test
    void shouldResolveSeriesAndLastEpisodePresentationAndDelegateNextEpisode() {
        User user = mock(User.class);
        when(user.getPreferredLanguage()).thenReturn("pt-BR");
        SeriesProgressReadRepository.SeriesProgressCandidate row = row("1399", 1, 3);
        TmdbTvFullDetails series = mock(TmdbTvFullDetails.class);
        when(series.name()).thenReturn("Breaking Bad");
        when(series.posterPath()).thenReturn("/poster.jpg");
        when(tmdbClient.getTvFullDetails("1399", "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(series));
        when(tmdbClient.getEpisodeFullDetails("1399", 1, 3, "pt-BR"))
                .thenReturn(new TmdbLookupResult.Found<>(new TmdbEpisodeFullDetails(
                        3, "Last Episode", null, "2026-10-01", 3, 1, 50, "/last.jpg", List.of())));
        ProgressEpisodeDTO next = new ProgressEpisodeDTO(
                1, 4, "Next Episode", LocalDate.of(2026, 10, 8), 52, "/next.jpg", true);
        when(nextEpisodeResolver.resolveNext(
                series, "pt-BR", List.of(new SeasonProgressDTO(1, 2L, 3, 66.6)), Set.of(), LocalDate.now()))
                .thenReturn(next);

        Map<String, SeriesProgressPresentationEnricher.Enrichment> result = enricher.enrich(
                user,
                List.of(row),
                Map.of("1399", List.of(new SeasonProgressDTO(1, 2L, 3, 66.6))),
                Set.of(),
                Map.of("1399", "https://image.tmdb.org/t/p/w342/custom.jpg"));

        SeriesProgressPresentationEnricher.Enrichment enrichment = result.get("1399");
        assertThat(enrichment.seriesTitle()).isEqualTo("Breaking Bad");
        assertThat(enrichment.seriesPosterPath()).isEqualTo("/poster.jpg");
        assertThat(enrichment.lastWatchedEpisodeTitle()).isEqualTo("Last Episode");
        assertThat(enrichment.nextEpisode()).isEqualTo(next);
    }

    @Test
    void shouldKeepTheRowWhenOptionalTvPresentationIsNotFound() {
        User user = mock(User.class);
        when(user.getPreferredLanguage()).thenReturn("en-US");
        SeriesProgressReadRepository.SeriesProgressCandidate row = row("404", null, null);
        when(tmdbClient.getTvFullDetails("404", "en-US"))
                .thenReturn(new TmdbLookupResult.NotFound<>());

        Map<String, SeriesProgressPresentationEnricher.Enrichment> result = enricher.enrich(
                user, List.of(row), Map.of("404", List.of()), Set.of(), Map.of());

        assertThat(result).containsKey("404");
        assertThat(result.get("404").seriesTitle()).isNull();
        assertThat(result.get("404").nextEpisode()).isNull();
    }

    private SeriesProgressReadRepository.SeriesProgressCandidate row(
            String seriesTmdbId, Integer lastSeason, Integer lastEpisode) {
        SeriesProgressReadRepository.SeriesProgressCandidate row =
                mock(SeriesProgressReadRepository.SeriesProgressCandidate.class);
        when(row.getSeriesTmdbId()).thenReturn(seriesTmdbId);
        when(row.getLastWatchedSeasonNumber()).thenReturn(lastSeason);
        when(row.getLastWatchedEpisodeNumber()).thenReturn(lastEpisode);
        return row;
    }
}
