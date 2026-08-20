package com.watchwise.watchwise_api.comment.controller;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.service.CommentService;
import com.watchwise.watchwise_api.common.dto.PageResponseDTO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
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
class CommentControllerTest {

    @Mock
    private CommentService commentService;

    @InjectMocks
    private CommentController commentController;

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
    @DisplayName("[getCommentsForContent] Should Return Page Envelope With Content And Metadata - When Called")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenGettingCommentsForContent() {
        UUID contentId = UUID.randomUUID();
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.getCommentsForContent(contentId, 1, 10))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));

        ResponseEntity<PageResponseDTO<CommentResponseDTO>> result = commentController.getCommentsForContent(contentId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
        assertThat(result.getBody().page()).isEqualTo(1);
        assertThat(result.getBody().totalElements()).isEqualTo(1);
        assertThat(result.getBody().hasNext()).isFalse();
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenCreatingCommentOnContent() {
        UUID contentId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Great movie!", null, null);
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.createCommentOnContent(currentUserId, contentId, creationDTO)).thenReturn(dto);

        ResponseEntity<CommentResponseDTO> result = commentController.createCommentOnContent(contentId, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[createCommentOnContent] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCreatingCommentOnContent() {
        UUID contentId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Great movie!", null, null);
        when(commentService.createCommentOnContent(currentUserId, contentId, creationDTO)).thenReturn(buildResponseDto());

        commentController.createCommentOnContent(contentId, creationDTO);

        verify(commentService).createCommentOnContent(currentUserId, contentId, creationDTO);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Return Page Envelope With Content And Metadata - When Called")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenGettingCommentsForList() {
        UUID listId = UUID.randomUUID();
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.getCommentsForList(currentUserId, listId, 1, 10))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));

        ResponseEntity<PageResponseDTO<CommentResponseDTO>> result = commentController.getCommentsForList(listId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getCommentsForList] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingCommentsForList() {
        UUID listId = UUID.randomUUID();
        when(commentService.getCommentsForList(currentUserId, listId, 1, 10))
                .thenReturn(new PageImpl<>(List.of(buildResponseDto()), PageRequest.of(0, 10), 1));

        commentController.getCommentsForList(listId, 1, 10);

        verify(commentService).getCommentsForList(currentUserId, listId, 1, 10);
    }

    @Test
    @DisplayName("[createCommentOnList] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenCreatingCommentOnList() {
        UUID listId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Nice picks", null, null);
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.createCommentOnList(currentUserId, listId, creationDTO)).thenReturn(dto);

        ResponseEntity<CommentResponseDTO> result = commentController.createCommentOnList(listId, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[createCommentOnList] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCreatingCommentOnList() {
        UUID listId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Nice picks", null, null);
        when(commentService.createCommentOnList(currentUserId, listId, creationDTO)).thenReturn(buildResponseDto());

        commentController.createCommentOnList(listId, creationDTO);

        verify(commentService).createCommentOnList(currentUserId, listId, creationDTO);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Return Page Envelope With Content And Metadata - When Called")
    void shouldReturnPageEnvelopeWithContentAndMetadataWhenGettingCommentsForDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.getCommentsForDiaryEntry(currentUserId, diaryEntryId, 1, 10))
                .thenReturn(new PageImpl<>(List.of(dto), PageRequest.of(0, 10), 1));

        ResponseEntity<PageResponseDTO<CommentResponseDTO>> result =
                commentController.getCommentsForDiaryEntry(diaryEntryId, 1, 10);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().content()).containsExactly(dto);
    }

    @Test
    @DisplayName("[getCommentsForDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenGettingCommentsForDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        when(commentService.getCommentsForDiaryEntry(currentUserId, diaryEntryId, 1, 10))
                .thenReturn(new PageImpl<>(List.of(buildResponseDto()), PageRequest.of(0, 10), 1));

        commentController.getCommentsForDiaryEntry(diaryEntryId, 1, 10);

        verify(commentService).getCommentsForDiaryEntry(currentUserId, diaryEntryId, 1, 10);
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Return Created With The Service Result - When Called")
    void shouldReturnCreatedWithTheServiceResultWhenCreatingCommentOnDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Agreed", null, null);
        CommentResponseDTO dto = buildResponseDto();
        when(commentService.createCommentOnDiaryEntry(currentUserId, diaryEntryId, creationDTO)).thenReturn(dto);

        ResponseEntity<CommentResponseDTO> result = commentController.createCommentOnDiaryEntry(diaryEntryId, creationDTO);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody()).isEqualTo(dto);
    }

    @Test
    @DisplayName("[createCommentOnDiaryEntry] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenCreatingCommentOnDiaryEntry() {
        UUID diaryEntryId = UUID.randomUUID();
        CommentCreationDTO creationDTO = new CommentCreationDTO("Agreed", null, null);
        when(commentService.createCommentOnDiaryEntry(currentUserId, diaryEntryId, creationDTO)).thenReturn(buildResponseDto());

        commentController.createCommentOnDiaryEntry(diaryEntryId, creationDTO);

        verify(commentService).createCommentOnDiaryEntry(currentUserId, diaryEntryId, creationDTO);
    }

    @Test
    @DisplayName("[deleteComment] Should Return NoContent - When Called")
    void shouldReturnNoContentWhenDeletingComment() {
        UUID commentId = UUID.randomUUID();

        ResponseEntity<Void> result = commentController.deleteComment(commentId);

        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("[deleteComment] Should Resolve The Current User Id From The Security Context - When Called")
    void shouldResolveTheCurrentUserIdFromTheSecurityContextWhenDeletingComment() {
        UUID commentId = UUID.randomUUID();

        commentController.deleteComment(commentId);

        verify(commentService).deleteComment(currentUserId, commentId);
    }

    private CommentResponseDTO buildResponseDto() {
        LocalDateTime now = LocalDateTime.now();
        return new CommentResponseDTO(UUID.randomUUID(), null, UUID.randomUUID(), null, null, null, "Great movie!", false, now, now);
    }
}
