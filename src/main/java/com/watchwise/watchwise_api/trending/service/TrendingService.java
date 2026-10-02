package com.watchwise.watchwise_api.trending.service;

import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;

import java.util.UUID;

public interface TrendingService {

    TrendingResponseDTO getTrending(UUID viewerId, TrendingTimeWindow timeWindow, int size);
}
