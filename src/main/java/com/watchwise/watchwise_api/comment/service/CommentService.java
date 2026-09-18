package com.watchwise.watchwise_api.comment.service;

import com.watchwise.watchwise_api.comment.dto.CommentCreationDTO;
import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface CommentService {

    Page<CommentResponseDTO> getCommentsForContent(UUID viewerId, UUID contentId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForList(UUID viewerId, UUID listId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForDiaryEntry(UUID viewerId, UUID diaryEntryId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForPick(UUID viewerId, UUID pickId, Integer pageNumber, Integer pageSize);

    Page<CommentResponseDTO> getCommentsForPicksTemplate(UUID viewerId, UUID templateId, Integer pageNumber, Integer pageSize);

    CommentResponseDTO createCommentOnContent(UUID userId, UUID contentId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnList(UUID userId, UUID listId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnDiaryEntry(UUID userId, UUID diaryEntryId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnPick(UUID userId, UUID pickId, CommentCreationDTO commentCreationDTO);

    CommentResponseDTO createCommentOnPicksTemplate(UUID userId, UUID templateId, CommentCreationDTO commentCreationDTO);

    void deleteComment(UUID userId, UUID commentId);

}
