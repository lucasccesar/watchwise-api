package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.common.tmdb.TmdbMovieFullDetails;
import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;
import com.watchwise.watchwise_api.content.entity.Content;

public interface ContentService {

    ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO);

    ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO, boolean trustedRuntimeMinutes);

    PreparedContentReference prepareReference(ContentRefCreationDTO contentRefCreationDTO, boolean trustedRuntimeMinutes);

    ContentRefDTO persistPreparedReference(PreparedContentReference prepared, boolean trustedRuntimeMinutes);

    void reconcileMoviePageReference(Content content, TmdbMovieFullDetails pageDetails, String language);

    ContentRefDTO getOrCreateReferenceFromVerifiedImport(ContentRefCreationDTO contentRefCreationDTO);

}
