package com.watchwise.watchwise_api.like.controller;

import com.watchwise.watchwise_api.like.service.LikeService;
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

@ExtendWith(MockitoExtension.class)
class LikeControllerTest {

    @Mock
    private LikeService likeService;

    @InjectMocks
    private LikeController likeController;

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
    @DisplayName("[likeComment] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenLikingComment() {
        UUID commentId = UUID.randomUUID();

        ResponseEntity<Void> result = likeController.likeComment(commentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[likeComment] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenLikingComment() {
        UUID commentId = UUID.randomUUID();

        likeController.likeComment(commentId);

        verify(likeService).likeComment(currentUserId, commentId);
    }

    @Test
    @DisplayName("[unlikeComment] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenUnlikingComment() {
        UUID commentId = UUID.randomUUID();

        ResponseEntity<Void> result = likeController.unlikeComment(commentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[unlikeComment] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenUnlikingComment() {
        UUID commentId = UUID.randomUUID();

        likeController.unlikeComment(commentId);

        verify(likeService).unlikeComment(currentUserId, commentId);
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenLikingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        ResponseEntity<Void> result = likeController.likeDiaryEntry(diaryEntryId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[likeDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenLikingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        likeController.likeDiaryEntry(diaryEntryId);

        verify(likeService).likeDiaryEntry(currentUserId, diaryEntryId);
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenUnlikingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        ResponseEntity<Void> result = likeController.unlikeDiaryEntry(diaryEntryId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[unlikeDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenUnlikingDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();

        likeController.unlikeDiaryEntry(diaryEntryId);

        verify(likeService).unlikeDiaryEntry(currentUserId, diaryEntryId);
    }
}
