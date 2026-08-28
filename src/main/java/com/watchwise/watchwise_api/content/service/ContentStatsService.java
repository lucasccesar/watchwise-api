package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentStatsResponseDTO;

import java.util.List;
import java.util.UUID;

public interface ContentStatsService {

    ContentStatsResponseDTO getStats(UUID contentId);

    List<ContentStatsResponseDTO> getStatsBatch(List<UUID> contentIds);

}
