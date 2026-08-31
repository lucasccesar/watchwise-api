package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.service.ContentDetailsService;
import com.watchwise.watchwise_api.content.service.ContentService;
import com.watchwise.watchwise_api.content.service.ContentStatsService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentControllerTest {

    @Mock
    private ContentService contentService;

    @Mock
    private ContentStatsService contentStatsService;

    @Mock
    private ContentDetailsService contentDetailsService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private ContentController contentController;

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
    @DisplayName("[getOrCreateReference] Should Return Ok With ContentRefDTO - When Service Resolves The Reference")
    void shouldReturnOkWithContentRefDtoWhenServiceResolvesTheReference() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        ResponseEntity<ContentRefDTO> result = contentController.getOrCreateReference(dto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(responseDto);
    }

    @Test
    @DisplayName("[getOrCreateReference] Should Delegate To Service With The Same DTO - When Called")
    void shouldDelegateToServiceWithTheSameDtoWhenCalled() {
        ContentRefCreationDTO dto = new ContentRefCreationDTO(null, ContentType.SEASON, "1399", 1, null, null, null);
        ContentRefDTO responseDto = new ContentRefDTO(
                UUID.randomUUID(), null, ContentType.SEASON, "1399", 1, null, null, null, LocalDateTime.now(), LocalDateTime.now()
        );
        when(contentService.getOrCreateReference(dto)).thenReturn(responseDto);

        contentController.getOrCreateReference(dto);

        verify(contentService).getOrCreateReference(dto);
    }

    @Test
    @DisplayName("[getStats] Should Return Ok With Stats - When Service Resolves Them")
    void shouldReturnOkWithStatsWhenServiceResolvesThem() {
        UUID contentId = UUID.randomUUID();
        ContentStatsResponseDTO stats = new ContentStatsResponseDTO(contentId, 8.2, 10, 4, 6);
        when(contentStatsService.getStats(contentId)).thenReturn(stats);

        ResponseEntity<ContentStatsResponseDTO> result = contentController.getStats(contentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(stats);
    }

    @Test
    @DisplayName("[getStatsBatch] Should Return Ok With The List From The Service - When Called With Multiple Ids")
    void shouldReturnOkWithTheListFromTheServiceWhenCalledWithMultipleIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        List<ContentStatsResponseDTO> stats = List.of(
                new ContentStatsResponseDTO(first, 8.2, 10, 4, 6),
                new ContentStatsResponseDTO(second, null, 0, 0, 0));
        when(contentStatsService.getStatsBatch(List.of(first, second))).thenReturn(stats);

        ResponseEntity<List<ContentStatsResponseDTO>> result = contentController.getStatsBatch(List.of(first, second));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(stats);
    }

    @Test
    @DisplayName("[getDetails] Should Return Ok With Details Resolved For The Current User - When Service Resolves Them")
    void shouldReturnOkWithDetailsResolvedForTheCurrentUserWhenServiceResolvesThem() {
        UUID contentId = UUID.randomUUID();
        ContentDetailsDTO details = new ContentDetailsDTO(
                contentId, ContentType.MOVIE, "The Matrix", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(contentDetailsService.getDetails(contentId, currentUserId)).thenReturn(details);

        ResponseEntity<ContentDetailsDTO> result = contentController.getDetails(contentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(details);
        verify(contentDetailsService).getDetails(contentId, currentUserId);
    }

    @Test
    @DisplayName("[getDetailsBatch] Should Return Ok With The List From The Service Resolved For The Current User - When Called With Multiple Ids")
    void shouldReturnOkWithTheListFromTheServiceResolvedForTheCurrentUserWhenCalledWithMultipleIds() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ContentDetailsDTO firstDetails = new ContentDetailsDTO(
                first, ContentType.MOVIE, "First", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        ContentDetailsDTO secondDetails = new ContentDetailsDTO(
                second, ContentType.MOVIE, "Second", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        when(contentDetailsService.getDetailsBatch(List.of(first, second), currentUserId))
                .thenReturn(List.of(firstDetails, secondDetails));

        ResponseEntity<List<ContentDetailsDTO>> result = contentController.getDetailsBatch(List.of(first, second));

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(firstDetails, secondDetails);
    }

}