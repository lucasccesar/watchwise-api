package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonSummary;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.TmdbSeriesHierarchyService;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class TmdbSeriesHierarchyServiceImpl implements TmdbSeriesHierarchyService {
    private final TmdbClient tmdbClient;
    private final Clock clock;

    public TmdbSeriesHierarchyServiceImpl(TmdbClient tmdbClient, Clock clock) {
        this.tmdbClient = tmdbClient;
        this.clock = clock;
    }

    @Override
    public List<ContentSeasonSelectionDTO> listSeasons(
            String seriesTmdbId, boolean includeSpecials, String language) {
        String id = normalizeSeriesTmdbId(seriesTmdbId);
        TmdbTvFullDetails series = requireFound(tmdbClient.getTvFullDetails(id, language), "series");
        if (series.seasons() == null) return List.of();
        return series.seasons().stream()
                .filter(season -> season != null && season.seasonNumber() != null && season.seasonNumber() >= 0)
                .filter(season -> includeSpecials || season.seasonNumber() != 0)
                .map(season -> new ContentSeasonSelectionDTO(id, season.seasonNumber(), season.name(),
                        season.episodeCount(), TmdbImageUrlBuilder.posterUrl(season.posterPath()), parseDate(season.airDate())))
                .toList();
    }

    @Override
    public List<ContentEpisodeSelectionDTO> listEpisodes(
            String seriesTmdbId, int seasonNumber, boolean includeUnreleased, String language) {
        String id = normalizeSeriesTmdbId(seriesTmdbId);
        if (seasonNumber < 0) throw new BadRequestException("seasonNumber must be zero or a positive integer");
        TmdbSeasonFullDetails season = requireFound(
                tmdbClient.getSeasonFullDetails(id, seasonNumber, language), "season");
        if (season.episodes() == null) return List.of();
        LocalDate today = LocalDate.now(clock);
        return season.episodes().stream()
                .filter(episode -> isValidEpisode(episode))
                .map(episode -> toEpisode(id, seasonNumber, episode))
                .filter(episode -> includeUnreleased || episode.airDate() != null && !episode.airDate().isAfter(today))
                .toList();
    }

    private boolean isValidEpisode(TmdbEpisodeSummary episode) {
        return episode != null && episode.episodeNumber() != null && episode.episodeNumber() > 0
                && episode.name() != null && !episode.name().isBlank();
    }

    private ContentEpisodeSelectionDTO toEpisode(String id, int seasonNumber, TmdbEpisodeSummary episode) {
        return new ContentEpisodeSelectionDTO(id, seasonNumber, episode.episodeNumber(), episode.name(),
                TmdbImageUrlBuilder.stillUrl(episode.stillPath()), parseDate(episode.airDate()));
    }

    private <T> T requireFound(TmdbLookupResult<T> lookup, String subject) {
        if (lookup == null || lookup.isUnavailable()) throw new TmdbUnavailableException("TMDB is currently unavailable");
        if (lookup instanceof TmdbLookupResult.NotFound<T>) {
            throw new NotFoundException("No " + subject + " found on TMDB for the given id");
        }
        if (lookup instanceof TmdbLookupResult.Found<T> found && found.value() != null) return found.value();
        throw new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private String normalizeSeriesTmdbId(String value) {
        String id = value == null ? null : value.trim();
        if (id == null || !id.matches("[1-9]\\d*")) {
            throw new BadRequestException("seriesTmdbId must be a positive numeric identifier");
        }
        return id;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }
}
