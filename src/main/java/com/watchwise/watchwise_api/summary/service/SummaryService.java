package com.watchwise.watchwise_api.summary.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.summary.dto.AllTimeStatsResponseDTO;
import com.watchwise.watchwise_api.summary.dto.EpisodeRatingsGridResponseDTO;
import com.watchwise.watchwise_api.summary.dto.MonthInReviewResponseDTO;
import com.watchwise.watchwise_api.summary.dto.SummaryResponseDTO;
import com.watchwise.watchwise_api.summary.dto.YearInReviewResponseDTO;

import java.time.YearMonth;
import java.util.UUID;

public interface SummaryService {

    SummaryResponseDTO getSummary(UUID viewerId, UUID userId, ContentType type);

    MonthInReviewResponseDTO getMonthInReview(UUID viewerId, UUID userId, ContentType type, YearMonth month);

    YearInReviewResponseDTO getYearInReview(UUID viewerId, UUID userId, ContentType type, Integer year);

    AllTimeStatsResponseDTO getAllTimeStats(UUID viewerId, UUID userId);

    EpisodeRatingsGridResponseDTO getEpisodeRatingsGrid(UUID viewerId, UUID userId, String seriesTmdbId);

}
