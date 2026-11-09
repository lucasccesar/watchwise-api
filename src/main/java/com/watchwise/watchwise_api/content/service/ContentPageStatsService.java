package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentPageStatsDTO;
import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface ContentPageStatsService {

    ContentPageStatsDTO getStats(UUID contentId);

    ContentPageStatsDTO getStats(UUID contentId, ContentType contentType);

    Resolution resolveStats(UUID contentId, ContentType contentType, Collection<UUID> contentIds);

    record Resolution(ContentPageStatsDTO pageStats, Map<UUID, ContentStatsResponseDTO> statsByContentId) {
    }
}
