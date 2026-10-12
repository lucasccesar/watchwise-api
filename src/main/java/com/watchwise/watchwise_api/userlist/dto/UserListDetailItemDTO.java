package com.watchwise.watchwise_api.userlist.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentStateDTO;

import java.time.LocalDateTime;
import java.util.UUID;

public record UserListDetailItemDTO(
        UUID id,
        ContentCardDTO card,
        UserListNestedDetailDTO nestedList,
        Integer position,
        String description,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String customPosterUrl,
        ContentStateDTO contentState,
        Double episodeAverageRating,
        Double globalEpisodeAverageRating,
        Double contentAverageRating
) {
    public UserListDetailItemDTO {
        if ((card == null) == (nestedList == null)) {
            throw new IllegalArgumentException("Exactly one of card or nestedList must be provided");
        }
    }
}
