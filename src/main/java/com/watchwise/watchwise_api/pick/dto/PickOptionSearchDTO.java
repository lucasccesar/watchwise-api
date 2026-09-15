package com.watchwise.watchwise_api.pick.dto;

import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import java.util.UUID;

public record PickOptionSearchDTO(UUID id, ContentRefDTO content, String personTmdbId, ContentRefDTO contextContent) { }
