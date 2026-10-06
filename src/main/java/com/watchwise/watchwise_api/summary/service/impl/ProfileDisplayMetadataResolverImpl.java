package com.watchwise.watchwise_api.summary.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.contentposter.service.UserContentPosterService;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightContentDTO;
import com.watchwise.watchwise_api.summary.service.ProfileDisplayMetadataResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileDisplayMetadataResolverImpl implements ProfileDisplayMetadataResolver {

    private final TmdbClient tmdbClient;
    private final UserContentPosterService userContentPosterService;

    @Override
    public ProfileHighlightContentDTO resolveStoredContent(UUID ownerId, Content content) {
        if (content == null || content.getType() == null) {
            return null;
        }

        Map<UUID, String> posters = userContentPosterService.findByUserAndContentIds(
                ownerId, List.of(content.getId()));
        String customPosterUrl = posters == null ? null : posters.get(content.getId());

        if (content.getType() == ContentType.MOVIE) {
            try {
                var details = tmdbClient.getMovieFullDetails(
                                content.getTmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)
                        .toOptional().orElse(null);
                if (details == null) {
                    return null;
                }
                return new ProfileHighlightContentDTO(
                        ContentType.MOVIE, content.getId(), content.getTmdbId(), null,
                        details.title(), releaseYearOf(details.releaseDate()),
                        TmdbImageUrlBuilder.posterUrl(details.posterPath()), customPosterUrl);
            } catch (TmdbUnavailableException exception) {
                return null;
            }
        }

        if (content.getType() == ContentType.SERIES) {
            try {
                var details = tmdbClient.getTvFullDetails(
                                content.getTmdbId(), TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)
                        .toOptional().orElse(null);
                if (details == null) {
                    return null;
                }
                return new ProfileHighlightContentDTO(
                        ContentType.SERIES, content.getId(), content.getTmdbId(), null,
                        details.name(), releaseYearOf(details.firstAirDate()),
                        TmdbImageUrlBuilder.posterUrl(details.posterPath()), customPosterUrl);
            } catch (TmdbUnavailableException exception) {
                return null;
            }
        }

        return null;
    }

    @Override
    public ProfileHighlightContentDTO resolveSeries(UUID ownerId, String seriesTmdbId) {
        if (seriesTmdbId == null || seriesTmdbId.isBlank()) {
            return null;
        }
        try {
            var details = tmdbClient.getTvFullDetails(
                            seriesTmdbId, TmdbClient.LANGUAGE_INDEPENDENT_LOOKUP_LANGUAGE)
                    .toOptional().orElse(null);
            if (details == null) {
                return null;
            }
            Map<String, String> posters = userContentPosterService.findSeriesPosters(ownerId, List.of(seriesTmdbId));
            return new ProfileHighlightContentDTO(
                    ContentType.SERIES, null, null, seriesTmdbId,
                    details.name(), releaseYearOf(details.firstAirDate()),
                    TmdbImageUrlBuilder.posterUrl(details.posterPath()),
                    posters == null ? null : posters.get(seriesTmdbId));
        } catch (TmdbUnavailableException exception) {
            return null;
        }
    }

    private Integer releaseYearOf(String date) {
        if (date == null || date.isBlank() || date.length() < 4) {
            return null;
        }
        try {
            return Integer.valueOf(date.substring(0, 4));
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
