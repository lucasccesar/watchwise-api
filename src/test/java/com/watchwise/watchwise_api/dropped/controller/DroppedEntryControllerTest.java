package com.watchwise.watchwise_api.dropped.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryCreationDTO;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import com.watchwise.watchwise_api.dropped.service.DroppedEntryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
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
class DroppedEntryControllerTest {

    @Mock
    private DroppedEntryService droppedEntryService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private DroppedEntryController droppedEntryController;

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
    @DisplayName("[getDropped] Should Return Ok With The Service Result Wrapped In A Page Envelope - When Called")
    void shouldReturnOkWithTheServiceResultWrappedInAPageEnvelopeWhenGettingDropped() {
        UUID targetUserId = UUID.randomUUID();
        DroppedEntryResponseDTO dto = buildResponseDto();
        Page<DroppedEntryResponseDTO> page = new PageImpl<>(List.of(dto));
        when(droppedEntryService.getDropped(currentUserId, targetUserId, ContentType.MOVIE, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<DroppedEntryResponseDTO>> result =
                droppedEntryController.getDropped(targetUserId, MovieOrSeriesType.MOVIE, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getDropped] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingDropped() {
        UUID targetUserId = UUID.randomUUID();
        when(droppedEntryService.getDropped(currentUserId, targetUserId, ContentType.MOVIE, 1, 10)).thenReturn(Page.empty());

        droppedEntryController.getDropped(targetUserId, MovieOrSeriesType.MOVIE, 1, 10);

        verify(droppedEntryService).getDropped(currentUserId, targetUserId, ContentType.MOVIE, 1, 10);
    }

    @Test
    @DisplayName("[markAsDropped] Should Return NoContent And Delegate To Service - When Called")
    void shouldReturnNoContentAndDelegateToServiceWhenMarkingAsDropped() {
        DroppedEntryCreationDTO creationDTO = new DroppedEntryCreationDTO("Lost interest");

        ResponseEntity<Void> result = droppedEntryController.markAsDropped(MovieOrSeriesType.MOVIE, "550", creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(droppedEntryService).markAsDropped(currentUserId, ContentType.MOVIE, "550", creationDTO);
    }

    @Test
    @DisplayName("[markAsDropped] Should Delegate With A Null Body - When The Request Body Is Omitted")
    void shouldDelegateWithANullBodyWhenTheRequestBodyIsOmitted() {
        ResponseEntity<Void> result = droppedEntryController.markAsDropped(MovieOrSeriesType.MOVIE, "550", null);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(droppedEntryService).markAsDropped(currentUserId, ContentType.MOVIE, "550", null);
    }

    @Test
    @DisplayName("[unmarkAsDropped] Should Return NoContent And Delegate To Service - When Called")
    void shouldReturnNoContentAndDelegateToServiceWhenUnmarkingAsDropped() {
        ResponseEntity<Void> result = droppedEntryController.unmarkAsDropped(MovieOrSeriesType.MOVIE, "550");

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(droppedEntryService).unmarkAsDropped(currentUserId, ContentType.MOVIE, "550");
    }

    private DroppedEntryResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, now, now);
        return new DroppedEntryResponseDTO(UUID.randomUUID(), ContentType.MOVIE, content, null, now, now);
    }
}
