package com.watchwise.watchwise_api.contentposter.service;

import com.watchwise.watchwise_api.contentposter.dto.UserContentPosterResponseDTO;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface UserContentPosterService {

    UserContentPosterResponseDTO upsert(UUID userId, UUID contentId, String customPosterUrl);

    void delete(UUID userId, UUID contentId);

    Map<UUID, String> findByUserAndContentIds(UUID userId, Collection<UUID> contentIds);

    Map<String, String> findSeriesPosters(UUID userId, Collection<String> seriesTmdbIds);
}
