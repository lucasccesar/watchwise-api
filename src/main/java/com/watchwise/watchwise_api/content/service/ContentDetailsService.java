package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;

import java.util.List;
import java.util.UUID;

public interface ContentDetailsService {

    ContentDetailsDTO getDetails(UUID contentId, UUID requestingUserId);

    ContentDetailsDTO getDetails(UUID contentId, UUID requestingUserId, UUID posterUserId);

    ContentPageDetails getPageDetails(
            UUID contentId, UUID requestingUserId, String preferredLanguage, String preferredRegion);

    List<ContentDetailsDTO> getDetailsBatch(List<UUID> contentIds, UUID requestingUserId);

    List<ContentDetailsDTO> getDetailsBatch(List<UUID> contentIds, UUID requestingUserId, UUID posterUserId);
}
