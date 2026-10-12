package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;

public record PickTargetPreviewDTO(
        ContentCardDTO contentCard,
        PickPersonPreviewDTO person,
        ContentCardDTO contextCard) {

    public PickTargetPreviewDTO {
        if ((contentCard == null) == (person == null)) {
            throw new IllegalArgumentException("Exactly one of contentCard or person must be provided");
        }
        if (person == null && contextCard != null) {
            throw new IllegalArgumentException("contextCard is only allowed for a person target");
        }
    }
}
