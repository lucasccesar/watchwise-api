package com.watchwise.watchwise_api.search.service;

import com.watchwise.watchwise_api.search.dto.SearchViewDTO;

import java.util.UUID;

public interface SearchViewService {

    SearchViewDTO search(SearchViewQuery query);

    default SearchViewDTO search(UUID viewerId, String query, SearchViewScope scope, int page, int size) {
        return search(new SearchViewQuery(viewerId, query, scope, page, size, null, null));
    }
}
