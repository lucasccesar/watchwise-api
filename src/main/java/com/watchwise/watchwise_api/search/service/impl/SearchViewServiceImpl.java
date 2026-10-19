package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
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
import com.watchwise.watchwise_api.search.service.SearchViewPagination;
import com.watchwise.watchwise_api.search.service.TmdbSearchWindowReader;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplatePreviewDTO;
import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import com.watchwise.watchwise_api.pickstemplate.service.PicksTemplateService;
import com.watchwise.watchwise_api.userlist.dto.UserListCardDTO;
import com.watchwise.watchwise_api.userlist.dto.UserListViewItemType;
import com.watchwise.watchwise_api.userlist.dto.UserListViewQueryMode;
import com.watchwise.watchwise_api.userlist.dto.UserListViewRequest;
import com.watchwise.watchwise_api.userlist.service.UserListViewService;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SearchViewServiceImpl implements SearchViewService {

    private final UserRepository userRepository;
    private final TmdbClient tmdbClient;
    private final SearchContentViewAssembler contentViewAssembler;
    private final UserListViewService userListViewService;
    private final SearchListViewAssembler searchListViewAssembler;
    private final PicksTemplateService picksTemplateService;
    private final SearchUserViewReader searchUserViewReader;
    private final PageRequestFactory pageRequestFactory;
    private final TmdbSearchWindowReader windowReader = new TmdbSearchWindowReader();
    private final ContentSearchPagePlanner pagePlanner = new ContentSearchPagePlanner();

    @Autowired
    public SearchViewServiceImpl(
            UserRepository userRepository,
            TmdbClient tmdbClient,
            SearchContentViewAssembler contentViewAssembler,
            UserListViewService userListViewService,
            SearchListViewAssembler searchListViewAssembler,
            PicksTemplateService picksTemplateService,
            SearchUserViewReader searchUserViewReader,
            PageRequestFactory pageRequestFactory) {
        this.userRepository = userRepository;
        this.tmdbClient = tmdbClient;
        this.contentViewAssembler = contentViewAssembler;
        this.userListViewService = userListViewService;
        this.searchListViewAssembler = searchListViewAssembler;
        this.picksTemplateService = picksTemplateService;
        this.searchUserViewReader = searchUserViewReader;
        this.pageRequestFactory = pageRequestFactory;
    }

    public SearchViewServiceImpl(
            UserRepository userRepository,
            TmdbClient tmdbClient,
            SearchContentViewAssembler contentViewAssembler) {
        this(userRepository, tmdbClient, contentViewAssembler, null, null, null, null, null);
    }

    @Override
    public SearchViewDTO search(com.watchwise.watchwise_api.search.service.SearchViewQuery searchQuery) {
        User viewer = userRepository.findById(searchQuery.viewerId())
                .orElseThrow(() -> new NotFoundException("User not found"));
        String trimmedQuery = searchQuery.query().trim();
        SearchViewScope scope = searchQuery.scope();
        int page = searchQuery.page();
        int size = searchQuery.size();

        return switch (scope) {
            case CONTENT -> searchContent(viewer, trimmedQuery, scope, page, size);
            case MOVIE -> searchMovies(viewer, trimmedQuery, scope, page, size);
            case SERIES -> searchSeries(viewer, trimmedQuery, scope, page, size);
            case PERSON -> searchPeople(viewer, trimmedQuery, scope, page, size);
            case LIST -> searchLists(searchQuery);
            case PICKS_TEMPLATE -> searchTemplates(searchQuery);
            case USER -> searchUsers(searchQuery);
        };
    }

    private SearchViewDTO searchLists(com.watchwise.watchwise_api.search.service.SearchViewQuery query) {
        requireLocalDependencies();
        Page<UserListCardDTO> page = userListViewService.getView(new UserListViewRequest(
                query.viewerId(), null, UserListViewQueryMode.SEARCH, null, query.query(),
                query.itemType(), null, null, null, query.page(), query.size()));
        return localResponse(query, searchListViewAssembler.assemble(page.getContent()), List.of(), List.of(), List.of(),
                page.getTotalElements());
    }

    private SearchViewDTO searchTemplates(com.watchwise.watchwise_api.search.service.SearchViewQuery query) {
        requireLocalDependencies();
        Page<PicksTemplatePreviewDTO> page = picksTemplateService.searchForView(
                query.viewerId(), query.origin(), query.query(), query.page(), query.size());
        return localResponse(query, List.of(), page.getContent(), List.of(), List.of(), page.getTotalElements());
    }

    private SearchViewDTO searchUsers(com.watchwise.watchwise_api.search.service.SearchViewQuery query) {
        requireLocalDependencies();
        PageRequest pageable = pageRequestFactory.build(query.page(), query.size(), 20);
        Page<com.watchwise.watchwise_api.search.dto.SearchUserViewDTO> page = searchUserViewReader.read(
                query.viewerId(), query.query(), pageable);
        return localResponse(query, List.of(), List.of(), page.getContent(), List.of(), page.getTotalElements());
    }

    private SearchViewDTO localResponse(
            com.watchwise.watchwise_api.search.service.SearchViewQuery query,
            List<com.watchwise.watchwise_api.search.dto.SearchListViewDTO> lists,
            List<PicksTemplatePreviewDTO> templates,
            List<com.watchwise.watchwise_api.search.dto.SearchUserViewDTO> users,
            List<SearchPersonViewDTO> people,
            long totalResults) {
        return new SearchViewDTO(
                query.scope(), query.query(), query.page(), query.size(),
                SearchViewPagination.cappedTotalPages(totalResults, query.size()), totalResults,
                SearchViewPagination.hasNext(query.page(), totalResults, query.size()),
                List.of(), people, lists, templates, users);
    }

    private void requireLocalDependencies() {
        if (userListViewService == null || searchListViewAssembler == null || picksTemplateService == null
                || searchUserViewReader == null || pageRequestFactory == null) {
            throw new IllegalStateException("Local visual search dependencies are not configured");
        }
    }

    private SearchViewDTO searchMovies(User viewer, String query, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbMovieSearchResult> window = windowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchMovies(
                        query, viewer.getPreferredLanguage(), viewer.getPreferredRegion(), sourcePage));
        List<SearchContentCandidate> candidates = window.results().stream()
                .map(this::movieCandidate)
                .toList();
        return contentResponse(viewer, query, scope, page, size, window,
                contentViewAssembler.assemble(viewer, candidates));
    }

    private SearchViewDTO searchSeries(User viewer, String query, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbTvSearchResult> window = windowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchTv(query, viewer.getPreferredLanguage(), sourcePage));
        List<SearchContentCandidate> candidates = window.results().stream()
                .map(this::seriesCandidate)
                .toList();
        return contentResponse(viewer, query, scope, page, size, window,
                contentViewAssembler.assemble(viewer, candidates));
    }

    private SearchViewDTO searchPeople(User viewer, String query, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Window<TmdbPersonSearchResult> window = windowReader.readPage(
                page, size, sourcePage -> tmdbClient.searchPeople(query, viewer.getPreferredLanguage(), sourcePage));
        List<SearchPersonViewDTO> people = window.results().stream()
                .map(person -> new SearchPersonViewDTO(
                        person.id(), person.name(),
                        com.watchwise.watchwise_api.common.tmdb.TmdbImageUrlBuilder.profileUrl(person.profilePath()),
                        person.knownForDepartment()))
                .toList();
        return new SearchViewDTO(
                scope, query, page, size, window.totalPages(), window.totalResults(), window.hasNext(),
                List.of(), people);
    }

    private SearchViewDTO searchContent(User viewer, String query, SearchViewScope scope, int page, int size) {
        TmdbSearchWindowReader.Range<TmdbMovieSearchResult> movieTotals = windowReader.readRange(
                0, 1, sourcePage -> tmdbClient.searchMovies(
                        query, viewer.getPreferredLanguage(), viewer.getPreferredRegion(), sourcePage));
        TmdbSearchWindowReader.Range<TmdbTvSearchResult> seriesTotals = windowReader.readRange(
                0, 1, sourcePage -> tmdbClient.searchTv(query, viewer.getPreferredLanguage(), sourcePage));
        ContentSearchPagePlanner.Plan plan = pagePlanner.plan(
                movieTotals.totalResults(), seriesTotals.totalResults(), page, size);

        Map<MovieOrSeriesType, Map<Long, SearchContentCandidate>> candidatesByType = new EnumMap<>(MovieOrSeriesType.class);
        loadMovieCandidates(query, viewer, plan, candidatesByType);
        loadSeriesCandidates(query, viewer, plan, candidatesByType);

        List<SearchContentCandidate> candidates = plan.slots().stream()
                .map(slot -> candidatesByType.getOrDefault(slot.type(), Map.of()).get(slot.sourceIndex()))
                .filter(candidate -> candidate != null)
                .toList();
        List<SearchContentViewDTO> contents = candidates.isEmpty()
                ? List.of()
                : contentViewAssembler.assemble(viewer, candidates);

        return new SearchViewDTO(
                scope, query, page, size, plan.totalPages(), plan.totalResults(), plan.hasNext(),
                contents, List.of());
    }

    private void loadMovieCandidates(
            String query,
            User viewer,
            ContentSearchPagePlanner.Plan plan,
            Map<MovieOrSeriesType, Map<Long, SearchContentCandidate>> candidatesByType) {
        List<ContentSearchPagePlanner.Slot> slots = slotsOf(plan, MovieOrSeriesType.MOVIE);
        if (slots.isEmpty()) {
            return;
        }
        long firstSourceIndex = slots.getFirst().sourceIndex();
        int count = Math.toIntExact(slots.getLast().sourceIndex() - firstSourceIndex + 1);
        TmdbSearchWindowReader.Range<TmdbMovieSearchResult> range = windowReader.readRange(
                firstSourceIndex, count,
                sourcePage -> tmdbClient.searchMovies(
                        query, viewer.getPreferredLanguage(), viewer.getPreferredRegion(), sourcePage));
        Map<Long, SearchContentCandidate> candidatesBySourceIndex = new java.util.HashMap<>();
        for (Map.Entry<Long, TmdbMovieSearchResult> result : range.resultsBySourceIndex().entrySet()) {
            candidatesBySourceIndex.put(result.getKey(), movieCandidate(result.getValue()));
        }
        candidatesByType.put(MovieOrSeriesType.MOVIE, candidatesBySourceIndex);
    }

    private void loadSeriesCandidates(
            String query,
            User viewer,
            ContentSearchPagePlanner.Plan plan,
            Map<MovieOrSeriesType, Map<Long, SearchContentCandidate>> candidatesByType) {
        List<ContentSearchPagePlanner.Slot> slots = slotsOf(plan, MovieOrSeriesType.SERIES);
        if (slots.isEmpty()) {
            return;
        }
        long firstSourceIndex = slots.getFirst().sourceIndex();
        int count = Math.toIntExact(slots.getLast().sourceIndex() - firstSourceIndex + 1);
        TmdbSearchWindowReader.Range<TmdbTvSearchResult> range = windowReader.readRange(
                firstSourceIndex, count,
                sourcePage -> tmdbClient.searchTv(query, viewer.getPreferredLanguage(), sourcePage));
        Map<Long, SearchContentCandidate> candidatesBySourceIndex = new java.util.HashMap<>();
        for (Map.Entry<Long, TmdbTvSearchResult> result : range.resultsBySourceIndex().entrySet()) {
            candidatesBySourceIndex.put(result.getKey(), seriesCandidate(result.getValue()));
        }
        candidatesByType.put(MovieOrSeriesType.SERIES, candidatesBySourceIndex);
    }

    private List<ContentSearchPagePlanner.Slot> slotsOf(
            ContentSearchPagePlanner.Plan plan,
            MovieOrSeriesType type) {
        return plan.slots().stream()
                .filter(slot -> slot.type() == type)
                .toList();
    }

    private SearchViewDTO contentResponse(
            User viewer,
            String query,
            SearchViewScope scope,
            int page,
            int size,
            TmdbSearchWindowReader.Window<?> window,
            List<SearchContentViewDTO> contents) {
        return new SearchViewDTO(
                scope, query, page, size, window.totalPages(), window.totalResults(), window.hasNext(),
                contents, List.of());
    }

    private SearchContentCandidate movieCandidate(TmdbMovieSearchResult movie) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.MOVIE, movie.id(), null, null, null),
                movie.title(), movie.posterPath(), parseDate(movie.releaseDate()), null,
                movie.voteAverage(), null);
    }

    private SearchContentCandidate seriesCandidate(TmdbTvSearchResult series) {
        return new SearchContentCandidate(
                new ContentCoordinate(ContentType.SERIES, series.id(), null, null, null),
                series.name(), series.posterPath(), parseDate(series.firstAirDate()), null,
                series.voteAverage(), null);
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
