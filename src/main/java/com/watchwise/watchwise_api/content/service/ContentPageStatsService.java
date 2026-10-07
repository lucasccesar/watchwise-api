package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;

import java.util.UUID;

public interface ContentPageStatsService {

    ContentPageStatsDTO getStats(UUID contentId);
}
