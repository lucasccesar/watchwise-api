package com.watchwise.watchwise_api.summary.service;

import com.watchwise.watchwise_api.content.entity.Content;
import com.watchwise.watchwise_api.summary.dto.ProfileHighlightContentDTO;

import java.util.UUID;

public interface ProfileDisplayMetadataResolver {

    ProfileHighlightContentDTO resolveStoredContent(UUID ownerId, Content content);

    ProfileHighlightContentDTO resolveSeries(UUID ownerId, String seriesTmdbId);
}
