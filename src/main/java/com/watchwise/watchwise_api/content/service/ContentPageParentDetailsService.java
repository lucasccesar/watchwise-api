package com.watchwise.watchwise_api.content.service;

import com.watchwise.watchwise_api.content.dto.ContentDetailsDTO;
import com.watchwise.watchwise_api.content.entity.Content;

import java.util.UUID;

public interface ContentPageParentDetailsService {

    ContentDetailsDTO resolveSeries(Content child, UUID viewerId);

    ContentDetailsDTO resolveSeason(Content child, UUID viewerId);
}
