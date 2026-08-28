package com.watchwise.watchwise_api.summary.controller;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.WatchTimeDTO;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
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

    private SummaryResponseDTO buildSummaryResponseDto() {
        return new SummaryResponseDTO(new WatchTimeDTO(0L, 0L), List.of(), List.of(), List.of(), List.of(), List.of());
    }
}
