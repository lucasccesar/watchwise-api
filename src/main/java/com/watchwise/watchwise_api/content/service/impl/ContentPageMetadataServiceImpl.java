package com.watchwise.watchwise_api.content.service.impl;

import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbEpisodeFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbExternalIds;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDate;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbProvider;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionProviders;
import com.watchwise.watchwise_api.common.tmdb.TmdbRegionReleaseDates;
import com.watchwise.watchwise_api.common.tmdb.TmdbSeasonFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvContentRating;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvFullDetails;
import com.watchwise.watchwise_api.common.tmdb.TmdbWatchProviders;
import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.dto.ContentPageWatchProviderDTO;
import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.content.service.ContentPageMetadataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class ContentPageMetadataServiceImpl implements ContentPageMetadataService {

    private static final String TMDB_MOVIE_URL = "https://www.themoviedb.org/movie/";
    private static final String TMDB_TV_URL = "https://www.themoviedb.org/tv/";
    private static final String IMDB_TITLE_URL = "https://www.imdb.com/title/";

    private final TmdbClient tmdbClient;

    @Override
    public ContentPageMetadataDTO getMetadata(Content content, String language, String region) {
        return switch (content.getType()) {
            case MOVIE -> movieMetadata(content, language, region);
            case SERIES -> seriesMetadata(content, language, region);
            case SEASON -> seasonMetadata(content, language, region);
            case EPISODE -> episodeMetadata(content, language, region);
        };
    }

    private ContentPageMetadataDTO movieMetadata(Content content, String language, String region) {
        TmdbMovieFullDetails details = tmdbClient.getMovieFullDetails(content.getTmdbId(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        String certification = tmdbClient.getMovieReleaseDates(content.getTmdbId(), language)
                .toOptional()
                .flatMap(releaseDates -> movieCertification(releaseDates, region))
                .orElse(null);

        return new ContentPageMetadataDTO(
                details.originalLanguage(),
                certification,
                details.homepage(),
                TMDB_MOVIE_URL + content.getTmdbId(),
                imdbUrl(details.externalIds()),
                providers(details.watchProviders(), region));
    }

    private ContentPageMetadataDTO seriesMetadata(Content content, String language, String region) {
        TmdbTvFullDetails details = tmdbClient.getTvFullDetails(content.getTmdbId(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        return tvMetadata(
                details,
                tvCertification(content.getTmdbId(), language, region),
                details.watchProviders(),
                region,
                TMDB_TV_URL + content.getTmdbId());
    }

    private ContentPageMetadataDTO seasonMetadata(Content content, String language, String region) {
        TmdbSeasonFullDetails season = tmdbClient
                .getSeasonFullDetails(content.getSeriesTmdbId(), content.getSeasonNumber(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tvDetails(content.getSeriesTmdbId(), language);
        TmdbWatchProviders watchProviders = providersForSeasonOrSeries(season.watchProviders(), series.watchProviders(), region);
        return tvMetadata(
                series,
                tvCertification(content.getSeriesTmdbId(), language, region),
                watchProviders,
                region,
                TMDB_TV_URL + content.getSeriesTmdbId() + "/season/" + content.getSeasonNumber());
    }

    private ContentPageMetadataDTO episodeMetadata(Content content, String language, String region) {
        tmdbClient
                .getEpisodeFullDetails(
                        content.getSeriesTmdbId(), content.getSeasonNumber(), content.getEpisodeNumber(), language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
        TmdbTvFullDetails series = tvDetails(content.getSeriesTmdbId(), language);
        return tvMetadata(
                series,
                tvCertification(content.getSeriesTmdbId(), language, region),
                series.watchProviders(),
                region,
                TMDB_TV_URL + content.getSeriesTmdbId()
                        + "/season/" + content.getSeasonNumber()
                        + "/episode/" + content.getEpisodeNumber());
    }

    private TmdbTvFullDetails tvDetails(String tmdbId, String language) {
        return tmdbClient.getTvFullDetails(tmdbId, language)
                .toOptional().orElseThrow(this::tmdbUnavailable);
    }

    private ContentPageMetadataDTO tvMetadata(
            TmdbTvFullDetails details,
            String certification,
            TmdbWatchProviders watchProviders,
            String region,
            String tmdbUrl) {
        return new ContentPageMetadataDTO(
                details.originalLanguage(),
                certification,
                details.homepage(),
                tmdbUrl,
                imdbUrl(details.externalIds()),
                providers(watchProviders, region));
    }

    private String tvCertification(String tmdbId, String language, String region) {
        return tmdbClient.getTvContentRatings(tmdbId, language)
                .toOptional()
                .flatMap(ratings -> ratings.results() == null
                        ? Optional.empty()
                        : ratings.results().stream()
                                .filter(Objects::nonNull)
                                .filter(rating -> sameRegion(rating.isoCode(), region))
                                .map(TmdbTvContentRating::rating)
                                .filter(this::hasText)
                                .findFirst())
                .orElse(null);
    }

    private Optional<String> movieCertification(TmdbMovieReleaseDates releaseDates, String region) {
        if (releaseDates.results() == null) {
            return Optional.empty();
        }
        return releaseDates.results().stream()
                .filter(Objects::nonNull)
                .filter(releaseRegion -> sameRegion(releaseRegion.isoCode(), region))
                .flatMap(releaseRegion -> releaseDates(releaseRegion).stream())
                .map(TmdbMovieReleaseDate::certification)
                .filter(this::hasText)
                .findFirst();
    }

    private List<TmdbMovieReleaseDate> releaseDates(TmdbRegionReleaseDates releaseRegion) {
        return releaseRegion.releaseDates() == null ? List.of() : releaseRegion.releaseDates();
    }

    private List<ContentPageWatchProviderDTO> providers(TmdbWatchProviders watchProviders, String region) {
        if (watchProviders == null || watchProviders.results() == null || region == null) {
            return List.of();
        }
        TmdbRegionProviders regionProviders = watchProviders.results().entrySet().stream()
                .filter(entry -> sameRegion(entry.getKey(), region))
                .map(java.util.Map.Entry::getValue)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (regionProviders == null) {
            return List.of();
        }

        List<ContentPageWatchProviderDTO> result = new ArrayList<>();
        appendProviders(result, regionProviders.flatrate(), "flatrate", regionProviders.link());
        appendProviders(result, regionProviders.rent(), "rent", regionProviders.link());
        appendProviders(result, regionProviders.buy(), "buy", regionProviders.link());
        return result;
    }

    private void appendProviders(
            List<ContentPageWatchProviderDTO> target,
            List<TmdbProvider> providers,
            String type,
            String watchUrl) {
        if (providers == null) {
            return;
        }
        providers.stream()
                .filter(Objects::nonNull)
                .map(provider -> new ContentPageWatchProviderDTO(
                        provider.providerId(), provider.providerName(), provider.logoPath(), type, watchUrl))
                .forEach(target::add);
    }

    private TmdbWatchProviders providersForSeasonOrSeries(
            TmdbWatchProviders seasonProviders, TmdbWatchProviders seriesProviders, String region) {
        if (hasRegion(seasonProviders, region)) {
            return seasonProviders;
        }
        return seriesProviders;
    }

    private boolean hasRegion(TmdbWatchProviders providers, String region) {
        if (providers == null || providers.results() == null || region == null) {
            return false;
        }
        return providers.results().keySet().stream().anyMatch(key -> sameRegion(key, region));
    }

    private String imdbUrl(TmdbExternalIds externalIds) {
        if (externalIds == null || !hasText(externalIds.imdbId())) {
            return null;
        }
        return IMDB_TITLE_URL + externalIds.imdbId();
    }

    private boolean sameRegion(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private TmdbUnavailableException tmdbUnavailable() {
        return new TmdbUnavailableException("TMDB is currently unavailable");
    }
}
