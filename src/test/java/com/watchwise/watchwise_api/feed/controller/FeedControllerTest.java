package com.watchwise.watchwise_api.feed.controller;

import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.service.FeedService;
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
class FeedControllerTest {

    @Mock
    private FeedService feedService;

    @InjectMocks
    private FeedController feedController;

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
    @DisplayName("[getFeed] Should Return Ok With The Service Result - When Called")
    void shouldReturnOkWithTheServiceResultWhenCalled() {
        CursorPageResponseDTO<FeedItemDTO> dto = new CursorPageResponseDTO<>(List.of(), 20, null, false);
        when(feedService.getFeed(currentUserId, "abc", 10)).thenReturn(dto);

        ResponseEntity<CursorPageResponseDTO<FeedItemDTO>> result = feedController.getFeed("abc", 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[getFeed] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCalled() {
        when(feedService.getFeed(currentUserId, null, null))
                .thenReturn(new CursorPageResponseDTO<>(List.of(), 20, null, false));

        feedController.getFeed(null, null);

        verify(feedService).getFeed(currentUserId, null, null);
    }
}
