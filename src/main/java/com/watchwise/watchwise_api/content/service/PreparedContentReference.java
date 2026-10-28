package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;

/** Metadata resolved before entering a caller's database critical section. */
public record PreparedContentReference(ContentRefCreationDTO content, ContentRefCreationDTO parentSeries) {
}
