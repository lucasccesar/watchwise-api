package com.watchwise.watchwise_api.watchlist.controller;

import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
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
class WatchlistEntryControllerTest {

    @Mock
    private WatchlistEntryService watchlistEntryService;

    @InjectMocks
    private WatchlistEntryController watchlistEntryController;

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
    @DisplayName("[getWatchlist] Should Return Ok With The Service Result Wrapped In A Page Envelope - When Called")
    void shouldReturnOkWithTheServiceResultWrappedInAPageEnvelopeWhenGettingWatchlist() {
        UUID targetUserId = UUID.randomUUID();
        WatchlistEntryResponseDTO dto = buildResponseDto();
        Page<WatchlistEntryResponseDTO> page = new PageImpl<>(List.of(dto));
        when(watchlistEntryService.getWatchlist(currentUserId, targetUserId, ContentType.MOVIE, 1, 10)).thenReturn(page);

        ResponseEntity<PageResponseDTO<WatchlistEntryResponseDTO>> result =
                watchlistEntryController.getWatchlist(targetUserId, ContentType.MOVIE, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getWatchlist] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingWatchlist() {
        UUID targetUserId = UUID.randomUUID();
        when(watchlistEntryService.getWatchlist(currentUserId, targetUserId, ContentType.MOVIE, 1, 10))
                .thenReturn(Page.empty());

        watchlistEntryController.getWatchlist(targetUserId, ContentType.MOVIE, 1, 10);

        verify(watchlistEntryService).getWatchlist(currentUserId, targetUserId, ContentType.MOVIE, 1, 10);
    }

    @Test
    @DisplayName("[insertEntry] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenInsertingEntry() {
        WatchlistEntryCreationDTO creationDTO = new WatchlistEntryCreationDTO("550");
        WatchlistEntryResponseDTO dto = buildResponseDto();
        when(watchlistEntryService.insertEntry(currentUserId, ContentType.MOVIE, creationDTO)).thenReturn(dto);

        ResponseEntity<WatchlistEntryResponseDTO> result = watchlistEntryController.insertEntry(ContentType.MOVIE, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[insertEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenInsertingEntry() {
        WatchlistEntryCreationDTO creationDTO = new WatchlistEntryCreationDTO("550");
        when(watchlistEntryService.insertEntry(currentUserId, ContentType.MOVIE, creationDTO)).thenReturn(buildResponseDto());

        watchlistEntryController.insertEntry(ContentType.MOVIE, creationDTO);

        verify(watchlistEntryService).insertEntry(currentUserId, ContentType.MOVIE, creationDTO);
    }

    @Test
    @DisplayName("[moveEntry] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenMovingEntry() {
        UUID watchlistEntryId = UUID.randomUUID();
        WatchlistEntryReorderDTO reorderDTO = new WatchlistEntryReorderDTO(1);
        WatchlistEntryResponseDTO dto = buildResponseDto();
        when(watchlistEntryService.moveEntry(currentUserId, ContentType.MOVIE, watchlistEntryId, reorderDTO)).thenReturn(dto);

        ResponseEntity<WatchlistEntryResponseDTO> result =
                watchlistEntryController.moveEntry(ContentType.MOVIE, watchlistEntryId, reorderDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[moveEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenMovingEntry() {
        UUID watchlistEntryId = UUID.randomUUID();
        WatchlistEntryReorderDTO reorderDTO = new WatchlistEntryReorderDTO(1);
        when(watchlistEntryService.moveEntry(currentUserId, ContentType.MOVIE, watchlistEntryId, reorderDTO))
                .thenReturn(buildResponseDto());

        watchlistEntryController.moveEntry(ContentType.MOVIE, watchlistEntryId, reorderDTO);

        verify(watchlistEntryService).moveEntry(currentUserId, ContentType.MOVIE, watchlistEntryId, reorderDTO);
    }

    @Test
    @DisplayName("[removeEntry] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenRemovingEntry() {
        UUID watchlistEntryId = UUID.randomUUID();

        ResponseEntity<Void> result = watchlistEntryController.removeEntry(ContentType.MOVIE, watchlistEntryId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[removeEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenRemovingEntry() {
        UUID watchlistEntryId = UUID.randomUUID();

        watchlistEntryController.removeEntry(ContentType.MOVIE, watchlistEntryId);

        verify(watchlistEntryService).removeEntry(currentUserId, ContentType.MOVIE, watchlistEntryId);
    }

    private WatchlistEntryResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        ContentRefDTO content = new ContentRefDTO(UUID.randomUUID(), "550", ContentType.MOVIE, null, null, null, null, null, now, now);
        return new WatchlistEntryResponseDTO(UUID.randomUUID(), ContentType.MOVIE, content, 1, now, now);
    }
}