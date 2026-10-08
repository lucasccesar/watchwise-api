package com.watchwise.watchwise_api.trending.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingMovieResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbTrendingTvResult;
import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentCardFieldSet;
import com.watchwise.watchwise_api.content.dto.ContentPreviewStatus;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCardContext;
import com.watchwise.watchwise_api.content.service.ContentCardSpec;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.content.service.impl.ContentCardAssembler;
import com.watchwise.watchwise_api.trending.dto.TrendingCardDTO;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingService;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class TrendingServiceImpl implements TrendingService {

    private static final Set<ContentCardFieldSet> CARD_FIELDS = Set.of(
            ContentCardFieldSet.BASIC_METADATA,
            ContentCardFieldSet.VIEWER_STATE);

    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final ContentCardAssembler contentCardAssembler;

    @Override
    public TrendingResponseDTO getTrending(UUID viewerId, TrendingTimeWindow timeWindow, int size) {
        User viewer = findViewer(viewerId);
        String language = viewer.getPreferredLanguage();
        String window = timeWindow.value();

        TmdbPage<TmdbTrendingMovieResult> movies = getPage(
                tmdbClient.getTrendingMovies(window, language, 1), 1);
        TmdbPage<TmdbTrendingTvResult> series = getPage(
                tmdbClient.getTrendingSeries(window, language, 1), 1);

        Map<ContentCoordinate, ContentCardDTO> cards = assembleCards(
                viewer, movieSpecs(movies.results(), size), seriesSpecs(series.results(), size));
        return new TrendingResponseDTO(
                toMovieCards(movies.results(), size, cards),
                toSeriesCards(series.results(), size, cards),
                toSectionPage(movies, size),
                toSectionPage(series, size));
    }

    @Override
    public TrendingResponseDTO getTrendingSection(
            UUID viewerId, MovieOrSeriesType type, TrendingTimeWindow timeWindow, int page, int size) {
        User viewer = findViewer(viewerId);
        String language = viewer.getPreferredLanguage();
        String window = timeWindow.value();

        if (type == MovieOrSeriesType.MOVIE) {
            TmdbPage<TmdbTrendingMovieResult> movies = getPage(
                    tmdbClient.getTrendingMovies(window, language, page), page);
            Map<ContentCoordinate, ContentCardDTO> cards = assembleCards(
                    viewer, movieSpecs(movies.results(), size), List.of());
            return new TrendingResponseDTO(
                    toMovieCards(movies.results(), size, cards),
                    List.of(),
                    toSectionPage(movies, size),
                    null);
        }

        TmdbPage<TmdbTrendingTvResult> series = getPage(
                tmdbClient.getTrendingSeries(window, language, page), page);
        Map<ContentCoordinate, ContentCardDTO> cards = assembleCards(
                viewer, List.of(), seriesSpecs(series.results(), size));
        return new TrendingResponseDTO(
                List.of(),
                toSeriesCards(series.results(), size, cards),
                null,
                toSectionPage(series, size));
    }

    private User findViewer(UUID viewerId) {
        return userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }

    private Map<ContentCoordinate, ContentCardDTO> assembleCards(
            User viewer,
            Collection<ContentCardSpec> movieSpecs,
            Collection<ContentCardSpec> seriesSpecs) {
        List<ContentCardSpec> specs = Stream.concat(movieSpecs.stream(), seriesSpecs.stream()).toList();
        if (specs.isEmpty()) {
            return Map.of();
        }
        return contentCardAssembler.assemble(
                specs,
                new ContentCardContext(
                        viewer.getPreferredLanguage(), viewer.getPreferredRegion(), null, viewer.getId()),
                CARD_FIELDS);
    }

    private List<ContentCardSpec> movieSpecs(List<TmdbTrendingMovieResult> results, int size) {
        return results.stream()
                .limit(size)
                .map(movie -> new ContentCardSpec(
                        new ContentCoordinate(ContentType.MOVIE, movie.id(), null, null, null),
                        movie.title(),
                        movie.posterPath(),
                        parseDate(movie.releaseDate()),
                        movie.runtime()))
                .toList();
    }

    private List<ContentCardSpec> seriesSpecs(List<TmdbTrendingTvResult> results, int size) {
        return results.stream()
                .limit(size)
                .map(series -> new ContentCardSpec(
                        new ContentCoordinate(ContentType.SERIES, series.id(), null, null, null),
                        series.name(),
                        series.posterPath(),
                        parseDate(series.firstAirDate()),
                        null))
                .toList();
    }

    private List<TrendingCardDTO> toMovieCards(
            List<TmdbTrendingMovieResult> results,
            int size,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        return results.stream()
                .limit(size)
                .map(movie -> toMovieCard(movie, cards))
                .toList();
    }

    private List<TrendingCardDTO> toSeriesCards(
            List<TmdbTrendingTvResult> results,
            int size,
            Map<ContentCoordinate, ContentCardDTO> cards) {
        return results.stream()
                .limit(size)
                .map(series -> toSeriesCard(series, cards))
                .toList();
    }

    private TrendingCardDTO toMovieCard(
            TmdbTrendingMovieResult movie, Map<ContentCoordinate, ContentCardDTO> cards) {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.MOVIE, movie.id(), null, null, null);
        ContentCardDTO card = cards.get(coordinate);
        String title = firstNonNull(card == null ? null : card.title(), movie.title());
        String posterPath = firstNonNull(card == null ? null : card.posterPath(), movie.posterPath());
        Integer year = firstNonNull(card == null ? null : card.releaseYear(), releaseYear(movie.releaseDate()));
        Integer runtime = firstNonNull(card == null ? null : card.runtimeMinutes(), movie.runtime());
        return new TrendingCardDTO(
                movie.id(), MovieOrSeriesType.MOVIE, title, TmdbImageUrlBuilder.posterUrl(posterPath),
                year, card == null ? null : card.genres(), movie.voteAverage(), movie.popularity(), runtime, null,
                card == null ? null : card.viewerState(),
                card == null ? ContentPreviewStatus.UNAVAILABLE : card.previewStatus());
    }

    private TrendingCardDTO toSeriesCard(
            TmdbTrendingTvResult series, Map<ContentCoordinate, ContentCardDTO> cards) {
        ContentCoordinate coordinate = new ContentCoordinate(ContentType.SERIES, series.id(), null, null, null);
        ContentCardDTO card = cards.get(coordinate);
        String title = firstNonNull(card == null ? null : card.title(), series.name());
        String posterPath = firstNonNull(card == null ? null : card.posterPath(), series.posterPath());
        Integer year = firstNonNull(card == null ? null : card.releaseYear(), releaseYear(series.firstAirDate()));
        Integer runtime = card == null ? null : card.runtimeMinutes();
        Integer numberOfSeasons = firstNonNull(
                series.numberOfSeasons(), card == null ? null : card.numberOfSeasons());
        return new TrendingCardDTO(
                series.id(), MovieOrSeriesType.SERIES, title, TmdbImageUrlBuilder.posterUrl(posterPath),
                year, card == null ? null : card.genres(), series.voteAverage(), series.popularity(), runtime,
                numberOfSeasons, card == null ? null : card.viewerState(),
                card == null ? ContentPreviewStatus.UNAVAILABLE : card.previewStatus());
    }

    private TrendingResponseDTO.SectionPage toSectionPage(TmdbPage<?> page, int size) {
        return new TrendingResponseDTO.SectionPage(
                page.page(), size, page.totalPages(), page.totalResults(), page.page() < page.totalPages());
    }

    private <T> TmdbPage<T> getPage(
            TmdbLookupResult<TmdbSearchPage<T>> lookup, int requestedPage) {
        if (lookup.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        if (!(lookup instanceof TmdbLookupResult.Found<?> found) || found.value() == null) {
            return new TmdbPage<>(List.of(), requestedPage, 0, 0);
        }
        @SuppressWarnings("unchecked")
        TmdbSearchPage<T> page = (TmdbSearchPage<T>) found.value();
        return new TmdbPage<>(
                page.results() == null ? List.of() : page.results(),
                page.page(),
                page.totalPages(),
                page.totalResults());
    }

    private LocalDate parseDate(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private Integer releaseYear(String date) {
        LocalDate parsed = parseDate(date);
        return parsed == null ? null : parsed.getYear();
    }

    private <T> T firstNonNull(T first, T fallback) {
        return first != null ? first : fallback;
    }

    private record TmdbPage<T>(List<T> results, int page, int totalPages, long totalResults) {
    }
}
