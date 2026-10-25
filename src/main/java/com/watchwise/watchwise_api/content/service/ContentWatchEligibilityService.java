package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.dto.WatchlistEligibility;

import java.util.UUID;
import java.util.Collection;
import java.util.Map;

public interface ContentWatchEligibilityService {

    WatchlistEligibility getWatchlistEligibility(UUID ownerId, MediaReference reference);

    Map<MediaReference, WatchlistEligibility> getWatchlistEligibility(
            UUID ownerId, Collection<MediaReference> references);
}
