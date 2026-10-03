package com.watchwise.watchwise_api.watchlist.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryCreationDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryReorderDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistEntryResponseDTO;
import com.watchwise.watchwise_api.watchlist.dto.WatchlistPageResponseDTO;
import com.watchwise.watchwise_api.watchlist.service.WatchlistEntryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
class WatchlistEntryControllerTest {

    @Mock private WatchlistEntryService watchlistEntryService;
    @Mock private RequestThrottler requestThrottler;
    @InjectMocks private WatchlistEntryController watchlistEntryController;

    private UUID currentUserId;

    @BeforeEach
    void setUp() {
        currentUserId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(currentUserId, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void shouldGetBothTypesWhenTypeIsOmitted() {
        UUID targetUserId = UUID.randomUUID();
        WatchlistPageResponseDTO expected = new WatchlistPageResponseDTO(
                List.of(), 1, 10, 0, 0, false, 3);
        when(watchlistEntryService.getWatchlist(currentUserId, targetUserId, null, 1, 10)).thenReturn(expected);

        ResponseEntity<WatchlistPageResponseDTO> result =
                watchlistEntryController.getWatchlist(targetUserId, null, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(expected);
        verify(watchlistEntryService).getWatchlist(currentUserId, targetUserId, null, 1, 10);
    }

    @Test
    void shouldPassMovieFilterWhenTypeIsProvided() {
        UUID targetUserId = UUID.randomUUID();
        WatchlistPageResponseDTO expected = new WatchlistPageResponseDTO(
                List.of(), 1, 10, 0, 0, false, 0);
        when(watchlistEntryService.getWatchlist(currentUserId, targetUserId, ContentType.MOVIE, 1, 10))
                .thenReturn(expected);

        watchlistEntryController.getWatchlist(targetUserId, MovieOrSeriesType.MOVIE, 1, 10);

        verify(watchlistEntryService).getWatchlist(currentUserId, targetUserId, ContentType.MOVIE, 1, 10);
    }

    @Test
    void shouldCreateEntry() {
        WatchlistEntryCreationDTO request = new WatchlistEntryCreationDTO("550");
        WatchlistEntryResponseDTO expected = new WatchlistEntryResponseDTO(null, ContentType.MOVIE, null, 1, null, null);
        when(watchlistEntryService.insertEntry(currentUserId, ContentType.MOVIE, request)).thenReturn(expected);

        ResponseEntity<WatchlistEntryResponseDTO> result =
                watchlistEntryController.insertEntry(MovieOrSeriesType.MOVIE, request);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(expected);
    }

    @Test
    void shouldMoveAndRemoveEntryUsingTypedRoutes() {
        UUID entryId = UUID.randomUUID();
        WatchlistEntryReorderDTO request = new WatchlistEntryReorderDTO(1);
        when(watchlistEntryService.moveEntry(currentUserId, ContentType.SERIES, entryId, request))
                .thenReturn(new WatchlistEntryResponseDTO(null, ContentType.SERIES, null, 1, null, null));

        watchlistEntryController.moveEntry(MovieOrSeriesType.SERIES, entryId, request);
        watchlistEntryController.removeEntry(MovieOrSeriesType.SERIES, entryId);

        verify(watchlistEntryService).moveEntry(currentUserId, ContentType.SERIES, entryId, request);
        verify(watchlistEntryService).removeEntry(currentUserId, ContentType.SERIES, entryId);
    }
}
