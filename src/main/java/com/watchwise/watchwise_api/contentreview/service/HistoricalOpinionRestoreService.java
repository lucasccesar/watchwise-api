package com.watchwise.watchwise_api.contentreview.service;

import java.util.Optional;
import java.util.UUID;

public interface HistoricalOpinionRestoreService {

    Optional<UUID> restore(UUID ownerId, HistoricalOpinion opinion, HistoricalViewingEvidence evidence);
}
