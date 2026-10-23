package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.contentreview.dto.ContentReviewCreationDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewPatchDTO;
import com.watchwise.watchwise_api.contentreview.dto.ContentReviewViewDTO;

import java.util.UUID;

public interface ContentReviewService {

    ContentReviewViewDTO create(UUID ownerId, UUID contentId, ContentReviewCreationDTO creation, String idempotencyKey);

    ContentReviewViewDTO get(UUID ownerId, UUID reviewId);

    ContentReviewViewDTO edit(UUID ownerId, UUID reviewId, ContentReviewPatchDTO patch);

    void delete(UUID ownerId, UUID reviewId);
}
