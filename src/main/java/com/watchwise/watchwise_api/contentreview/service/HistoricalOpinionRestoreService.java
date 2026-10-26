package com.watchwise.watchwise_api.contentreview.service;

import com.watchwise.watchwise_api.platformimport.model.ConfirmedWatchScopeSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface HistoricalOpinionRestoreService {

    Optional<UUID> restore(UUID ownerId, HistoricalOpinion opinion, HistoricalViewingEvidence evidence);

    Optional<UUID> restore(UUID ownerId, HistoricalOpinion opinion, HistoricalViewingEvidence evidence,
                           ConfirmedWatchScopeSnapshot preparedScope);
}
