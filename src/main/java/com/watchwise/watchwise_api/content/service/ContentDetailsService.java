package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;

import java.util.List;
import java.util.UUID;

public interface ContentDetailsService {

    ContentDetailsDTO getDetails(UUID contentId, UUID requestingUserId);

    List<ContentDetailsDTO> getDetailsBatch(List<UUID> contentIds, UUID requestingUserId);
}
