package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvDisplayDetails;
import com.watchwise.watchwise_api.summary.service.EpisodeRatingsMetadata;
import com.watchwise.watchwise_api.summary.service.EpisodeRatingsMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class EpisodeRatingsMetadataServiceImpl implements EpisodeRatingsMetadataService {

    private final TmdbClient tmdbClient;

    @Override
    public Map<String, EpisodeRatingsMetadata> resolve(Collection<String> seriesTmdbIds, String language) {
        if (seriesTmdbIds == null) {
            throw new BadRequestException("Series TMDB IDs are required");
        }

        Set<String> normalizedIds = new LinkedHashSet<>();
        for (String seriesTmdbId : seriesTmdbIds) {
            normalizedIds.add(normalizeSeriesTmdbId(seriesTmdbId));
        }

        Map<String, EpisodeRatingsMetadata> metadataBySeriesId = new LinkedHashMap<>();
        for (String seriesTmdbId : normalizedIds) {
            metadataBySeriesId.put(seriesTmdbId, resolve(seriesTmdbId, language));
        }
        return metadataBySeriesId;
    }

    @Override
    public EpisodeRatingsMetadata resolve(String seriesTmdbId, String language) {
        String normalizedSeriesTmdbId = normalizeSeriesTmdbId(seriesTmdbId);
        TmdbLookupResult<TmdbTvDisplayDetails> lookup =
                tmdbClient.getTvDisplayDetails(normalizedSeriesTmdbId, language);

        if (lookup instanceof TmdbLookupResult.Found<TmdbTvDisplayDetails> found) {
            TmdbTvDisplayDetails details = found.value();
            return new EpisodeRatingsMetadata(
                    normalizedSeriesTmdbId,
                    details.name(),
                    releaseYearOf(details.firstAirDate()),
                    TmdbImageUrlBuilder.posterUrl(details.posterPath()),
                    TmdbImageUrlBuilder.backdropUrl(details.backdropPath()));
        }
        if (lookup instanceof TmdbLookupResult.NotFound<TmdbTvDisplayDetails>) {
            throw new NotFoundException("Series not found in TMDB");
        }
        throw new TmdbUnavailableException("TMDB is currently unavailable");
    }

    private String normalizeSeriesTmdbId(String seriesTmdbId) {
        if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
            throw new BadRequestException("Series TMDB ID must not be blank");
        }
        return seriesTmdbId.trim();
    }

    private Integer releaseYearOf(String firstAirDate) {
        if (firstAirDate == null || firstAirDate.isBlank() || firstAirDate.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(firstAirDate.substring(0, 4));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
