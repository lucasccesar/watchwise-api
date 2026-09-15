package com.watchwise.watchwise_api.pickstemplate.dto;

import com.watchwise.watchwise_api.pickstemplate.entity.PickOrigin;
import java.util.UUID;

public record PicksTemplatePreviewDTO(UUID id, PickOrigin origin, String name, String description,
                                      String coverImage) { }
