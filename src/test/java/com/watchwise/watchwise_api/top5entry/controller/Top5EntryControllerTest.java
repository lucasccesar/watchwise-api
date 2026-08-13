package com.watchwise.watchwise_api.top5entry.controller;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;
import com.watchwise.watchwise_api.top5entry.service.Top5EntryService;
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
class Top5EntryControllerTest {

    @Mock
    private Top5EntryService top5EntryService;

    @InjectMocks
    private Top5EntryController top5EntryController;

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
    @DisplayName("[getTop5] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenGettingTop5() {
        UUID targetUserId = UUID.randomUUID();
        Top5EntryResponseDTO dto = buildResponseDto();
        when(top5EntryService.getTop5(currentUserId, targetUserId, ContentType.MOVIE)).thenReturn(List.of(dto));

        ResponseEntity<List<Top5EntryResponseDTO>> result = top5EntryController.getTop5(targetUserId, ContentType.MOVIE);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getTop5] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingTop5() {
        UUID targetUserId = UUID.randomUUID();
        when(top5EntryService.getTop5(currentUserId, targetUserId, ContentType.MOVIE)).thenReturn(List.of());

        top5EntryController.getTop5(targetUserId, ContentType.MOVIE);

        verify(top5EntryService).getTop5(currentUserId, targetUserId, ContentType.MOVIE);
    }

    @Test
    @DisplayName("[insertEntry] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenInsertingEntry() {
        Top5EntryCreationDTO creationDTO = new Top5EntryCreationDTO(buildContentRefCreationDto(), 1);
        Top5EntryResponseDTO dto = buildResponseDto();
        when(top5EntryService.insertEntry(currentUserId, ContentType.MOVIE, creationDTO)).thenReturn(dto);

        ResponseEntity<Top5EntryResponseDTO> result = top5EntryController.insertEntry(ContentType.MOVIE, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[insertEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenInsertingEntry() {
        Top5EntryCreationDTO creationDTO = new Top5EntryCreationDTO(buildContentRefCreationDto(), 1);
        when(top5EntryService.insertEntry(currentUserId, ContentType.MOVIE, creationDTO)).thenReturn(buildResponseDto());

        top5EntryController.insertEntry(ContentType.MOVIE, creationDTO);

        verify(top5EntryService).insertEntry(currentUserId, ContentType.MOVIE, creationDTO);
    }

    @Test
    @DisplayName("[removeEntry] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenRemovingEntry() {
        UUID top5EntryId = UUID.randomUUID();

        ResponseEntity<Void> result = top5EntryController.removeEntry(ContentType.MOVIE, top5EntryId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[removeEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenRemovingEntry() {
        UUID top5EntryId = UUID.randomUUID();

        top5EntryController.removeEntry(ContentType.MOVIE, top5EntryId);

        verify(top5EntryService).removeEntry(currentUserId, ContentType.MOVIE, top5EntryId);
    }

    private ContentRefCreationDTO buildContentRefCreationDto() {
        return new ContentRefCreationDTO("550", ContentType.MOVIE, null, null, null);
    }

    private Top5EntryResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, now, now);
        return new Top5EntryResponseDTO(UUID.randomUUID(), ContentType.MOVIE, content, 1, now, now);
    }
}
