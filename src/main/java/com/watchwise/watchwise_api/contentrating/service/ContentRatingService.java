package com.watchwise.watchwise_api.contentrating.service;

import com.watchwise.watchwise_api.contentrating.dto.ContentRatingResponseDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;

import java.util.Optional;
import java.util.UUID;

public interface ContentRatingService {

    ContentRatingResponseDTO set(UUID ownerId, UUID contentId, Integer score, Long expectedVersion);

    void remove(UUID ownerId, UUID contentId);

    Optional<UUID> restoreInitial(
            UUID ownerId,
            MediaReference content,
            Integer score,
            HistoricalViewingEvidence evidence);
}
