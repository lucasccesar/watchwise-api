package com.watchwise.watchwise_api.contentrating.service;

import com.watchwise.watchwise_api.contentrating.dto.ContentRatingResponseDTO;
import com.watchwise.watchwise_api.contentrating.dto.ContentRatingStateDTO;
import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.contentreview.service.HistoricalViewingEvidence;
import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ContentRatingService {

    ContentRatingStateDTO getCurrent(UUID ownerId, UUID contentId);

    ContentRatingResponseDTO set(UUID ownerId, UUID contentId, Integer score, Long expectedVersion);

    /** Records a score for a completed pass already verified by the calling internal workflow. */
    ContentRatingResponseDTO setForPass(
            UUID ownerId, UUID contentId, Integer score, Integer watchNumber, Long expectedVersion, Instant updatedAt);

    void remove(UUID ownerId, UUID contentId);

    Optional<UUID> restoreInitial(
            UUID ownerId,
            MediaReference content,
            Integer score,
            HistoricalViewingEvidence evidence);

    Optional<UUID> restoreInitial(
            UUID ownerId,
            MediaReference content,
            Integer score,
            HistoricalViewingEvidence evidence,
            ConfirmedWatchScopeSnapshot preparedScope);
}
