package com.watchwise.watchwise_api.diaryentry.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryBulkCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryCreationResultDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryResponseDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DiaryEntryUpdateDTO;
import com.watchwise.watchwise_api.diaryentry.dto.DeletionImpactDTO;
import com.watchwise.watchwise_api.diaryentry.service.DiaryEntryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiaryEntryControllerTest {

    @Mock
    private DiaryEntryService diaryEntryService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private DiaryEntryController diaryEntryController;

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
    @DisplayName("[getDiaryEntries] Should Return Page Envelope With Content And Metadata - When Called")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenGettingDiaryEntries() {
        UUID targetUserId = UUID.randomUUID();
        DiaryEntryResponseDTO dto = buildResponseDto();
        when(diaryEntryService.getDiaryEntries(currentUserId, targetUserId, 2024, 1, 10))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));

        ResponseEntity<PageResponseDTO<DiaryEntryResponseDTO>> result =
                diaryEntryController.getDiaryEntries(targetUserId, 2024, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[getDiaryEntries] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingDiaryEntries() {
        UUID targetUserId = UUID.randomUUID();
        when(diaryEntryService.getDiaryEntries(currentUserId, targetUserId, null, null, null))
                .thenReturn(Page.empty());

        diaryEntryController.getDiaryEntries(targetUserId, null, null, null);

        verify(diaryEntryService).getDiaryEntries(currentUserId, targetUserId, null, null, null);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenCreatingDiaryEntry() {
        DiaryEntryCreationDTO creationDTO = minimalCreationDto();
        DiaryEntryCreationResultDTO dto = buildCreationResultDto();
        when(diaryEntryService.createDiaryEntry(currentUserId, creationDTO)).thenReturn(dto);

        ResponseEntity<DiaryEntryCreationResultDTO> result = diaryEntryController.createDiaryEntry(creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[createDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCreatingDiaryEntry() {
        DiaryEntryCreationDTO creationDTO = minimalCreationDto();
        when(diaryEntryService.createDiaryEntry(currentUserId, creationDTO)).thenReturn(buildCreationResultDto());

        diaryEntryController.createDiaryEntry(creationDTO);

        verify(diaryEntryService).createDiaryEntry(currentUserId, creationDTO);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenUpdatingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        DiaryEntryUpdateDTO updateDTO = minimalUpdateDto();
        DiaryEntryResponseDTO dto = buildResponseDto();
        when(diaryEntryService.updateDiaryEntry(currentUserId, diaryEntryId, updateDTO)).thenReturn(dto);

        ResponseEntity<DiaryEntryResponseDTO> result = diaryEntryController.updateDiaryEntry(diaryEntryId, updateDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[updateDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenUpdatingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        DiaryEntryUpdateDTO updateDTO = minimalUpdateDto();
        when(diaryEntryService.updateDiaryEntry(currentUserId, diaryEntryId, updateDTO)).thenReturn(buildResponseDto());

        diaryEntryController.updateDiaryEntry(diaryEntryId, updateDTO);

        verify(diaryEntryService).updateDiaryEntry(currentUserId, diaryEntryId, updateDTO);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenDeletingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        ResponseEntity<Void> result = diaryEntryController.deleteDiaryEntry(diaryEntryId, false);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenDeletingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        diaryEntryController.deleteDiaryEntry(diaryEntryId, false);

        verify(diaryEntryService).deleteDiaryEntry(currentUserId, diaryEntryId, false);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Pass OverrideProtectedEntries True To The Service - When The Query Param Is True")
    void shouldPassOverrideProtectedEntriesTrueToTheServiceWhenTheQueryParamIsTrue() {
        UUID entryId = UUID.randomUUID();

        diaryEntryController.deleteDiaryEntry(entryId, true);

        verify(diaryEntryService).deleteDiaryEntry(currentUserId, entryId, true);
    }

    @Test
    @DisplayName("[deleteDiaryEntry] Should Default OverrideProtectedEntries To False - When The Query Param Is Absent")
    void shouldDefaultOverrideProtectedEntriesToFalseWhenTheQueryParamIsAbsent() {
        UUID entryId = UUID.randomUUID();

        diaryEntryController.deleteDiaryEntry(entryId, false);

        verify(diaryEntryService).deleteDiaryEntry(currentUserId, entryId, false);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Return Created With The List From The Service - When Called")
    void shouldReturnCreatedWithTheListFromTheServiceWhenCalled() {
        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "901", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 3, null);
        DiaryEntryResponseDTO entryResponseDTO = buildResponseDto();
        when(diaryEntryService.createDiaryEntriesInBulk(currentUserId, dto)).thenReturn(List.of(entryResponseDTO));

        ResponseEntity<List<DiaryEntryResponseDTO>> result = diaryEntryController.createDiaryEntriesInBulk(dto);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).containsExactly(entryResponseDTO);
    }

    @Test
    @DisplayName("[createDiaryEntriesInBulk] Should Check Rate Limit Before Calling Service - When Called")
    void shouldCheckRateLimitBeforeCallingServiceWhenBulkCreateCalled() {
        ContentRefCreationDTO seasonRef = new ContentRefCreationDTO(null, ContentType.SEASON, "901", 1, null, null, null);
        DiaryEntryBulkCreationDTO dto = new DiaryEntryBulkCreationDTO(seasonRef, LocalDate.now(), 3, null);
        when(diaryEntryService.createDiaryEntriesInBulk(currentUserId, dto)).thenReturn(List.of());

        diaryEntryController.createDiaryEntriesInBulk(dto);

        InOrder order = inOrder(requestThrottler, diaryEntryService);
        order.verify(requestThrottler).checkAllowed(any(), anyInt(), any());
        order.verify(diaryEntryService).createDiaryEntriesInBulk(currentUserId, dto);
    }

    private DiaryEntryCreationDTO minimalCreationDto() {
        return new DiaryEntryCreationDTO(
                new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null, null, null),
                null, null, null, null, null, null);
    }

    private DiaryEntryUpdateDTO minimalUpdateDto() {
        return new DiaryEntryUpdateDTO(null, null, null, null, null);
    }

    private DiaryEntryResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, now, now);
        return new DiaryEntryResponseDTO(
                UUID.randomUUID(), currentUserId, content, null, null, null, 1, null, null, false, now, now);
    }

    private DiaryEntryCreationResultDTO buildCreationResultDto() {
        return new DiaryEntryCreationResultDTO(buildResponseDto(), null, null);
    }

    @Test
    @DisplayName("[getDeletionImpact] Should Return DeletionImpactDTO - When Called With Valid Entry ID")
    void shouldReturnDeletionImpactDtoWhenCalledWithValidEntryId() {
        UUID diaryEntryId = UUID.randomUUID();
        DeletionImpactDTO expectedResult = new DeletionImpactDTO(List.of());
        when(diaryEntryService.computeDeletionImpact(currentUserId, diaryEntryId)).thenReturn(expectedResult);

        ResponseEntity<DeletionImpactDTO> response = diaryEntryController.getDeletionImpact(diaryEntryId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expectedResult);
        verify(diaryEntryService).computeDeletionImpact(currentUserId, diaryEntryId);
    }
}