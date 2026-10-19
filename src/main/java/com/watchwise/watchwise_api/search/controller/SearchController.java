package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.search.dto.SearchRequestDTO;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.dto.SearchViewDTO;
import com.watchwise.watchwise_api.search.dto.SearchViewRequestDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
import com.watchwise.watchwise_api.search.service.SearchViewService;
import com.watchwise.watchwise_api.search.service.SearchViewQuery;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SearchController {

    private static final int MAX_EXTERNAL_PAGE = 500;

    private final SearchService searchService;
    private final SearchViewService searchViewService;
    private final RequestThrottler requestThrottler;

    @Value("${app.rate-limit.search.max-requests}")
    private int searchMaxRequests;

    @Value("${app.rate-limit.search.window-minutes}")
    private long searchWindowMinutes;

    private static final int DEFAULT_VIEW_PAGE = 1;
    private static final int DEFAULT_VIEW_SIZE = 18;
    private static final int MAX_VIEW_QUERY_LENGTH = 100;

    @GetMapping("/search")
    public ResponseEntity<SearchResultDTO> search(
            @Valid @ModelAttribute SearchRequestDTO request
    ) {
        String trimmedQuery = request.q().trim();
        if (trimmedQuery.length() < 3) {
            throw new BadRequestException("q must contain at least 3 characters after trimming");
        }
        validateExternalPage(request.type(), request.page());

        UUID currentUserId = getCurrentUserId();
        requestThrottler.checkAllowed(
                "search|" + currentUserId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));

        SearchResultDTO result = searchService.search(
                currentUserId, trimmedQuery, request.type(), request.page(), request.size());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/search/view")
    public ResponseEntity<SearchViewDTO> searchView(
            @Valid @ModelAttribute SearchViewRequestDTO request
    ) {
        String trimmedQuery = request.q().trim();
        validateViewQuery(trimmedQuery);

        UUID currentUserId = getCurrentUserId();
        requestThrottler.checkAllowed(
                "search|" + currentUserId,
                searchMaxRequests,
                Duration.ofMinutes(searchWindowMinutes));

        int page = request.page() == null ? DEFAULT_VIEW_PAGE : request.page();
        int size = request.size() == null ? DEFAULT_VIEW_SIZE : request.size();
        SearchViewDTO result;
        if (request.origin() == null && request.itemType() == null) {
            result = searchViewService.search(currentUserId, trimmedQuery, request.scope(), page, size);
        } else {
            result = searchViewService.search(new SearchViewQuery(
                    currentUserId, trimmedQuery, request.scope(), page, size,
                    request.origin(), request.itemType()));
        }
        return ResponseEntity.ok(result);
    }

    private void validateViewQuery(String trimmedQuery) {
        if (trimmedQuery.length() < 3) {
            throw new BadRequestException("q must contain at least 3 characters after trimming");
        }
        if (trimmedQuery.length() > MAX_VIEW_QUERY_LENGTH) {
            throw new BadRequestException("q must contain at most 100 characters after trimming");
        }
    }

    private void validateExternalPage(SearchType type, Integer page) {
        if (page != null && page > MAX_EXTERNAL_PAGE && isExternalSearch(type)) {
            throw new BadRequestException("page must be less than or equal to 500 for external searches");
        }
    }

    private boolean isExternalSearch(SearchType type) {
        return type == null
                || type == SearchType.MOVIE
                || type == SearchType.SERIES
                || type == SearchType.PERSON;
    }

    private UUID getCurrentUserId() {
        return (UUID) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }
}
