package com.watchwise.watchwise_api.top5entry.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryCreationDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryPatchDTO;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface Top5EntryService {

    List<Top5EntryResponseDTO> getTop5(UUID viewerId, UUID userId, ContentType type);

    Top5EntryResponseDTO insertEntry(UUID userId, ContentType type, Top5EntryCreationDTO top5EntryCreationDTO);

    void removeEntry(UUID userId, ContentType type, UUID top5EntryId);

    Top5EntryResponseDTO updateEntry(UUID userId, ContentType type, UUID top5EntryId, Top5EntryPatchDTO top5EntryPatchDTO);

}