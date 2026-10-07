package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentPageMetadataDTO;
import com.watchwise.watchwise_api.content.entity.Content;

public interface ContentPageMetadataService {

    ContentPageMetadataDTO getMetadata(Content content, String language, String region);
}
