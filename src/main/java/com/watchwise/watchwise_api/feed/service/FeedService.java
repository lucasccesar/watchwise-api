package com.watchwise.watchwise_api.feed.service;

import com.watchwise.watchwise_api.common.dto.CursorPageResponseDTO;
import com.watchwise.watchwise_api.feed.dto.FeedItemDTO;
import com.watchwise.watchwise_api.feed.dto.FeedPageViewDTO;

import java.util.UUID;

public interface FeedService {

    CursorPageResponseDTO<FeedItemDTO> getFeed(UUID userId, String cursor, Integer size);

    FeedPageViewDTO getFeedView(UUID userId, String cursor, Integer size);

}
