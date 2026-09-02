package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.common.exception.BadRequestException;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.HomeSummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.service.SummaryService;
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

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SummaryControllerTest {

    @Mock
    private SummaryService summaryService;

    @InjectMocks
    private SummaryController summaryController;

    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of())
        );
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[getSummary] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenCalled() {
        UUID targetUserId = UUID.randomUUID();
        SummaryResponseDTO dto = buildSummaryResponseDto();
        when(summaryService.getSummary(currentUserId, targetUserId, ContentType.MOVIE)).thenReturn(dto);

        ResponseEntity<SummaryResponseDTO> result = summaryController.getSummary(targetUserId, ContentType.MOVIE);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getSummary] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCalled() {
        UUID targetUserId = UUID.randomUUID();
        when(summaryService.getSummary(currentUserId, targetUserId, ContentType.SERIES)).thenReturn(buildSummaryResponseDto());

        summaryController.getSummary(targetUserId, ContentType.SERIES);

        verify(summaryService).getSummary(currentUserId, targetUserId, ContentType.SERIES);
    }

    @Test
    @DisplayName("[getHomeSummary] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGettingHomeSummary() {
        UUID targetUserId = UUID.randomUUID();
        HomeSummaryResponseDTO dto = new HomeSummaryResponseDTO(0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        when(summaryService.getHomeSummary(currentUserId, targetUserId)).thenReturn(dto);

        ResponseEntity<HomeSummaryResponseDTO> result = summaryController.getHomeSummary(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getHomeSummary] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingHomeSummary() {
        UUID targetUserId = UUID.randomUUID();
        HomeSummaryResponseDTO dto = new HomeSummaryResponseDTO(0, 0, 0, List.of(), List.of(), List.of(), List.of(), List.of());
        when(summaryService.getHomeSummary(currentUserId, targetUserId)).thenReturn(dto);

        summaryController.getHomeSummary(targetUserId);

        verify(summaryService).getHomeSummary(currentUserId, targetUserId);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGetMonthInReviewIsCalled() {
        UUID targetUserId = UUID.randomUUID();
        MonthInReviewResponseDTO dto = buildMonthInReviewResponseDto();
        when(summaryService.getMonthInReview(currentUserId, targetUserId, ContentType.MOVIE, YearMonth.of(2026, 8))).thenReturn(dto);

        ResponseEntity<MonthInReviewResponseDTO> result = summaryController.getMonthInReview(targetUserId, ContentType.MOVIE, "2026-08");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
        verify(summaryService).getMonthInReview(currentUserId, targetUserId, ContentType.MOVIE, YearMonth.of(2026, 8));
    }

    @Test
    @DisplayName("[getMonthInReview] Should Pass A Null YearMonth To The Service - When Month Param Is Not Provided")
    void shouldPassANullYearMonthToTheServiceWhenMonthParamIsNotProvided() {
        UUID targetUserId = UUID.randomUUID();
        when(summaryService.getMonthInReview(any(), any(), any(), isNull())).thenReturn(buildMonthInReviewResponseDto());

        summaryController.getMonthInReview(targetUserId, ContentType.MOVIE, null);

        verify(summaryService).getMonthInReview(currentUserId, targetUserId, ContentType.MOVIE, null);
    }

    @Test
    @DisplayName("[getMonthInReview] Should Throw BadRequestException - When Month Param Is Not In YYYY-MM Format")
    void shouldThrowBadRequestExceptionWhenMonthParamIsNotInYyyyMmFormat() {
        UUID targetUserId = UUID.randomUUID();

        assertThatThrownBy(() -> summaryController.getMonthInReview(targetUserId, ContentType.MOVIE, "not-a-month"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("month must be in YYYY-MM format");
    }

    @Test
    @DisplayName("[getYearInReview] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGetYearInReviewIsCalled() {
        UUID targetUserId = UUID.randomUUID();
        YearInReviewResponseDTO dto = buildYearInReviewResponseDto();
        when(summaryService.getYearInReview(currentUserId, targetUserId, ContentType.SERIES, 2026)).thenReturn(dto);

        ResponseEntity<YearInReviewResponseDTO> result = summaryController.getYearInReview(targetUserId, ContentType.SERIES, 2026);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getAllTimeStats] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGetAllTimeStatsIsCalled() {
        UUID targetUserId = UUID.randomUUID();
        AllTimeStatsResponseDTO dto = buildAllTimeStatsResponseDto();
        when(summaryService.getAllTimeStats(currentUserId, targetUserId)).thenReturn(dto);

        ResponseEntity<AllTimeStatsResponseDTO> result = summaryController.getAllTimeStats(targetUserId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getEpisodeRatingsGrid] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGetEpisodeRatingsGridIsCalled() {
        UUID targetUserId = UUID.randomUUID();
        EpisodeRatingsGridResponseDTO dto = new EpisodeRatingsGridResponseDTO("1399", List.of());
        when(summaryService.getEpisodeRatingsGrid(currentUserId, targetUserId, "1399")).thenReturn(dto);

        ResponseEntity<EpisodeRatingsGridResponseDTO> result = summaryController.getEpisodeRatingsGrid(targetUserId, "1399");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    private SummaryResponseDTO buildSummaryResponseDto() {
        return new SummaryResponseDTO(new WatchTimeDTO(0L, 0L), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private MonthInReviewResponseDTO buildMonthInReviewResponseDto() {
        return new MonthInReviewResponseDTO(List.of(), List.of(), List.of(), List.of(), 0L, 0L, null, null, List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private YearInReviewResponseDTO buildYearInReviewResponseDto() {
        return new YearInReviewResponseDTO(List.of(), 0L, 0L, 0.0, 0.0, 0.0, List.of(), List.of(), null, null, List.of(), List.of(), List.of(), List.of());
    }

    private AllTimeStatsResponseDTO buildAllTimeStatsResponseDto() {
        return new AllTimeStatsResponseDTO(0L, 0L, 0L, 0L, 0.0, 0.0, 0.0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
