package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentRefCreationDTO;
import com.watchwise.watchwise_api.content.dto.ContentRefDTO;

public interface ContentService {

    ContentRefDTO getOrCreateReference(ContentRefCreationDTO contentRefCreationDTO);

}