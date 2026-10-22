package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.MediaReference;
import com.watchwise.watchwise_api.content.dto.WatchlistEligibility;

import java.util.UUID;

public interface ContentWatchEligibilityService {

    WatchlistEligibility getWatchlistEligibility(UUID ownerId, MediaReference reference);
}
