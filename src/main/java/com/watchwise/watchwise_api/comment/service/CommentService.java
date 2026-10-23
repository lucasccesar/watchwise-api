package com.watchwise.watchwise_api.comment.service;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import com.watchwise.watchwise_api.comment.dto.CommentUpdateDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface CommentService {

    Page<CommentResponseDTO> getCommentsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForList(UUID viewerId, UUID listId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForDiaryEntry(UUID viewerId, UUID diaryEntryId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForContentReview(UUID viewerId, UUID reviewId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForDroppedEntry(UUID viewerId, UUID droppedEntryId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForPick(UUID viewerId, UUID pickId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForPicksTemplate(UUID viewerId, UUID templateId, Integer pageNumber, Integer pageSize);

    CommentResponseDTO createCommentOnContent(UUID userId, UUID contentId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnList(UUID userId, UUID listId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnDiaryEntry(UUID userId, UUID diaryEntryId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnContentReview(UUID userId, UUID reviewId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnDroppedEntry(UUID userId, UUID droppedEntryId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnPick(UUID userId, UUID pickId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnPicksTemplate(UUID userId, UUID templateId, CommentCreationDTO commentCreationDTO);

    void deleteComment(UUID userId, UUID commentId);

    CommentResponseDTO updateComment(UUID userId, UUID commentId, CommentUpdateDTO commentUpdateDTO);

}
