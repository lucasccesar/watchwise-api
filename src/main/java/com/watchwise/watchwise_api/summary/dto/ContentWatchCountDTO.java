package com.watchwise.watchwise_api.summary.dto;

import com.watchwise.watchwise_api.content.dto.ContentCardDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

public record ContentWatchCountDTO(
        ContentRefDTO content,
        long count,
        ContentCardDTO card) {

    public ContentWatchCountDTO(ContentRefDTO content, long count) {
        this(content, count, null);
    }

    public ContentWatchCountDTO withCard(ContentCardDTO card) {
        return new ContentWatchCountDTO(content, count, card);
    }
}
