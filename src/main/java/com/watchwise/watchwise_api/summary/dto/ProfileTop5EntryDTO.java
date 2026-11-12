package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.ContentType;
import com.watchwise.watchwise_api.top5entry.dto.Top5EntryResponseDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record ProfileTop5EntryDTO(
        UUID id,
        ContentType type,
        ContentRefDTO content,
        Integer position,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl,
        Integer score,
        boolean hasWatched,
        ContentCardDTO card) {

    public static ProfileTop5EntryDTO from(
            Top5EntryResponseDTO entry, boolean hasWatched, ContentCardDTO card) {
        return new ProfileTop5EntryDTO(
                entry.id(), entry.type(), entry.content(), entry.position(), entry.createdAt(), entry.updatedAt(),
                card == null ? entry.customPosterUrl() : card.customPosterUrl(), entry.score(), hasWatched, card);
    }

    public ProfileTop5EntryDTO withCard(ContentCardDTO card) {
        return new ProfileTop5EntryDTO(
                id, type, content, position, createdAt, updatedAt,
                card == null ? customPosterUrl : card.customPosterUrl(), score, hasWatched, card);
    }
}
