package com.watchwise.watchwise_api.comment.service;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface CommentService {

    Page<CommentResponseDTO> getCommentsForContent(UUID contentId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForList(UUID viewerId, UUID listId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForDiaryEntry(UUID viewerId, UUID diaryEntryId, Integer pageNumber, Integer pageSize);

    CommentResponseDTO createCommentOnContent(UUID userId, UUID contentId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnList(UUID userId, UUID listId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnDiaryEntry(UUID userId, UUID diaryEntryId, CommentCreationDTO commentCreationDTO);

    void deleteComment(UUID userId, UUID commentId);

}
