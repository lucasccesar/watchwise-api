package com.watchwise.watchwise_api.feed.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;

import java.util.List;

public record FeedTop5PreviewDTO(
        ContentType type,
        List<Top5EntryResponseDTO> entries,
        List<ContentCardDTO> cards) {

    public FeedTop5PreviewDTO {
        entries = entries == null ? List.of() : List.copyOf(entries);
        cards = cards == null ? List.of() : List.copyOf(cards);
    }
}
