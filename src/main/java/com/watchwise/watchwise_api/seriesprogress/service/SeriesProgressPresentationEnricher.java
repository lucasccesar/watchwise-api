package com.watchwise.watchwise_api.seriesprogress.service;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.diaryentry.dto.SeasonProgressDTO;
import com.watchwise.watchwise_api.diaryentry.repository.WatchedEpisodeCoordinate;
import com.watchwise.watchwise_api.seriesprogress.dto.ProgressEpisodeDTO;
import com.watchwise.watchwise_api.seriesprogress.repository.SeriesProgressReadRepository;
import com.watchwise.watchwise_api.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class SeriesProgressPresentationEnricher {

    private final TmdbClient tmdbClient;
    private final SeriesProgressNextEpisodeResolver nextEpisodeResolver;

    public Map<String, Enrichment> enrich(
            User targetUser,
            List<SeriesProgressReadRepository.SeriesProgressCandidate> pageRows,
            Map<String, List<SeasonProgressDTO>> seasonProgressBySeries,
            Set<WatchedEpisodeCoordinate> watchedCoordinates,
            Map<String, String> customSeriesPosters) {
        String language = targetUser.getPreferredLanguage();
        LocalDate today = LocalDate.now();
        Map<String, Set<WatchedEpisodeCoordinate>> coordinatesBySeries = watchedCoordinates == null
                ? Map.of()
                : watchedCoordinates.stream().collect(Collectors.groupingBy(
                        WatchedEpisodeCoordinate::seriesTmdbId,
                        Collectors.toUnmodifiableSet()));

        return pageRows.stream().collect(Collectors.toUnmodifiableMap(
                SeriesProgressReadRepository.SeriesProgressCandidate::getSeriesTmdbId,
                row -> enrichOne(row, language, today, seasonProgressBySeries,
                        coordinatesBySeries, customSeriesPosters)));
    }

    private Enrichment enrichOne(
            SeriesProgressReadRepository.SeriesProgressCandidate row,
            String language,
            LocalDate today,
            Map<String, List<SeasonProgressDTO>> seasonProgressBySeries,
            Map<String, Set<WatchedEpisodeCoordinate>> coordinatesBySeries,
            Map<String, String> customSeriesPosters) {
        String seriesTmdbId = row.getSeriesTmdbId();
        TmdbLookupResult<TmdbTvFullDetails> seriesLookup = tmdbClient.getTvFullDetails(seriesTmdbId, language);
        if (!(seriesLookup instanceof TmdbLookupResult.Found<TmdbTvFullDetails> found)) {
            return new Enrichment(null, null, null, null);
        }

        TmdbTvFullDetails series = found.value();
        String lastEpisodeTitle = resolveLastEpisodeTitle(row, language);
        ProgressEpisodeDTO nextEpisode = null;
        try {
            nextEpisode = nextEpisodeResolver.resolveNext(
                    series,
                    language,
                    seasonProgressBySeries.getOrDefault(seriesTmdbId, List.of()),
                    coordinatesBySeries.getOrDefault(seriesTmdbId, Set.of()),
                    today);
        } catch (TmdbUnavailableException ignored) {
            // Presentation enrichment is optional; the numeric progress row remains usable.
        }

        return new Enrichment(series.name(), series.posterPath(), lastEpisodeTitle, nextEpisode);
    }

    private String resolveLastEpisodeTitle(
            SeriesProgressReadRepository.SeriesProgressCandidate row,
            String language) {
        Integer seasonNumber = row.getLastWatchedSeasonNumber();
        Integer episodeNumber = row.getLastWatchedEpisodeNumber();
        if (seasonNumber == null || episodeNumber == null) {
            return null;
        }

        TmdbLookupResult<TmdbEpisodeFullDetails> lookup = tmdbClient.getEpisodeFullDetails(
                row.getSeriesTmdbId(), seasonNumber, episodeNumber, language);
        return lookup instanceof TmdbLookupResult.Found<TmdbEpisodeFullDetails> found
                ? found.value().name()
                : null;
    }

    public record Enrichment(
            String seriesTitle,
            String seriesPosterPath,
            String lastWatchedEpisodeTitle,
            ProgressEpisodeDTO nextEpisode) {
    }
}
