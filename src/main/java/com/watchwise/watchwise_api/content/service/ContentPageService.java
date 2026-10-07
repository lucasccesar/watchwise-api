package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentPageDTO;

import java.util.UUID;

public interface ContentPageService {

    ContentPageDTO getPage(UUID contentId, UUID viewerId);
}
