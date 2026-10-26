package com.watchwise.watchwise_api.pickstemplate.service;

import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateCreationDTO;
import com.watchwise.watchwise_api.pickstemplate.dto.PicksTemplateResponseDTO;

import java.util.UUID;

public interface PreparedTemplateCreationService {
    PreparedTemplateCreation prepare(UUID actorId, PicksTemplateCreationDTO dto);

    PicksTemplateResponseDTO createPrepared(UUID actorId, PreparedTemplateCreation prepared);
}
