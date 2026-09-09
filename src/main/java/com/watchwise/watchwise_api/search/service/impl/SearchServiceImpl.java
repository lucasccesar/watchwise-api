package com.watchwise.watchwise_api.search.service.impl;

import com.watchwise.watchwise_api.common.exception.NotFoundException;
import com.watchwise.watchwise_api.common.exception.TmdbUnavailableException;
import com.watchwise.watchwise_api.common.pagination.PageRequestFactory;
import com.watchwise.watchwise_api.common.tmdb.TmdbClient;
import com.watchwise.watchwise_api.common.tmdb.TmdbLookupResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbMultiSearchResult;
import com.watchwise.watchwise_api.common.tmdb.TmdbSearchPage;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.search.dto.SearchContentDTO;
import com.watchwise.watchwise_api.search.dto.SearchPersonDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.dto.SearchUserListDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
import com.watchwise.watchwise_api.user.dto.UserPreviewDTO;
import com.watchwise.watchwise_api.user.entity.User;
import com.watchwise.watchwise_api.user.repository.UserRepository;
import com.watchwise.watchwise_api.userlist.entity.UserList;
import com.watchwise.watchwise_api.userlist.repository.UserListRepository;
import com.watchwise.watchwise_api.userlist.service.UserListItemService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private final UserRepository userRepository;
    private final UserListRepository userListRepository;
    private final UserListItemService userListItemService;
    private final TmdbClient tmdbClient;
    private final PageRequestFactory pageRequestFactory;

    @Override
    public SearchResultDTO search(UUID viewerId, String query, SearchType type, Integer pageNumber, Integer pageSize) {
        User viewer = userRepository.findById(viewerId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        String trimmedQuery = query.trim();
        PageRequest pageRequest = pageRequestFactory.build(
                pageNumber,
                PageRequestFactory.DEFAULT_PAGE_SIZE,
                PageRequestFactory.DEFAULT_PAGE_SIZE);
        int page = pageRequest.getPageNumber() + 1;
        int resultLimit = pageRequest.getPageSize();

        if (type == null) {
            SearchResultDTO externalResults = searchMulti(trimmedQuery, viewer.getPreferredLanguage(), page, resultLimit);
            return new SearchResultDTO(
                    externalResults.contents(),
                    externalResults.people(),
                    searchLists(viewerId, trimmedQuery, pageRequest),
                    searchUsers(trimmedQuery, pageRequest));
        }

        return switch (type) {
            case MOVIE -> searchMovies(trimmedQuery, viewer.getPreferredLanguage(), page, resultLimit);
            case SERIES -> searchSeries(trimmedQuery, viewer.getPreferredLanguage(), page, resultLimit);
            case PERSON -> searchPeople(trimmedQuery, viewer.getPreferredLanguage(), page, resultLimit);
            case LIST -> new SearchResultDTO(List.of(), List.of(), searchLists(viewerId, trimmedQuery, pageRequest), List.of());
            case USER -> new SearchResultDTO(List.of(), List.of(), List.of(), searchUsers(trimmedQuery, pageRequest));
        };
    }

    private SearchResultDTO searchMovies(String query, String language, int page, int resultLimit) {
        List<SearchContentDTO> contents = requireResults(tmdbClient.searchMovies(query, language, page)).stream()
                .map(movie -> new SearchContentDTO(
                        movie.id(), MovieOrSeriesType.MOVIE, movie.title(), movie.posterPath(), releaseYear(movie.releaseDate())))
                .limit(resultLimit)
                .toList();
        return new SearchResultDTO(contents, List.of(), List.of(), List.of());
    }

    private SearchResultDTO searchSeries(String query, String language, int page, int resultLimit) {
        List<SearchContentDTO> contents = requireResults(tmdbClient.searchTv(query, language, page)).stream()
                .map(series -> new SearchContentDTO(
                        series.id(), MovieOrSeriesType.SERIES, series.name(), series.posterPath(), releaseYear(series.firstAirDate())))
                .limit(resultLimit)
                .toList();
        return new SearchResultDTO(contents, List.of(), List.of(), List.of());
    }

    private SearchResultDTO searchPeople(String query, String language, int page, int resultLimit) {
        List<SearchPersonDTO> people = requireResults(tmdbClient.searchPeople(query, language, page)).stream()
                .map(person -> new SearchPersonDTO(person.id(), person.name(), person.profilePath()))
                .limit(resultLimit)
                .toList();
        return new SearchResultDTO(List.of(), people, List.of(), List.of());
    }

    private SearchResultDTO searchMulti(String query, String language, int page, int resultLimit) {
        List<TmdbMultiSearchResult> results = requireResults(tmdbClient.searchMulti(query, language, page));
        List<SearchContentDTO> contents = results.stream()
                .filter(result -> "movie".equals(result.mediaType()) || "tv".equals(result.mediaType()))
                .map(this::toSearchContentDto)
                .limit(resultLimit)
                .toList();
        List<SearchPersonDTO> people = results.stream()
                .filter(result -> "person".equals(result.mediaType()))
                .map(result -> new SearchPersonDTO(result.id(), result.name(), result.profilePath()))
                .limit(resultLimit)
                .toList();
        return new SearchResultDTO(contents, people, List.of(), List.of());
    }

    private SearchContentDTO toSearchContentDto(TmdbMultiSearchResult result) {
        if ("movie".equals(result.mediaType())) {
            return new SearchContentDTO(
                    result.id(), MovieOrSeriesType.MOVIE, result.title(), result.posterPath(), releaseYear(result.releaseDate()));
        }
        return new SearchContentDTO(
                result.id(), MovieOrSeriesType.SERIES, result.name(), result.posterPath(), releaseYear(result.firstAirDate()));
    }

    private List<SearchUserListDTO> searchLists(UUID viewerId, String query, PageRequest pageRequest) {
        Page<UserList> lists = userListRepository.findVisibleByNameContainingIgnoreCase(
                viewerId, escapeLikeWildcards(query), pageRequest);
        List<UUID> listIds = lists.stream().map(UserList::getId).toList();
        Map<UUID, List<ContentRefDTO>> previewsByListId = userListItemService.getPreviewItemsByListIds(listIds);
        Map<UUID, Long> nestedCountsByListId = userListItemService.countNestedListsByListIds(listIds);
        return lists.stream()
                .map(list -> new SearchUserListDTO(
                        list.getId(),
                        toUserPreviewDto(list.getUser()),
                        list.getName(),
                        previewsByListId.getOrDefault(list.getId(), List.of()),
                        nestedCountsByListId.getOrDefault(list.getId(), 0L)))
                .toList();
    }

    private List<UserPreviewDTO> searchUsers(String query, PageRequest pageRequest) {
        return userRepository.findByUsernameStartingWithIgnoreCase(query, escapeLikeWildcards(query), pageRequest)
                .stream()
                .map(this::toUserPreviewDto)
                .toList();
    }

    private UserPreviewDTO toUserPreviewDto(User user) {
        return new UserPreviewDTO(user.getId(), user.getUsername(), user.getProfilePicture(), user.getIsProfilePublic());
    }

    private <T> List<T> requireResults(TmdbLookupResult<TmdbSearchPage<T>> result) {
        if (result.isUnavailable()) {
            throw new TmdbUnavailableException("TMDB is currently unavailable");
        }
        return result.toOptional().map(TmdbSearchPage::results).orElseGet(List::of);
    }

    private Integer releaseYear(String date) {
        if (date == null || date.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(date).getYear();
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private String escapeLikeWildcards(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
