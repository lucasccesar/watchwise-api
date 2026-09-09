package com.watchwise.watchwise_api.search.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.search.dto.SearchResultDTO;
import com.watchwise.watchwise_api.search.dto.SearchRequestDTO;
import com.watchwise.watchwise_api.search.service.SearchService;
import com.watchwise.watchwise_api.search.service.SearchType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchControllerTest {

    @Mock
    private SearchService searchService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private SearchController searchController;

    private UUID viewerId;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewerId, null, List.of())
        );
        setRateLimitConfigurationIfPresent();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void setRateLimitConfigurationIfPresent() {
        if (ReflectionUtils.findField(SearchController.class, "searchMaxRequests") != null) {
            ReflectionTestUtils.setField(searchController, "searchMaxRequests", 30);
        }
        if (ReflectionUtils.findField(SearchController.class, "searchWindowMinutes") != null) {
            ReflectionTestUtils.setField(searchController, "searchWindowMinutes", 5);
        }
    }

    @Test
    @DisplayName("[search] Should Check Per-User Rate Limit - When Request Is Valid")
    void shouldCheckPerUserRateLimitWhenRequestIsValid() {
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(viewerId, "Alien", SearchType.MOVIE, 1, 20)).thenReturn(expected);

        searchController.search(new SearchRequestDTO("Alien", SearchType.MOVIE, 1, 20));

        verify(requestThrottler).checkAllowed("search|" + viewerId, 30, Duration.ofMinutes(5));
        verify(searchService).search(viewerId, "Alien", SearchType.MOVIE, 1, 20);
    }

    @Test
    @DisplayName("[search] Should Reject Request And Not Search - When Rate Limit Is Exceeded")
    void shouldRejectRequestAndNotSearchWhenRateLimitIsExceeded() {
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler)
                .checkAllowed("search|" + viewerId, 30, Duration.ofMinutes(5));

        assertThatThrownBy(() -> searchController.search(
                new SearchRequestDTO("Alien", SearchType.MOVIE, 1, 20)))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many requests. Try again later.");

        verify(searchService, never()).search(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("[search] Should Return Search Result And Trim Query - When Request Is Valid")
    void shouldReturnSearchResultAndTrimQueryWhenRequestIsValid() {
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(viewerId, "Alien", SearchType.MOVIE, 2, 10)).thenReturn(expected);

        ResponseEntity<SearchResultDTO> result = searchController.search(
                new SearchRequestDTO(" Alien ", SearchType.MOVIE, 2, 10));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(searchService).search(viewerId, "Alien", SearchType.MOVIE, 2, 10);
    }

    @Test
    @DisplayName("[search] Should Resolve Viewer From Security Context - When Request Is Valid")
    void shouldResolveViewerFromSecurityContextWhenRequestIsValid() {
        SearchResultDTO expected = new SearchResultDTO(List.of(), List.of(), List.of(), List.of());
        when(searchService.search(viewerId, "Alien", null, null, null)).thenReturn(expected);

        searchController.search(new SearchRequestDTO("Alien", null, null, null));

        verify(searchService).search(viewerId, "Alien", null, null, null);
    }

    @Test
    @DisplayName("[search] Should Throw BadRequestException And Not Search - When Trimmed Query Is Too Short")
    void shouldThrowBadRequestExceptionAndNotSearchWhenTrimmedQueryIsTooShort() {
        assertThatThrownBy(() -> searchController.search(
                new SearchRequestDTO("  ab  ", null, null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("q must contain at least 3 characters after trimming");

        verify(searchService, never()).search(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
