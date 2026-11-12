package com.watchwise.watchwise_api.top5entry.service;

import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface Top5EntryReader {

    List<Top5EntryResponseDTO> read(UUID ownerId, ContentType type);
}
