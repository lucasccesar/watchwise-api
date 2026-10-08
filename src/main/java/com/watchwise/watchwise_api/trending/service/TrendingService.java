package com.watchwise.watchwise_api.trending.service;

import com.watchwise.watchwise_api.trending.dto.TrendingResponseDTO;
import com.watchwise.watchwise_api.content.entity.MovieOrSeriesType;

import java.util.UUID;

public interface TrendingService {

    TrendingResponseDTO getTrending(UUID viewerId, TrendingTimeWindow timeWindow, int size);

    TrendingResponseDTO getTrendingSection(
            UUID viewerId, MovieOrSeriesType type, TrendingTimeWindow timeWindow, int page, int size);
}
