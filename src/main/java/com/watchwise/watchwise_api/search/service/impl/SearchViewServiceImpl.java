package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder;
import com.watchwise.watchwise_api.common.tmdb.TmdbMovieSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbPersonSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.common.tmdb.TmdbTvSearchResult;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.content.service.ContentCoordinate;
import com.watchwise.watchwise_api.search.dto.SearchContentViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchPersonViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchViewDTO;
import com.watchwise.watchwise_api.search.service.ContentSearchPagePlanner;
import com.watchwise.watchwise_api.search.service.SearchViewScope;
import com.watchwise.watchwise_api.search.service.SearchViewService;
import com.watchwise.watchwise_api.search.service.TmdbSearchWindowReader;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class SearchViewServiceImpl implements SearchViewService {

    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final TmdbSearchWindowReader searchWindowReader = new TmdbSearchWindowReader();
    private final ContentSearchPagePlanner contentPagePlanner = new ContentSearchPagePlanner();
    private final SearchContentViewAssembler contentViewAssembler;

    public SearchViewServiceImpl(
            UserRepository userRepository,
            TmdbClient tmdbClient,
            SearchContentViewAssembler contentViewAssembler) {
        this.userRepository = userRepository;
        this.tmdbClient = tmdbClient;
        this.contentViewAssembler = contentViewAssembler;
    }

    @Override
    public SearchViewDTO search(
            java.util.UUID viewerId, String query, SearchViewScope scope, int page, int size) {
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String trimmedQuery = query.trim();
        String language = viewer.getPreferredLanguage();

        return switch (scope) {
            case MOVIE -> searchMovies(viewer, trimmedQuery, language, scope, page, size);
            case SERIES -> searchSeries(viewer, trimmedQuery, language, scope, page, size);
            case PERSON -> searchPeople(trimmedQuery, language, scope, page, size);
            case CONTENT -> searchContent(viewer, trimmedQuery, language, scope, page, size);
        };
    }

    private SearchViewDTO searchMovies(
            User viewer, String query, String language, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window = searchWindowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchMovies(query, language, sourcePage));
        List<SearchContentViewDTO> contents = contentViewAssembler.assemble(
                viewer, window.results().stream().map(this::toMovieCandidate).toList());
        return contentView(scope, query, page, size, window.totalPages(), window.totalResults(),
                window.hasNext(), contents);
    }

    private SearchViewDTO searchSeries(
            User viewer, String query, String language, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbTvSearchResult> window = searchWindowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchTv(query, language, sourcePage));
        List<SearchContentViewDTO> contents = contentViewAssembler.assemble(
                viewer, window.results().stream().map(this::toSeriesCandidate).toList());
        return contentView(scope, query, page, size, window.totalPages(), window.totalResults(),
                window.hasNext(), contents);
    }

    private SearchViewDTO searchPeople(
            String query, String language, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbPersonSearchResult> window = searchWindowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchPeople(query, language, sourcePage));
        List<SearchPersonViewDTO> people = window.results().stream()
                .map(person -> new SearchPersonViewDTO(
                        person.id(), person.name(), TmdbImageUrlBuilder.profileUrl(person.profilePath()),
                        person.knownForDepartment()))
                .toList();
        return new SearchViewDTO(
                scope, query, page, size, window.totalPages(), window.totalResults(), window.hasNext(),
                List.of(), people);
    }

    private SearchViewDTO searchContent(
            User viewer, String query, String language, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Range<TmdbMovieSearchResult> movieMetadata = searchWindowReader.readRange(
                0, 1, sourcePage -> tmdbClient.searchMovies(query, language, sourcePage));
        TmdbSearchWindowReader.Range<TmdbTvSearchResult> seriesMetadata = searchWindowReader.readRange(
                0, 1, sourcePage -> tmdbClient.searchTv(query, language, sourcePage));
        ContentSearchPagePlanner.Plan plan = contentPagePlanner.plan(
                movieMetadata.totalResults(), seriesMetadata.totalResults(), page, size);

        List<SearchContentCandidate> candidates = loadContentCandidates(query, language, plan);
        List<SearchContentViewDTO> contents = contentViewAssembler.assemble(viewer, candidates);
        return contentView(scope, query, page, size, plan.totalPages(), plan.totalResults(),
                plan.hasNext(), contents);
    }

    private List<SearchContentCandidate> loadContentCandidates(
            String query, String language, ContentSearchPagePlanner.Plan plan) {
        Map<MovieOrSeriesType, Map<Long, SearchContentCandidate>> candidatesByType =
                new EnumMap<>(MovieOrSeriesType.class);
        loadCandidatesForType(plan, MovieOrSeriesType.MOVIE, query, language, candidatesByType);
        loadCandidatesForType(plan, MovieOrSeriesType.SERIES, query, language, candidatesByType);

        return plan.slots().stream()
                .map(slot -> candidatesByType.getOrDefault(slot.type(), Map.of()).get(slot.sourceIndex()))
                .filter(Objects::nonNull)
                .toList();
    }

    private void loadCandidatesForType(
            ContentSearchPagePlanner.Plan plan,
            MovieOrSeriesType type,
            String query,
            String language,
            Map<MovieOrSeriesType, Map<Long, SearchContentCandidate>> candidatesByType) {
        List<ContentSearchPagePlanner.Slot> slots = plan.slots().stream()
                .filter(slot -> slot.type() == type)
                .toList();
        if (slots.isEmpty()) {
            return;
        }

        long firstSourceIndex = slots.stream()
                .mapToLong(ContentSearchPagePlanner.Slot::sourceIndex)
                .min()
                .orElseThrow();
        long lastSourceIndex = slots.stream()
                .mapToLong(ContentSearchPagePlanner.Slot::sourceIndex)
                .max()
                .orElseThrow();
        int count = Math.toIntExact(lastSourceIndex - firstSourceIndex + 1);

        Map<Long, SearchContentCandidate> candidatesBySourceIndex = new HashMap<>();
        if (type == MovieOrSeriesType.MOVIE) {
            TmdbSearchWindowReader.Range<TmdbMovieSearchResult> range = searchWindowReader.readRange(
                    firstSourceIndex, count,
                    sourcePage -> tmdbClient.searchMovies(query, language, sourcePage));
            for (int offset = 0; offset < range.results().size(); offset++) {
                candidatesBySourceIndex.put(
                        firstSourceIndex + offset, toMovieCandidate(range.results().get(offset)));
            }
        } else {
            TmdbSearchWindowReader.Range<TmdbTvSearchResult> range = searchWindowReader.readRange(
                    firstSourceIndex, count,
                    sourcePage -> tmdbClient.searchTv(query, language, sourcePage));
            for (int offset = 0; offset < range.results().size(); offset++) {
                candidatesBySourceIndex.put(
                        firstSourceIndex + offset, toSeriesCandidate(range.results().get(offset)));
            }
        }
        candidatesByType.put(type, candidatesBySourceIndex);
    }

    private SearchContentCandidate toMovieCandidate(TmdbMovieSearchResult movie) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.MOVIE, movie.id(), null, null, null),
                movie.title(), movie.posterPath(), parseDate(movie.releaseDate()), null,
                movie.voteAverage(), null);
    }

    private SearchContentCandidate toSeriesCandidate(TmdbTvSearchResult series) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.SERIES, series.id(), null, null, null),
                series.name(), series.posterPath(), parseDate(series.firstAirDate()), null,
                series.voteAverage(), null);
    }

    private SearchViewDTO contentView(
            SearchViewScope scope,
            String query,
            int page,
            int size,
            int totalPages,
            long totalResults,
            boolean hasNext,
            List<SearchContentViewDTO> contents) {
        return new SearchViewDTO(
                scope, query, page, size, totalPages, totalResults, hasNext,
                contents == null ? List.of() : contents, List.of());
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
