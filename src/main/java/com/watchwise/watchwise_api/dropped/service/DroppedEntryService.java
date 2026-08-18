package com.watchwise.watchwise_api.dropped.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryCreationDTO;
import com.watchwise.watchwise_api.dropped.dto.DroppedEntryResponseDTO;
import org.springframework.data.domain.Page;

import java.util.UUID;

public interface DroppedEntryService {

    Page<DroppedEntryResponseDTO> getDropped(UUID viewerId, UUID userId, ContentType type, Integer pageNumber, Integer pageSize);

    void markAsDropped(UUID userId, ContentType type, String tmdbId, DroppedEntryCreationDTO droppedEntryCreationDTO);

    void unmarkAsDropped(UUID userId, ContentType type, String tmdbId);

}