package com.watchwise.watchwise_api.trending.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.common.exception.GlobalExceptionHandler;
import com.watchwise.watchwise_api.common.exception.TooManyRequestsException;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.trending.service.TrendingService;
import com.watchwise.watchwise_api.trending.service.TrendingTimeWindow;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TrendingControllerTest {

    @Mock
    private TrendingService trendingService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private TrendingController trendingController;

    private UUID viewerId;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewerId, null, List.of())
        );
        ReflectionTestUtils.setField(trendingController, "trendingMaxRequests", 30);
        ReflectionTestUtils.setField(trendingController, "trendingWindowMinutes", 5L);
        mockMvc = MockMvcBuilders.standaloneSetup(trendingController)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getTrending] Should Use Default Size - When Day Window Is Valid")
    void shouldUseDefaultSizeWhenTimeWindowIsValid() {
        TrendingResponseDTO expected = new TrendingResponseDTO(List.of(), List.of());
        when(trendingService.getTrending(viewerId, TrendingTimeWindow.DAY, 12)).thenReturn(expected);

        ResponseEntity<TrendingResponseDTO> response = trendingController.getTrending("day", 12);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(requestThrottler).checkAllowed("trending|" + viewerId, 30, Duration.ofMinutes(5));
        verify(trendingService).getTrending(viewerId, TrendingTimeWindow.DAY, 12);
    }

    @Test
    @DisplayName("[getTrending] Should Delegate Week And Expanded Size - When Parameters Are Valid")
    void shouldDelegateWeekAndExpandedSizeWhenParametersAreValid() {
        TrendingResponseDTO expected = new TrendingResponseDTO(List.of(), List.of());
        when(trendingService.getTrending(viewerId, TrendingTimeWindow.WEEK, 21)).thenReturn(expected);

        ResponseEntity<TrendingResponseDTO> response = trendingController.getTrending("week", 21);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(requestThrottler).checkAllowed("trending|" + viewerId, 30, Duration.ofMinutes(5));
        verify(trendingService).getTrending(viewerId, TrendingTimeWindow.WEEK, 21);
    }

    @Test
    @DisplayName("[getTrendingSection] Should Delegate Independent Movie Page - When Parameters Are Valid")
    void shouldDelegateIndependentMoviePageWhenParametersAreValid() {
        TrendingResponseDTO expected = new TrendingResponseDTO(List.of(), List.of());
        when(trendingService.getTrendingSection(viewerId, com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE,
                TrendingTimeWindow.DAY, 2, 12)).thenReturn(expected);

        ResponseEntity<TrendingResponseDTO> response = trendingController.getTrendingSection("movie", "day", 2, 12);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(requestThrottler).checkAllowed("trending|" + viewerId, 30, Duration.ofMinutes(5));
        verify(trendingService).getTrendingSection(viewerId,
                com.watchwise.watchwise_api.content.entity.MovieOrSeriesType.MOVIE,
                TrendingTimeWindow.DAY, 2, 12);
    }

    @Test
    @DisplayName("[getTrending] Should Reject Invalid Time Window Before Rate Limit")
    void shouldRejectInvalidTimeWindowBeforeRateLimit() {
        assertThatThrownBy(() -> trendingController.getTrending("month", 12))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("timeWindow must be one of: day, week");

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrending] Should Reject Invalid Size Before Rate Limit")
    void shouldRejectInvalidSizeBeforeRateLimit() {
        assertThatThrownBy(() -> trendingController.getTrending("day", 20))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be one of: 12, 21");

        assertThatThrownBy(() -> trendingController.getTrending("day", 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("size must be one of: 12, 21");

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrendingSection] Should Reject Invalid Page Before Rate Limit")
    void shouldRejectInvalidPageBeforeRateLimit() {
        assertThatThrownBy(() -> trendingController.getTrendingSection("movie", "day", 0, 12))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("page must be greater than 0");

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrendingSection] Should Reject External Page Above 500 Before Rate Limit")
    void shouldRejectExternalPageAbove500BeforeRateLimit() {
        assertThatThrownBy(() -> trendingController.getTrendingSection("movie", "day", 501, 12))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("page must be less than or equal to 500 for external searches");

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrendingSection] Should Reject Invalid Type Before Rate Limit")
    void shouldRejectInvalidTypeBeforeRateLimit() {
        assertThatThrownBy(() -> trendingController.getTrendingSection("people", "day", 1, 12))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("type must be one of: movie, series");

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrending] Should Reject Non-Numeric Size As ApiError - When Request Is Bound")
    void shouldRejectNonNumericSizeAsApiErrorWhenRequestIsBound() throws Exception {
        mockMvc.perform(get("/trending")
                        .param("timeWindow", "day")
                        .param("size", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.path").value("/trending"))
                .andExpect(jsonPath("$.detail").doesNotExist())
                .andExpect(jsonPath("$.instance").doesNotExist());

        verifyNoInteractions(requestThrottler, trendingService);
    }

    @Test
    @DisplayName("[getTrending] Should Reject Request - When Rate Limit Is Exceeded")
    void shouldRejectRequestWhenRateLimitIsExceeded() {
        doThrow(new TooManyRequestsException("Too many requests. Try again later."))
                .when(requestThrottler)
                .checkAllowed("trending|" + viewerId, 30, Duration.ofMinutes(5));

        assertThatThrownBy(() -> trendingController.getTrending("day", 12))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("Too many requests. Try again later.");

        verify(trendingService, never()).getTrending(any(), any(), anyInt());
    }
}
