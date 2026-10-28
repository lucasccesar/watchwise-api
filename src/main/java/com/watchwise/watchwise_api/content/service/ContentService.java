package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

public interface ContentService {

    ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO);

    ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO, boolean trustedRuntimeMinutes);

    PreparedContentReference prepareReference(ContentRefCreationDTO contentRefCreationDTO, boolean trustedRuntimeMinutes);

    ContentRefDTO persistPreparedReference(PreparedContentReference prepared, boolean trustedRuntimeMinutes);

    ContentRefDTO getOrCreateReferenceFromVerifiedImport(ContentRefCreationDTO contentRefCreationDTO);

}
