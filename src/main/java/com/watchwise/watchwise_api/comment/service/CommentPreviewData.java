package com.watchwise.watchwise_api.comment.service;

import com.watchwise.watchwise_api.comment.dto.CommentResponseDTO;

import java.util.List;

public record CommentPreviewData(
        long commentsCount,
        List<CommentResponseDTO> recentComments
) { }
