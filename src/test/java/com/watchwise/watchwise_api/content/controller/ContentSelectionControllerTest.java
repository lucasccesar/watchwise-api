package com.watchwise.watchwise_api.content.controller;

import com.watchwise.watchwise_api.common.security.RequestThrottler;
import com.watchwise.watchwise_api.content.dto.ContentEpisodeSelectionDTO;
import com.watchwise.watchwise_api.content.dto.ContentSeasonSelectionDTO;
import com.watchwise.watchwise_api.content.service.ContentSelectionService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ContentSelectionControllerTest {

    @Mock
    private ContentSelectionService contentSelectionService;

    @Mock
    private RequestThrottler requestThrottler;

    @InjectMocks
    private ContentSelectionController controller;

    private UUID viewerId;

    @BeforeEach
    void setUp() {
        viewerId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(viewerId, null, List.of()));
        ReflectionTestUtils.setField(controller, "searchMaxRequests", 30);
        ReflectionTestUtils.setField(controller, "searchWindowMinutes", 5L);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("[listSeasons] Should Return The Service Result And Apply Rate Limiting - When Specials Are Requested")
    void listsSeasonsUsingViewerAndRequestedSpecialsFlag() {
        List<ContentSeasonSelectionDTO> expected = List.of(
                new ContentSeasonSelectionDTO("1399", 0, "Specials", 2, null, null));
        when(contentSelectionService.listSeasons(viewerId, "1399", true)).thenReturn(expected);

        var response = controller.listSeasons("1399", true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(contentSelectionService).listSeasons(viewerId, "1399", true);
        verify(requestThrottler).checkAllowed("content-selection|" + viewerId, 30, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("[listSeasons] Should Exclude Specials - When The Default Flag Is False")
    void listsSeasonsWithoutSpecialsByDefault() {
        when(contentSelectionService.listSeasons(viewerId, "1399", false)).thenReturn(List.of());

        var response = controller.listSeasons("1399", false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEmpty();
        verify(contentSelectionService).listSeasons(viewerId, "1399", false);
    }

    @Test
    @DisplayName("[listEpisodes] Should Include Unreleased Episodes And Specials - When Using Default Behavior")
    void listsEpisodesIncludingUnreleasedByDefaultAndAcceptsSpecialsSeason() {
        List<ContentEpisodeSelectionDTO> expected = List.of(
                new ContentEpisodeSelectionDTO("1399", 0, 1, "Special", null, null));
        when(contentSelectionService.listEpisodes(viewerId, "1399", 0, true)).thenReturn(expected);

        var response = controller.listEpisodes("1399", 0, true);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo(expected);
        verify(contentSelectionService).listEpisodes(viewerId, "1399", 0, true);
        verify(requestThrottler).checkAllowed("content-selection|" + viewerId, 30, Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("[listEpisodes] Should Forward The Explicit Unreleased Flag - When A Season Is Requested")
    void forwardsExplicitUnreleasedFlag() {
        when(contentSelectionService.listEpisodes(viewerId, "1399", 2, false)).thenReturn(List.of());

        var response = controller.listEpisodes("1399", 2, false);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(contentSelectionService).listEpisodes(viewerId, "1399", 2, false);
    }
}
