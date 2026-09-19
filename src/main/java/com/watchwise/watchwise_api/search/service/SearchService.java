package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.search.dto.SearchResultDTO;

import java.util.UUID;

public interface SearchService {

    SearchResultDTO search(UUID viewerId, String query, SearchType type, Integer pageNumber, Integer pageSize);
}
